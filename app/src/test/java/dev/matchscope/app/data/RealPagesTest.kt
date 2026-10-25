package dev.matchscope.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Reduced DOM captured from public pages on 2026-10-18; no fabricated scores. */
class RealPagesTest {
    private fun html(kind: String) = javaClass.classLoader!!.getResourceAsStream("hltv/$kind-2026-10-18.html")!!.bufferedReader().use { it.readText() }

    @Test fun `new match wrapper separates live rounds from series maps`() {
        val matches = HltvParser.matches(html("matches"))
        assertEquals(6, matches.size)
        val live = matches.first { it.id == "2399547" }
        assertEquals(MatchStatus.LIVE, live.status)
        assertEquals("NEXVOID", live.left.name)
        assertEquals("0 : 0", live.score) // Round score was 9-11, not series score.
        assertEquals("9437", live.event.id)
        val future = matches.first { it.id == "2399091" }
        assertEquals(Instant.ofEpochMilli(1792310400000), future.startsAt)
        assertEquals("Butterfly", future.left.name)
        assertEquals("BO3", future.format)
    }

    @Test fun `dated result wins over featured copy without inventing start time`() {
        val result = HltvParser.results(html("results"))
        assertEquals(5, result.size)
        val falcons = result.single { it.id == "2399415" }
        assertNull(falcons.startsAt)
        assertEquals(Instant.ofEpochMilli(1792267856000), falcons.resultAt)
        assertEquals(LocalDate.parse("2026-10-18"), falcons.day(ZoneId.of("Asia/Hong_Kong")))
        assertEquals("1 : 2", falcons.score)
    }

    @Test fun `full match retains all ten traditional player statistics and veto`() {
        val match = HltvParser.match(html("match"), "2399415")
        assertEquals("11283", match.left.id)
        assertEquals("BETBOOM", match.right.name)
        assertEquals(MatchStatus.FINISHED, match.status)
        assertEquals("1 : 2", match.score)
        assertEquals(Instant.parse("2026-10-17T17:00:00Z"), match.startsAt)
        assertEquals(listOf("Anubis", "Dust2", "Mirage"), match.maps.map { it.name })
        assertEquals(listOf(13, 14, 7), match.maps.map { it.left })
        assertEquals(listOf(5, 16, 13), match.maps.map { it.right })
        assertEquals(7, match.veto.size)
        assertEquals(10, match.stats.size)
        val monesy = match.stats.single { it.name == "m0NESY" }
        assertEquals("55-47", monesy.kd)
        assertEquals("81.0", monesy.adr)
        assertEquals("67.6%", monesy.kast)
        assertEquals("1.14", monesy.rating)
        assertEquals("3.0", monesy.ratingVersion)
        assertEquals("19230", monesy.player!!.id)
        assertEquals(listOf(5, 5), match.players.values.map { it.size })
    }

    @Test fun `player profile uses labeled rating and ability metrics from stated period`() {
        val profile = HltvParser.player(html("player"), "3741")
        assertEquals("NiKo", profile.player.name)
        assertEquals("Nikola Kovač", profile.realName)
        assertEquals("Bosnia and Herzegovina", profile.country)
        assertEquals(29, profile.age)
        assertEquals("11283", profile.team!!.id)
        assertEquals("(Past 3 months • 43 maps)", profile.statisticsPeriod)
        assertEquals("1.09", profile.statistics.toMap()["Rating 3.0"])
        assertEquals("66/100", profile.statistics.toMap()["Firepower"])
        assertEquals(8, profile.statistics.size)
    }

    @Test fun `upcoming detail has scheduled start and both lineups without invented score`() {
        val match = HltvParser.match(html("upcoming"), "2399416")
        assertEquals(MatchStatus.UPCOMING, match.status)
        assertEquals("Aurora", match.left.name)
        assertEquals("Falcons", match.right.name)
        assertEquals("BO3", match.format)
        assertEquals(Instant.ofEpochMilli(1792323000000), match.startsAt)
        assertNull(match.leftScore)
        assertNull(match.rightScore)
        assertTrue(match.stats.isEmpty())
        assertEquals(listOf(5,5), match.players.values.map { it.size })
        assertTrue(match.players[match.right.id]!!.any { it.id == "3741" && it.name == "NiKo" })
    }

    @Test fun `wrong detail identity or verification page cannot masquerade as profile`() {
        assertThrows(SourceUnavailable::class.java) { HltvParser.match(html("match"), "1") }
        assertThrows(SourceUnavailable::class.java) { HltvParser.player(html("player"), "1") }
        assertThrows(SourceUnavailable::class.java) { HltvParser.player("<title>请稍候…</title><div id='challenge-stage'></div>", "3741") }
    }
}
