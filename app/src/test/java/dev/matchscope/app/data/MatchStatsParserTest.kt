package dev.matchscope.app.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test

class MatchStatsParserTest {
    private fun document(name: String = "match-stats"): Document {
        val html = javaClass.classLoader!!.getResourceAsStream("hltv/$name-2026-10-18.html")!!
            .bufferedReader().use { it.readText() }
        return Jsoup.parse(html, "https://www.hltv.org")
    }

    @Test fun `all maps and three map scopes keep both teams and all sides`() {
        val groups = MatchStatsParser.parse(document())
        assertEquals(12, groups.size)
        assertEquals(listOf("all", "242012", "242022", "242030"), groups.map { it.mapKey }.distinct())
        assertEquals(listOf(null, "Anubis", "Dust2", "Mirage"), groups.distinctBy { it.mapKey }.map { it.mapName })
        groups.forEach { group ->
            assertEquals(10, group.rows.size)
            assertEquals(setOf("11283", "12394"), group.rows.map { it.teamId }.toSet())
            assertEquals(10, group.rows.map { it.player!!.id }.toSet().size)
            assertTrue(group.rows.all { it.ratingVersion == "3.0" && it.player!!.photoUrl != null })
        }
        assertEquals(setOf(MatchSide.BOTH, MatchSide.T, MatchSide.CT), groups.filter { it.mapKey == "all" }.map { it.side }.toSet())

        fun monesy(map: String, side: MatchSide) = groups.single { it.mapKey == map && it.side == side }
            .rows.single { it.player!!.id == "19230" }
        assertEquals("55-47", monesy("all", MatchSide.BOTH).kd)
        assertEquals("34-20", monesy("all", MatchSide.CT).kd)
        assertEquals("21-27", monesy("all", MatchSide.T).kd)
        assertEquals("100.5", monesy("all", MatchSide.CT).adr)
        assertEquals("60.0%", monesy("all", MatchSide.T).kast)
        assertEquals("16-7", monesy("242012", MatchSide.BOTH).kd)
        assertEquals("2.28", monesy("242012", MatchSide.CT).rating)
        assertEquals("1.21", monesy("242012", MatchSide.T).rating)
        assertEquals("24-23", monesy("242022", MatchSide.BOTH).kd)
        assertEquals("15-17", monesy("242030", MatchSide.BOTH).kd)
    }

    @Test fun `economy adjusted cells and swing remain distinct from traditional stats`() {
        val player = MatchStatsParser.parse(document()).single { it.mapKey == "all" && it.side == MatchSide.BOTH }
            .rows.single { it.name == "m0NESY" }
        assertEquals("55-47", player.kd)
        assertEquals("81.0", player.adr)
        assertEquals("67.6%", player.kast)
        assertEquals("51-50", player.ecoKd)
        assertEquals("78.0", player.ecoAdr)
        assertEquals("68.6%", player.ecoKast)
        assertEquals("+0.65%", player.swing)
        assertEquals("1.14", player.rating)
    }

    @Test fun `map names and source stats links match identifiers rather than table order`() {
        val doc = document()
        val firstMap = doc.selectFirst(".stats-menu-link .dynamic-map-name-full[id=242012]")!!.parent()!!
        firstMap.parent()!!.appendChild(firstMap)
        val groups = MatchStatsParser.parse(doc)
        assertTrue(groups.filter { it.mapKey == "all" }.all {
            it.statsUrl == "https://www.hltv.org/stats/matches/132134/falcons-vs-betboom"
        })
        assertTrue(groups.filter { it.mapKey == "242012" }.all {
            it.mapName == "Anubis" && it.statsUrl == "https://www.hltv.org/stats/matches/mapstatsid/242012/betboom-vs-falcons"
        })
        assertTrue(groups.filter { it.mapKey == "242030" }.all {
            it.mapName == "Mirage" && it.statsUrl == "https://www.hltv.org/stats/matches/mapstatsid/242030/falcons-vs-betboom"
        })
    }

    @Test fun `missing maps sides and statistics are not synthesized from another scope`() {
        assertTrue(MatchStatsParser.parse(document("upcoming")).isEmpty())
        val legacy = MatchStatsParser.parse(document("match"))
        assertEquals(3, legacy.size)
        assertTrue(legacy.all { it.mapKey == "all" })

        val doc = document()
        doc.select("#242012-content table.ctstats").remove()
        doc.select("#242012-content table.totalstats td.kd.traditional-data").remove()
        val groups = MatchStatsParser.parse(doc)
        assertFalse(groups.any { it.mapKey == "242012" && it.side == MatchSide.CT })
        val anubis = groups.single { it.mapKey == "242012" && it.side == MatchSide.BOTH }
        assertTrue(anubis.rows.all { it.kd == null && it.ecoKd != null })
    }

    @Test fun `unknown map label remains unknown and foreign stats URLs are discarded`() {
        val doc = document()
        doc.select(".stats-menu-link .dynamic-map-name-full[id=242012]").remove()
        val link = doc.selectFirst(".mapholder a.results-stats[href*=/242012/]")!!
        link.attr("href", "https://example.com/stats/matches/mapstatsid/242012/x")
        val groups = MatchStatsParser.parse(doc).filter { it.mapKey == "242012" }
        assertEquals(3, groups.size)
        assertTrue(groups.all { it.mapName == null && it.statsUrl == null })
    }

    @Test fun `unidentified map or team cannot silently inherit another identity`() {
        val missingMap = document()
        missingMap.selectFirst("#242012-content")!!.removeAttr("id")
        assertThrows(SourceUnavailable::class.java) { MatchStatsParser.parse(missingMap) }
        val missingTeam = document()
        missingTeam.selectFirst("#all-content table.totalstats .header-row a")!!.remove()
        assertThrows(SourceUnavailable::class.java) { MatchStatsParser.parse(missingTeam) }
    }
}
