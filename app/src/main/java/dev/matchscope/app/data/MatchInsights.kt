package dev.matchscope.app.data

import java.time.Instant

/** VRS figures on a match page estimate this result's impact, not the final official standings. */
enum class MatchVrsMode { FORECAST, RESULT }
data class MatchVrsImpact(val pointsChange: Int? = null, val rank: Int? = null, val rankChange: Int? = null)
data class MatchVrsTeam(
    val teamId: String,
    val points: Int? = null,
    val rank: Int? = null,
    val ifWin: MatchVrsImpact? = null,
    val ifLoss: MatchVrsImpact? = null,
    val result: MatchVrsImpact? = null,
)
data class MatchVrs(val mode: MatchVrsMode, val teams: List<MatchVrsTeam>)

enum class MatchMapMetric { WIN, PICK, BAN }
data class MatchMapRate(val percentage: Double? = null, val sampleSize: Int? = null, val statisticsUrl: String? = null)
data class MatchMapComparison(val mapKey: String, val name: String, val left: MatchMapRate, val right: MatchMapRate)
data class MatchMapPool(
    val metric: MatchMapMetric,
    val description: String? = null,
    val maps: List<MatchMapComparison> = emptyList(),
    val leftStatisticsUrl: String? = null,
    val rightStatisticsUrl: String? = null,
)
data class MatchComparison(val label: String, val left: String?, val right: String?)

enum class MatchHistoryScope { CORE, TEAM }
enum class MatchResultUnit { SERIES, MAP }
data class MatchHistoryResult(
    val matchId: String,
    val matchUrl: String,
    val left: Team,
    val right: Team,
    val unit: MatchResultUnit,
    val format: String? = null,
    val mapName: String? = null,
    val leftScore: Int? = null,
    val rightScore: Int? = null,
    val playedAt: Instant? = null,
    val dateLabel: String? = null,
    val event: Tournament? = null,
)
data class MatchHeadToHead(
    val leftTeamId: String,
    val rightTeamId: String,
    val leftMapWins: Int? = null,
    val rightMapWins: Int? = null,
    val overtimes: Int? = null,
    val results: List<MatchHistoryResult> = emptyList(),
    val moreUrl: String? = null,
)
data class MatchRecentForm(
    val team: Team,
    val scope: MatchHistoryScope,
    val period: String? = null,
    val results: List<MatchHistoryResult> = emptyList(),
    val moreUrl: String? = null,
)
data class MatchInsights(
    val vrs: MatchVrs? = null,
    val mapPools: List<MatchMapPool> = emptyList(),
    val comparisons: List<MatchComparison> = emptyList(),
    val headToHead: MatchHeadToHead? = null,
    val recentForm: List<MatchRecentForm> = emptyList(),
    val complete: Boolean = true,
)
