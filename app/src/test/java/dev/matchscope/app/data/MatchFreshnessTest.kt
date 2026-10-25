package dev.matchscope.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MatchFreshnessTest {
    private val now = Instant.parse("2026-10-20T12:00:00Z")
    private fun detail(status: MatchStatus, age: Long) = Match("1", Team("1", "A"), Team("2", "B"), Tournament("1", "Cup"), status,
        detailed = true, insights = MatchInsights(complete = true),
        stamp = SourceStamp("https://www.hltv.org/mobile/MatchScreen?matchId=1", now.minusSeconds(age), DataOrigin.CACHE),
        statGroups = listOf(MatchStatGroup("all", null, MatchSide.BOTH, listOf(PlayerStat("Player", "1", "1-0", "90", "100%", "1.2", "3.0")))))

    @Test fun `complete live details expire rather than freezing the score forever`() {
        assertTrue(detail(MatchStatus.LIVE, 59).canReuseDetails(now))
        assertFalse(detail(MatchStatus.LIVE, 60).canReuseDetails(now))
        assertFalse(detail(MatchStatus.LIVE, -1).canReuseDetails(now))
        assertFalse(detail(MatchStatus.LIVE, 1).copy(note = "更新失败").canReuseDetails(now))
    }
    @Test fun `scheduled start invalidates even a recent pre-start capture`() {
        val match = detail(MatchStatus.UPCOMING, 20).copy(startsAt = now.minusSeconds(5))
        assertFalse(match.canReuseDetails(now))
        assertTrue(match.copy(startsAt = now.plusSeconds(5)).canReuseDetails(now))
        assertFalse(detail(MatchStatus.UPCOMING, 300).canReuseDetails(now))
    }
    @Test fun `finished statistics refresh more frequently on the result day`() {
        assertTrue(detail(MatchStatus.FINISHED, 899).canReuseDetails(now))
        assertFalse(detail(MatchStatus.FINISHED, 900).canReuseDetails(now))
        assertTrue(detail(MatchStatus.FINISHED, 3_600).copy(resultAt = now.minusSeconds(172_800)).canReuseDetails(now))
        assertFalse(detail(MatchStatus.FINISHED, 21_600).copy(resultAt = now.minusSeconds(172_800)).canReuseDetails(now))
        assertFalse(detail(MatchStatus.FINISHED, 1).copy(statGroups = emptyList()).canReuseDetails(now))
        assertFalse(detail(MatchStatus.FINISHED, 1).copy(stamp = SeedSnapshot.matches.stamp).canReuseDetails(now))
    }
    @Test fun `fallback names distinguish common teams sharing an initial`() {
        assertNotEquals(Team("1", "Falcons").monogram, Team("2", "FURIA").monogram)
        assertEquals("BB", Team("1", "BB").monogram)
        assertEquals("TS", Team("1", " Team   Spirit ").monogram)
        assertEquals("待定", Team("pending:1", "待定").monogram)
        assertEquals("?", Team("1", " ").monogram)
    }

    @Test fun `live emphasis requires a recent non snapshot status and honors per match capture time`() {
        assertTrue(detail(MatchStatus.LIVE, 59).hasRecentLiveStatus(now))
        assertFalse(detail(MatchStatus.LIVE, 60).hasRecentLiveStatus(now))
        assertFalse(detail(MatchStatus.LIVE, -1).hasRecentLiveStatus(now))
        val freshFeed = SourceStamp("https://www.hltv.org/mobile/MatchesV4", now, DataOrigin.NETWORK)
        assertFalse(detail(MatchStatus.LIVE, 300).hasRecentLiveStatus(now, freshFeed))
        assertTrue(detail(MatchStatus.LIVE, 300).copy(stamp = null).hasRecentLiveStatus(now, freshFeed))
        assertFalse(detail(MatchStatus.LIVE, 1).copy(stamp = freshFeed.copy(origin = DataOrigin.SNAPSHOT)).hasRecentLiveStatus(now))
        assertFalse(detail(MatchStatus.FINISHED, 1).hasRecentLiveStatus(now))
    }
}
