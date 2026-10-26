package dev.matchscope.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.matchscope.app.data.*
import dev.matchscope.app.ui.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MatchDuelUiTest {
    @get:Rule val app = createComposeRule()
    private fun realMatch(): Match = MobileMatchParser.detail(JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
        .open("mobile/match.json").bufferedReader().use { it.readText() }), "2399415")
    private fun vm() = ScopeViewModel(ApplicationProvider.getApplicationContext<Application>())

    @Test fun duelTabIsVisibleAndWorksWithoutScoreboardOrSeriesTotal() {
        val detail = realMatch().copy(id = "90000001", status = MatchStatus.LIVE, stats = emptyList(), statGroups = emptyList(),
            duels = realMatch().duels.filterKeys { it == "anubis" })
        val vm = vm()
        app.setContent { MatchScopeTheme { Box(Modifier.width(360.dp).height(800.dp)) {
            MatchDetailScreen(detail, ScopeState(), vm, {}, LazyListState())
        } } }
        app.onNodeWithText("对位", substring = false).assertIsDisplayed().performClick()
        app.onNodeWithTag("duel-map-anubis").assertIsSelected()
        app.onNodeWithTag("duel-matrix").assertIsDisplayed()
        val pageTop = app.onNodeWithTag("match-detail-list").fetchSemanticsNode().boundsInRoot.top
        val tabTop = app.onNodeWithText("对位", substring = false).fetchSemanticsNode().boundsInRoot.top
        assertTrue("Tabs should pin without a clipped hero footer", tabTop - pageTop < 65f)
        app.onAllNodes(hasTestTagPrefix("duel-cell-")).assertCountEquals(25)
        app.onNodeWithTag("duel-map-all").assertDoesNotExist()
        app.onNodeWithText("暂无选手统计").assertDoesNotExist()
    }

    @Test fun fullMatrixFitsNarrowScreensAndLargeTextInBothThemes() {
        val detail = realMatch()
        var dark by mutableStateOf(true)
        var key by mutableStateOf("all")
        val vm = vm()
        app.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MatchScopeTheme(dark) { Box(Modifier.width(320.dp).background(Background)) {
                    MatchDuelPanel(detail, ScopeState(), vm, key, { key = it }, {})
                } }
            }
        }
        listOf(true, false).forEach { theme ->
            app.runOnIdle { dark = theme }
            val bounds = app.onNodeWithTag("duel-matrix").fetchSemanticsNode().boundsInRoot
            app.onAllNodes(hasTestTagPrefix("duel-cell-")).assertCountEquals(25)
            app.onAllNodes(hasTestTagPrefix("duel-cell-")).fetchSemanticsNodes().forEach { node ->
                assertTrue("All duel cells must stay inside the screen", node.boundsInRoot.left >= bounds.left && node.boundsInRoot.right <= bounds.right + 1f)
            }
            listOf("m0NESY", "karrigan", "kyousuke", "Magnojez").forEach { name ->
                val layout = mutableListOf<TextLayoutResult>()
                app.onNodeWithText(name, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
                assertEquals(1, layout.single().lineCount)
                assertFalse("$name must fit without clipping", layout.single().hasVisualOverflow)
            }
            app.onNodeWithContentDescription("Boombl4 对 m0NESY，3 次击杀，13 次被杀").assertIsDisplayed()
            app.saveEvidence("v0107-duels-${if (theme) "dark" else "light"}-320dp")
        }
        app.onNodeWithTag("duel-map-anubis").performClick().assertIsSelected()
        app.onNodeWithText("首杀", substring = false).performClick()
        app.onNodeWithTag("duel-matrix").assertIsDisplayed()
        app.onNodeWithText("AWP 击杀", substring = false).performClick()
        app.onAllNodesWithText("0 : 0").onFirst().assertIsDisplayed()
    }

    @Test fun metricSurvivesRestorationAndCellShowsBothDirections() {
        val match = realMatch()
        val restoration = StateRestorationTester(app)
        restoration.setContent { MatchScopeTheme { DuelTable(match.duels.getValue("all"), emptyMap()) {} } }
        app.onNodeWithText("首杀", substring = false).performClick()
        restoration.emulateSavedInstanceStateRestore()
        app.onNodeWithText("首杀", substring = false).assertIsSelected()
        app.onNodeWithContentDescription("Boombl4 对 m0NESY，0 次击杀，6 次被杀").performClick()
        app.onNodeWithText("Boombl4 击杀 m0NESY：0").assertIsDisplayed()
        app.onNodeWithText("m0NESY 击杀 Boombl4：6").assertIsDisplayed()
        app.onNodeWithText("关闭", substring = false).performClick()
        app.onNodeWithText("AWP 击杀", substring = false).performClick()
        app.onNodeWithContentDescription("Boombl4 对 m0NESY，0 次击杀，6 次被杀").assertIsDisplayed()
    }

    @Test fun scoreboardShortcutPreservesMapAndReturningKeepsTheChosenDuelMap() {
        val original = realMatch()
        val match = original.copy(id = "90000002", veto = emptyList(), statGroups = original.statGroups.flatMap { group ->
            listOf(group, group.copy(side = MatchSide.T))
        })
        val vm = vm()
        val scroll = LazyListState()
        app.setContent { MatchScopeTheme { Box(Modifier.width(360.dp).height(720.dp)) {
            MatchDetailScreen(match, ScopeState(), vm, {}, scroll)
        } } }
        app.onNodeWithText("数据", substring = false).performClick()
        app.onNodeWithText("阿努比斯", substring = false).performClick()
        app.onNodeWithText("T", substring = false).performClick()
        app.onNodeWithText("对位数据", substring = false).performClick()
        app.onNodeWithTag("duel-map-anubis").assertIsSelected()
        app.onNodeWithText("T", substring = false).assertDoesNotExist()
        app.onNodeWithTag("duel-map-mirage").performClick()
        app.onNodeWithText("数据", substring = false).performClick()
        app.onNode(hasText("荒漠迷城 · T 阵营", substring = false) and
            hasAnyAncestor(hasTestTag("stats-header-${match.left.id}"))).assertIsDisplayed()
        app.onNodeWithText("对位", substring = false).performClick()
        app.onNodeWithTag("duel-map-mirage").assertIsSelected()
    }

    @Test fun missingDataHasRecoveryAndUpcomingGameDoesNotOfferEmptyRetry() {
        var detail by mutableStateOf(realMatch().copy(duels = emptyMap()))
        val vm = vm()
        app.setContent { MatchScopeTheme { MatchDuelPanel(detail, ScopeState(), vm, "all", {}, {}) } }
        app.onNodeWithText("暂无对位数据").assertIsDisplayed()
        app.onNodeWithText("重试", substring = false).assertHasClickAction()
        app.onNodeWithText("打开 HLTV 验证", substring = false).assertHasClickAction()
        app.runOnIdle { detail = detail.copy(status = MatchStatus.UPCOMING) }
        app.onNodeWithText("比赛尚未开始").assertIsDisplayed()
        app.onNodeWithText("重试", substring = false).assertDoesNotExist()
    }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("test tag starts with $prefix") {
        it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith(prefix)
    }
}
