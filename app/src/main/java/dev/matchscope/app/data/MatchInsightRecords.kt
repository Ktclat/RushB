package dev.matchscope.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Match analysis is optional in records written before these sections were available. */
internal object MatchInsightRecords {
    private fun JSONObject.s(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
    private fun JSONObject.n(key: String): Int? = if (!has(key) || isNull(key)) null else getInt(key)
    private fun JSONObject.d(key: String): Double? = if (!has(key) || isNull(key)) null else getDouble(key)
    private fun <T> JSONObject.list(key: String, decode: (JSONObject) -> T): List<T> = optJSONArray(key)?.let { array ->
        (0 until array.length()).map { decode(array.getJSONObject(it)) }
    }.orEmpty()

    fun insights(value: MatchInsights): JSONObject = JSONObject()
        .put("vrs", value.vrs?.let(::vrs))
        .put("mapPools", JSONArray(value.mapPools.map(::mapPool)))
        .put("comparisons", JSONArray(value.comparisons.map { comparison ->
            JSONObject().put("label", comparison.label).put("left", comparison.left).put("right", comparison.right)
        }))
        .put("headToHead", value.headToHead?.let(::headToHead))
        .put("recentForm", JSONArray(value.recentForm.map(::recentForm)))
        .put("complete", value.complete)

    fun insights(j: JSONObject?): MatchInsights {
        if (j == null) return MatchInsights(complete = false)
        return MatchInsights(
            vrs = j.optJSONObject("vrs")?.let(::vrs),
            mapPools = j.list("mapPools", ::mapPool),
            comparisons = j.list("comparisons") { MatchComparison(it.getString("label"), it.s("left"), it.s("right")) },
            headToHead = j.optJSONObject("headToHead")?.let(::headToHead),
            recentForm = j.list("recentForm", ::recentForm),
            complete = j.optBoolean("complete", false),
        )
    }

    fun duels(value: Sourced<MatchDuels>): JSONObject = JSONObject()
        .put("stamp", Records.stamp(value.stamp)).put("warning", value.warning)
        .put("sourceUrl", value.value.sourceUrl)
        .put("matrices", JSONArray(value.value.matrices.map { matrix ->
            JSONObject().put("metric", matrix.metric.name)
                .put("rowPlayers", JSONArray(matrix.rowPlayers.map(Records::player)))
                .put("columnPlayers", JSONArray(matrix.columnPlayers.map(Records::player)))
                .put("cells", JSONArray(matrix.cells.map { cell ->
                    JSONObject().put("rowPlayerId", cell.rowPlayerId).put("columnPlayerId", cell.columnPlayerId)
                        .put("rowKills", cell.rowKills).put("columnKills", cell.columnKills)
                }))
        }))

    fun duels(j: JSONObject): Sourced<MatchDuels> = Sourced(
        value = MatchDuels(
            sourceUrl = j.getString("sourceUrl"),
            matrices = j.list("matrices") { matrix ->
                DuelMatrix(
                    metric = DuelMetric.valueOf(matrix.getString("metric")),
                    rowPlayers = matrix.list("rowPlayers", Records::player),
                    columnPlayers = matrix.list("columnPlayers", Records::player),
                    cells = matrix.list("cells") { cell ->
                        DuelCell(cell.getString("rowPlayerId"), cell.getString("columnPlayerId"), cell.getInt("rowKills"), cell.getInt("columnKills"))
                    },
                )
            },
        ),
        stamp = Records.stamp(j.getJSONObject("stamp"), cached = true),
        warning = j.s("warning"),
    )

    private fun impact(value: MatchVrsImpact): JSONObject = JSONObject()
        .put("pointsChange", value.pointsChange).put("rank", value.rank).put("rankChange", value.rankChange)

    private fun impact(j: JSONObject) = MatchVrsImpact(j.n("pointsChange"), j.n("rank"), j.n("rankChange"))

    private fun vrs(value: MatchVrs): JSONObject = JSONObject().put("mode", value.mode.name)
        .put("teams", JSONArray(value.teams.map { team ->
            JSONObject().put("teamId", team.teamId).put("points", team.points).put("rank", team.rank)
                .put("ifWin", team.ifWin?.let(::impact)).put("ifLoss", team.ifLoss?.let(::impact))
                .put("result", team.result?.let(::impact))
        }))

    private fun vrs(j: JSONObject) = MatchVrs(
        mode = MatchVrsMode.valueOf(j.getString("mode")),
        teams = j.list("teams") { team ->
            MatchVrsTeam(
                teamId = team.getString("teamId"), points = team.n("points"), rank = team.n("rank"),
                ifWin = team.optJSONObject("ifWin")?.let(::impact),
                ifLoss = team.optJSONObject("ifLoss")?.let(::impact),
                result = team.optJSONObject("result")?.let(::impact),
            )
        },
    )

    private fun rate(value: MatchMapRate): JSONObject = JSONObject()
        .put("percentage", value.percentage).put("sampleSize", value.sampleSize).put("statisticsUrl", value.statisticsUrl)

    private fun rate(j: JSONObject) = MatchMapRate(j.d("percentage"), j.n("sampleSize"), j.s("statisticsUrl"))

    private fun mapPool(value: MatchMapPool): JSONObject = JSONObject()
        .put("metric", value.metric.name).put("description", value.description)
        .put("leftStatisticsUrl", value.leftStatisticsUrl).put("rightStatisticsUrl", value.rightStatisticsUrl)
        .put("maps", JSONArray(value.maps.map { map ->
            JSONObject().put("mapKey", map.mapKey).put("name", map.name).put("left", rate(map.left)).put("right", rate(map.right))
        }))

    private fun mapPool(j: JSONObject) = MatchMapPool(
        metric = MatchMapMetric.valueOf(j.getString("metric")),
        description = j.s("description"),
        maps = j.list("maps") { map ->
            MatchMapComparison(map.getString("mapKey"), map.getString("name"), rate(map.getJSONObject("left")), rate(map.getJSONObject("right")))
        },
        leftStatisticsUrl = j.s("leftStatisticsUrl"),
        rightStatisticsUrl = j.s("rightStatisticsUrl"),
    )

    private fun result(value: MatchHistoryResult): JSONObject = JSONObject()
        .put("matchId", value.matchId).put("matchUrl", value.matchUrl)
        .put("left", Records.team(value.left)).put("right", Records.team(value.right))
        .put("unit", value.unit.name).put("format", value.format).put("mapName", value.mapName)
        .put("leftScore", value.leftScore).put("rightScore", value.rightScore)
        .put("playedAt", value.playedAt?.toString()).put("dateLabel", value.dateLabel)
        .put("event", value.event?.let(Records::event))

    private fun result(j: JSONObject) = MatchHistoryResult(
        matchId = j.getString("matchId"), matchUrl = j.getString("matchUrl"),
        left = Records.team(j.getJSONObject("left")), right = Records.team(j.getJSONObject("right")),
        unit = MatchResultUnit.valueOf(j.getString("unit")), format = j.s("format"), mapName = j.s("mapName"),
        leftScore = j.n("leftScore"), rightScore = j.n("rightScore"),
        playedAt = j.s("playedAt")?.let(Instant::parse), dateLabel = j.s("dateLabel"),
        event = j.optJSONObject("event")?.let(Records::event),
    )

    private fun headToHead(value: MatchHeadToHead): JSONObject = JSONObject()
        .put("leftTeamId", value.leftTeamId).put("rightTeamId", value.rightTeamId)
        .put("leftMapWins", value.leftMapWins).put("rightMapWins", value.rightMapWins).put("overtimes", value.overtimes)
        .put("results", JSONArray(value.results.map(::result))).put("moreUrl", value.moreUrl)

    private fun headToHead(j: JSONObject) = MatchHeadToHead(
        leftTeamId = j.getString("leftTeamId"), rightTeamId = j.getString("rightTeamId"),
        leftMapWins = j.n("leftMapWins"), rightMapWins = j.n("rightMapWins"), overtimes = j.n("overtimes"),
        results = j.list("results", ::result), moreUrl = j.s("moreUrl"),
    )

    private fun recentForm(value: MatchRecentForm): JSONObject = JSONObject()
        .put("team", Records.team(value.team)).put("scope", value.scope.name).put("period", value.period)
        .put("results", JSONArray(value.results.map(::result))).put("moreUrl", value.moreUrl)

    private fun recentForm(j: JSONObject) = MatchRecentForm(
        team = Records.team(j.getJSONObject("team")), scope = MatchHistoryScope.valueOf(j.getString("scope")),
        period = j.s("period"), results = j.list("results", ::result), moreUrl = j.s("moreUrl"),
    )
}
