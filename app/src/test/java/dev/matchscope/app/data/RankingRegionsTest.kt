package dev.matchscope.app.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RankingRegionsTest {
    @Test fun `an incomplete new edition cannot mix regional ranks with an older world list`() {
        val names = VrsRegion.entries.map { "standings_${it.key}_2026_10_05.md" } +
            listOf("standings_global_2026_10_12.md", "standings_europe_2026_10_12.md") +
            VrsRegion.entries.map { "standings_${it.key}_2026_99_01.md" }
        assertEquals(listOf("2026_10_05"), completeVrsEditions(names))
    }

    @Test fun `world membership requires same edition and matching roster even for identical synthetic ids`() {
        val entry = RankingEntry(1, Team("vrs:test", "Test"), 2000, roster = listOf("A", "B", "C", "D", "E"))
        val stamp = SeedSnapshot.rankings.stamp.copy(publishedOn = "2026-10-05")
        val feeds = mapOf(VrsRegion.GLOBAL to Feed(listOf(entry), stamp),
            VrsRegion.EUROPE to Feed(listOf(entry), stamp))
        assertEquals("欧洲", rankingRegion(entry, VrsRegion.GLOBAL, feeds))
        assertEquals("未分类", rankingRegion(entry, VrsRegion.GLOBAL, feeds + (VrsRegion.EUROPE to Feed(listOf(entry), stamp.copy(publishedOn = "2026-09-28")))))
        assertEquals("未分类", rankingRegion(entry, VrsRegion.GLOBAL, feeds + (VrsRegion.EUROPE to Feed(listOf(entry.copy(roster = listOf("A", "B", "X", "Y", "Z"))), stamp))))
        assertEquals("美洲", rankingRegion(entry, VrsRegion.AMERICAS, feeds))
    }

    @Test fun `every bundled global entry has exactly one regional identity`() {
        val feeds = VrsRegion.entries.associateWith { region ->
            val raw = File("src/main/assets/standings_${region.key}_2026_10_05.md").readText()
            Feed(VrsParser.parse(raw, "https://example.org"), SeedSnapshot.rankings.stamp.copy(publishedOn = VrsParser.publishedDate(raw)))
        }
        feeds.getValue(VrsRegion.GLOBAL).items.forEach { entry ->
            assertNotEquals(entry.team.name, "未分类", rankingRegion(entry, VrsRegion.GLOBAL, feeds))
        }
    }

    @Test fun `HLTV neutral dash means zero but missing and new do not`() {
        fun parse(change: String) = HltvParser.rankings("""
            <div class="ranked-team"><span class="position">#2</span><span class="name">Team</span>
            <span class="points">(894 points)</span>$change<a href="/team/9565/team">Profile</a></div>
        """).single().change
        assertEquals(0, parse("""<div class="change neutral">-</div>"""))
        assertEquals(0, parse("""<div class="change">0</div>"""))
        assertEquals(3, parse("""<div class="change positive">+3</div>"""))
        assertEquals(-2, parse("""<div class="change negative">-2</div>"""))
        assertNull(parse("""<div class="change">NEW</div>"""))
        assertNull(parse(""))
    }
}
