package dev.matchscope.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ParsersTest {
    @Test fun `match time is converted before calendar filtering`() {
        val match = SeedSnapshot.matchItems.first().copy(startsAt = Instant.parse("2026-10-17T18:30:00Z"))
        assertEquals(LocalDate.parse("2026-10-18"), match.day(ZoneId.of("Asia/Hong_Kong")))
        assertEquals(LocalDate.parse("2026-10-17"), match.day(ZoneId.of("UTC")))
        assertEquals(1, visibleMatches(listOf(match), LocalDate.parse("2026-10-18"), ZoneId.of("Asia/Hong_Kong"), null).size)
        assertTrue(visibleMatches(listOf(match), LocalDate.parse("2026-10-18"), ZoneId.of("UTC"), null).isEmpty())
    }

    @Test fun `missing time remains unknown and never uses midnight`() {
        val match = HltvParser.matches("""
            <div class="upcomingMatch" team1="1" team2="2">
              <a class="a-reset" href="/matches/100/a-vs-b"></a>
              <div class="matchTeamName">A &amp; B</div><div class="matchTeamName">Team 2</div>
              <div class="matchMeta">bo3</div><img class="matchEventLogo" title="Example Cup">
            </div>
        """).single()
        assertNull(match.startsAt)
        assertNull(match.leftScore)
        assertEquals("A & B", match.left.name)
        assertEquals(MatchStatus.UPCOMING, match.status)
        assertEquals("https://www.hltv.org/matches/100/a-vs-b", match.url)
    }

    @Test fun `live page never turns a round counter into a series score`() {
        val match = HltvParser.matches("""
            <div class="liveMatch-container" team1="1" team2="2">
              <a href="/matches/101/a-vs-b"></a><div class="team1"><span class="team">A</span></div>
              <div class="team2"><span class="team">B</span></div><div class="matchTime matchLive" data-unix="1792261800000">LIVE</div>
              <div class="matchTeamScore">12</div><div class="matchTeamScore">9</div>
            </div>
        """).single()
        assertEquals(MatchStatus.LIVE, match.status)
        assertNull(match.leftScore)
        assertNotNull(match.startsAt)
    }

    @Test fun `duplicate featured results collapse by match ID`() {
        val row = """<div class="result-con" data-zonedgrouping-entry-unix="1792261800000"><a href="/matches/102/a-vs-b"><span class="team">A</span><span class="result-score">2 - 1</span><span class="team">B</span><span class="event-name">Cup</span><span class="map-text">bo3</span></a></div>"""
        val results = HltvParser.results(row + row)
        assertEquals(1, results.size)
        assertEquals("2 : 1", results.single().score)
        assertEquals(MatchStatus.FINISHED, results.single().status)
    }

    @Test fun `challenge and unknown pages fail closed`() {
        assertThrows(SourceUnavailable::class.java) { HltvParser.matches("<title>Just a moment...</title>") }
        assertThrows(SourceUnavailable::class.java) { HltvParser.matches("<html><h1>Redesigned site</h1></html>") }
        assertThrows(SourceUnavailable::class.java) { HltvParser.rankings("<html>Blocked</html>") }
        assertThrows(SourceUnavailable::class.java) { HltvParser.news("<html>Not RSS</html>") }
    }

    @Test fun `HLTV ranking is parsed without confusing VRS points`() {
        val result = HltvParser.rankings("""
            <div class="ranked-team"><span class="position">#2</span><span class="name">Vitality</span>
            <span class="points">(894 HLTV points)</span><span class="change">+1</span><a class="moreLink" href="/team/9565/vitality">Profile</a></div>
        """).single()
        assertEquals(2, result.rank)
        assertEquals(894, result.points)
        assertEquals(1, result.change)
        assertEquals("9565", result.team.id)
    }

    @Test fun `RSS keeps safe article links and decoded text`() {
        val news = HltvParser.news("""<rss><channel><item><title>A &amp; B</title><link>https://www.hltv.org/news/1/title</link><pubDate>Today</pubDate></item><item><title>Invalid</title><link>javascript:alert(1)</link></item></channel></rss>""")
        assertEquals(1, news.size)
        assertEquals("A & B", news.single().title)
    }

    @Test fun `VRS standings retain published date roster and independent identity`() {
        val input = """
            ### Standings as of 2026_10_05<br />
            | Standing | Points | Team Name | Roster | |
            | :- | -: | :- | :- | :- |
            | 1 | 2046 | Spirit | donk, magixx, sh1ro, tN1R, zont1x | [details](details/foo.md) |
        """.trimIndent()
        val entry = VrsParser.parse(input, "https://example.org/source").single()
        assertEquals("2026-10-05", VrsParser.publishedDate(input))
        assertEquals("vrs:spirit", entry.team.id)
        assertEquals(5, entry.roster.size)
        assertEquals(2046, entry.points)
        assertNull(entry.change)
    }

    @Test fun `snapshot records all have provenance and real score completeness`() {
        assertEquals(DataOrigin.SNAPSHOT, SeedSnapshot.matches.stamp.origin)
        assertEquals(3, SeedSnapshot.matches.items.size)
        assertTrue(SeedSnapshot.matches.items.all { it.url.startsWith("https://www.hltv.org/matches/${it.id}/") })
        assertTrue(SeedSnapshot.matches.items.filter { it.status == MatchStatus.UPCOMING }.all { it.leftScore == null && it.rightScore == null })
    }

    @Test fun `all four bundled Valve standings are valid and dated`() {
        listOf("global", "europe", "americas", "asia").forEach { region ->
            val raw = javaClass.classLoader!!.getResourceAsStream("standings_${region}_2026_10_05.md")!!.bufferedReader().use { it.readText() }
            val rows = VrsParser.parse(raw, "https://github.com/ValveSoftware/counter-strike_regional_standings")
            assertEquals("2026-10-05", VrsParser.publishedDate(raw))
            assertEquals(1, rows.first().rank)
            assertTrue(rows.size > 20)
            assertTrue(rows.all { it.points >= 0 && it.roster.isNotEmpty() })
            // Valve also publishes partial roster entries; keep them partial.
            assertTrue(rows.any { it.roster.size < 5 })
        }
    }
}
