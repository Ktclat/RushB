package dev.matchscope.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class LiveMatchScoreTest {
    private val now = Instant.parse("2026-10-24T12:00:00Z")
    private val stamp = SourceStamp("https://www.hltv.org/matches", now, DataOrigin.NETWORK)
    private val match = Match("123", Team("1", "Alpha"), Team("2", "Bravo"), Tournament("3", "Cup"), MatchStatus.LIVE,
        format = "BO3", maps = listOf(MapScore("Mirage", 13, 8), MapScore("Nuke"), MapScore("Dust2")))
    private fun document(left: Int, right: Int, wonLeft: Int, wonRight: Int, team: String = "1") = """
        <div class="match-wrapper" data-match-id="123" live="true" team1="$team" team2="2">
        <span data-livescore-current-map-score="$left" data-livescore-team="1">$left</span>
        <span data-livescore-current-map-score="$right" data-livescore-team="2">$right</span>
        <span data-livescore-maps-won-for data-livescore-team="1">$wonLeft</span>
        <span data-livescore-maps-won-for data-livescore-team="2">$wonRight</span></div>
    """

    @Test fun `current map rounds and series wins stay separate across map transitions`() {
        fun score(l: Int, r: Int, wl: Int, wr: Int) = match.copy(liveScore = LiveScoreParser.parse(document(l, r, wl, wr), match, stamp))
        assertEquals("1 : 0", score(13, 8, 1, 0).displayScore(now))
        assertEquals("1 : 0", score(0, 0, 1, 0).displayScore(now))
        val second = score(3, 4, 1, 0)
        assertEquals("3 (1) : 4 (0)", second.displayScore(now))
        assertEquals("Nuke", second.liveScore?.mapName)
        assertEquals("1 : 1", score(9, 13, 1, 1).displayScore(now))
        assertEquals("2 (1) : 1 (1)", score(2, 1, 1, 1).displayScore(now))
    }
    @Test fun `overtime only finishes at a completed MR3 boundary`() {
        listOf(13 to 11, 16 to 12, 16 to 14, 19 to 17, 22 to 20).forEach { (l,r) -> assertTrue(completedCs2Map(l,r)) }
        listOf(12 to 12, 13 to 12, 15 to 15, 17 to 15, 18 to 16, 20 to 18).forEach { (l,r) -> assertFalse(completedCs2Map(l,r)) }
    }
    @Test fun `an old score loses live emphasis without inventing a fresh capture`() {
        val current = match.copy(liveScore = LiveScoreParser.parse(document(3,4,1,0), match, stamp))
        assertTrue(current.hasRecentLiveStatus(now.plusSeconds(59)))
        assertFalse(current.hasRecentLiveStatus(now.plusSeconds(60)))
        assertEquals("1 : 0", current.displayScore(now.plusSeconds(60)))
        assertEquals("待更新", current.statusLabel(now.plusSeconds(60)))
        assertEquals(now, current.liveScore?.stamp?.capturedAt)
    }
    @Test(expected = SourceUnavailable::class) fun `a reused match id cannot mix different teams`() {
        LiveScoreParser.parse(document(3,4,1,0,"99"), match, stamp)
    }
    @Test fun `completed results supersede a retained live score`() {
        val live = match.copy(liveScore = LiveScoreParser.parse(document(3,4,1,0), match, stamp))
        val final = match.copy(status = MatchStatus.FINISHED, leftScore = 1, rightScore = 2, stamp = stamp.copy(capturedAt = now.plusSeconds(30)))
        val selected = preferredMatchRecords(listOf(live, final)).single()
        assertNull(selected.liveScore)
        assertEquals("1 : 2", selected.displayScore(now.plusSeconds(30)))
    }
    @Test fun `new map wins remove previous map rounds even when another record keeps them`() {
        val old = match.copy(stamp = stamp, liveScore = LiveScoreParser.parse(document(12,10,0,0), match, stamp))
        val between = match.copy(leftScore = 1, rightScore = 0, stamp = stamp.copy(capturedAt = now.plusSeconds(20)))
        val selected = preferredMatchRecords(listOf(old, between)).single()
        assertNull(selected.liveScore)
        assertEquals("1 : 0", selected.displayScore(now.plusSeconds(20)))
    }
    @Test fun `a reversed team record also reverses round and series scores`() {
        val old = match.copy(stamp = stamp, liveScore = LiveScoreParser.parse(document(3,4,1,0), match, stamp))
        val reverse = match.copy(left = match.right, right = match.left, stamp = stamp.copy(capturedAt = now.plusSeconds(10)))
        assertEquals("4 (0) : 3 (1)", preferredMatchRecords(listOf(old, reverse)).single().displayScore(now.plusSeconds(10)))
    }
    @Test fun `source veto order and ownership are explicit and leftovers use LEFT`() {
        val steps = match.copy(veto = listOf("1. Alpha removed Mirage", "2. Bravo picked Nuke", "3. Dust2 was left over")).vetoSteps()
        assertEquals(listOf(VetoAction.BAN, VetoAction.PICK, VetoAction.LEFT), steps.map { it.action })
        assertEquals(listOf("1", "2", null), steps.map { it.team?.id })
        assertEquals(listOf("Mirage", "Nuke", "Dust2"), steps.map { it.map })
    }
    @Test fun `HLTV region joins matching roster cores while retaining ranking changes`() {
        val entry = RankingEntry(3, Team("1", "Alpha"), 900, change = 0, roster = listOf("a","b","c","d","e"))
        val regional = entry.copy(team = Team("vrs:alpha", "Alpha"))
        val edition = stamp.copy(publishedOn = "2026-10-05")
        val feeds = mapOf(VrsRegion.GLOBAL to Feed(listOf(regional), edition), VrsRegion.EUROPE to Feed(listOf(regional), edition))
        assertEquals("欧洲", hltvRankingRegion(entry, feeds))
        assertEquals("未分类", hltvRankingRegion(entry.copy(roster = listOf("u","v","w","x","y")), feeds))
        assertEquals(0, entry.change)
    }
}
