package dev.matchscope.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class BrowseDataTest {
    @Test fun `legacy pending teams become unknown slots while stage and real identities survive`() {
        val event = Tournament("1", "Cup")
        val legacy = Match("123", Team("pending:123:team1", "Cup - Grand Final"), Team("pending:123:team2", "待定"), event, MatchStatus.UPCOMING)
        val fixed = legacy.normalizePlaceholders()
        assertEquals("Grand Final", fixed.stage)
        assertEquals(listOf("待定", "待定"), listOf(fixed.left.name, fixed.right.name))
        assertEquals(fixed, fixed.normalizePlaceholders())
        val real = legacy.copy(left = Team("12", "Grand Final"), stage = "Semi-final")
        assertEquals(real.left, real.normalizePlaceholders().left)
        assertEquals("Semi-final", real.normalizePlaceholders().stage)
    }

    @Test fun `past scheduled starts only show stale status when the capture predates the start`() {
        val start = java.time.Instant.parse("2026-10-19T10:00:00Z")
        val stamp = SourceStamp("https://www.hltv.org/matches", start.minusSeconds(600), DataOrigin.CACHE)
        val match = Match("1", Team("1", "A"), Team("2", "B"), Tournament("1", "Cup"), MatchStatus.UPCOMING, startsAt = start)
        assertEquals("未开始", match.statusLabel(start.minusSeconds(1), stamp))
        assertEquals("待更新", match.statusLabel(start, stamp))
        assertEquals("未开始", match.copy(stamp = stamp.copy(capturedAt = start.plusSeconds(10))).statusLabel(start.plusSeconds(20), stamp))
        assertEquals("待更新", match.copy(status = MatchStatus.LIVE).statusLabel(start, stamp))
        assertEquals("进行中", match.copy(status = MatchStatus.LIVE).statusLabel(start, stamp.copy(capturedAt = start.minusSeconds(10))))
        assertEquals("已延期", match.copy(status = MatchStatus.POSTPONED).statusLabel(start, stamp))
        assertEquals("未开始", match.copy(startsAt = null).statusLabel(start, stamp))
    }

    @Test fun `profile cache scores resolve by identity and orientation and preserve real zeros`() {
        val a = Team("1", "A"); val b = Team("2", "B")
        val cup = Tournament("1", "Cup")
        val known = Match("123", a, b, cup, MatchStatus.FINISHED, leftScore = 1, rightScore = 2)
        val row = ProfileMatch(b, a, cup, MatchStatus.FINISHED, matchUrl = known.url)
        val resolved = resolveProfileMatch(row, listOf(known))
        assertEquals(2, resolved.leftScore); assertEquals(1, resolved.rightScore)
        assertEquals(0, resolveProfileMatch(row.copy(leftScore = 0), listOf(known)).leftScore)
        assertEquals(row, resolveProfileMatch(row, listOf(known.copy(right = Team("3", "B")))))
        assertEquals(row, resolveProfileMatch(row, listOf(known.copy(id = "456"))))
    }

    private fun event(id: String, start: String, end: String) = Tournament(id, id, LocalDate.parse(start), LocalDate.parse(end))

    @Test fun `live groups lead without merging events that share a name or losing chronology`() {
        val first = Tournament("a", "Cup"); val second = Tournament("b", "Cup")
        fun match(id: String, event: Tournament, hour: Int, status: MatchStatus = MatchStatus.UPCOMING) =
            Match(id, Team("1", "A"), Team("2", "B"), event, status,
                startsAt = java.time.Instant.parse("2026-10-19T00:00:00Z").plusSeconds(hour * 3600L))
        val groups = matchGroups(listOf(match("early", first, 1), match("later", second, 5),
            match("live", second, 4, MatchStatus.LIVE), match("next", first, 2)))
        assertEquals(listOf(listOf("live", "later"), listOf("early", "next")), groups.map { group -> group.map { it.id } })
        assertTrue(matchGroups(emptyList()).isEmpty())
    }

    @Test fun `calendar clips ranges at week and month boundaries without merging simultaneous events`() {
        val a = event("a", "2026-09-28", "2026-10-05")
        val b = event("b", "2026-10-01", "2026-10-03")
        val c = event("c", "2026-10-04", "2026-10-10")
        val spans = eventSpans(listOf(c, b, a), LocalDate.parse("2026-09-28"))
        assertEquals(listOf(0, 3, 6), spans.map { it.start })
        assertEquals(listOf(6, 5, 6), spans.map { it.end })
        assertEquals(listOf(0, 1, 1), spans.map { it.lane })
        assertEquals(3, eventsInMonth(listOf(a, b, c, event("d", "2026-11-01", "2026-11-02")), YearMonth.of(2026, 10)).size)
    }

    @Test fun `directory ranks by identity and leaves unranked teams after ranked teams`() {
        val a = Team("1", "A"); val b = Team("2", "B"); val other = Team("3", "B")
        val result = orderedTeams(listOf(a, other, b), listOf(RankingEntry(1, b, 1000), RankingEntry(7, a, 400)))
        assertEquals(listOf("2", "1", "3"), result.map { it.id })
    }

    @Test fun `rank parser accepts grouped points and current nickname markup and rejects duplicates`() {
        val row = """<div class="ranked-team"><span class="position">#1</span><span class="name">Spirit</span><span class="points">(1,000 points)</span><a href="/team/7020/spirit">team</a><div class="rankingNicknames"><span>donk</span></div></div>"""
        val parsed = HltvParser.rankings(row).single()
        assertEquals(1000, parsed.points); assertEquals(listOf("donk"), parsed.roster); assertEquals("7020", parsed.team.id)
        assertThrows(SourceUnavailable::class.java) { HltvParser.rankings(row + row) }
        assertEquals("2026-10-12", HltvParser.rankingDate("<div class='regional-ranking-header-text'>Counter-Strike World ranking on October 12th, 2026</div>"))
    }

    @Test fun `unknown region remains unknown and ambiguous regional entries are not guessed`() {
        val team = Team("1", "Example", roster = listOf("a", "b", "c"))
        assertEquals("未分类", teamRegion(team, emptyMap()))
        val entry = RankingEntry(1, Team("vrs:example", "Example"), 100, roster = team.roster)
        val stamp = SeedSnapshot.rankings.stamp
        assertEquals("亚洲", teamRegion(team, mapOf(VrsRegion.ASIA to Feed(listOf(entry), stamp))))
        assertEquals("未分类", teamRegion(team, mapOf(VrsRegion.ASIA to Feed(listOf(entry), stamp), VrsRegion.EUROPE to Feed(listOf(entry), stamp))))
    }
}
