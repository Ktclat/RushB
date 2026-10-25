package dev.matchscope.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class VrsRegion(val key: String, val label: String) {
    GLOBAL("global", "世界"), EUROPE("europe", "欧洲"), AMERICAS("americas", "美洲"), ASIA("asia", "亚洲")
}

class MatchRepository(context: Context, browser: BrowserSession, private val mobileMode: () -> MobileTransport = { MobileTransport.HTTPS }, mobileHttp: MobileFetcher = MobileHttpTransport(), private val pageMode: () -> MobileTransport = mobileMode, private val relay: () -> RelayConfig) {
    private val assets = context.assets
    private val mobileGate = MobileRequestGate()
    private val phoneDocuments = PhoneDocumentReader(context)
    val mobile = MobileJsonSource(File(context.filesDir, "mobile-json-v1"), browser, http = mobileHttp, requestGate = mobileGate, preferredTransport = mobileMode,
        backgroundBrowser = MobileFetcher { phoneDocuments.response(it.url, "application/json") })
    private val mobileEmbeds = MobileTransport.entries.associateWith { mode -> PageStore(File(context.filesDir, "mobile-embeds/${mode.name.lowercase()}"), download = { url ->
        mobileGate.read { PageBody(if (mode == MobileTransport.BROWSER) {
            // Optional brackets must not leave the main event indefinitely behind a challenge.
            withTimeoutOrNull(60_000) { browser.load(url) } ?: throw SourceUnavailable("对阵读取超时", 300_000)
        } else fetchHttps(url)) }
    }) }
    private val pages = PageStore(File(context.filesDir, "source-cache"), download = { url ->
        if (HltvAccess.allowed(url)) {
            if (relay().enabled) relayPage(url, relay())
            else PageBody(if (pageMode() == MobileTransport.BROWSER) browser.load(url) else fetchHttps(url))
        } else PageBody(fetchHttps(url))
    }, accessScope = { if (relay().enabled) "relay" else pageMode().name }, recoveredPage = { url ->
        if (!relay().enabled && pageMode() == MobileTransport.BROWSER) browser.takeVerifiedDocument(url)?.let { PageBody(it) } else null
    })
    // RSS refresh should not cover readable cached news with an automatic browser window.
    // The explicit verification action hands back this exact XML document once.
    private val newsPages = PageStore(File(context.filesDir, "source-cache"), download = { url ->
        if (relay().enabled) relayPage(url, relay()) else PageBody(fetchHttps(url))
    }, accessScope = { if (relay().enabled) "relay" else "HTTPS" }, recoveredPage = { url ->
        if (!relay().enabled) browser.takeVerifiedDocument(url)?.let { PageBody(it) } else null
    })
    // Opening an archive is an explicit user action. It uses the phone's real page reader,
    // while automatic RSS refresh stays in the background and never opens a browser window.
    private val newsWebsitePages = PageStore(File(context.filesDir, "source-cache"), download = { url ->
        if (relay().enabled) relayPage(url, relay()) else PageBody(browser.load(url))
    }, accessScope = { if (relay().enabled) "relay" else "BROWSER" }, recoveredPage = { url ->
        if (!relay().enabled) browser.takeVerifiedDocument(url)?.let { PageBody(it) } else null
    })
    private val rankingHttp = PageStore(File(context.filesDir, "ranking-http"))
    private val backgroundPages = PageStore(File(context.filesDir, "source-cache"), download = { PageBody(phoneDocuments.read(it)) }, accessScope = { "BROWSER" },
        recoveredPage = { url -> browser.takeVerifiedDocument(url)?.let { PageBody(it) } })
    private val records = RecordStore(File(context.filesDir, "records"))
    private val rawBase = "https://raw.githubusercontent.com/ValveSoftware/counter-strike_regional_standings/main"

    fun vrsSnapshot(region: VrsRegion): Feed<RankingEntry> {
        val name = "standings_${region.key}_2026_10_05.md"
        val url = "$rawBase/invitation/2026/$name"
        val text = assets.open(name).bufferedReader().use { it.readText() }
        return Feed(VrsParser.parse(text, url), SeedSnapshot.stamp(url, VrsParser.publishedDate(text)))
    }

    fun restoredMatches(): Feed<Match>? = records.read("match-feed")?.let { runCatching { Records.feed(it) }.getOrNull() }
    fun restoredLibrary(): LocalLibrary = readLocalLibrary(records)
    suspend fun rememberMatch(match: Match) = withContext(Dispatchers.IO) {
        if (match.id.toLongOrNull() == null) return@withContext
        val selected = preferredMatchRecords(listOfNotNull(restoredMatch(match.id), match)).single()
        records.write("match-${match.id}", Records.match(selected))
    }
    suspend fun scheduleMatch(row: ProfileMatch): Match? {
        val reference = row.asMatch() ?: return null
        fun verified(match: Match) = match.id == reference.id && match.event.hasIdentity() &&
            setOf(match.left.id, match.right.id) == setOf(reference.left.id, reference.right.id)
        val cached = withContext(Dispatchers.IO) { restoredMatch(reference.id) }
        // Event identity does not need another request just because scores have expired.
        if (cached != null && verified(cached)) return cached
        return match(reference.id, reference.url).takeIf(::verified)
    }
    fun restoredSaved(): List<Match> = records.read("saved")?.optJSONArray("items")?.let { array ->
        (0 until array.length()).mapNotNull { runCatching { Records.match(array.getJSONObject(it), true) }.getOrNull() }
    }.orEmpty()
    suspend fun saveBookmarks(matches: List<Match>) = withContext(Dispatchers.IO) {
        records.write("saved", JSONObject().put("items", JSONArray(matches.map(Records::match))))
    }
    fun restoredMatch(id: String): Match? {
        if (id.toLongOrNull() == null) return null
        val saved = records.read("match-$id")?.let { runCatching { Records.match(it, true) }.getOrNull() } ?: return null
        if (saved.insights.complete) return saved
        val page = pages.cached(saved.url) { HltvParser.match(it, id) } ?: return saved
        val upgraded = page.value.copy(stamp = page.stamp(saved.url), note = saved.note)
        records.write("match-$id", Records.match(upgraded))
        return upgraded
    }
    fun restoredPlayer(id: String): Sourced<PlayerProfile>? = if(id.toLongOrNull() == null) null else records.read("player-$id")?.let { runCatching { Records.profile(it) }.getOrNull() }

    private fun sourceKey(prefix: String, url: String): String = prefix + "-" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    fun restoredDuels(url: String): Sourced<MatchDuels>? = records.read(sourceKey("duels", url))?.let {
        runCatching { MatchInsightRecords.duels(it) }.getOrNull()
    }

    suspend fun duels(statsUrl: String, refresh: Boolean = false): Sourced<MatchDuels> = withContext(Dispatchers.IO) {
        val url = DuelParser.performanceUrl(statsUrl) ?: throw SourceUnavailable("暂无对位数据")
        val result = pages.read(url, 300_000, forceRefresh = refresh) { raw -> DuelParser.parse(org.jsoup.Jsoup.parse(raw, url)) }
        Sourced(result.value, result.stamp(url), result.warning).also {
            records.write(sourceKey("duels", url), MatchInsightRecords.duels(it))
        }
    }

    suspend fun history(url: String, teams: List<Team>, allTeams: Boolean, refresh: Boolean = false): Sourced<MatchHistoryPage> = withContext(Dispatchers.IO) {
        require(historyQueryMatches(url, teams.map { it.id }.toSet(), allTeams))
        val result = pages.read(url, 900_000, forceRefresh = refresh) { raw ->
            val rows = HltvParser.results(raw).map { row ->
                fun identify(team: Team) = teams.firstOrNull { it.name.equals(team.name, true) } ?: team
                row.copy(left = identify(row.left), right = identify(row.right))
            }
            if (rows.any { row ->
                val ids = setOf(row.left.id, row.right.id)
                if (allTeams) !ids.containsAll(teams.map { it.id }) else teams.none { it.id in ids }
            }) throw SourceUnavailable("赛果筛选未生效")
            val next = ProfileParser.resultsNextUrl(org.jsoup.Jsoup.parse(raw, url))
                ?.takeIf { historyQueryMatches(it, teams.map { team -> team.id }.toSet(), allTeams) }
            MatchHistoryPage(rows, next)
        }
        Sourced(result.value, result.stamp(url), result.warning)
    }

    fun restoredTeam(id: String): Sourced<TeamProfile>? = if(id.toLongOrNull() == null) null else records.read("team-$id")?.let { runCatching { Records.teamProfile(it) }.getOrNull() }
    fun restoredEvent(id: String): Sourced<Tournament>? {
        if (id.toLongOrNull() == null) return null
        val saved = records.read("event-$id")?.let { runCatching { Records.eventProfile(it) }.getOrNull() } ?: return null
        if (saved.value.content.complete) return saved
        val page = pages.cached(saved.value.url) { HltvParser.event(it, id) } ?: return saved
        return Sourced(page.value, page.stamp(saved.value.url), saved.warning).also { records.write("event-$id", Records.eventProfile(it)) }
    }
    suspend fun team(id: String, url: String, forceRefresh: Boolean = false): Sourced<TeamProfile> = withContext(Dispatchers.IO) {
        require(id.toLongOrNull() != null)
        if (!relay().enabled) {
            val known = restoredMatches()?.items.orEmpty()
            var result = mobile.read(MobileRequest.create(MobileRoute.TEAM, id), refresh = forceRefresh) { MobileProfileParser.team(it, id, known) }
            try {
                val news = mobile.read(MobileRequest.create(MobileRoute.TEAM_NEWS, id), refresh = forceRefresh, decode = MobileProfileParser::news)
                result = result.copy(value = result.value.copy(content = result.value.content.copy(news = news.value, newsStamp = news.stamp)), warning = result.warning ?: news.warning?.let { "新闻更新失败" })
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                val previous = restoredTeam(id)?.value?.content
                result = result.copy(value = result.value.copy(content = result.value.content.copy(news = previous?.news.orEmpty(), newsStamp = previous?.newsStamp)), warning = "新闻更新失败")
            }
            return@withContext result.also { records.write("team-$id", Records.teamProfile(it)) }
        }
        val result = pages.read(url, 3_600_000, forceRefresh = forceRefresh) { HltvParser.team(it, id) }
        Sourced(result.value, result.stamp(url), result.warning).also { records.write("team-$id", Records.teamProfile(it)) }
    }
    suspend fun event(id: String, url: String, forceRefresh: Boolean = false): Sourced<Tournament> = withContext(Dispatchers.IO) {
        require(id.toLongOrNull() != null)
        if (!relay().enabled) {
            val result = mobile.read(MobileRequest.create(MobileRoute.EVENT, id), refresh = forceRefresh) { MobileProfileParser.event(it, id) }
            val previous = restoredEvent(id)
            val brackets = mutableListOf<EventBracket>()
            val bracketStamps = mutableMapOf<String, SourceStamp>()
            var warning = result.warning
            for ((name, embedUrl) in result.value.content.bracketLinks) {
                require(mobileBracketUrl(embedUrl, id))
                val bracketId = java.net.URI(embedUrl).rawQuery.substringAfter("bracketId=")
                try {
                    val page = mobileEmbeds.getValue(mobileMode()).read(embedUrl, 120_000, forceRefresh = forceRefresh) { html ->
                        val values = EventContentParser.parse(org.jsoup.Jsoup.parse(html, embedUrl), id).brackets
                        values.singleOrNull { it.id == bracketId } ?: throw SourceUnavailable("对阵更新失败")
                    }
                    brackets += page.value.copy(name = name)
                    bracketStamps[bracketId] = page.stamp(embedUrl)
                    warning = warning ?: page.warning
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (dev.matchscope.app.BuildConfig.DEBUG) android.util.Log.w("RushB", "Event bracket read failed: $embedUrl", e)
                    warning = "对阵更新失败"
                    previous?.value?.content?.brackets?.firstOrNull { it.id == bracketId }?.let { old ->
                        brackets += old
                        bracketStamps[bracketId] = (previous.value.content.bracketStamps[bracketId] ?: previous.stamp).copy(origin = DataOrigin.CACHE)
                    }
                }
            }
            val schedule = result.value.content.matches.map { it.copy(stamp = result.stamp, note = result.warning) }
            return@withContext result.copy(value = result.value.copy(content = result.value.content.copy(matches = schedule, brackets = brackets, bracketStamps = bracketStamps)), warning = warning)
                .also { records.write("event-$id", Records.eventProfile(it)) }
        }
        val result = pages.read(url, 3_600_000, forceRefresh = forceRefresh) { HltvParser.event(it, id) }
        Sourced(result.value, result.stamp(url), result.warning).also { records.write("event-$id", Records.eventProfile(it)) }
    }

    private fun <T> PageValue<T>.stamp(url: String) = SourceStamp(url, at, if(cached) DataOrigin.CACHE else DataOrigin.NETWORK)

    suspend fun match(id: String, sourceUrl: String, forceRefresh: Boolean = false, background: Boolean = false): Match = withContext(Dispatchers.IO) {
        require(id.matches(Regex("\\d+")))
        if (!relay().enabled) {
            val result = mobile.read(MobileRequest.create(MobileRoute.MATCH, id), refresh = forceRefresh, background = background) { MobileMatchParser.detail(it, id) }
            return@withContext result.value.copy(stamp = result.stamp, note = result.warning).also { records.write("match-$id", Records.match(it)) }
        }
        val result = pages.read(sourceUrl, 120_000, forceRefresh = forceRefresh) { HltvParser.match(it, id) }
        result.value.copy(stamp = result.stamp(sourceUrl), note = result.warning).also {
            records.write("match-$id", Records.match(it))
        }
    }

    fun restoredMatchStatistics(id: String): Match? = id.takeIf { it.toLongOrNull() != null }?.let {
        records.read("match-statistics-$it")?.let { raw -> runCatching { Records.match(raw, true) }.getOrNull() }
    }

    suspend fun matchStatistics(match: Match, refresh: Boolean = false): Match = withContext(Dispatchers.IO) {
        require(match.id.toLongOrNull() != null)
        val url = "https://www.hltv.org/matches/${match.id}/-"
        val ttl = if (match.status == MatchStatus.FINISHED) 86_400_000L else 60_000L
        val result = backgroundPages.read(url, ttl, forceRefresh = refresh) { raw ->
            val parsed = HltvParser.match(raw, match.id)
            if (parsed.left.id != match.left.id || parsed.right.id != match.right.id) throw SourceUnavailable("统计战队不匹配")
            if (parsed.statGroups.none { it.side != MatchSide.BOTH && it.rows.isNotEmpty() }) throw SourceUnavailable("暂无阵营统计")
            parsed
        }
        result.value.copy(stamp = result.stamp(url), note = result.warning).also {
            records.write("match-statistics-${match.id}", Records.match(it))
        }
    }

    suspend fun player(id: String, sourceUrl: String, forceRefresh: Boolean = false): Sourced<PlayerProfile> = withContext(Dispatchers.IO) {
        require(id.matches(Regex("\\d+")))
        if (!relay().enabled) {
            val known = restoredMatches()?.items.orEmpty()
            var result = mobile.read(MobileRequest.create(MobileRoute.PLAYER, id), refresh = forceRefresh) { MobileProfileParser.player(it, id, known) }
            try {
                val news = mobile.read(MobileRequest.create(MobileRoute.PLAYER_NEWS, id), refresh = forceRefresh, decode = MobileProfileParser::news)
                result = result.copy(value = result.value.copy(content = result.value.content.copy(news = news.value, newsStamp = news.stamp)), warning = result.warning ?: news.warning?.let { "新闻更新失败" })
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                val previous = restoredPlayer(id)?.value?.content
                result = result.copy(value = result.value.copy(content = result.value.content.copy(news = previous?.news.orEmpty(), newsStamp = previous?.newsStamp)), warning = "新闻更新失败")
            }
            return@withContext result.also { records.write("player-$id", Records.profile(it)) }
        }
        val result = pages.read(sourceUrl, 3_600_000, forceRefresh = forceRefresh) { HltvParser.player(it, id) }
        Sourced(result.value, result.stamp(sourceUrl), result.warning).also { records.write("player-$id", Records.profile(it)) }
    }

    suspend fun resolveMatch(item: ProfileMatch): Match = withContext(Dispatchers.IO) {
        val direct = item.asMatch()
        if (direct != null) return@withContext direct
        val url = item.statisticsUrl?.takeIf(HltvAccess::allowed) ?: throw SourceUnavailable("暂无比赛详情")
        val page = pages.read(url, 3_600_000) { raw ->
            MatchStatsParser.matchPageUrl(org.jsoup.Jsoup.parse(raw, url)) ?: throw SourceUnavailable("暂时无法读取比赛链接")
        }
        item.copy(matchUrl = page.value).asMatch() ?: throw SourceUnavailable("比赛链接无效")
    }

    suspend fun moreProfileMatches(kind: String, id: String, current: ProfileContent): ProfileContent = withContext(Dispatchers.IO) {
        require(kind in setOf("player", "team") && id.toLongOrNull() != null)
        val url = current.moreMatchesUrl ?: return@withContext current
        require(HltvAccess.allowed(url) && java.net.URI(url).path == "/results")
        val result = pages.read(url, 900_000) { raw ->
            val doc = org.jsoup.Jsoup.parse(raw, url)
            val next = ProfileParser.resultsNextUrl(doc)
            HltvParser.results(raw) to next
        }
        // Results rows expose names, while profile rows supply stable team/event identities.
        val teams = current.matches.flatMap { listOf(it.left, it.right) }.filter { it.id.toLongOrNull() != null }.distinctBy { it.id }
        val events = (current.events.map { it.event } + current.matches.map { it.event }).filter { it.id.toLongOrNull() != null }.distinctBy { it.id }
        fun identity(t: Team) = teams.singleOrNull { it.name.equals(t.name, true) } ?: t
        val added = result.value.first.map { m -> ProfileMatch(identity(m.left), identity(m.right), events.singleOrNull { it.name == m.event.name } ?: m.event,
            m.status, m.resultAt, m.leftScore, m.rightScore, m.url) }
        // The first full results page replaces the profile's abbreviated statistics-only rows.
        // Never infer a match ID from a date, score, or a statistics ID.
        val retained = current.matches.filter { it.status != MatchStatus.FINISHED || it.matchUrl != null }
        current.copy(matches = (retained + added).distinctBy { it.key }, moreMatchesUrl = result.value.second)
    }

    suspend fun persistPlayer(value: Sourced<PlayerProfile>) = withContext(Dispatchers.IO) { records.write("player-${value.value.player.id}", Records.profile(value)) }
    suspend fun persistTeam(value: Sourced<TeamProfile>) = withContext(Dispatchers.IO) { records.write("team-${value.value.team.id}", Records.teamProfile(value)) }

    suspend fun matches(date: LocalDate, previous: Feed<Match>, forceRefresh: Boolean = false): Feed<Match> = withContext(Dispatchers.IO) {
        if (!relay().enabled) {
            val result = mobile.read(MobileRequest.create(MobileRoute.MATCHES), refresh = forceRefresh, decode = MobileMatchParser::list)
            val fresh = result.value.map { it.copy(stamp = result.stamp, note = result.warning) }
            // The endpoint has a bounded date range. Keep older known records at their original age.
            val retained = previous.items.filter { old -> old.status == MatchStatus.FINISHED && fresh.none { it.id == old.id } }.map { old ->
                val stamp = old.stamp ?: previous.stamp
                old.copy(stamp = stamp.copy(origin = if (stamp.origin == DataOrigin.SNAPSHOT) DataOrigin.SNAPSHOT else DataOrigin.CACHE))
            }
            return@withContext Feed(fresh + retained, result.stamp, result.warning).also { records.write("match-feed", Records.feed(it)) }
        }
        val matchesUrl = "https://www.hltv.org/matches"
        // The normal results page works without filter challenges and includes the latest 100 results.
        val resultsUrl = if(date >= LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1)) "https://www.hltv.org/results"
            else "https://www.hltv.org/results?startDate=${date.minusDays(1)}&endDate=${date.plusDays(1)}"
        val warnings = mutableListOf<String>()
        suspend fun part(url: String, parser: (String) -> List<Match>): List<Match>? = try {
            val result = pages.read(url, 120_000, forceRefresh = forceRefresh, parse = parser)
            result.warning?.let(warnings::add)
            result.value.map { it.copy(stamp = result.stamp(url), note = result.warning) }
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { warnings += "${if(url == matchesUrl) "赛程" else "赛果"}更新失败：${e.message ?: "网络连接失败"}"; null }
        val upcoming = part(matchesUrl, HltvParser::matches)
        val results = part(resultsUrl, HltvParser::results)
        if(upcoming == null && results == null) throw SourceUnavailable(warnings.joinToString("\n"))
        fun retained(finished: Boolean) = previous.items.filter { (it.status == MatchStatus.FINISHED) == finished }.map {
            val stamp = it.stamp ?: previous.stamp
            it.copy(stamp = if(stamp.origin == DataOrigin.SNAPSHOT) stamp else stamp.copy(origin = DataOrigin.CACHE), note = "此记录更新失败，保留原资料。")
        }
        val all = ((results ?: retained(true)) + (upcoming ?: retained(false))).distinctBy { it.id }
        val stamps = all.mapNotNull { it.stamp }
        val source = SourceStamp(matchesUrl, stamps.minOfOrNull { it.capturedAt } ?: java.time.Instant.now(),
            if(warnings.isEmpty() && stamps.all { it.origin == DataOrigin.NETWORK }) DataOrigin.NETWORK else DataOrigin.CACHE)
        Feed(all, source, warnings.distinct().takeIf { it.isNotEmpty() }?.joinToString("\n"))
            .also { records.write("match-feed", Records.feed(it)) }
    }

    fun restoredEvents(): Feed<Tournament>? = records.read("event-feed")?.let { j -> runCatching {
        Feed(j.objects("items").map(Records::event), Records.stamp(j.getJSONObject("stamp"), true))
    }.getOrNull() }

    suspend fun events(forceRefresh: Boolean = false): Feed<Tournament> = withContext(Dispatchers.IO) {
        val result = mobile.read(MobileRequest.create(MobileRoute.EVENTS), refresh = forceRefresh, decode = MobileProfileParser::events)
        Feed(result.value, result.stamp, result.warning).also { records.write("event-feed", JSONObject().put("stamp", Records.stamp(it.stamp)).put("items", JSONArray(it.items.map(Records::event)))) }
    }

    suspend fun calendarEvent(id: String): Sourced<Tournament> = withContext(Dispatchers.IO) {
        val result = mobile.read(MobileRequest.create(MobileRoute.EVENT, id)) { MobileProfileParser.event(it, id) }
        val old = restoredEvent(id)
        val content = result.value.content
        val merged = result.value.copy(content = content.copy(brackets = old?.value?.content?.brackets.orEmpty(), bracketStamps = old?.value?.content?.bracketStamps.orEmpty()))
        Sourced(merged, result.stamp, result.warning).also { records.write("event-$id", Records.eventProfile(it)) }
    }

    fun restoredRankings(): Feed<RankingEntry>? {
        val url = "https://www.hltv.org/ranking/teams"
        val cached = listOfNotNull(pages.cached(url) { HltvParser.rankings(it) to HltvParser.rankingDate(it) }, rankingHttp.cached(url) { HltvParser.rankings(it) to HltvParser.rankingDate(it) }).maxByOrNull { it.at } ?: return null
        return Feed(cached.value.first, cached.stamp(url).copy(publishedOn = cached.value.second))
    }
    fun restoredNews(): Feed<NewsItem>? {
        val rss = newsPages.cached(NewsParser.RSS, HltvParser::news)?.let { withArticlePreviews(Feed(it.value, it.stamp(NewsParser.RSS))) }
        return listOfNotNull(restoredNewsMonth(YearMonth.now()), rss).maxByOrNull { it.stamp.capturedAt }
    }
    fun restoredNewsMonth(month: YearMonth): Feed<NewsItem>? {
        val url = NewsParser.archiveUrl(month)
        val cached = newsWebsitePages.cached(url) { NewsParser.index(it, url) } ?: return null
        return withArticlePreviews(Feed(cached.value, cached.stamp(url)))
    }
    suspend fun newsMonth(month: YearMonth, refresh: Boolean = false): Feed<NewsItem> {
        require(month.year >= 2002 && month <= YearMonth.now())
        val url = NewsParser.archiveUrl(month)
        val result = newsWebsitePages.read(url, 900_000, forceRefresh = refresh) { NewsParser.index(it, url) }
        return withContext(Dispatchers.IO) { withArticlePreviews(Feed(result.value, result.stamp(url), result.warning)) }
    }
    // Reuse only a locally verified body. Archive date-only values cannot be converted
    // to an instant; once a body exists, its precise date and image survive restarts.
    private fun withArticlePreviews(feed: Feed<NewsItem>): Feed<NewsItem> = feed.copy(items = feed.items.map { item ->
        val article = restoredArticle(item.url)?.value ?: return@map item
        item.copy(publishedAt = article.publishedAt.takeIf(String::isNotBlank) ?: item.publishedAt,
            imageUrl = item.imageUrl ?: article.coverUrl ?: article.blocks.firstNotNullOfOrNull { it.imageUrl }, hasCachedBody = true)
    })
    fun restoredArticle(url: String): Sourced<NewsArticle>? {
        if (!NewsParser.allowed(url)) return null
        val cached = pages.cached(url) { NewsParser.article(it, url) } ?: return null
        return Sourced(cached.value, cached.stamp(url))
    }
    suspend fun article(url: String, refresh: Boolean = false): Sourced<NewsArticle> {
        require(NewsParser.allowed(url))
        val result = pages.read(url, 3_600_000, forceRefresh = refresh) { NewsParser.article(it, url) }
        return Sourced(result.value, result.stamp(url), result.warning)
    }

    suspend fun articlePreview(url: String): Sourced<NewsArticle> {
        require(NewsParser.allowed(url))
        val result = backgroundPages.read(url, 86_400_000) { NewsParser.article(it, url) }
        return Sourced(result.value, result.stamp(url), result.warning)
    }

    suspend fun liveScore(match: Match): LiveMatchScore? {
        val url = "https://www.hltv.org/matches"
        val page = backgroundPages.read(url, 12_000, maxStaleMs = 0) { raw ->
            // Parse/validate before committing a document to the cache.
            LiveScoreParser.parse(raw, match, SourceStamp(url, java.time.Instant.EPOCH, DataOrigin.NETWORK))
        }
        return page.value?.copy(stamp = page.stamp(url))
    }

    suspend fun hltvRankings(forceRefresh: Boolean = false, allowBrowser: Boolean = true): Feed<RankingEntry> {
        val url = "https://www.hltv.org/ranking/teams"
        val result = (if (allowBrowser) pages else rankingHttp).read(url, 21_600_000, forceRefresh = forceRefresh) { HltvParser.rankings(it) to HltvParser.rankingDate(it) }
        return Feed(result.value.first, result.stamp(url).copy(publishedOn = result.value.second), result.warning)
    }

    private fun vrsListing(year: Int) = "https://api.github.com/repos/ValveSoftware/counter-strike_regional_standings/contents/invitation/$year"
    private fun vrsEditions(raw: String): List<String> {
        val entries = JSONArray(raw)
        return completeVrsEditions((0 until entries.length()).map { entries.getJSONObject(it).getString("name") })
            .ifEmpty { throw SourceUnavailable("尚无完整的 VRS 邀请排名。") }
    }
    private fun vrsUrl(region: VrsRegion, edition: String) =
        "$rawBase/invitation/${edition.take(4)}/standings_${region.key}_$edition.md"
    private fun parseVrs(raw: String, url: String, edition: String): List<RankingEntry> {
        if (VrsParser.publishedDate(raw) != edition.replace('_', '-')) throw SourceUnavailable("VRS 榜单日期不一致。")
        return VrsParser.parse(raw, url)
    }
    fun restoredVrs(): Map<VrsRegion, Feed<RankingEntry>>? {
        val year = LocalDate.now(java.time.ZoneOffset.UTC).year
        for (y in listOf(year, year - 1)) {
            val editions = pages.cached(vrsListing(y), ::vrsEditions)?.value.orEmpty()
            for (edition in editions) {
                val bundle = VrsRegion.entries.mapNotNull { region ->
                    val url = vrsUrl(region, edition)
                    pages.cached(url) { parseVrs(it, url, edition) }?.let {
                        region to Feed(it.value, it.stamp(url).copy(publishedOn = edition.replace('_', '-')))
                    }
                }.toMap()
                if (bundle.size == VrsRegion.entries.size) return bundle
            }
        }
        return null
    }
    suspend fun vrsBundle(forceRefresh: Boolean = false): Map<VrsRegion, Feed<RankingEntry>> {
        val year = LocalDate.now(java.time.ZoneOffset.UTC).year
        val listing = pages.read(vrsListing(year), 21_600_000, forceRefresh = forceRefresh, parse = ::vrsEditions)
        val edition = listing.value.first()
        return VrsRegion.entries.associateWith { region ->
            val url = vrsUrl(region, edition)
            val result = pages.read(url, 3_600_000, forceRefresh = forceRefresh) { parseVrs(it, url, edition) }
            Feed(result.value, result.stamp(url).copy(publishedOn = edition.replace('_', '-')), result.warning ?: listing.warning)
        }
    }
    suspend fun vrs(region: VrsRegion, forceRefresh: Boolean = false): Feed<RankingEntry> = vrsBundle(forceRefresh).getValue(region)

    suspend fun news(forceRefresh: Boolean = false): Feed<NewsItem> {
        val cached = withContext(Dispatchers.IO) { restoredNews() }
        val age = cached?.let { java.time.Duration.between(it.stamp.capturedAt, java.time.Instant.now()).toMillis() }
        if (!forceRefresh && cached != null && age != null && age in 0 until 900_000) return cached
        try {
            val result = newsPages.read(NewsParser.RSS, 900_000, forceRefresh = forceRefresh, parse = HltvParser::news)
            if (result.warning != null && cached != null && cached.stamp.capturedAt > result.at)
                return cached.copy(warning = result.warning)
            return withContext(Dispatchers.IO) { withArticlePreviews(Feed(result.value, result.stamp(NewsParser.RSS), result.warning)) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (cached != null && age != null && age in 0 until 7 * 86_400_000L)
                return cached.copy(warning = e.message ?: "新闻更新失败")
            throw e
        }
    }
}
