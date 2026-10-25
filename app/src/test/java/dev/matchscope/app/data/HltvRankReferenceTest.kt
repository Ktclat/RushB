package dev.matchscope.app.data

import dev.matchscope.app.ui.ScopeState
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HltvRankReferenceTest {
    private val team = Team("11283", "Falcons")
    private fun stamp(second: Long, origin: DataOrigin = DataOrigin.CACHE) =
        SourceStamp(team.url, Instant.ofEpochSecond(second), origin)

    @Test fun `newest verified rank remains consistent after cold restoration and does not use VRS`() {
        val restored = ScopeState(rankings = Feed(listOf(RankingEntry(5, team, 0)), stamp(100)),
            library = LocalLibrary(teamRanks = mapOf(team.id to Sourced(6, stamp(200)))),
            vrs = mapOf(VrsRegion.GLOBAL to Feed(listOf(RankingEntry(1, team, 0)), stamp(500))))
        assertEquals(6, restored.hltvRank(team.id))
        val opened = restored.copy(teamProfiles = mapOf(team.id to Sourced(TeamProfile(team, emptyList(), 4), stamp(300))))
        assertEquals(4, opened.hltvRank(team.id))
        val refreshed = opened.copy(rankings = Feed(listOf(RankingEntry(3, team, 0)), stamp(400)))
        assertEquals(3, refreshed.hltvRank(team.id))
        assertNull(refreshed.hltvRank("999999"))
    }

    @Test fun `feed wins tied timestamps while snapshot and invalid ranks cannot override verified profile`() {
        val state = ScopeState(rankings = Feed(listOf(RankingEntry(5, team, 0)), stamp(100)),
            teamProfiles = mapOf(team.id to Sourced(TeamProfile(team, emptyList(), 6), stamp(100))))
        assertEquals(5, state.hltvRank(team.id))
        assertEquals(6, state.copy(rankings = state.rankings.copy(stamp = stamp(500, DataOrigin.SNAPSHOT))).hltvRank(team.id))
        assertEquals(6, state.copy(rankings = Feed(listOf(RankingEntry(0, team, 0)), stamp(500))).hltvRank(team.id))
    }
}
