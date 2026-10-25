package dev.matchscope.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class EventEligibilityTest {
    private val today = LocalDate.parse("2026-10-19")
    private val month = YearMonth.from(today)
    private fun event(id: String, name: String, start: String, end: String, tier: EventTier? = null) =
        Tournament(id, name, LocalDate.parse(start), LocalDate.parse(end), tier = tier)
    private val epl = event("8244", "ESL Pro League Season 24", "2026-10-03", "2026-10-11")
    private val pgl = event("8050", "PGL Masters Bucharest 2026", "2026-10-24", "2026-10-31")
    private val major = event("999999", "Test Major", "2026-10-18", "2026-11-02", EventTier.MAJOR)
    private val unknown = event("other", "ESL Open Cup", "2026-10-18", "2026-10-21").copy(prize = "$1,000,000", teamCount = 16)
    private val events = listOf(unknown, major, pgl, epl)

    @Test fun `restored detail cannot erase explicit index classification or merge namesakes`() {
        val detail = major.copy(tier = null, startDate = null, endDate = null, content = EventContent(teams = listOf(Team("1", "A"))))
        val namesake = unknown.copy(name = major.name)
        val result = mergedEvents(listOf(detail, major, namesake))
        assertEquals(2, result.size)
        assertEquals(EventTier.MAJOR, result.first().tier)
        assertEquals(major.startDate, result.first().startDate)
        assertEquals(detail.content, result.first().content)
        assertNull(result.last().tier)
    }

    @Test fun `only explicit classifications enter important timeline`() {
        assertEquals(listOf("8244", "999999", "8050"), calendarEvents(events, month, today).map { it.id })
        assertNull(EventEligibility.evidence(unknown))
        assertNull(EventEligibility.evidence(pgl.copy(id = "same-brand-different-edition")))
        assertEquals(EventTier.MAJOR, EventEligibility.evidence(major)!!.tier)
        assertNull(EventEligibility.evidence(major)!!.checkedOn)
        assertEquals(4, calendarEvents(events, month, today, importantOnly = false).size)
    }
    @Test fun `date phase query and order combine without altering source dates`() {
        assertEquals(listOf(pgl), calendarEvents(events, month, today, query = "pgl", phase = EventPhase.UPCOMING))
        assertEquals(listOf(epl), calendarEvents(events, month, today, phase = EventPhase.FINISHED))
        assertEquals(listOf(major), calendarEvents(events, month, today, day = today, phase = EventPhase.ONGOING))
        assertEquals(listOf("8050", "999999", "8244"), calendarEvents(events, month, today, order = EventOrder.DATE_DESC).map { it.id })
        assertEquals(listOf("8244", "8050", "999999"), calendarEvents(events, month, today, order = EventOrder.NAME).map { it.id })
        assertEquals(listOf(major), calendarEvents(events, month.plusMonths(1), today))
        assertEquals(LocalDate.parse("2026-11-02"), major.endDate)
    }
    @Test fun `followed view includes known participants and never guesses missing rosters`() {
        val team = Team("12", "Example")
        val confirmed = unknown.copy(content = EventContent(teams = listOf(team)))
        assertEquals(listOf(confirmed), calendarEvents(events + confirmed.copy(id = "confirmed"), month, today,
            importantOnly = false, followedTeamIds = setOf("12")).map { it.copy(id = unknown.id) })
        assertTrue(calendarEvents(events, month, today, followedTeamIds = setOf("12")).isEmpty())
        assertTrue(calendarEvents(events, month, today, followedTeamIds = emptySet()).isEmpty())
    }
}
