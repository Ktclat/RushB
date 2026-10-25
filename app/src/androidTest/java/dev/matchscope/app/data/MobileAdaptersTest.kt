package dev.matchscope.app.data

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MobileAdaptersTest {
    private fun json(name: String) = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("mobile/$name.json").bufferedReader().use { it.readText() })
    @Test fun pendingStageIsNotATeamAndLegacyCacheIsMigrated() {
        val pending = MobileMatchParser.list(json("matches")).first { it.id == "2399481" }
        assertEquals("待定", pending.left.name)
        assertEquals("待定", pending.right.name)
        assertEquals("Play-in #1", pending.stage)
        assertEquals(pending, Records.match(Records.match(pending)))
        val legacy = Records.match(pending).apply {
            remove("stage")
            getJSONObject("left").put("name", "${pending.event.name} - Play-in #1")
        }
        val restored = Records.match(legacy, true)
        assertEquals("待定", restored.left.name)
        assertEquals("Play-in #1", restored.stage)
        assertEquals(pending.id, restored.id)
    }
    @Test fun completedProfileScoresDecodeWithoutKnownMatchFeed() {
        val team = MobileProfileParser.team(json("team"), "11283")
        val player = MobileProfileParser.player(json("player"), "19230")
        listOf(team.content, player.content).forEach { content ->
            val rows = content.matches.filter { it.status == MatchStatus.FINISHED }
            assertTrue(rows.isNotEmpty())
            assertTrue(rows.all { it.leftScore != null && it.rightScore != null })
            val actual = rows.first { it.matchUrl?.contains("/2399415/") == true }
            assertEquals(1, actual.leftScore); assertEquals(2, actual.rightScore)
        }
    }
    @Test fun majorClassificationSurvivesCacheWithoutPromotingFeaturedEvents() {
        val events = MobileProfileParser.events(json("events"))
        val major = events.first { it.id == "8302" }
        assertEquals(EventTier.MAJOR, major.tier)
        assertEquals(EventTier.MAJOR, Records.event(Records.event(major)).tier)
        assertNull(events.first { it.id == "8932" }.tier)
        val legacy = Records.event(major).apply { remove("tier") }
        assertNull(Records.event(legacy).tier)
    }
    @Test fun fullFeedKeepsUnknownTeamsAndZeroScores() {
        val matches = MobileMatchParser.list(json("matches"))
        assertEquals(557, matches.size)
        assertTrue(matches.any { it.left.id.startsWith("pending:") || it.right.id.startsWith("pending:") })
        assertEquals(258, matches.count { it.status == MatchStatus.FINISHED })
        val state = dev.matchscope.app.ui.ScopeState(matches = Feed(matches, SourceStamp("https://www.hltv.org/mobile/MatchesV4", Instant.now(), DataOrigin.NETWORK)))
        assertFalse(state.teams.any { it.id.startsWith("pending:") })
        assertTrue(matches.any { it.leftScore == 0 || it.rightScore == 0 })
        assertTrue(matches.filter { it.status == MatchStatus.UPCOMING }.any { it.leftScore == null && it.rightScore == null })
    }
    @Test fun realMatchMatrixOrientationAndTotalsSurviveStorage() {
        val match = MobileMatchParser.detail(json("match"), "2399415").copy(stamp = SourceStamp("https://www.hltv.org/mobile/MatchScreen?matchId=2399415", Instant.parse("2026-10-18T14:38:40Z"), DataOrigin.NETWORK))
        assertEquals("1 : 2", match.score)
        assertEquals(4, match.statGroups.size)
        assertEquals(10, match.stats.size)
        assertEquals("55-47", match.stats.first { it.player?.id == "19230" }.kd)
        assertEquals(13, match.maps.first().left)
        assertEquals(5, match.maps.first().right)
        assertEquals(4, match.duels.size)
        val matrix = match.duels.getValue("all").matrices.first { it.metric == DuelMetric.ALL_KILLS }
        assertEquals(25, matrix.cells.size)
        val cell = matrix.cells.single { it.rowPlayerId == "11840" && it.columnPlayerId == "24177" }
        assertEquals(7, cell.rowKills); assertEquals(5, cell.columnKills)
        assertTrue(match.duels.getValue("anubis").matrices.any { it.cells.any { cell -> cell.rowKills == 0 && cell.columnKills == 0 } })
        assertEquals(14, match.insights.vrs!!.teams.first().ifWin!!.pointsChange)
        assertNull(match.insights.mapPools.first { it.metric == MatchMapMetric.WIN }.maps.first { it.name == "Cache" }.left.percentage)
        val restored = Records.match(Records.match(match), true)
        assertEquals(match.duels, restored.duels)
        assertEquals(DataOrigin.CACHE, restored.stamp!!.origin)
    }
    @Test fun reorderedColumnsAreAlignedByPlayerAndIncompleteMatricesAreRejected() {
        val root = json("match")
        val matrix = root.getJSONObject("matchData").getJSONArray("mapStats").getJSONObject(0).getJSONArray("killMatrixes").getJSONObject(0)
        val columns = matrix.getJSONArray("team1PlayerIds"); val id = columns.getString(0); columns.put(0, columns.getString(1)); columns.put(1,id)
        val rows = matrix.getJSONArray("lines")
        for (i in 0 until rows.length()) { val cells = rows.getJSONObject(i).getJSONArray("cells"); val cell = cells.getJSONObject(0); cells.put(0,cells.getJSONObject(1)); cells.put(1,cell) }
        val all = MobileMatchParser.detail(root,"2399415").duels.getValue("all").matrices.first { it.metric == DuelMetric.ALL_KILLS }
        assertEquals(7, all.cells.single { it.rowPlayerId == "11840" && it.columnPlayerId == "24177" }.rowKills)
        rows.getJSONObject(0).getJSONArray("cells").remove(0)
        assertTrue(runCatching { MobileMatchParser.detail(root,"2399415") }.isFailure)
    }
    @Test fun eventsProvideCompleteScheduleAndPrizeShares() {
        assertEquals(203, MobileProfileParser.events(json("events")).size)
        val event = MobileProfileParser.event(json("event"), "8932")
        assertEquals(14,event.content.matches.size)
        assertNotNull(event.bannerUrl)
        assertEquals(8,event.content.teams.size)
        assertEquals(4,event.content.bracketLinks.size)
        assertEquals("$250,000",event.content.prizes.first().playerPrize)
        assertEquals("$250,000",event.content.prizes.first().clubPrize)
        val saved = Records.event(event); val restored = Records.event(saved)
        assertEquals(event.content.matches.size, restored.content.matches.size)
        assertEquals(event.bannerUrl, restored.bannerUrl)
        assertFalse(mobileBracketUrl("https://www.hltv.org/mobile/embeds/Brackets?eventId=1&bracketId=5359","8932"))
    }
    @Test fun actualEmbeddedBracketsAndNewsDecode() {
        val brackets = listOf("5359", "5360", "5361", "5362").map { EventContentParser.bracket(json("bracket-$it")) }
        assertEquals(4, brackets.size)
        assertTrue(brackets.flatMap { it.rounds }.flatMap { it.slots }.any { it.matchId == "2399415" })
        val news = MobileProfileParser.news(json("player-news"))
        assertTrue(news.isNotEmpty())
        assertTrue(news.first().url.contains("/news/45746/"))
        assertTrue(news.first().title.contains("BETBOOM"))
    }
    @Test fun profilesKeepDeepEventsCareerAndAttributeScores() {
        val known = MobileMatchParser.list(json("matches"))
        val team = MobileProfileParser.team(json("team"), "11283", known)
        val player = MobileProfileParser.player(json("player"), "19230", known)
        assertEquals(5,team.players.size); assertEquals(97,team.content.events.size)
        assertEquals("欧洲", team.team.region)
        assertEquals(107,player.content.events.size); assertEquals(5,player.content.career.size)
        assertEquals(7,player.content.roles.size)
        assertEquals(89,player.content.roles.first { it.label == "Firepower" }.score)
        assertEquals("8932",player.content.matches.first().event.id)
        assertEquals("11283",player.team!!.id)
        assertTrue(player.content.matches.any { it.leftScore == 0 || it.rightScore == 0 })
        assertTrue(runCatching { MobileProfileParser.player(json("player"), "1") }.isFailure)
    }
    @Test fun nativeExtrasKeepHonorsPeopleImagesAndStatisticsAcrossRestart() {
        val player=MobileProfileParser.player(json("player"),"19230")
        assertEquals(43,player.content.honors.size)
        assertEquals(7,player.content.honors.count { it.category=="MVP" })
        assertEquals(4,player.content.teammates.size)
        assertEquals(10,player.content.similarPlayers.size)
        assertEquals(9,player.content.earnings.size)
        assertNotNull(player.content.honors.first { it.category=="Top 20" }.imageUrl)
        assertEquals(2022,player.content.honors.first { it.category=="Top 20" }.year)
        assertTrue(player.content.events.first { it.event.id=="8932" }.statistics.contains("CT Rating" to "1.47"))
        val stamp=SourceStamp("https://www.hltv.org/mobile/PlayerScreen?playerId=19230",Instant.parse("2026-10-18T14:41:16Z"),DataOrigin.NETWORK)
        val restored=Records.profile(Records.profile(Sourced(player,stamp))).value
        assertEquals(player.content,restored.content)
        val team=MobileProfileParser.team(json("team"),"11283")
        assertEquals(2,team.content.honors.size)
        assertEquals(6,team.content.roster.size)
        assertTrue(team.content.roster.any { it.status=="教练" })
        assertTrue(team.content.roster.first { it.player.id=="429" }.roles.contains("IGL"))
        val event=MobileProfileParser.event(json("event"),"8932")
        assertEquals(2,event.content.statistics.size)
        assertEquals(8,event.content.statistics.first().players.size)
        assertEquals("1.52",event.content.statistics.first().players.first().rating)
        assertEquals("1.67",event.content.statistics.last().players.first().rating)
        assertEquals(event.content.statistics,Records.event(Records.event(event)).content.statistics)
        assertNotNull(MobileProfileParser.news(json("player-news")).first().imageUrl)
    }

}
