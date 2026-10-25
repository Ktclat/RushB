package dev.matchscope.app

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class SmokeTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()

    @Test fun navigateSearchAndRankings() {
        app.onNodeWithContentDescription("全局搜索").performClick()
        app.onNode(hasSetTextAction()).performTextInput("Falcons")
        app.onAllNodesWithText("Falcons").onFirst().assertIsDisplayed()
        app.onNodeWithContentDescription("返回").performClick()
        app.onAllNodesWithText("排名").onFirst().performClick()
        app.onNodeWithText("Valve VRS", useUnmergedTree = true).performClick()
        app.onNodeWithText("世界邀请排名").assertIsDisplayed()
        app.onNodeWithTag("ranking-1").assertIsDisplayed()
        app.onAllNodesWithText("我的").onFirst().performClick()
        app.onNodeWithText("收藏比赛").assertIsDisplayed()
    }

    @Test fun resultDetailsAndBookmarkSurviveActivityRecreation() {
        // Open a known record through search; a fixed date is not always in today's seven-day strip.
        app.onNodeWithContentDescription("全局搜索").performClick()
        app.onNode(hasSetTextAction()).performTextInput("Falcons")
        val targetMatch = hasTestTag("match-row-2399415")
        app.onNode(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(targetMatch)
        app.onNode(targetMatch).performClick()
        app.onNodeWithText("比赛详情").assertIsDisplayed()
        app.onNodeWithText("1 : 2").assertIsDisplayed()
        if (app.onAllNodesWithText("已收藏比赛").fetchSemanticsNodes().isNotEmpty()) {
            app.onNodeWithText("已收藏比赛").performClick()
        }
        app.onNodeWithText("收藏比赛", substring = false).performClick()
        app.onNodeWithText("已收藏比赛").assertIsDisplayed()
        app.activityRule.scenario.recreate()
        app.onNodeWithText("已收藏比赛").assertIsDisplayed()
        app.onNodeWithText("数据").performClick()
        // Scroll as a user would: performScrollTo can put a child beneath the pinned tabs.
        val playerStatistics = hasText("m0NESY") and hasText("55-47") and hasClickAction()
        for (attempt in 0 until 6) {
            if (app.onNode(playerStatistics).isDisplayed()) break
            app.onNodeWithTag("match-detail-list").performTouchInput {
                swipeUp(startY = height * .8f, endY = height * .5f, durationMillis = 300)
            }
        }
        app.onNode(playerStatistics and hasText("1.14")).assertIsDisplayed()
        // The dense layout can show the first player without scrolling. Exercise title collapse explicitly.
        for (attempt in 0 until 5) {
            if (app.onNodeWithTag("compact-match-title").isDisplayed()) break
            app.onNodeWithTag("match-detail-list").performTouchInput {
                swipeUp(startY = height * .85f, endY = height * .65f, durationMillis = 300)
            }
        }
        app.onNodeWithTag("compact-match-title").assertIsDisplayed()
        app.onNodeWithTag("compact-match-title").onChildren().filter(hasText("1 : 2")).assertCountEquals(1)
        app.saveEvidence("match-statistics")
    }
}
