package dev.matchscope.app.data

enum class DuelMetric { ALL_KILLS, OPENING_KILLS, AWP_KILLS }

/** Counts are oriented to the matrix axes, not to the match page's team order. */
data class DuelCell(
    val rowPlayerId: String,
    val columnPlayerId: String,
    val rowKills: Int,
    val columnKills: Int,
)

data class DuelMatrix(
    val metric: DuelMetric,
    val rowPlayers: List<PlayerRef>,
    val columnPlayers: List<PlayerRef>,
    val cells: List<DuelCell>,
)

/** One independently fetched performance page, for either a series or one map. */
data class MatchDuels(
    val sourceUrl: String,
    val matrices: List<DuelMatrix>,
)
