package dev.matchscope.app.data

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EventContentTest {
    private fun event(): Tournament {
        val source = InstrumentationRegistry.getInstrumentation().context.assets.open("hltv/event-brackets-2026-10-18.html").bufferedReader().use { it.readText() }
        return HltvParser.event(source, "8932")
    }

    @Test fun sourceBracketsPreserveScoresLinksAndHiddenAdvancementNodes() {
        val e = event()
        assertTrue(e.content.complete)
        assertEquals(8, e.content.teams.size)
        assertEquals(4, e.content.brackets.size)
        assertEquals(14, e.content.brackets.sumOf { it.rounds.sumOf { r -> r.slots.size } })
        val playoff = e.content.brackets.single { it.id == "5359" }
        val semi = playoff.rounds.first().slots.first()
        assertEquals("2399415", semi.matchId)
        assertEquals("Falcons", semi.left!!.name)
        assertEquals(1, semi.leftScore)
        assertEquals(2, semi.rightScore)
        assertEquals(MatchStatus.FINISHED, semi.status)
        val final = playoff.rounds.last().slots.single()
        assertEquals("12394", final.left!!.id)
        assertEquals("8297", final.right!!.id)
        assertEquals(listOf("Semifinals1", "Semifinals2"), final.entries.map { it.slotId })
        assertNull(final.leftScore)
        assertEquals("BO5", final.format)
        assertEquals("2399417", final.asMatch(e)!!.id)
        assertFalse(final.asMatch(e)!!.event.content.complete)
        val group = e.content.brackets.single { it.name == "Group A" }
        assertEquals(5, group.rounds.sumOf { it.slots.size })
        assertFalse(group.rounds.any { r -> r.slots.any { it.id == "GrandFinal" } })
        assertTrue(group.rounds.flatMap { it.slots }.flatMap { it.entries }.any { it.loser })
    }

    @Test fun publicPrizeSharesAndEventCacheRoundTripWithoutLosingNulls() {
        val e = event()
        assertEquals("$1,000,000", e.prize)
        assertEquals("$500,000", e.content.information.toMap()["Player share"])
        assertEquals("$250,000", e.content.prizes.first().playerPrize)
        assertEquals("$250,000", e.content.prizes.first().clubPrize)
        assertNull(e.content.prizes.first().team)
        val restored = Records.event(JSONObject(Records.event(e).toString()))
        assertEquals(e, restored)
        assertFalse(Records.event(Records.event(e.copy(content = EventContent()))).content.complete)
    }

    @Test fun slotCannotNavigateWhenItsMatchIdentityDoesNotMatchLink() {
        val raw = InstrumentationRegistry.getInstrumentation().context.assets.open("hltv/event-brackets-2026-10-18.html").bufferedReader().use { it.readText() }
        val modified = raw.replace("/matches/2399417/furia-vs-betboom-", "https://example.org/matches/2399417/furia-vs-betboom-")
        assertThrows(SourceUnavailable::class.java) { HltvParser.event(modified, "8932") }
    }
}
