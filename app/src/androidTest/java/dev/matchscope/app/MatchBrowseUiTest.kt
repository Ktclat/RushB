package dev.matchscope.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.matchscope.app.data.*
import dev.matchscope.app.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class MatchBrowseUiTest {
    @get:Rule val app = createComposeRule()
    private val day = LocalDate.parse("2026-10-18")
    private val stamp = SourceStamp("https://www.hltv.org/mobile/MatchesV4", Instant.now(), DataOrigin.NETWORK)
    private val events = listOf(Tournament("priority", "Important Cup", location = "Malta", tier = EventTier.TIER_ONE),
        Tournament("europe", "European Cup", location = "Europe (Online)"), Tournament("asia", "Asian Cup", location = "Shanghai, China"))
    private val matches = events.mapIndexed { i, event ->
        Match("m$i", Team("a$i", "Alpha"), Team("b$i", "Bravo"), Tournament(event.id, event.name), MatchStatus.FINISHED,
            startsAt = Instant.parse("2026-10-18T00:00:00Z").plusSeconds((3 - i) * 3600L), leftScore = 2, rightScore = 1)
    }
    private val state = ScopeState(matches = Feed(matches, stamp), eventIndex = Feed(events, stamp), timezone = "UTC")
    @Before fun images() { MediaLoader.install() }

    private fun expandOrdinary() {
        app.onNodeWithContentDescription("展开 European Cup").performClick()
        app.onNodeWithContentDescription("展开 Asian Cup").performClick()
    }

    @Test fun defaultPriorityAndGlobalTimeSortingAreVisibleAndSurviveRestoration() {
        val restoration = StateRestorationTester(app)
        restoration.setContent { MatchScopeTheme { Box(Modifier.width(360.dp).height(760.dp).background(Background)) {
            MatchesScreen(state, day, {}, {}, {}, {})
        } } }
        app.onNodeWithContentDescription("排序比赛").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "重点优先"))
        app.onNodeWithText("重点优先").assertDoesNotExist()
        app.onNodeWithTag("match-row-m1").assertDoesNotExist()
        app.onNodeWithTag("match-row-m2").assertDoesNotExist()
        expandOrdinary()
        assertTrue(app.onNodeWithTag("match-row-m0").getUnclippedBoundsInRoot().top < app.onNodeWithTag("match-row-m2").getUnclippedBoundsInRoot().top)
        app.onNodeWithContentDescription("排序比赛").performClick()
        app.onNodeWithText("重点赛事优先").assertIsSelected()
        app.onNodeWithText("时间从早到晚").performClick()
        app.onNodeWithContentDescription("排序比赛").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "时间升序"))
        app.onNodeWithText("时间升序").assertDoesNotExist()
        assertTrue(app.onNodeWithTag("match-row-m2").getUnclippedBoundsInRoot().top < app.onNodeWithTag("match-row-m0").getUnclippedBoundsInRoot().top)
        restoration.emulateSavedInstanceStateRestore()
        app.onNodeWithContentDescription("排序比赛").performClick()
        app.onNodeWithText("时间从早到晚").assertIsSelected()
        app.onNodeWithContentDescription("关闭排序").performClick()
        app.saveEvidence("v092-home-time-order")
    }

    @Test fun filtersUseDirectoryMetadataCombineWithStatusAndCanClearAnEmptyResult() {
        val restoration = StateRestorationTester(app)
        restoration.setContent { MatchScopeTheme { Box(Modifier.width(320.dp).height(760.dp).background(Background)) {
            MatchesScreen(state, day, {}, {}, {}, {})
        } } }
        app.onNodeWithContentDescription("筛选比赛").performClick()
        app.onNodeWithText("欧洲", substring = false).performClick()
        app.onNodeWithText("Major 或 Tier 1").performClick()
        app.onNodeWithText("显示 1 场").performClick()
        app.onNodeWithTag("match-row-m0").assertIsDisplayed()
        app.onNodeWithTag("match-row-m1").assertDoesNotExist()
        app.onNodeWithTag("match-row-m2").assertDoesNotExist()
        app.onNodeWithContentDescription("筛选比赛").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "筛选 2"))
        app.onNodeWithText("筛选 2").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        app.onNodeWithTag("match-row-m0").assertIsDisplayed()
        app.onNodeWithTag("match-row-m1").assertDoesNotExist()
        app.onNodeWithText("未开始", substring = false).performClick()
        app.onNodeWithText("没有符合条件的比赛").assertIsDisplayed()
        app.onNodeWithText("清除筛选").performClick()
        expandOrdinary()
        matches.forEach { app.onNodeWithTag("match-row-${it.id}").assertIsDisplayed() }
        app.onNodeWithText("筛选 2").assertDoesNotExist()
        app.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange)).assertCountEquals(0)
    }

    @Test fun dismissingFilterDraftDoesNotApplyItAndResetRestoresAllMatches() {
        app.setContent { MatchScopeTheme { Box(Modifier.width(320.dp).height(760.dp)) { MatchesScreen(state, day, {}, {}, {}, {}) } } }
        expandOrdinary()
        app.onNodeWithContentDescription("筛选比赛").performClick()
        app.onNodeWithText("亚洲", substring = false).performClick()
        app.onNodeWithContentDescription("关闭筛选").performClick()
        matches.forEach { app.onNodeWithTag("match-row-${it.id}").assertIsDisplayed() }
        app.onNodeWithContentDescription("筛选比赛").performClick()
        app.onNodeWithText("亚洲", substring = false).performClick()
        app.onNodeWithText("显示 1 场").performClick()
        app.onNodeWithTag("match-row-m2").assertIsDisplayed()
        app.onNodeWithTag("match-row-m0").assertDoesNotExist()
        app.onNodeWithContentDescription("筛选比赛").performClick()
        app.onNodeWithText("重置").performClick()
        app.onNodeWithText("显示 3 场").performClick()
        matches.forEach { app.onNodeWithTag("match-row-${it.id}").assertIsDisplayed() }
    }
}
