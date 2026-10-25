package dev.matchscope.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()
    @Test fun matchFilterSurvivesDetailNavigationTabSwitchAndRecreation() {
        app.onAllNodesWithText("已结束", substring = false).onFirst().performClick()
        app.onNodeWithContentDescription("全局搜索").performClick()
        app.onNodeWithContentDescription("返回").performClick()
        app.onAllNodesWithText("已结束", substring = false).onFirst().assertIsSelected()
        app.onNodeWithTag("nav-profile").performClick()
        app.onNodeWithTag("nav-matches").performClick()
        app.onAllNodesWithText("已结束", substring = false).onFirst().assertIsSelected()
        app.activityRule.scenario.recreate()
        app.onAllNodesWithText("已结束", substring = false).onFirst().assertIsSelected()
    }
    @Test fun selectionMovesBetweenAllFourTabs() {
        val tabs = listOf("matches", "calendar", "rankings", "profile")
        for (selected in tabs) {
            app.onNodeWithTag("nav-$selected").performClick()
            app.mainClock.advanceTimeBy(250)
            for (tab in tabs) {
                if (tab == selected) app.onNodeWithTag("nav-$tab").assertIsSelected()
                else app.onNodeWithTag("nav-$tab").assertIsNotSelected()
            }
            app.saveEvidence("nav-$selected")
        }
        app.activityRule.scenario.recreate()
        app.onNodeWithTag("nav-profile").assertIsSelected()
    }
}
