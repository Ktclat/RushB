package dev.matchscope.app.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MatchInsightsParserTest {
    private fun document(kind: String): Document {
        val html = javaClass.classLoader!!.getResourceAsStream("hltv/match-insights-$kind-2026-10-18.html")!!
            .bufferedReader().use { it.readText() }
        return Jsoup.parse(html, "https://www.hltv.org")
    }

    @Test fun `post match VRS contains result estimate without counterfactual wins and losses`() {
        val data = MatchInsightsParser.parse(document("result")).vrs!!
        assertEquals(MatchVrsMode.RESULT, data.mode)
        val falcons = data.teams.single { it.teamId == "11283" }
        assertEquals(1880, falcons.points)
        assertEquals(4, falcons.rank)
        assertEquals(MatchVrsImpact(-19, 5, -1), falcons.result)
        assertNull(falcons.ifWin)
        assertNull(falcons.ifLoss)
        val betboom = data.teams.single { it.teamId == "12394" }
        assertEquals(1621, betboom.points)
        assertEquals(10, betboom.rank)
        assertEquals(MatchVrsImpact(106, 8, 2), betboom.result)
    }

    @Test fun `pre match forecast keeps each team's win and loss scenarios separate`() {
        val data = MatchInsightsParser.parse(document("forecast")).vrs!!
        assertEquals(MatchVrsMode.FORECAST, data.mode)
        val aurora = data.teams.single { it.teamId == "11861" }
        assertEquals(1839, aurora.points)
        assertEquals(6, aurora.rank)
        assertEquals(MatchVrsImpact(24, 4, 2), aurora.ifWin)
        assertEquals(MatchVrsImpact(-12, 6, null), aurora.ifLoss)
        assertNull(aurora.result)
        val falcons = data.teams.single { it.teamId == "11283" }
        assertEquals(MatchVrsImpact(35, 4, 1), falcons.ifWin)
        assertEquals(MatchVrsImpact(-16, 6, -1), falcons.ifLoss)
    }

    @Test fun `map win pick and ban metrics keep source sample counts and minimum sample blanks`() {
        val data = MatchInsightsParser.parse(document("forecast"))
        assertEquals(listOf(MatchMapMetric.WIN, MatchMapMetric.PICK, MatchMapMetric.BAN), data.mapPools.map { it.metric })
        assertTrue(data.mapPools.all { it.maps.size == 7 })
        val wins = data.mapPools.single { it.metric == MatchMapMetric.WIN }
        assertTrue(wins.description!!.contains("past 3 months"))
        assertTrue(wins.description.contains("min. 3 maps"))
        assertTrue(wins.leftStatisticsUrl!!.contains("startDate=2026-07-18"))
        assertTrue(wins.leftStatisticsUrl.contains("lineup=7938"))
        val dust2 = wins.maps.single { it.mapKey == "dust2" }
        assertEquals("Dust2", dust2.name)
        assertEquals(50.0, dust2.left.percentage!!, 0.0)
        assertEquals(10, dust2.left.sampleSize)
        assertEquals(69.0, dust2.right.percentage!!, 0.0)
        assertEquals(13, dust2.right.sampleSize)
        assertTrue(dust2.right.statisticsUrl!!.contains("/map/31?"))
        val cache = wins.maps.single { it.mapKey == "cache" }
        assertEquals(0, cache.right.sampleSize)
        assertNull(cache.right.percentage)
        val ancient = wins.maps.single { it.mapKey == "ancient" }
        assertEquals(1, ancient.left.sampleSize)
        assertNull(ancient.left.percentage) // The graph encodes zero; the source table deliberately withholds it.
        val picks = data.mapPools.single { it.metric == MatchMapMetric.PICK }.maps.single { it.mapKey == "dust2" }
        assertEquals(32.0, picks.left.percentage!!, 0.0)
        assertEquals(19, picks.left.sampleSize) // Pick opportunities, not played maps.
        assertEquals(53.0, picks.right.percentage!!, 0.0)
        assertEquals(15, picks.right.sampleSize)
    }

    @Test fun `head to head keeps two map results from one series and their true match link`() {
        val data = MatchInsightsParser.parse(document("result")).headToHead!!
        assertEquals(0, data.leftMapWins)
        assertEquals(2, data.rightMapWins)
        assertEquals(0, data.overtimes)
        assertEquals(2, data.results.size)
        assertEquals(listOf("Ancient", "Nuke"), data.results.map { it.mapName })
        assertEquals(listOf(10, 11), data.results.map { it.leftScore })
        assertEquals(listOf(13, 13), data.results.map { it.rightScore })
        assertTrue(data.results.all { it.unit == MatchResultUnit.MAP && it.format == null })
        assertTrue(data.results.all { it.matchId == "2394975" && it.matchUrl.contains("/matches/2394975/") })
        assertEquals(Instant.ofEpochMilli(1781283920000), data.results.first().playedAt)
        assertEquals("8301", data.results.first().event!!.id)
        assertEquals("https://www.hltv.org/results?team=11283&team=12394&requireAllTeams=", data.moreUrl)
    }

    @Test fun `recent form preserves team perspective core scope and series versus single map scores`() {
        val data = MatchInsightsParser.parse(document("result")).recentForm
        assertEquals(4, data.size)
        val falcons = data.single { it.team.id == "11283" && it.scope == MatchHistoryScope.TEAM }
        assertEquals(17, falcons.results.size)
        assertEquals("Matches, past 3 months", falcons.period)
        assertEquals("https://www.hltv.org/results?team=11283", falcons.moreUrl)
        val first = falcons.results.first()
        assertEquals("2399409", first.matchId)
        assertEquals("11283", first.left.id)
        assertEquals("9996", first.right.id)
        assertEquals(2, first.leftScore)
        assertEquals(1, first.rightScore)
        assertEquals("BO3", first.format)
        assertEquals(MatchResultUnit.SERIES, first.unit)
        assertNull(first.playedAt) // Empty relative date is not a timestamp or today's date.
        assertNull(first.dateLabel)
        assertNull(first.event)
        val singleMap = falcons.results.single { it.right.name == "K27" }
        assertEquals(MatchResultUnit.MAP, singleMap.unit)
        assertEquals("d2", singleMap.mapName)
        assertEquals(13, singleMap.leftScore)
        assertEquals(6, singleMap.rightScore)
        assertNull(singleMap.format)
        val core = data.single { it.team.id == "11283" && it.scope == MatchHistoryScope.CORE }
        assertEquals(17, core.results.size)
        assertNull(core.moreUrl) // Team history must not be presented as a core-specific filter.
        assertEquals(13, data.single { it.team.id == "12394" && it.scope == MatchHistoryScope.TEAM }.results.size)
    }

    @Test fun `rank comparisons follow lineup team ids independently of DOM order`() {
        val doc = document("forecast")
        val lineups = doc.select(".lineup")
        lineups.last()!!.after(lineups.first()!!)
        val comparisons = MatchInsightsParser.parse(doc).comparisons
        assertEquals(listOf(MatchComparison("HLTV 排名", "#6", "#5"), MatchComparison("VRS 排名", "#6", "#5")), comparisons)
    }

    @Test fun `missing source sections and incomplete VRS are not invented`() {
        val finished = document("result")
        assertTrue(MatchInsightsParser.parse(finished).mapPools.isEmpty())
        finished.select(".past-matches,.head-to-head,.head-to-head-listing,.vrs-forecast-container,.lineup").remove()
        assertEquals(MatchInsights(), MatchInsightsParser.parse(finished))
        val forecast = document("forecast")
        forecast.select(".vrs-forecast-right .vrs-forecast-numbers-wrapper").remove()
        assertTrue(MatchInsightsParser.parse(forecast).vrs!!.teams.all { it.ifLoss == null })
    }

    @Test fun `external links never become native match ids or data source links`() {
        val doc = document("forecast")
        doc.select(".head-to-head-listing td.date a[href]").attr("href", "https://example.com/matches/2399415/x")
        doc.select(".past-matches-score a[href]").attr("href", "https://example.com/matches/2399415/x")
        doc.select(".map-stats-infobox-winpercentage a[href]").attr("href", "https://example.com/stats/lineup/map/31")
        val data = MatchInsightsParser.parse(doc)
        assertTrue(data.headToHead!!.results.isEmpty())
        assertTrue(data.recentForm.all { it.results.isEmpty() })
        assertTrue(data.mapPools.flatMap { it.maps }.all { it.left.statisticsUrl == null && it.right.statisticsUrl == null })
    }

    @Test fun `full head to head route uses the official intersection switch`() {
        val html = javaClass.classLoader!!.getResourceAsStream("hltv/results-team-filter-2026-10-18.html")!!
            .bufferedReader().use { it.readText() }
        val filter = Jsoup.parse(html, "https://www.hltv.org").selectFirst("a[href]")!!
        assertEquals("Require all teams", filter.text())
        assertEquals("/results?requireAllTeams=", filter.attr("href"))
        val url = MatchInsightsParser.parse(document("result")).headToHead!!.moreUrl!!
        assertTrue(HltvAccess.allowed(url))
        assertEquals(setOf("team=11283", "team=12394", "requireAllTeams="), java.net.URI(url).rawQuery.split('&').toSet())
    }
}
