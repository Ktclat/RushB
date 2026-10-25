package dev.matchscope.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MatchBrowseTest {
    private val now = Instant.parse("2026-10-20T10:00:00Z")
    private val stamp = SourceStamp("https://www.hltv.org/mobile/MatchesV4", now, DataOrigin.NETWORK)
    private fun match(id: String, event: Tournament, hour: Long) =
        Match(id, Team("a", "A", "China"), Team("b", "B", "Brazil"), event, MatchStatus.FINISHED,
            startsAt = now.plusSeconds(hour * 3600), stamp = stamp)

    @Test fun `important events open across all dates and ordinary events require a fresh live match today`() {
        val zone = java.time.ZoneId.of("Asia/Hong_Kong")
        val day = now.atZone(zone).toLocalDate()
        for (offset in -1L..1L) {
            for (tier in EventTier.entries) {
                val important = match("important", Tournament("tier", "Cup", tier = tier), 0)
                assertTrue(defaultEventExpanded(listOf(important), day.plusDays(offset), now, zone, stamp))
            }
            val ordinary = match("ordinary", Tournament("other", "Cup"), 0)
            assertFalse(defaultEventExpanded(listOf(ordinary), day.plusDays(offset), now, zone, stamp))
            assertFalse(defaultEventExpanded(listOf(ordinary.copy(status = MatchStatus.UPCOMING)), day.plusDays(offset), now, zone, stamp))
            assertEquals(offset == 0L, defaultEventExpanded(listOf(ordinary.copy(status = MatchStatus.LIVE)), day.plusDays(offset), now, zone, stamp))
            assertFalse(defaultEventExpanded(listOf(ordinary.copy(status = MatchStatus.LIVE, stamp = stamp.copy(capturedAt = now.minusSeconds(120)))), day.plusDays(offset), now, zone, stamp))
        }
    }

    @Test fun `default prioritizes verified major then tier one then other events without hiding matches`() {
        val ordinary = match("early", Tournament("other", "Major Qualifier", prize = "$1000000"), -5).copy(status = MatchStatus.LIVE)
        val tierOne = match("tier1", Tournament("8932", "Thunderpick World Championship 2026"), 4)
        val major = match("major", Tournament("major", "Major", tier = EventTier.MAJOR), 5)
        assertEquals(listOf("major", "tier1", "early"), matchGroups(listOf(ordinary, tierOne, major), now = now, fallbackStamp = stamp).flatten().map { it.id })
        assertEquals(listOf("early", "major", "tier1"), matchGroups(listOf(ordinary, tierOne, major), MatchOrder.LIVE_FIRST, now, stamp).flatten().map { it.id })
    }

    @Test fun `time sort crosses event groups and puts unknown times last in both directions`() {
        val a = Tournament("a", "Same Name"); val b = Tournament("b", "Same Name")
        val input = listOf(match("late", a, 8), match("middle", b, 4), match("early", a, 1), match("unknown", b, 0).copy(startsAt = null))
        val ascending = matchGroups(input, MatchOrder.TIME_ASC)
        assertEquals(listOf("early", "middle", "late", "unknown"), ascending.flatten().map { it.id })
        assertEquals(listOf("a", "b", "a", "b"), ascending.map { it.first().event.id })
        assertEquals(listOf("late", "middle", "early", "unknown"), matchGroups(input, MatchOrder.TIME_DESC).flatten().map { it.id })
        assertTrue(matchGroups(emptyList(), MatchOrder.TIME_DESC).isEmpty())
    }

    @Test fun `expired live status cannot outrank a currently confirmed live group`() {
        val old = match("old", Tournament("old", "Old"), -4).copy(status = MatchStatus.LIVE, stamp = stamp.copy(capturedAt = now.minusSeconds(120)))
        val fresh = match("fresh", Tournament("new", "New"), 0).copy(status = MatchStatus.LIVE)
        assertEquals(listOf("fresh", "old"), matchGroups(listOf(old, fresh), MatchOrder.LIVE_FIRST, now, stamp).flatten().map { it.id })
    }

    @Test fun `regions use explicit venues or online regions and never team nationality or event names`() {
        mapOf("Tampere, Finland" to MatchRegion.EUROPE, "Malta" to MatchRegion.EUROPE,
            "Europe (Online)" to MatchRegion.EUROPE, "Shanghai, China" to MatchRegion.ASIA,
            "South America (Online)" to MatchRegion.AMERICAS, "Austin, United States" to MatchRegion.AMERICAS,
            "Melbourne, Australia" to MatchRegion.OCEANIA, "Riyadh, Saudi Arabia" to MatchRegion.MIDDLE_EAST,
            "Johannesburg, South Africa" to MatchRegion.AFRICA, "International (Online)" to MatchRegion.GLOBAL,
            "Online" to MatchRegion.UNKNOWN, "TBA" to MatchRegion.UNKNOWN).forEach { (location, region) ->
            assertEquals(location, region, eventRegion(Tournament("1", "Cup", location = location)))
        }
        val unknown = match("1", Tournament("1", "Asian Cup"), 0)
        assertTrue(filterMatchEvents(listOf(unknown), MatchRegion.ASIA, MatchLevel.ALL).isEmpty())
        assertEquals(listOf(unknown), filterMatchEvents(listOf(unknown), MatchRegion.UNKNOWN, MatchLevel.ALL))
    }

    @Test fun `region and tier filters intersect without labeling unknown events tier two`() {
        val events = listOf(Tournament("8932", "TWC", location = "Malta"),
            Tournament("major", "Major", location = "Singapore", tier = EventTier.MAJOR),
            Tournament("qualifier", "ESL Pro League Qualifier", location = "Europe (Online)"))
        val matches = events.mapIndexed { i, e -> match("$i", e, i.toLong()) }
        assertEquals(listOf("0"), filterMatchEvents(matches, MatchRegion.EUROPE, MatchLevel.IMPORTANT).map { it.id })
        assertEquals(listOf("1"), filterMatchEvents(matches, MatchRegion.ASIA, MatchLevel.MAJOR).map { it.id })
        assertEquals(listOf("0"), filterMatchEvents(matches, MatchRegion.ALL, MatchLevel.TIER_ONE).map { it.id })
        assertEquals(listOf("2"), filterMatchEvents(matches, MatchRegion.ALL, MatchLevel.OTHER).map { it.id })
    }

    @Test fun `missing location in an old detail record retains explicit index metadata`() {
        val detail = Tournament("tier", "Cup", location = "")
        val index = detail.copy(location = "Bucharest, Romania", tier = EventTier.TIER_ONE)
        val merged = mergedEvents(listOf(detail, index)).single()
        assertEquals(MatchRegion.EUROPE, eventRegion(merged))
        assertTrue(EventEligibility.important(merged))
    }
}
