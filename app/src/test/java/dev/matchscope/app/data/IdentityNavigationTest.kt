package dev.matchscope.app.data

import dev.matchscope.app.ui.ScopeState
import org.junit.Assert.*
import org.junit.Test

class IdentityNavigationTest {
    @Test fun `VRS uses the same current ranking logo before an older profile variant`() {
        val team = Team("1", "Example", logoUrl = "https://img-cdn.hltv.org/teamlogo/current.png")
        val old = team.copy(logoUrl = "https://img-cdn.hltv.org/teamlogo/old.png")
        val state = ScopeState(rankings = Feed(listOf(RankingEntry(1, team, 1000)), SeedSnapshot.rankings.stamp),
            teamProfiles = mapOf("1" to Sourced(TeamProfile(old, emptyList()), SeedSnapshot.rankings.stamp)))
        assertEquals(team.logoUrl, state.displayTeam(Team("vrs:example", "Example")).logoUrl)
    }
    @Test fun `result without team id links to an unambiguous known team and event`() {
        val result = SeedSnapshot.matchItems.last().copy(left = Team("name:falcons", "Falcons", url = ""),
            event = Tournament("result-2399415", SeedSnapshot.thunderpick.name, url = ""))
        val state = ScopeState(matches = SeedSnapshot.matches.copy(items = listOf(result)))
        assertEquals("11283", state.resolveTeam("name:falcons")!!.id)
        assertEquals("8932", state.resolveEvent("result-2399415")!!.id)
    }

    @Test fun `small player team reference does not erase roster and current profile takes precedence`() {
        val player = PlayerProfile(PlayerRef("3741", "NiKo"), team = Team("11283", "Falcons"))
        val state = ScopeState(players = mapOf("3741" to Sourced(player, SeedSnapshot.rankings.stamp)))
        assertEquals(5, state.resolveTeam("11283")!!.roster.size)
        assertNotNull(state.displayTeam(player.team!!).logoUrl)
        val current = TeamProfile(SeedSnapshot.falcons.copy(roster = listOf("current")), emptyList())
        assertEquals(listOf("current"), state.copy(teamProfiles = mapOf("11283" to Sourced(current, SeedSnapshot.rankings.stamp))).resolveTeam("11283")!!.roster)
    }
}
