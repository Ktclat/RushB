package dev.matchscope.app.data

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MatchSideStatisticsTest {
    private val groups = MatchStatsParser.parse(Jsoup.parse(javaClass.classLoader!!
        .getResourceAsStream("hltv/match-stats-2026-10-18.html")!!.bufferedReader().use { it.readText() }, "https://www.hltv.org"))
    private val stamp = SourceStamp("https://www.hltv.org/matches/2399415/-", Instant.parse("2026-10-18T06:00:00Z"), DataOrigin.NETWORK)
    private val website = Match("2399415", Team("11283", "Falcons"), Team("12394", "BB"), Tournament("1", "Cup"), MatchStatus.FINISHED,
        statGroups = groups, stamp = stamp)
    private val mobile = website.copy(statGroups = groups.filter { it.side == MatchSide.BOTH }.map { it.copy(mapKey = it.mapName?.lowercase() ?: "all", statsUrl = null) },
        stamp = stamp.copy(capturedAt = stamp.capturedAt.plusSeconds(60)))

    @Test fun `website sides bind to mobile keys and preserve record age`() {
        val result = mobile.withSideStatistics(website)
        assertTrue(result.hasCompleteSideStatistics())
        assertEquals(listOf("all", "anubis", "dust2", "mirage"), result.statGroups.map { it.mapKey }.distinct())
        assertEquals(mobile.stamp, result.stamp)
        assertEquals("34-20", result.statGroups.single { it.mapKey == "all" && it.side == MatchSide.CT }.rows.single { it.name == "m0NESY" }.kd)
        assertEquals("1.21", result.statGroups.single { it.mapKey == "anubis" && it.side == MatchSide.T }.rows.single { it.name == "m0NESY" }.rating)
    }
    @Test fun `unrelated matches teams maps and players cannot supply sides`() {
        assertEquals(mobile, mobile.withSideStatistics(website.copy(id = "1")))
        assertEquals(mobile, mobile.withSideStatistics(website.copy(left = Team("9", "Other"))))
        val unknown = website.copy(statGroups = groups.map { it.copy(mapKey = "unknown", mapName = "Unknown") })
        assertEquals(mobile, mobile.withSideStatistics(unknown))
        val mixedRoster = website.copy(statGroups = groups.map { g -> if (g.side == MatchSide.T) g.copy(rows = g.rows.drop(1)) else g })
        assertEquals(mobile, mobile.withSideStatistics(mixedRoster))
    }
    @Test fun `old side data is removed when total kills advance`() {
        val advanced = mobile.copy(statGroups = mobile.statGroups.map { g -> if (g.mapKey == "all") g.copy(rows = g.rows.mapIndexed { i, row -> if (i == 0) row.copy(kd = "56-47") else row }) else g })
        val result = advanced.withSideStatistics(website)
        assertEquals(listOf(MatchSide.BOTH), result.statGroups.filter { it.mapKey == "all" }.map { it.side })
        assertEquals(3, result.statGroups.count { it.mapKey == "anubis" })
        assertFalse(result.hasCompleteSideStatistics())
    }
}
