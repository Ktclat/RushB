package dev.matchscope.app.data

import org.junit.Assert.*
import org.junit.Test

class MatchHistoryQueryTest {
    @Test fun paginationPreservesBothTeamIntersection() {
        val ids = setOf("11283", "12394")
        assertTrue(historyQueryMatches("https://www.hltv.org/results?offset=100&team=11283&team=12394&requireAllTeams=", ids, true))
        assertFalse(historyQueryMatches("https://www.hltv.org/results?team=11283&team=12394", ids, true))
        assertFalse(historyQueryMatches("https://www.hltv.org/results?team=11283&team=12394&requireAllTeams=false", ids, true))
        assertFalse(historyQueryMatches("https://www.hltv.org/results?team=11283&requireAllTeams=", ids, true))
        assertFalse(historyQueryMatches("https://www.hltv.org/results?team=11283&team=12394&team=999&requireAllTeams=", ids, true))
        assertFalse(historyQueryMatches("https://www.hltv.org.example.org/results?team=11283&team=12394&requireAllTeams=", ids, true))
    }

    @Test fun teamHistoryRejectsOtherTeamAndUnfilteredPage() {
        assertTrue(historyQueryMatches("https://www.hltv.org/results?team=11283&offset=100", setOf("11283"), false))
        assertFalse(historyQueryMatches("https://www.hltv.org/results?team=12394", setOf("11283"), false))
        assertFalse(historyQueryMatches("https://www.hltv.org/results", emptySet(), false))
    }

    @Test fun mapArtOnlyPermitsObservedStaticResources() {
        assertEquals("https://www.hltv.org/img/static/maps/anubis.png", MediaUrls.validated("https://www.hltv.org/img/static/maps/anubis.png"))
        listOf("https://www.hltv.org/img/static/maps/unknown.png", "https://www.hltv.org/matches/2399415", "http://www.hltv.org/img/static/maps/anubis.png", "https://www.hltv.org/img/static/maps/anubis.png?token=1").forEach {
            assertNull(MediaUrls.validated(it))
        }
    }
}
