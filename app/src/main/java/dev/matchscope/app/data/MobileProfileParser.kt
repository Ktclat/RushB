package dev.matchscope.app.data

import org.json.JSONObject
import java.time.ZoneOffset

object MobileProfileParser {
    fun news(root: JSONObject): List<NewsItem> {
        if (root.optJSONArray("articles") == null) throw MobileAccessException(MobileFailure.INVALID_JSON)
        return root.objects("articles").map { row ->
            val id = mobileId(row.ms("id")); val url = row.getString("hltvNewsUrl")
            if (!HltvAccess.allowed(url) || !java.net.URI(url).path.startsWith("/news/$id/")) throw MobileAccessException(MobileFailure.WRONG_RESOURCE)
            NewsItem(row.getString("title").trim(), url, row.ms("created").orEmpty(), mobileImage(row.opt("imageUrl")))
        }
    }
    fun eventSummary(j: JSONObject): Tournament = Tournament(mobileId(j.ms("eventId")), j.ms("name") ?: j.ms("eventName") ?: throw MobileAccessException(MobileFailure.INVALID_JSON),
        startDate = mobileTime(j.ms("start_time"))?.atZone(ZoneOffset.UTC)?.toLocalDate(), endDate = mobileTime(j.ms("end_time") ?: j.ms("endDate"))?.atZone(ZoneOffset.UTC)?.toLocalDate(),
        location = j.ms("location"), prize = j.ms("prize"), teamCount = j.mi("teams_count"), logoUrl = mobileImage(j.opt("logo")), bannerUrl = mobileImage(j.opt("banner")),
        tier = if (j.optBoolean("isMajor", false)) EventTier.MAJOR else null)
    fun events(root: JSONObject): List<Tournament> {
        val keys = listOf("completed", "ongoing", "upcoming")
        if (keys.any { root.optJSONObject(it)?.optJSONArray("events") == null }) throw MobileAccessException(MobileFailure.INVALID_JSON)
        return keys.flatMap { root.getJSONObject(it).objects("events").map(::eventSummary) }.distinctBy { it.id }
    }
    fun event(root: JSONObject, id: String): Tournament {
        val event = eventSummary(root.getJSONObject("event"))
        if (event.id != id) throw MobileAccessException(MobileFailure.WRONG_RESOURCE)
        val matches = (root.objects("matches") + root.objects("results")).map(MobileMatchParser::summary).distinctBy { it.id }
        if (matches.any { it.event.id != id }) throw MobileAccessException(MobileFailure.WRONG_RESOURCE)
        val links = root.objects("bracketGroupings").flatMap { it.objects("brackets") }.mapNotNull { bracket ->
            val url = bracket.ms("embedUrl") ?: return@mapNotNull null
            if (mobileBracketUrl(url, id)) bracket.getString("name") to url else null
        }
        return event.copy(content = EventContent(teams = root.objects("teams").map(::mobileTeam),
            prizes = root.objects("prizeDistribution").map { EventPrize(it.getString("ordinal"), it.optJSONObject("team")?.let(::mobileTeam), it.ms("prizeMoney"), it.ms("clubShare")) },
            complete = true, matches = matches, bracketLinks = links, schemaVersion = 2, statistics = eventStatistics(root)))
    }
    fun player(root: JSONObject, id: String, known: List<Match> = emptyList()): PlayerProfile {
        val player = mobilePlayer(root)
        if (player.id != id) throw MobileAccessException(MobileFailure.WRONG_RESOURCE)
        val team = root.optJSONObject("team")?.let(::mobileTeam)
        val stats = root.optJSONObject("stats")
        val rating = stats?.optJSONObject("versionedRating")
        val statistics = buildList {
            rating?.ms("value")?.let { add("Rating ${rating.ms("version").orEmpty()}".trim() to it) }
            listOf("killsPrRound" to "每回合击杀", "dpr" to "每回合死亡", "adr" to "ADR", "kast" to "KAST", "roundSwing" to "Swing", "multiKills" to "多杀回合").forEach { (key, label) -> stats?.ms(key)?.let { add(label to it) } }
            listOf("ctRating" to "CT Rating", "tRating" to "T Rating").forEach { (key, label) -> stats?.optJSONObject(key)?.ms("value")?.let { add(label to it) } }
        }
        val attributes = stats?.optJSONObject("attributeScores") ?: stats
        val roles = listOf("firepower" to "Firepower", "entrying" to "Entrying", "trading" to "Trading", "opening" to "Opening", "closing" to "Clutching", "sniping" to "Sniping", "utility" to "Utility")
            .mapNotNull { (key, label) -> (attributes?.mi(key) ?: if (key == "closing") attributes?.mi("clutching") else null)?.takeIf { it in 0..100 }?.let { RoleStat(label, it) } }
        val career = root.objects("teamHistory").map { row ->
            val start = mobileTime(row.ms("startTime"))?.atZone(ZoneOffset.UTC)?.toLocalDate()
            val end = mobileTime(row.ms("endTime"))?.atZone(ZoneOffset.UTC)?.toLocalDate()
            CareerTeam(mobileTeam(row), start, end, "${start ?: "—"} — ${end ?: "至今"}")
        }.reversed()
        val summary = buildList {
            root.ms("prizeMoney")?.let { add("累计奖金" to it) }
            root.mi("majorWins")?.let { add("Major 冠军" to it.toString()) }
            root.mi("majorMvps")?.let { add("Major MVP" to it.toString()) }
            root.strings("roles").takeIf { it.isNotEmpty() }?.let { add("位置" to it.joinToString(" · ")) }
            val trophies = root.optJSONObject("trophies")
            trophies?.optJSONArray("placementTrophies")?.let { add("冠军" to it.length().toString()) }
            trophies?.optJSONArray("mvpTrophies")?.let { add("MVP" to it.length().toString()) }
            trophies?.optJSONArray("evpTrophies")?.let { add("EVP" to it.length().toString()) }
            trophies?.optJSONArray("top20Trophies")?.let { add("年度 Top 20" to it.length().toString()) }
        }
        return PlayerProfile(player, root.ms("name"), root.ms("country"), root.mi("age"), team, statistics,
            content = ProfileContent(matches = matches(root, known), events = profileEvents(root, team), roles = roles, career = career, summary = summary, schemaVersion = 2,
                honors = playerHonors(root), teammates = peers(root, "teammates"), similarPlayers = peers(root, "similarPlayers"), earnings = earnings(root),
                moreMatchesUrl = "https://www.hltv.org/results?player=$id", moreEventsUrl = "https://www.hltv.org/stats/players/events/$id/-"))
    }
    fun team(root: JSONObject, id: String, known: List<Match> = emptyList()): TeamProfile {
        if ((root.ms("teamId") ?: root.ms("teamName")) != id) throw MobileAccessException(MobileFailure.WRONG_RESOURCE)
        val players = root.objects("activeLineup").map(::mobilePlayer)
        val country = root.ms("countryName") ?: "未分类"
        val region = when (country) { "Europe" -> "欧洲"; "Asia" -> "亚洲"; "North America", "South America" -> "美洲"; else -> country }
        val team = Team(id, root.getString("name"), region, players.map { it.name }, logoUrl = mobileImage(root.opt("logo")))
        val roster = listOf("activeLineup" to "主力", "benchedPlayers" to "替补").flatMap { (key, status) -> root.objects(key).map { RosterMember(mobilePlayer(it), it.ms("country"), status, rating = it.optJSONObject("rating")?.ms("value"), roles = it.strings("roles")) } } + listOfNotNull(root.optJSONObject("coach")?.takeIf { it.ms("playerId") != null }?.let { RosterMember(mobilePlayer(it), it.ms("country"), "教练", roles = it.strings("roles")) })
        val summary = listOfNotNull(root.ms("winRate")?.let { "胜率" to it }, root.mi("vrsRank")?.let { "VRS 排名" to "#$it" }, root.optJSONArray("trophies")?.let { "冠军" to it.length().toString() })
        return TeamProfile(team, players, root.mi("worldRank"), root.optJSONObject("coach")?.ms("nick"),
            ProfileContent(matches = matches(root, known), events = profileEvents(root, team), roster = roster, summary = summary, schemaVersion = 2, honors = root.objects("trophies").map { honor(it, "冠军") },
                moreMatchesUrl = "https://www.hltv.org/results?team=$id", moreEventsUrl = "https://www.hltv.org/stats/teams/events/$id/-"))
    }
    private fun eventStatistics(root: JSONObject): List<EventStatistics> {
        val stats = root.optJSONObject("stats") ?: return emptyList()
        return listOf("topPlayers" to "全部", "playOffTopPlayers" to "淘汰赛").mapNotNull { (key, label) ->
            val rows = stats.objects(key).map { p -> val rating = p.optJSONObject("rating")
                EventPlayerStats(mobilePlayer(p), p.ms("teamName"), mobileImage(p.opt("teamLogo")), rating?.ms("value"), rating?.ms("version"), p.mi("mapCount")) }
            rows.takeIf { it.isNotEmpty() }?.let { EventStatistics(key,label,it) }
        }
    }
    private fun honor(j: JSONObject, category: String): ProfileHonor {
        val eventId = j.ms("eventId")?.let(::mobileId)
        val event = eventId?.let { Tournament(it, j.ms("eventName") ?: j.getString("shortEventName"),
            endDate = mobileTime(j.ms("date"))?.atZone(ZoneOffset.UTC)?.toLocalDate()) }
        val year = j.mi("year")?.let { if (it in 0..99) it + 2000 else it }
        val title = event?.name ?: j.ms("name") ?: if (category == "Top 20") "$year Top 20" else category
        val news = j.ms("newsId")?.let(::mobileId)?.let { "https://www.hltv.org/news/$it/-" }
        return ProfileHonor(category,title,event,year,j.mi("placement"),mobileImage(j.opt("trophyImg")),news)
    }
    private fun playerHonors(root: JSONObject): List<ProfileHonor> {
        val trophies = root.optJSONObject("trophies") ?: return emptyList()
        return listOf("placementTrophies" to "冠军", "mvpTrophies" to "MVP", "evpTrophies" to "EVP", "top20Trophies" to "Top 20", "hltvAwards" to "年度奖项", "grandSlamTrophies" to "大满贯")
            .flatMap { (key,label) -> trophies.objects(key).map { honor(it,label) } }.distinctBy { it.key }
    }
    private fun peers(root: JSONObject, key: String) = root.objects(key).map { row ->
        val rating = row.optJSONObject("rating")
        ProfilePeer(mobilePlayer(row),row.ms("teamName"),rating?.ms("value"),rating?.ms("version"),row.strings("roles"))
    }
    private fun earnings(root: JSONObject) = root.objects("earningsByTeam").map { row ->
        val name = row.getString("teamName")
        val id = row.ms("teamId")?.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) } ?: "historical:$name"
        TeamEarning(Team(id,name,logoUrl = mobileImage(row.opt("teamLogo"))),row.getString("prizeMoney"),mobileTime(row.ms("mostRecentEvent"))?.atZone(ZoneOffset.UTC)?.toLocalDate())
    }
    private fun profileEvents(root: JSONObject, current: Team?): List<ProfileEvent> = listOf("upcomingEvents" to true, "events" to false).flatMap { (key, upcoming) -> root.objects(key).map { row ->
        val name = row.ms("teamName")
        val historical = root.objects("teamHistory").firstOrNull { it.ms("name") == name }?.let(::mobileTeam)
        val team = if (name == null || current?.name == name) current else historical ?: Team("historical:$name", name, logoUrl = mobileImage(row.opt("teamLogo")))
        val extra = root.objects("playerActiveEventExtraData").firstOrNull { it.ms("eventId") == row.ms("eventId") }
        val statistics = listOfNotNull(row.mi("maps")?.let { "地图" to it.toString() }, row.optJSONObject("rating")?.let { rating -> rating.ms("value")?.let { "Rating ${rating.ms("version").orEmpty()}".trim() to it } }) + listOf("ctRating" to "CT Rating", "tRating" to "T Rating").mapNotNull { (key, label) -> extra?.optJSONObject(key)?.ms("value")?.let { label to it } }
        ProfileEvent(eventSummary(row), row.ms("placement"), team, upcoming || row.optBoolean("isLive"), statistics = statistics)
    } }
    private fun matches(root: JSONObject, known: List<Match>): List<ProfileMatch> = listOf("upcomingMatches" to false, "recentMatches" to true).flatMap { (key, finished) -> root.objects(key).map { row ->
        val id = mobileId(row.ms("matchId")); val left = mobileTeam(row.getJSONObject("ownTeam")); val right = mobileTeam(row.getJSONObject("opponentTeam"))
        val detail = known.firstOrNull { it.id == id }
        val time = mobileTime(row.ms("startDateTime")) ?: detail?.startsAt ?: detail?.resultAt
        val event = detail?.event ?: Tournament("unknown:$id", "—")
        ProfileMatch(left, right, event, if (row.optBoolean("live")) MatchStatus.LIVE else if (finished) MatchStatus.FINISHED else MatchStatus.UPCOMING,
            time, row.mi("ownTeamScore"), row.mi("opponentTeamScore") ?: row.mi("opponentScore"), "https://www.hltv.org/matches/$id/-")
    } }
}

internal fun mobileBracketUrl(url: String, eventId: String): Boolean = runCatching {
    val uri = java.net.URI(url)
    uri.scheme == "https" && uri.host == "www.hltv.org" && uri.port == -1 && uri.userInfo == null && uri.fragment == null && uri.path == "/mobile/embeds/Brackets" &&
        Regex("eventId=$eventId&bracketId=[1-9][0-9]{0,9}").matches(uri.rawQuery.orEmpty())
}.getOrDefault(false)
