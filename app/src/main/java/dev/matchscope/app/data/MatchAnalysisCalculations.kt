package dev.matchscope.app.data

import java.net.URI
import java.util.Locale

/** Calculations keep match, map, team-history, and profile-time windows separate. */
object MatchAnalysisCalculations {
    fun headToHeadMaps(match: Match): List<MatchHistoryResult> {
        val current = if (match.status == MatchStatus.FINISHED) match.maps.filter { it.left != null && it.right != null }.map {
            MatchHistoryResult(match.id, match.url, match.left, match.right, MatchResultUnit.MAP, mapName = it.name,
                leftScore = it.left, rightScore = it.right, playedAt = match.startsAt ?: match.resultAt, event = match.event)
        } else emptyList()
        return (current.reversed() + match.insights.headToHead?.results.orEmpty()).distinctBy { "${it.matchId}:${it.mapName}:${it.unit}" }
    }

    private val kdPattern = Regex("^(\\d+)\\s*[-:]\\s*(\\d+)$")
    private val publishedRatingLabel = Regex("^Team\\s+rating\\s+(\\d+(?:\\.\\d+)?)$", RegexOption.IGNORE_CASE)

    /** ProfileParser's #statsBox maps are the team page's stated past-three-month statistics. */
    fun profileMapPool(match: Match, left: TeamProfile?, right: TeamProfile?): MatchMapPool? {
        if (left?.team?.id != match.left.id || right?.team?.id != match.right.id) return null
        if (match.insights.mapPools.any { it.metric == MatchMapMetric.WIN && it.maps.isNotEmpty() }) return null
        val a = left.content.maps.filter { it.name.isNotBlank() }.associateBy { it.name.trim().lowercase(Locale.ROOT) }
        val b = right.content.maps.filter { it.name.isNotBlank() }.associateBy { it.name.trim().lowercase(Locale.ROOT) }
        val keys = (a.keys + b.keys).distinct()
        if (keys.isEmpty()) return null
        fun rate(map: MapPerformance?): MatchMapRate {
            if (map == null) return MatchMapRate()
            val percentage = map.winRate.trim().takeIf { it.endsWith('%') }?.dropLast(1)?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }
            val counts = listOf(map.wins, map.draws, map.losses)
            val total = if (counts.all { it != null && it >= 0 }) counts.sumOf { it!!.toLong() }.takeIf { it <= Int.MAX_VALUE }?.toInt() else null
            return MatchMapRate(percentage, total)
        }
        return MatchMapPool(
            MatchMapMetric.WIN, "战队近3个月地图胜率",
            keys.map { key -> MatchMapComparison(key, (a[key]?.name ?: b[key]!!.name).trim(), rate(a[key]), rate(b[key])) },
            left.team.url.takeIf(HltvAccess::allowed), right.team.url.takeIf(HltvAccess::allowed),
        )
    }

    /** Complete comparison rows: published rankings plus precisely labelled match/history calculations. */
    fun comparisonRows(match: Match): List<MatchComparison> {
        val rows = match.insights.comparisons.filterNot { publishedRatingLabel.matches(it.label.trim()) }.toMutableList()
        fun add(label: String, left: String?, right: String?) {
            if (left != null || right != null) rows += MatchComparison(label, left, right)
        }
        val sides = listOf(match.left.id, match.right.id).associateWith { id -> match.stats.filter { it.teamId == id } }
        fun kd(id: String): String? {
            val players = sides.getValue(id)
            if (players.isEmpty()) return null
            val values = players.map { player ->
                val value = player.kd?.trim()?.let(kdPattern::matchEntire) ?: return null
                val kills = value.groupValues[1].toLongOrNull() ?: return null
                val deaths = value.groupValues[2].toLongOrNull() ?: return null
                kills to deaths
            }
            val kills = values.sumOf { it.first.toDouble() }
            val deaths = values.sumOf { it.second.toDouble() }
            return if (deaths > 0) decimal(kills / deaths) else null
        }
        add("本场 K/D", kd(match.left.id), kd(match.right.id))

        // A parser may supply the actual overview-page team rating under its original source label.
        val published = match.insights.comparisons.firstOrNull { row ->
            publishedRatingLabel.matches(row.label.trim()) && (number(row.left) != null || number(row.right) != null)
        }
        if (published != null) {
            val version = publishedRatingLabel.matchEntire(published.label.trim())!!.groupValues[1]
            add("本场 Rating $version", number(published.left)?.let(::decimal), number(published.right)?.let(::decimal))
        } else {
            val versions = sides.values.flatten().mapNotNull { it.ratingVersion?.trim()?.takeIf(String::isNotBlank) }.distinct()
            if (versions.size == 1) {
                val version = versions.single()
                fun average(id: String): String? {
                    val players = sides.getValue(id)
                    if (players.isEmpty() || players.any { it.ratingVersion?.trim() != version }) return null
                    val ratings = players.map { number(it.rating) ?: return null }
                    return decimal(ratings.average())
                }
                add("本场 Rating $version（选手均值）", average(match.left.id), average(match.right.id))
            }
        }
        add("近5场胜率", recentWinRate(match, match.left.id), recentWinRate(match, match.right.id))
        match.insights.headToHead?.let { history ->
            val values = mapOf(history.leftTeamId to history.leftMapWins, history.rightTeamId to history.rightMapWins)
            add("赛前交手地图胜场", values[match.left.id]?.toString(), values[match.right.id]?.toString())
        }
        return rows.distinctBy { it.label }
    }

    /** MAP rows are a route into the real match; their round score is never a series score. */
    fun historyMatch(row: MatchHistoryResult): Match {
        require(row.matchId.matches(Regex("\\d+"))) { "比赛编号无效" }
        require(HltvAccess.allowed(row.matchUrl) && Regex("^/matches/${row.matchId}(?:/.*)?$").matches(URI(row.matchUrl).path)) { "比赛链接与编号不一致" }
        val event = row.event ?: Tournament("history-${row.matchId}", "赛事待确认", url = "")
        return Match(
            row.matchId, row.left, row.right, event, MatchStatus.FINISHED,
            format = row.format?.takeIf(String::isNotBlank) ?: "赛制待确认",
            leftScore = row.leftScore.takeIf { row.unit == MatchResultUnit.SERIES },
            rightScore = row.rightScore.takeIf { row.unit == MatchResultUnit.SERIES },
            maps = if (row.unit == MatchResultUnit.MAP && !row.mapName.isNullOrBlank()) listOf(MapScore(row.mapName, row.leftScore, row.rightScore)) else emptyList(),
            url = row.matchUrl, resultAt = row.playedAt, detailed = false,
        )
    }

    private fun recentWinRate(match: Match, teamId: String): String? {
        val form = match.insights.recentForm.firstOrNull { it.scope == MatchHistoryScope.TEAM && it.team.id == teamId } ?: return null
        val current = if (match.status == MatchStatus.FINISHED && match.leftScore != null && match.rightScore != null && match.leftScore >= 0 && match.rightScore >= 0)
            MatchHistoryResult(match.id, match.url, match.left, match.right, MatchResultUnit.SERIES,
                format = match.format, leftScore = match.leftScore, rightScore = match.rightScore)
        else null
        val results = (listOfNotNull(current) + form.results).distinctBy { it.matchId }.mapNotNull { row ->
            if (row.left.id != teamId && row.right.id != teamId) return@mapNotNull null
            val left = row.leftScore?.takeIf { it >= 0 } ?: return@mapNotNull null
            val right = row.rightScore?.takeIf { it >= 0 } ?: return@mapNotNull null
            val own = if (row.left.id == teamId) left else right
            val other = if (row.left.id == teamId) right else left
            own > other
        }.take(5)
        if (results.isEmpty()) return null
        val wins = results.count { it }
        val percent = String.format(Locale.ROOT, "%.0f", wins * 100.0 / results.size)
        return "$percent%（$wins/${results.size}）"
    }

    private fun number(raw: String?) = raw?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    private fun decimal(value: Double) = String.format(Locale.ROOT, "%.2f", value)
}
