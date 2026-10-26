package dev.matchscope.app.data

import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneOffset

internal fun JSONObject.ms(key: String): String? = if (isNull(key)) null else opt(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
internal fun JSONObject.mi(key: String): Int? = ms(key)?.toIntOrNull()
internal fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
internal fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
internal fun mobileImage(value: Any?): String? {
    val urls = when (value) { is JSONObject -> listOfNotNull(value.ms("pngUrl"),value.ms("svgOrPngUrl")); is String -> listOf(value); else -> emptyList() }.mapNotNull(MediaUrls::validated)
    MediaAssets.remember(urls)
    return urls.firstOrNull()
}
internal fun mobileTime(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
internal fun mobileId(value: String?): String = value?.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) } ?: throw MobileAccessException(MobileFailure.INVALID_JSON)
internal fun mobileTeam(j: JSONObject): Team = Team(mobileId(j.ms("teamId") ?: j.ms("id")), j.ms("name") ?: j.ms("teamName") ?: throw MobileAccessException(MobileFailure.INVALID_JSON),
    logoUrl = mobileImage(j.opt("avatar") ?: j.opt("logo") ?: j.opt("teamLogo")))
internal fun mobilePlayer(j: JSONObject): PlayerRef = PlayerRef(mobileId(j.ms("playerId")), j.ms("nick") ?: throw MobileAccessException(MobileFailure.INVALID_JSON),
    photoUrl = listOf("playerBodyShotImageUrl", "playerBodyShot", "playerBodyShotImage", "playerImageUrl", "playerImage").firstNotNullOfOrNull { mobileImage(j.opt(it)) })

/** Adapters are based on the real Android response captured on 2026-10-18. */
object MobileMatchParser {
    fun list(root: JSONObject): List<Match> {
        if (root.optJSONArray("matches") == null) throw MobileAccessException(MobileFailure.INVALID_JSON)
        return root.objects("matches").map(::summary).distinctBy { it.id }
    }
    fun summary(j: JSONObject): Match {
        val id = mobileId(j.ms("matchId"))
        fun team(key: String) = j.optJSONObject(key)?.let(::mobileTeam)
            ?: Team("pending:$id:$key", "待定")
        val result = j.optJSONObject("matchResult")
        val event = Tournament(mobileId(j.ms("eventId")), j.ms("eventName") ?: throw MobileAccessException(MobileFailure.INVALID_JSON), logoUrl = mobileImage(j.opt("eventLogo")))
        val status = when { j.optBoolean("live") -> MatchStatus.LIVE; j.optBoolean("postMatch") -> MatchStatus.FINISHED; else -> MatchStatus.UPCOMING }
        return Match(id, team("team1"), team("team2"), event, status,
            format = j.ms("mapShort")?.uppercase() ?: "赛制待确认", startsAt = mobileTime(j.ms("startDateTime")),
            leftScore = result?.mi("team1Score"), rightScore = result?.mi("team2Score"),
            stage = listOfNotNull(j.ms("placeholderText"), j.ms("team2PlaceholderText")).distinct().joinToString(" · ").takeIf(String::isNotBlank))
            .normalizePlaceholders()
    }
    fun detail(root: JSONObject, expectedId: String): Match {
        val data = mobileMatchEnvelope(root, expectedId).getJSONObject("matchData")
        val base = summary(data.getJSONObject("match"))
        val players = data.objects("allPlayers").map(::mobilePlayer).associateBy { it.id }
        fun stats(value: JSONObject?): List<PlayerStat> = listOf("team1Stats" to base.left, "team2Stats" to base.right).flatMap { (key, team) ->
            value?.objects(key).orEmpty().map { row ->
                val id = mobileId(row.ms("playerId")); val player = players[id] ?: throw MobileAccessException(MobileFailure.INVALID_JSON)
                val rating = row.optJSONObject("versionedRating")
                PlayerStat(player.name, team.id, row.mi("kills")?.let { k -> row.mi("deaths")?.let { "$k-$it" } }, row.ms("adr"), row.ms("kast"),
                    rating?.ms("value") ?: row.ms("rating"), rating?.ms("version"), player, row.ms("roundSwing"))
            }
        }
        val allStats = stats(data.optJSONObject("postMatchPlayerStats"))
        val groups = mutableListOf<MatchStatGroup>()
        if (allStats.isNotEmpty()) groups += MatchStatGroup("all", null, MatchSide.BOTH, allStats)
        val mapStats = data.objects("mapStats")
        mapStats.forEach { item ->
            val map = item.getJSONObject("mapInfo"); val name = map.getString("mapName")
            val rows = stats(item.optJSONObject("playerStats"))
            if (rows.isNotEmpty()) groups += MatchStatGroup(name.lowercase(), name, MatchSide.BOTH, rows)
        }
        val maps = data.objects("maps").map { map ->
            val score = map.optJSONObject("mapResult")
            val halves = listOfNotNull(score?.optJSONObject("firstHalf"), score?.optJSONObject("secondHalf")).mapNotNull { half ->
                half.mi("team1Score")?.let { left -> half.mi("team2Score")?.let { "$left:$it" } }
            }.takeIf { it.isNotEmpty() }?.joinToString("; ")
            MapScore(map.getString("mapName"), score?.mi("team1Score"), score?.mi("team2Score"), halves)
        }
        val lineup = data.optJSONObject("expectedLineups")
        val lineups = listOf(base.left to "team1PlayerIds", base.right to "team2PlayerIds").associate { (team, key) -> team.id to lineup?.strings(key).orEmpty().mapNotNull(players::get) }
        val duels = linkedMapOf<String, MatchDuels>()
        val source = MobileRequest.create(MobileRoute.MATCH, expectedId).url
        mapStats.forEach { value ->
            val key = value.getJSONObject("mapInfo").getString("mapName").lowercase()
            val matrices = value.objects("killMatrixes").mapNotNull { matrix(it, players) }
            if (matrices.isNotEmpty()) duels[key] = MatchDuels(source, matrices)
        }
        return base.copy(maps = maps, veto = data.ms("veto")?.lineSequence()?.filter(String::isNotBlank)?.toList().orEmpty(), stats = allStats,
            statGroups = groups, players = lineups, detailed = true, insights = insights(data, base), duels = duels).withDuelTotals()
    }
    private fun matrix(j: JSONObject, players: Map<String, PlayerRef>): DuelMatrix? {
        val metric = when (j.ms("type")) { "ALL" -> DuelMetric.ALL_KILLS; "FIRST_KILL" -> DuelMetric.OPENING_KILLS; "AWP" -> DuelMetric.AWP_KILLS; else -> return null }
        val columns = j.strings("team1PlayerIds").map { players[it] ?: throw MobileAccessException(MobileFailure.INVALID_JSON) }
        val lines = j.objects("lines")
        val rows = lines.map { players[it.getString("playerId")] ?: throw MobileAccessException(MobileFailure.INVALID_JSON) }
        if (columns.isEmpty() || rows.isEmpty() || columns.distinctBy { it.id }.size != columns.size || rows.distinctBy { it.id }.size != rows.size || rows.any { row -> columns.any { it.id == row.id } }) throw MobileAccessException(MobileFailure.INVALID_JSON)
        val cells = lines.flatMapIndexed { index, row ->
            val items = row.objects("cells")
            if (items.size != columns.size) throw MobileAccessException(MobileFailure.INVALID_JSON)
            items.mapIndexed { column, cell ->
                val kills = cell.mi("kills")?.takeIf { it >= 0 } ?: throw MobileAccessException(MobileFailure.INVALID_JSON)
                val deaths = cell.mi("deaths")?.takeIf { it >= 0 } ?: throw MobileAccessException(MobileFailure.INVALID_JSON)
                DuelCell(rows[index].id, columns[column].id, kills, deaths)
            }
        }
        return DuelMatrix(metric, rows, columns, cells)
    }
    private fun insights(j: JSONObject, match: Match): MatchInsights {
        val vrs = j.optJSONObject("vrsForCast")?.let { forecast -> MatchVrs(MatchVrsMode.FORECAST, listOf(match.left, match.right).mapIndexed { index, team ->
            val p = "team${index + 1}"; val current = forecast.mi("${p}CurrentPoints"); val rank = forecast.mi("${p}CurrentRank")
            fun impact(outcome: String) = MatchVrsImpact(forecast.mi("$p${outcome}Points")?.let { points -> current?.let { points - it } }, forecast.mi("$p${outcome}Rank"), forecast.mi("$p${outcome}Rank")?.let { next -> rank?.let { it - next } })
            MatchVrsTeam(team.id, current, rank, impact("Win"), impact("Lose"))
        }) }
        val pool = j.optJSONObject("mapTeamStats")
        val infos = pool?.optJSONObject("mapInfos")
        val pools = if (infos == null) emptyList() else MatchMapMetric.entries.map { metric ->
            val textKey = when(metric) { MatchMapMetric.WIN -> "winRate"; MatchMapMetric.PICK -> "pickPercentage"; MatchMapMetric.BAN -> "banPercentage" }
            fun rate(team: Int, id: String): MatchMapRate {
                val value = pool.optJSONObject("team${team}MapStats")?.optJSONObject(id) ?: return MatchMapRate()
                val rate = value.ms(textKey)?.let { text -> value.optDouble("${textKey}Value", Double.NaN).takeIf { it.isFinite() && it in 0.0..1.0 }?.times(100) ?: text.removeSuffix("%").toDoubleOrNull() }
                return MatchMapRate(rate, value.mi("played").takeIf { metric == MatchMapMetric.WIN })
            }
            MatchMapPool(metric, "阵容核心 · 近三个月", infos.keys().asSequence().map { id ->
                val name = infos.getJSONObject(id).getString("mapName")
                MatchMapComparison(name.lowercase(), name, rate(1, id), rate(2, id))
            }.toList())
        }
        val h2h = j.optJSONObject("headToHead")?.let { value -> MatchHeadToHead(match.left.id, match.right.id, value.mi("team1Wins"), value.mi("team2Wins"), value.mi("overtimes"),
            value.objects("matchups").map { row -> val id = mobileId(row.ms("matchId")); MatchHistoryResult(id, "https://www.hltv.org/matches/$id/-", match.left, match.right, MatchResultUnit.MAP,
                mapName = row.ms("mapName"), leftScore = row.mi("team1Score"), rightScore = row.mi("team2Score"), playedAt = mobileTime(row.ms("date")),
                event = row.ms("eventName")?.let { Tournament("unknown:$id", it, logoUrl = mobileImage(row.opt("eventLogo"))) }) },
            "https://www.hltv.org/results?team=${match.left.id}&team=${match.right.id}&requireAllTeams=true") }
        val forms = listOf(match.left, match.right).mapIndexedNotNull { index, team ->
            val rows = j.objects("team${index + 1}FormMatches")
            if (rows.isEmpty()) null else MatchRecentForm(team, MatchHistoryScope.TEAM, results = rows.map { row ->
                val id = mobileId(row.ms("matchId")); val opponent = Team("unknown:$id", row.getString("opponentName"), logoUrl = mobileImage(row.opt("opponentLogo")))
                MatchHistoryResult(id, "https://www.hltv.org/matches/$id/-", team, opponent, MatchResultUnit.SERIES, leftScore = row.mi("teamScore"), rightScore = row.mi("opponentScore"))
            }, moreUrl = "https://www.hltv.org/results?team=${team.id}")
        }
        return MatchInsights(vrs, pools, headToHead = h2h, recentForm = forms)
    }
}
