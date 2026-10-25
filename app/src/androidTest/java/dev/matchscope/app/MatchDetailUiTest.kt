package dev.matchscope.app

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.matchscope.app.ui.MatchScopeTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.matchscope.app.data.*
import dev.matchscope.app.ui.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.luminance
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MatchDetailUiTest {
    @get:Rule val app = createComposeRule()
    private val left = Team("11283", "Falcons")
    private val right = Team("12394", "BETBOOM")
    private fun match() = Match("2399415", left, right, Tournament("1", "Test Event"), MatchStatus.FINISHED, leftScore = 1, rightScore = 2)

    @Test fun scrollingStatisticsKeepsTabsTeamMapAndColumnLabelsVisible() {
        val rows = listOf(left, right).flatMap { team -> (1..10).map { n -> PlayerStat("${team.name} $n", team.id, "$n-0", "90.0", "80%", "1.2", "3.0") } }
        val detail = match().copy(statGroups = listOf(MatchStatGroup("all", null, MatchSide.BOTH, rows)))
        val vm = ScopeViewModel(ApplicationProvider.getApplicationContext<Application>())
        val scroll = LazyListState()
        app.setContent { MatchScopeTheme { Box(Modifier.width(360.dp).height(440.dp)) {
            MatchDetailScreen(detail, ScopeState(), vm, {}, scroll)
        } } }
        app.onNodeWithText("数据", substring = false).performClick()
        listOf(left, right).forEach { team ->
            app.onNodeWithTag("match-detail-list").performScrollToKey("stats-rows:${team.id}")
            app.onNodeWithTag("pinned-statistics").assertIsDisplayed()
            app.onNode(hasText(team.name) and hasAnyAncestor(hasTestTag("pinned-statistics"))).assertIsDisplayed()
            app.onNode(hasText("ADR") and hasAnyAncestor(hasTestTag("pinned-statistics"))).assertIsDisplayed()
            app.onNodeWithText("阵容", substring = false).assertIsDisplayed()
            val pixels = app.onNodeWithTag("pinned-statistics").captureToImage().toPixelMap()
            var visibleTextPixels = 0
            for (y in pixels.height / 2 until pixels.height - 2) for (x in pixels.width / 3 until pixels.width - 2) {
                if (pixels[x, y].luminance() > .2f) visibleTextPixels++
            }
            assertTrue("Pinned column labels must render pixels, not only exist in semantics", visibleTextPixels > 20)
        }
        app.onNodeWithTag("match-detail-list").performScrollToKey("stats-header:${right.id}")
        val nextHeaderIndex = scroll.layoutInfo.visibleItemsInfo.first { it.key == "stats-header:${right.id}" }.index
        val tabs = app.onNodeWithText("阵容", substring = false).fetchSemanticsNode().boundsInRoot
        val offset = (tabs.bottom - app.onNodeWithTag("match-detail-list").fetchSemanticsNode().boundsInRoot.top + 40).toInt()
        runBlocking { withContext(Dispatchers.Main) { scroll.scrollToItem(nextHeaderIndex, -offset) } }
        app.waitForIdle()
        val pin = app.onNodeWithTag("pinned-statistics").fetchSemanticsNode().boundsInRoot
        val nextHeader = app.onNodeWithTag("stats-header-${right.id}").fetchSemanticsNode().boundsInRoot
        assertTrue("Previous header must yield before the next team's name", pin.bottom <= nextHeader.top + 1f)
        app.saveEvidence("v092-pinned-statistics")
        app.onNodeWithText("阵容", substring = false).performClick()
        app.onNodeWithTag("pinned-statistics").assertDoesNotExist()
    }

    @Test fun bpCardsAcceptBothSourceAndExistingOfflineVetoLabels() {
        val source = match().copy(veto = listOf("1. BETBOOM removed Inferno", "2. Falcons picked Dust2", "3. Mirage was left over"))
        app.setContent { MatchScopeTheme { Column { VetoCards(source); VetoCards(source.copy(veto = listOf("BETBOOM 移除 Inferno", "Falcons 选择 Dust2", "剩余地图 Mirage"))) } } }
        listOf("BAN", "PICK", "LEFT", "炼狱小镇", "炙热沙城Ⅱ", "荒漠迷城").forEach { text -> app.onAllNodesWithText(text).assertCountEquals(2) }
    }

    @Test fun matrixMetricsKeepRowOwnershipAndPlayerNavigation() {
        val row = PlayerRef("11840", "Boombl4")
        val column = PlayerRef("19230", "m0NESY")
        val duel = MatchDuels("https://www.hltv.org/stats/matches/performance/132134/falcons-vs-betboom", listOf(
            DuelMatrix(DuelMetric.ALL_KILLS, listOf(row), listOf(column), listOf(DuelCell(row.id, column.id, 3, 13))),
            DuelMatrix(DuelMetric.OPENING_KILLS, listOf(row), listOf(column), listOf(DuelCell(row.id, column.id, 0, 6))),
            DuelMatrix(DuelMetric.AWP_KILLS, listOf(row), listOf(column), listOf(DuelCell(row.id, column.id, 0, 6))),
        ))
        var opened: String? = null
        app.setContent { MatchScopeTheme { DuelTable(duel, emptyMap()) { opened = it } } }
        app.onNodeWithContentDescription("Boombl4 对 m0NESY，3 次击杀，13 次被杀").assertIsDisplayed().performClick()
        app.onNodeWithText("Boombl4 击杀 m0NESY：3").assertIsDisplayed()
        app.onNodeWithText("m0NESY 击杀 Boombl4：13").assertIsDisplayed()
        app.onNodeWithText("关闭", substring = false).performClick()
        app.onNodeWithText("首杀").performClick()
        app.onNodeWithContentDescription("Boombl4 对 m0NESY，0 次击杀，6 次被杀").assertIsDisplayed()
        app.onNodeWithText("AWP 击杀").performClick()
        app.onNodeWithText("0 : 6").assertIsDisplayed()
        app.onNodeWithText("m0NESY").performClick()
        app.runOnIdle { assertEquals("player|19230", opened) }
    }

    @Test fun selectedMapScoreAndPlayerTableChangeTogether() {
        val player = PlayerRef("19230", "m0NESY")
        fun group(key: String, name: String?, kd: String) = MatchStatGroup(key, name, MatchSide.BOTH,
            listOf(PlayerStat(player.name, left.id, kd, "81.0", "70.1%", "1.14", "3.0", player)))
        val match = match().copy(maps = listOf(MapScore("Anubis", 13, 5)),
            statGroups = listOf(group("all", null, "55-47"), group("anubis", "Anubis", "22-9")))
        val vm = ScopeViewModel(ApplicationProvider.getApplicationContext<Application>())
        app.setContent { MatchScopeTheme { MatchDataPanel(match, ScopeState(), vm) {} } }
        app.onNodeWithText("55-47", useUnmergedTree = true).assertIsDisplayed()
        app.onNodeWithText("阿努比斯").performClick()
        app.onNodeWithText("13 : 5").assertIsDisplayed()
        app.onNodeWithText("22-9", useUnmergedTree = true).assertIsDisplayed()
        app.onNodeWithText("55-47", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun compactHeaderUsesScoreForLiveAndTimeForFuture() {
        val zone = ZoneId.of("Asia/Hong_Kong")
        val finished = match()
        assertEquals("1 : 2", compactMatchLabel(finished, zone))
        assertEquals("1 : 2", compactMatchLabel(finished.copy(status = MatchStatus.LIVE), zone))
        val future = finished.copy(status = MatchStatus.UPCOMING, startsAt = Instant.parse("2026-10-18T15:00:00Z"))
        assertEquals("10.18 23:00", compactMatchLabel(future, zone))
        app.setContent { MatchScopeTheme { Column { CompactMatchTitle(finished, zone); CompactMatchTitle(future, zone) } } }
        app.onNodeWithText("1 : 2").assertIsDisplayed()
        app.onNodeWithText("10.18 23:00").assertIsDisplayed()
    }
}
