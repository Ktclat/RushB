package dev.matchscope.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SearchMatchesTest {
    private val now = Instant.parse("2026-10-24T07:00:00Z")
    private val stamp = SourceStamp("https://www.hltv.org/mobile/MatchesV4", now, DataOrigin.CACHE)
    private fun match(id: String, status: MatchStatus, seconds: Long?) = Match(id, Team("a", "A"), Team("b", "B"),
        Tournament("e", "Cup"), status, startsAt = seconds?.let(now::plusSeconds), stamp = stamp)

    @Test fun `nearest upcoming match is first while newest completed result is first in a separate group`() {
        val input = listOf(match("later", MatchStatus.UPCOMING, 9000), match("old", MatchStatus.FINISHED, -9000),
            match("unknown", MatchStatus.UPCOMING, null), match("sooner", MatchStatus.UPCOMING, 600),
            match("recent", MatchStatus.FINISHED, -600))
        val groups = searchMatchGroups(input, now, stamp)
        assertEquals(listOf("即将开始", "近期赛果"), groups.map { it.title })
        assertEquals(listOf("sooner", "later", "unknown"), groups[0].matches.map { it.id })
        assertEquals(listOf("recent", "old"), groups[1].matches.map { it.id })
    }

    @Test fun `old live status and pre start captures cannot become current live or upcoming headings`() {
        val old = stamp.copy(capturedAt = now.minusSeconds(3600))
        val input = listOf(match("live", MatchStatus.LIVE, -10), match("stale-live", MatchStatus.LIVE, -120).copy(stamp = old),
            match("expired", MatchStatus.UPCOMING, -600).copy(stamp = old))
        val groups = searchMatchGroups(input, now, stamp)
        assertEquals(listOf("进行中", "待更新"), groups.map { it.title })
        assertEquals(listOf("live"), groups.first().matches.map { it.id })
        assertEquals(setOf("stale-live", "expired"), groups.last().matches.map { it.id }.toSet())
    }

    @Test fun `postponed and cancelled matches remain accessible without being called upcoming`() {
        val groups = searchMatchGroups(listOf(match("postponed", MatchStatus.POSTPONED, 500), match("cancelled", MatchStatus.CANCELLED, 1000)), now, stamp)
        assertEquals("延期与取消", groups.single().title)
        assertEquals(2, groups.single().matches.size)
        assertTrue(searchMatchGroups(emptyList(), now, stamp).isEmpty())
    }
}
