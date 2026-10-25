package dev.matchscope.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class IdentityPagesTest {
    private fun html(kind: String) = javaClass.classLoader!!.getResourceAsStream("hltv/$kind-2026-10-18.html")!!.bufferedReader().use { it.readText() }

    @Test fun `photos are attached by player identity and logos retain signed URLs`() {
        val match = HltvParser.match(html("match"), "2399415")
        assertTrue(match.left.logoUrl!!.contains("w=200&s="))
        assertTrue(match.right.logoUrl!!.contains("G4ZrdB0"))
        assertEquals(10, match.players.values.flatten().count { it.photoUrl != null })
        val niko = match.players["11283"]!!.single { it.id == "3741" }
        assertTrue(niko.photoUrl!!.contains("ZPB6zop8FumkqqbHl8BGos"))
        assertEquals(niko.photoUrl, match.stats.single { it.player?.id == "3741" }.player!!.photoUrl)
        val results = HltvParser.results(html("results"))
        assertTrue(results.single { it.left.name == "FURIA" }.left.logoUrl!!.contains(".svg?"))
        assertTrue(results.first().event.logoUrl!!.contains("eventlogo/"))
        val matches = HltvParser.matches(html("matches"))
        assertNotNull(matches.first().left.logoUrl)
        assertNull(matches.first().right.logoUrl) // Source only has a letter placeholder.
        val upcoming = HltvParser.match(html("upcoming"), "2399416")
        assertEquals(10, upcoming.players.values.flatten().count { it.photoUrl != null })
        assertTrue(upcoming.players["11861"]!!.single { it.id == "8574" }.photoUrl!!.contains("HwdtHmtO"))
    }

    @Test fun `current team profile keeps real five person roster and world ranking separate from VRS`() {
        val team = HltvParser.team(html("team"), "11283")
        assertEquals("Falcons", team.team.name)
        assertEquals(listOf("429", "3741", "12018", "19230", "24177"), team.players.map { it.id })
        assertTrue(team.players.all { it.photoUrl!!.contains("playerbodyshot/") })
        assertEquals(5, team.worldRanking)
        assertEquals("Danny 'zonic' Sørensen", team.coach)
        assertTrue(team.team.logoUrl!!.contains("w=100"))
        assertThrows(SourceUnavailable::class.java) { HltvParser.team(html("team"), "1") }
        assertThrows(SourceUnavailable::class.java) { HltvParser.team("<title>Just a moment</title>", "11283") }
    }

    @Test fun `player portrait and event identity come from primary page sections`() {
        val player = HltvParser.player(html("player"), "3741")
        assertTrue(player.player.photoUrl!!.contains("ZPB6zop8FumkqqbHl8BGos"))
        val event = HltvParser.event(html("event"), "8932")
        assertEquals("Thunderpick World Championship 2026", event.name)
        assertEquals(LocalDate.parse("2026-10-14"), event.startDate)
        assertEquals(LocalDate.parse("2026-10-18"), event.endDate)
        assertEquals("$1,000,000", event.prize)
        assertEquals(8, event.teamCount)
        assertEquals("Malta", event.location)
        assertTrue(event.logoUrl!!.contains("w=400"))
        assertThrows(SourceUnavailable::class.java) { HltvParser.event(html("event"), "1") }
    }

    @Test fun `artwork allowlist rejects local files credentials trackers and alternate hosts`() {
        val valid = "https://img-cdn.hltv.org/teamlogo/example.svg?ixlib=java-2.1.0&s=signature"
        assertEquals(valid, MediaUrls.validated(valid))
        listOf("file:///sdcard/photo.png", "http://img-cdn.hltv.org/teamlogo/a.png", "https://img-cdn.hltv.org.evil.test/teamlogo/a.png",
            "https://user:pass@img-cdn.hltv.org/teamlogo/a.png", "https://127.0.0.1/teamlogo/a.png", "https://img-cdn.hltv.org:8765/teamlogo/a.png",
            "https://img-cdn.hltv.org/advertisement/a.png", "https://www.hltv.org/dynamic-svg/teamplaceholder?letter=O", "https://img-cdn.hltv.org/teamlogo/../../a.png").forEach {
            assertNull(it, MediaUrls.validated(it))
        }
        assertEquals(7, SeedArtwork.teams.values.count { MediaUrls.validated(it) != null })
        assertEquals(7, SeedSnapshot.teams.count { MediaUrls.validated(it.logoUrl) != null })
        assertTrue(SeedSnapshot.matchItems.flatMap { it.players.values.flatten() }.all { it.photoUrl != null })
    }
}
