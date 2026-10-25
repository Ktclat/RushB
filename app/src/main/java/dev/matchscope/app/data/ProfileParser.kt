package dev.matchscope.app.data

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Public profile tabs are present in the page DOM even while their web tabs are hidden. */
object ProfileParser {
    /** Keep the source's query verbatim so a player/team filter is not lost on the next page. */
    fun resultsNextUrl(doc: Document): String? = doc.select(".pagination-next[href]:not(.inactive)").firstNotNullOfOrNull { link ->
        link.safeUrl("/results").notBlank()?.takeIf { url ->
            val uri = URI(url)
            uri.path == "/results" && uri.rawFragment == null
        }
    }

    fun player(doc: Document): ProfileContent {
        val matches = matches(doc)
        val career = doc.select(".team-breakdown tr.team").mapNotNull { row ->
            val team = row.selectFirst(".team-name-cell a[href^=/team/]")?.let(::team) ?: return@mapNotNull null
            val dates = row.select(".time-period-cell [data-unix]").mapNotNull { it.instant()?.utcDate() }
            val statisticsUrl = row.selectFirst("a[href^=/stats/players/matches/]")?.safeUrl("/stats/players/matches/")
            // These periods are local calendar dates: the stats link supplies the exact dates,
            // whereas converting the page's midnight CET timestamps to UTC loses a day.
            CareerTeam(team, queryDate(statisticsUrl, "startDate") ?: dates.getOrNull(0),
                if (dates.size > 1) queryDate(statisticsUrl, "endDate") ?: dates[1] else null, row.value(".time-period-cell"),
                row.select(".trophy-row-trophy a[href^=/events/]").mapNotNull(::event).distinctBy { it.id })
        }
        val roles = doc.select(".playerpage-container .player-stat").mapNotNull { row ->
            val label = row.selectFirst(".player-stat-top > b")?.text()?.trim() ?: return@mapNotNull null
            val score = row.selectFirst(".statsVal p > b")?.text()?.toIntOrNull() ?: return@mapNotNull null
            // A missing or malformed source score is not a zero. Sniping=0 is a real score.
            if (score !in 0..100) null else RoleStat(label, score)
        }.distinctBy { it.label }
        val summary = buildList {
            doc.selectFirst(".dob-popup-row")?.children()?.getOrNull(1)?.text()?.takeIf(String::isNotBlank)?.let { add("Born" to it) }
            doc.value(".playerPrizeMoney .listRight").notBlank()?.let { add("Prize money" to it) }
            addAll(highlights(doc, "#teamsBox .highlighted-stat, #majorAchievement .highlighted-stat, #lanAchievement .highlighted-stat, #Trophies .highlighted-stat, #EVPs .highlighted-stat"))
            if (doc.selectFirst("#MVPs") != null) add("MVP medals" to doc.select("#MVPs tr:has(a[href^=/events/])").size.toString())
            for ((selector, label) in listOf("#Top20 tr:has(td)" to "Top 20", "#Awards tr:has(td)" to "Award")) {
                doc.select(selector).forEach { row ->
                    val year = row.value(".trophy-rating-number")
                    val title = row.value(".trophy-event").ifBlank { row.selectFirst("td")?.text().orEmpty() }
                    if (title.isNotBlank()) add(listOf(label, year).filter(String::isNotBlank).joinToString(" · ") to title)
                }
            }
        }.distinct()
        return ProfileContent(
            matches = matches,
            events = (matchEvents(matches) + achievements(doc) + trophies(doc)).distinctBy { listOf(it.event.id, it.category, it.placement, it.team?.id, it.upcoming) },
            news = news(doc), career = career, roles = roles, summary = summary,
            moreMatchesUrl = moreResults(doc),
            moreEventsUrl = playerEventsUrl(doc),
            complete = doc.select("#matchesBox, #newsBox, #teamsBox").isNotEmpty(),
        )
    }

    fun team(doc: Document): ProfileContent {
        val matches = matches(doc)
        val summary = buildList {
            doc.select(".profile-team-stat").forEach { row ->
                val label = row.selectFirst("b")?.clone()?.also { it.select(".regional-name-short").remove() }?.text()?.trim().orEmpty()
                val value = row.value(".right")
                if (label.isNotBlank() && value.isNotBlank()) add(label to value)
            }
            addAll(highlights(doc, "#matchesBox .highlighted-stat, #majorAchievement .highlighted-stat, #lanAchievement .highlighted-stat"))
            doc.select("#rosterBox .coach-table tr:has(td)").firstOrNull()?.let { row ->
                val cells = row.select("td")
                listOf(1 to "Coach tenure", 2 to "Maps coached", 3 to "Coach trophies", 4 to "Coach win rate").forEach { (index, name) ->
                    cells.getOrNull(index)?.text()?.takeIf(String::isNotBlank)?.let { add(name to it) }
                }
            }
        }.distinct()
        val roster = doc.select("#rosterBox .players-table tr:has(td)").mapNotNull { row ->
            val link = row.selectFirst("a[href^=/player/]") ?: return@mapNotNull null
            val pid = resourceId(link.attr("href"), "player") ?: return@mapNotNull null
            val name = row.value(".playersBox-playernick > .text-ellipsis")
                .ifBlank { row.value(".playersBox-playernick") }
            if (name.isBlank()) return@mapNotNull null
            val cells = row.select("td")
            RosterMember(PlayerRef(pid, name, link.safeUrl("/player/"), row.artwork(".playerBox-bodyshot")),
                country = row.selectFirst(".flag")?.attr("alt")?.takeIf(String::isNotBlank),
                status = row.value(".player-status").notBlank(), tenure = cells.getOrNull(2)?.text()?.notBlank(),
                maps = cells.getOrNull(3)?.text()?.replace(",", "")?.toIntOrNull(), rating = row.value(".rating-cell").notBlank())
        }.distinctBy { it.player.id }
        val events = listOf("#ongoingEvents" to true, "#endedEvents" to false).flatMap { (selector, future) ->
            doc.select("$selector > .upcoming-events-holder > a[href^=/events/]").mapNotNull { row ->
                val identity = event(row) ?: return@mapNotNull null
                val dates = row.select(".eventbox-date [data-unix]").mapNotNull { it.instant()?.utcDate() }
                ProfileEvent(identity.copy(startDate = dates.firstOrNull(), endDate = dates.lastOrNull()),
                    placement = row.value(".achievement").notBlank(), upcoming = future, category = "参赛")
            }
        }
        val maps = doc.select("#statsBox .map-statistics-container").mapNotNull { row ->
            val name = row.value(".map-statistics-row-map-mapname")
            if (name.isBlank()) return@mapNotNull null
            val wdl = highlights(row, ".map-statistics-extended-wdl .highlighted-stat").toMap()
            val other = row.select(".map-statistics-extended-general-stat, .map-statistics-extended-highlight-veto").mapNotNull { pair ->
                val cells = pair.children()
                val label = cells.getOrNull(0)?.text().orEmpty()
                val value = cells.getOrNull(1)?.text().orEmpty()
                if (label.isBlank() || value.isBlank()) null else label to value
            }
            MapPerformance(name, row.value(".map-statistics-row-win-percentage"),
                wdl["Win"]?.toIntOrNull(), wdl["Draws"]?.toIntOrNull(), wdl["Losses"]?.toIntOrNull(), other)
        }
        return ProfileContent(matches = matches, events = (events + achievements(doc)).distinctBy { listOf(it.event.id, it.category, it.upcoming) },
            news = news(doc), roster = roster, maps = maps, summary = summary,
            moreMatchesUrl = moreResults(doc),
            moreEventsUrl = doc.selectFirst("a[href^=/stats/teams/events/]")?.safeUrl("/stats/teams/events/")?.notBlank(),
            complete = doc.select("#matchesBox, #newsBox, #eventsBox, #rosterBox").isNotEmpty())
    }

    private fun matches(doc: Document): List<ProfileMatch> = doc.select("#matchesBox .match-table").flatMap { table ->
        var tournament: Tournament? = null
        table.select("tr").mapNotNull { row ->
            if (row.hasClass("event-header-cell")) {
                tournament = row.selectFirst("a[href^=/events/]")?.let(::event)
                return@mapNotNull null
            }
            if (!row.hasClass("team-row")) return@mapNotNull null
            val teams = row.select("a.team-name[href^=/team/]").mapNotNull { link ->
                team(link)?.let { found ->
                    found.copy(logoUrl = row.select(".team-logo-container a[href^=/team/]")
                        .firstOrNull { resourceId(it.attr("href"), "team") == found.id }?.artwork("img"))
                }
            }
            if (teams.size != 2 || tournament == null) return@mapNotNull null
            val matchUrl = row.selectFirst("a[href^=/matches/]")?.safeUrl("/matches/")?.notBlank()
            val statsUrl = row.selectFirst("a[href^=/stats/matches/]")?.safeUrl("/stats/matches/")?.notBlank()
            if (matchUrl == null && statsUrl == null) return@mapNotNull null
            val scores = row.select(".score-cell > .score").map { it.text().toIntOrNull() }
            val status = if (scores.size == 2 && scores.all { it != null }) MatchStatus.FINISHED else MatchStatus.UPCOMING
            ProfileMatch(teams[0], teams[1], tournament!!, status, row.selectFirst(".date-cell [data-unix]")?.instant(),
                scores.getOrNull(0), scores.getOrNull(1), matchUrl, statsUrl)
        }
    }.distinctBy { it.matchUrl ?: it.statisticsUrl }

    private fun matchEvents(matches: List<ProfileMatch>) = matches.map { match ->
        ProfileEvent(match.event, upcoming = match.status != MatchStatus.FINISHED, category = "参赛")
    }.distinctBy { it.event.id to it.upcoming }

    private fun achievements(doc: Document): List<ProfileEvent> = listOf("#majorAchievement" to "Major", "#lanAchievement" to "LAN").flatMap { (selector, category) ->
        doc.select("$selector .achievement-table tr.team").mapNotNull { row ->
            val event = row.selectFirst(".tournament-name-cell a[href^=/events/]")?.let(::event) ?: return@mapNotNull null
            ProfileEvent(event, row.value(".achievement").notBlank(), row.selectFirst(".team-name-cell a[href^=/team/]")?.let(::team), category = category)
        }
    }

    private fun trophies(doc: Document): List<ProfileEvent> = listOf("#Trophies" to "冠军", "#MVPs" to "MVP", "#EVPs" to "EVP").flatMap { (selector, category) ->
        doc.select("$selector tr:has(td)").mapNotNull { row ->
            val identity = row.selectFirst("a[href^=/events/]")?.let(::event) ?: return@mapNotNull null
            ProfileEvent(identity.copy(logoUrl = row.artwork(".trophy-icon") ?: identity.logoUrl),
                placement = if (category == "冠军") "1st" else null, category = category)
        }
    }

    private fun news(doc: Document): List<NewsItem> = doc.select("#newsBox a.subTab-newsArticle[href^=/news/]").mapNotNull { row ->
        val url = row.safeUrl("/news/").notBlank() ?: return@mapNotNull null
        val title = row.clone().also { it.select(".subTab-newsDate").remove() }.text().trim()
        if (title.isBlank()) null else NewsItem(title, url, row.selectFirst(".subTab-newsDate")?.instant()?.toString()
            ?: row.value(".subTab-newsDate"))
    }.distinctBy { it.url }

    private fun highlights(root: Element, selector: String) = root.select(selector).mapNotNull { row ->
        val label = row.value(".description")
        val value = row.value(".stat")
        val period = row.value(".stat-timespan")
        if (label.isBlank() || value.isBlank()) null else listOf(label, period).filter(String::isNotBlank).joinToString(" · ") to value
    }

    private fun moreResults(doc: Document): String? = doc.select("#matchesBox a[href^=/results]").firstOrNull()?.safeUrl("/results")?.notBlank()

    private fun playerEventsUrl(doc: Document): String? {
        if (doc.value(".playerNickname").isBlank()) return null
        val canonical = doc.selectFirst("link[rel=canonical]")?.safeUrl("/player/")?.notBlank() ?: return null
        val uri = URI(canonical)
        if (!Regex("^/player/\\d+/[^/]+$").matches(uri.path)) return null
        return "https://${uri.host}${uri.rawPath.replaceFirst("/player/", "/stats/players/events/")}"
    }

    private fun team(link: Element): Team? {
        val tid = resourceId(link.attr("href"), "team") ?: return null
        val url = link.safeUrl("/team/").notBlank() ?: return null
        val name = link.selectFirst(".team-name")?.text()?.notBlank() ?: link.text().notBlank() ?: link.selectFirst("img")?.attr("alt")?.notBlank() ?: return null
        return Team(tid, name, url = url, logoUrl = link.artwork("img"))
    }

    private fun event(link: Element): Tournament? {
        val eid = resourceId(link.attr("href"), "events") ?: return null
        val url = link.safeUrl("/events/").notBlank() ?: return null
        val name = link.value(".eventbox-eventname").notBlank() ?: link.text().notBlank()
            ?: link.selectFirst("img")?.let { it.attr("title").notBlank() ?: it.attr("alt").notBlank() } ?: return null
        // Profile result headings append a placement to the actual event name.
        val cleanName = name.replace(Regex(" - (?:\\d+(?:-\\d+)?(?:st|nd|rd|th)|Stage \\d+|Legends|Challengers|\\d+/\\d+ final)$"), "")
        return Tournament(eid, cleanName, url = url, logoUrl = link.artwork(".eventbox-eventlogo img, img"))
    }

    private fun Element.safeUrl(prefix: String): String {
        // Resolve the original href ourselves: Jsoup.absUrl normalizes credentials away,
        // which would make a credential-bearing source link appear to pass this policy.
        val source = runCatching { URI(attr("href").trim()) }.getOrNull() ?: return ""
        if (source.rawUserInfo != null) return ""
        val uri = runCatching { if (source.isAbsolute) source else URI(baseUri().ifBlank { "https://www.hltv.org" }).resolve(source) }.getOrNull() ?: return ""
        return uri.toString().takeIf { uri.scheme == "https" && uri.host in setOf("hltv.org", "www.hltv.org") && uri.rawUserInfo == null && uri.port == -1 && uri.path.startsWith(prefix) }.orEmpty()
    }
    private fun queryDate(url: String?, key: String): LocalDate? = url?.let {
        runCatching { URI(it).rawQuery.orEmpty().split('&').firstOrNull { part -> part.startsWith("$key=") }
            ?.substringAfter('=')?.let(LocalDate::parse) }.getOrNull()
    }
    private fun resourceId(url: String, kind: String) = Regex("^/(?:$kind)/(\\d+)(?:/|$)").find(runCatching { URI(url).path }.getOrDefault(url))?.groupValues?.get(1)
    private fun Element.value(selector: String) = selectFirst(selector)?.text()?.trim().orEmpty()
    private fun Element.instant(): Instant? = attr("data-unix").toLongOrNull()?.let { runCatching { Instant.ofEpochMilli(it) }.getOrNull() }
    private fun Instant.utcDate(): LocalDate = atZone(ZoneOffset.UTC).toLocalDate()
    private fun String.notBlank(): String? = trim().takeIf { it.isNotEmpty() && it != "-" }
    private fun Element.artwork(selector: String): String? = select(selector).firstOrNull { !it.hasClass("night-only") }?.let { image ->
        val retina = image.attr("srcset").split(',').firstOrNull { it.trim().endsWith(" 2x") }?.trim()?.substringBefore(' ')
        MediaUrls.validated(retina) ?: MediaUrls.validated(image.absUrl("src"))
    }
}
