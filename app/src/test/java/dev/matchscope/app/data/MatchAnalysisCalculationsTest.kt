package dev.matchscope.app.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MatchAnalysisCalculationsTest {
    private val falcons = Team("11283", "Falcons")
    private val betboom = Team("12394", "BETBOOM")

    private fun document(name: String): Document {
        val html = javaClass.classLoader!!.getResourceAsStream("hltv/$name-2026-10-18.html")!!
            .bufferedReader().use { it.readText() }
        return Jsoup.parse(html, "https://www.hltv.org")
    }

    private fun match(): Match {
        val groups = MatchStatsParser.parse(document("match-stats"))
        return Match("2399415", falcons, betboom, Tournament("test-event", "测试赛事"),
            MatchStatus.FINISHED, format = "BO3", leftScore = 1, rightScore = 2,
            stats = groups.single { it.mapKey == "all" && it.side == MatchSide.BOTH }.rows,
            statGroups = groups, insights = MatchInsightsParser.parse(document("match-insights-result")))
    }

    private fun profile(team: Team, maps: List<MapPerformance>) = TeamProfile(team, emptyList(), content = ProfileContent(maps = maps))

    @Test fun `profile fallback uses documented three month window and actual WDL map count`() {
        val doc = document("team-profile")
        assertTrue(doc.select("#statsBox h2").any { it.text() == "Map win statistics past 3 months" })
        val left = TeamProfile(falcons, emptyList(), content = ProfileParser.team(doc))
        val pool = MatchAnalysisCalculations.profileMapPool(match(), left, profile(betboom, emptyList()))!!
        assertEquals(MatchMapMetric.WIN, pool.metric)
        assertEquals("战队近3个月地图胜率", pool.description)
        assertEquals(left.content.maps.size, pool.maps.size)
        val inferno = pool.maps.single { it.mapKey == "inferno" }
        assertEquals(80.0, inferno.left.percentage!!, 0.0)
        assertEquals(5, inferno.left.sampleSize)
        assertEquals(MatchMapRate(), inferno.right)
        assertTrue(pool.maps.all { it.left.statisticsUrl == null && it.right.statisticsUrl == null })
        assertEquals(falcons.url, pool.leftStatisticsUrl)
    }

    @Test fun `profile fallback preserves missing counts and percentages and unions available maps`() {
        val left = profile(falcons, listOf(MapPerformance("Nuke", "-", 2, 0, 1), MapPerformance("Dust2", "50%", 2, null, 2)))
        val right = profile(betboom, listOf(MapPerformance("nuke", "75%", 3, 0, 1), MapPerformance("Ancient", "0%", 0, 0, 3)))
        val pool = MatchAnalysisCalculations.profileMapPool(match(), left, right)!!
        assertEquals(listOf("nuke", "dust2", "ancient"), pool.maps.map { it.mapKey })
        val nuke = pool.maps.first()
        assertNull(nuke.left.percentage)
        assertEquals(3, nuke.left.sampleSize)
        assertEquals(75.0, nuke.right.percentage!!, 0.0)
        assertEquals(4, nuke.right.sampleSize)
        assertNull(pool.maps.single { it.mapKey == "dust2" }.left.sampleSize)
        assertNull(pool.maps.single { it.mapKey == "ancient" }.left.percentage)
        assertEquals(0.0, pool.maps.single { it.mapKey == "ancient" }.right.percentage!!, 0.0)
    }

    @Test fun `profile fallback refuses wrong teams and never replaces match lineup statistics`() {
        val left = profile(falcons, listOf(MapPerformance("Nuke", "50%", 1, 0, 1)))
        val right = profile(betboom, emptyList())
        assertNull(MatchAnalysisCalculations.profileMapPool(match(), right, left))
        assertNull(MatchAnalysisCalculations.profileMapPool(match(), left, null))
        val pool = MatchInsightsParser.parse(document("match-insights-forecast")).mapPools
        assertNull(MatchAnalysisCalculations.profileMapPool(match().copy(insights = MatchInsights(mapPools = pool)), left, right))
        assertNull(MatchAnalysisCalculations.profileMapPool(match(), profile(falcons, emptyList()), right))
    }

    @Test fun `match statistics use total kills deaths and explicitly labelled same version player mean`() {
        val rows = MatchAnalysisCalculations.comparisonRows(match())
        assertEquals(MatchComparison("本场 K/D", "0.90", "1.10"), rows.single { it.label == "本场 K/D" })
        assertEquals(MatchComparison("本场 Rating 3.0（选手均值）", "0.95", "1.12"), rows.single { it.label.startsWith("本场 Rating") })
        assertEquals(MatchComparison("赛前交手地图胜场", "0", "2"), rows.single { it.label == "赛前交手地图胜场" })
        assertTrue(rows.containsAll(match().insights.comparisons))
    }

    @Test fun `published team rating takes priority without completing missing side from player mean`() {
        val source = match()
        val published = MatchComparison("Team rating 3.0", "0.96", null)
        val rows = MatchAnalysisCalculations.comparisonRows(source.copy(insights = source.insights.copy(comparisons = source.insights.comparisons + published)))
        assertEquals(MatchComparison("本场 Rating 3.0", "0.96", null), rows.single { it.label.startsWith("本场 Rating") })
        assertFalse(rows.any { it.label.contains("选手均值") || it.label == published.label })
    }

    @Test fun `mixed rating versions missing kills and zero deaths do not yield misleading aggregates`() {
        val source = match()
        val broken = source.stats.mapIndexed { index, player ->
            when {
                index == 0 -> player.copy(kd = null, ratingVersion = "2.1")
                player.teamId == betboom.id -> player.copy(kd = "20-0")
                else -> player
            }
        }
        val rows = MatchAnalysisCalculations.comparisonRows(source.copy(stats = broken))
        assertFalse(rows.any { it.label.startsWith("本场 Rating") || it.label == "本场 K/D" })
        val partial = MatchAnalysisCalculations.comparisonRows(source.copy(stats = source.stats.mapIndexed { i, p -> if (i == 0) p.copy(rating = null, kd = "-") else p }))
        assertEquals(MatchComparison("本场 K/D", null, "1.10"), partial.single { it.label == "本场 K/D" })
        assertEquals(MatchComparison("本场 Rating 3.0（选手均值）", null, "1.12"), partial.single { it.label.startsWith("本场 Rating") })
    }

    @Test fun `last five team results include finished current series once and ignore core filter`() {
        val source = match()
        val rows = MatchAnalysisCalculations.comparisonRows(source)
        assertEquals(MatchComparison("近5场胜率", "60%（3/5）", "60%（3/5）"), rows.single { it.label == "近5场胜率" })
        val current = MatchHistoryResult(source.id, source.url, source.left, source.right, MatchResultUnit.SERIES,
            leftScore = source.leftScore, rightScore = source.rightScore)
        val duplicated = source.copy(insights = source.insights.copy(recentForm = source.insights.recentForm.map { it.copy(results = listOf(current, current) + it.results) }))
        assertEquals(rows.single { it.label == "近5场胜率" }, MatchAnalysisCalculations.comparisonRows(duplicated).single { it.label == "近5场胜率" })
        val coreOnly = source.copy(insights = source.insights.copy(recentForm = source.insights.recentForm.filter { it.scope == MatchHistoryScope.CORE }))
        assertFalse(MatchAnalysisCalculations.comparisonRows(coreOnly).any { it.label == "近5场胜率" })
    }

    @Test fun `last five preserves team perspective and reports actual sample when fewer than five`() {
        val source = match().copy(status = MatchStatus.UPCOMING, leftScore = null, rightScore = null)
        val result = MatchHistoryResult("2394000", "https://www.hltv.org/matches/2394000/x", betboom, falcons, MatchResultUnit.MAP,
            mapName = "Nuke", leftScore = 6, rightScore = 13)
        val missing = result.copy(matchId = "2394001", leftScore = null)
        val form = MatchRecentForm(falcons, MatchHistoryScope.TEAM, results = listOf(missing, result))
        val rows = MatchAnalysisCalculations.comparisonRows(source.copy(insights = MatchInsights(recentForm = listOf(form))))
        assertEquals(MatchComparison("近5场胜率", "100%（1/1）", null), rows.single { it.label == "近5场胜率" })
    }

    @Test fun `head to head counter is explicitly map based and keyed by team identity`() {
        val source = match()
        val head = source.insights.headToHead!!
        val reversed = head.copy(leftTeamId = head.rightTeamId, rightTeamId = head.leftTeamId, leftMapWins = head.rightMapWins, rightMapWins = head.leftMapWins)
        val rows = MatchAnalysisCalculations.comparisonRows(source.copy(insights = source.insights.copy(headToHead = reversed)))
        assertEquals(MatchComparison("赛前交手地图胜场", "0", "2"), rows.single { it.label == "赛前交手地图胜场" })
    }

    @Test fun `historical map routes never expose round scores as series scores or invent best of one`() {
        val row = match().insights.headToHead!!.results.first()
        val result = MatchAnalysisCalculations.historyMatch(row)
        assertEquals("2394975", result.id)
        assertEquals(row.matchUrl, result.url)
        assertNull(result.leftScore)
        assertNull(result.rightScore)
        assertEquals(listOf(MapScore("Ancient", 10, 13)), result.maps)
        assertFalse(result.format == "BO1")
        assertEquals(Instant.ofEpochMilli(1781283920000), result.resultAt)
        assertNull(result.startsAt)
        assertFalse(result.detailed)
        assertFalse(result.insights.complete)
        assertEquals(row.event, result.event)
    }

    @Test fun `historical series retains team oriented series score while requiring real detail load`() {
        val row = match().insights.recentForm.single { it.team.id == falcons.id && it.scope == MatchHistoryScope.TEAM }.results.first()
        val result = MatchAnalysisCalculations.historyMatch(row)
        assertEquals("2399409", result.id)
        assertEquals(falcons.id, result.left.id)
        assertEquals(2, result.leftScore)
        assertEquals(1, result.rightScore)
        assertEquals("BO3", result.format)
        assertTrue(result.maps.isEmpty())
        assertFalse(result.detailed)
        assertNull(result.startsAt)
        assertNull(result.resultAt)
    }

    @Test fun `historical native route requires matching official identity`() {
        val row = match().insights.headToHead!!.results.first()
        for (invalid in listOf(row.copy(matchId = "not-a-match"), row.copy(matchId = "2399415"),
            row.copy(matchUrl = "https://example.com/matches/${row.matchId}/x"))) {
            assertThrows(IllegalArgumentException::class.java) { MatchAnalysisCalculations.historyMatch(invalid) }
        }
    }
}
