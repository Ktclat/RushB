package dev.matchscope.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MatchDuelsTest {
    private val a = PlayerRef("1", "Alpha")
    private val b = PlayerRef("2", "Bravo")
    private fun matrix(kills: Int = 0, deaths: Int = 3) = DuelMatrix(DuelMetric.ALL_KILLS, listOf(a), listOf(b), listOf(DuelCell(a.id, b.id, kills, deaths)))
    private fun source(matrix: DuelMatrix = matrix()) = MatchDuels("https://www.hltv.org/mobile/MatchScreen?matchId=1", listOf(matrix))
    private fun match() = Match("1", Team("1", "A"), Team("2", "B"), Tournament("1", "Event"), MatchStatus.FINISHED,
        leftScore = 2, rightScore = 0, maps = listOf(MapScore("Dust2", 13, 5), MapScore("Mirage", 13, 7), MapScore("Nuke")),
        duels = mapOf("dust2" to source(), "mirage" to source(matrix(4, 2))),
        stamp = SourceStamp("https://www.hltv.org/mobile/MatchScreen?matchId=1", Instant.parse("2026-10-18T12:00:00Z"), DataOrigin.CACHE))

    @Test fun unplayedDeciderDoesNotHideValidTwoZeroSeriesTotal() {
        val original = match()
        val combined = original.withDuelTotals()
        val cell = combined.duels.getValue("all").matrices.single().cells.single()
        assertEquals(4, cell.rowKills)
        assertEquals(5, cell.columnKills)
        assertEquals(original.stamp, combined.stamp)
        assertEquals(listOf("all", "dust2", "mirage"), combined.duelMapOptions().map { it.key })
        assertTrue(combined.statGroups.isEmpty())
    }

    @Test fun reversedAxesStillSumTheSamePlayersKills() {
        val match = match().let { it.copy(duels = it.duels + ("mirage" to source(matrix(4, 2).transposed()))) }
        assertEquals(DuelCell("1", "2", 4, 5), match.withDuelTotals().duels.getValue("all").matrices.single().cells.single())
    }

    @Test fun missingMapOrCellOrMetricNeverBecomesZeroInATotal() {
        val match = match()
        val missingMap = match.copy(duels = match.duels - "mirage")
        val missingCell = match.copy(duels = match.duels + ("mirage" to source(matrix().copy(cells = emptyList()))))
        val duplicateCell = match.copy(duels = match.duels + ("mirage" to source(matrix().let { it.copy(cells = it.cells + it.cells) })))
        val missingMetric = match.copy(duels = match.duels + ("mirage" to source(matrix().copy(metric = DuelMetric.AWP_KILLS))))
        listOf(missingMap, missingCell, duplicateCell, missingMetric).forEach { assertFalse(it.withDuelTotals().duels.containsKey("all")) }
        assertEquals(listOf("dust2"), missingMap.duelMapOptions().map { it.key })
    }

    @Test fun incompleteResultsLiveGamesAndDifferentRostersStayMapOnly() {
        val base = match()
        val swappedPlayer = matrix().copy(rowPlayers = listOf(PlayerRef("3", "Substitute")), cells = listOf(DuelCell("3", "2", 4, 2)))
        val otherCapture = source().copy(sourceUrl = "https://www.hltv.org/stats/matches/performance/7/example")
        listOf(base.copy(status = MatchStatus.LIVE), base.copy(leftScore = 1, rightScore = 2), base.copy(leftScore = null),
            base.copy(duels = base.duels + ("mirage" to source(swappedPlayer))),
            base.copy(duels = base.duels + ("mirage" to otherCapture))).forEach { assertFalse(it.withDuelTotals().duels.containsKey("all")) }
    }

    @Test fun legitimateZeroMatrixRemainsAvailable() {
        val zero = source(matrix(0, 0))
        val match = match().copy(duels = mapOf("dust2" to zero, "mirage" to zero)).withDuelTotals()
        assertEquals(DuelCell("1", "2", 0, 0), match.duels.getValue("all").matrices.single().cells.single())
        assertEquals(3, match.duelMapOptions().size)
    }

    @Test fun incompleteMetricDoesNotDiscardOtherCompleteMetrics() {
        val both = source().copy(matrices = listOf(matrix(), matrix().copy(metric = DuelMetric.OPENING_KILLS)))
        val match = match().let { it.copy(duels = it.duels + ("dust2" to both)) }.withDuelTotals()
        assertEquals(listOf(DuelMetric.ALL_KILLS), match.duels.getValue("all").matrices.map { it.metric })
        assertEquals(2, match.duels.getValue("dust2").matrices.size)
    }
}
