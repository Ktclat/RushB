package dev.matchscope.app

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import dev.matchscope.app.data.*
import dev.matchscope.app.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NavigationDragUiTest {
    @get:Rule val app = createComposeRule()
    @Test fun fixedAndFloatingNavigationSupportDragTapAndCancelledGestures() {
        var selected by mutableStateOf(RootTab.MATCHES)
        var style by mutableStateOf(NavigationStyle.FIXED)
        var commits = 0
        app.setContent { MatchScopeTheme { Box(Modifier.width(320.dp)) { ToolNavigation(selected, style) { selected = it; commits++ } } } }
        for (mode in NavigationStyle.entries) {
            app.runOnIdle { style = mode; selected = RootTab.MATCHES }
            app.onNodeWithTag("navigation-drag-track").performTouchInput {
                swipe(Offset(width * .125f, height / 2f), Offset(width * .875f, height / 2f), 550)
            }
            app.onNodeWithTag("nav-profile").assertIsSelected()
            app.onNodeWithTag("navigation-drag-track").performTouchInput {
                down(Offset(width * .875f, height / 2f)); moveTo(Offset(width * .125f, height / 2f), 300); cancel()
            }
            app.onNodeWithTag("nav-profile").assertIsSelected()
            app.onNodeWithTag("nav-rankings").performClick().assertIsSelected()
        }
        app.runOnIdle { assertEquals(4, commits) }
    }
    @Test fun everyVisibleHeadlineHasAStableCoverAndOnlyVisibleStoriesRequestPreviews() {
        val items = (1..40).map { NewsItem("News article $it", "https://www.hltv.org/news/$it/news-$it", "2026-10-24") }
        var requested = emptyList<String>()
        val state = ScopeState(news = Feed(items, SeedSnapshot.matches.stamp), networkAvailable = true)
        app.setContent { MatchScopeTheme { Box(Modifier.width(320.dp).height(480.dp)) {
            NewsListContent(state, {}, {}, {}, { requested = it })
        } } }
        app.mainClock.advanceTimeBy(800)
        app.onAllNodesWithTag("news-cover", useUnmergedTree = true).onFirst().assertIsDisplayed()
        app.runOnIdle { assertTrue(requested.isNotEmpty()); assertTrue(requested.size < items.size); assertFalse(items.last().url in requested) }
    }
    @Test fun liveScoreFitsNarrowHeaderAtLargeFontScale() {
        app.setContent { MatchScopeTheme { CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
            Box(Modifier.width(176.dp)) { FittedMatchScore("12 (1) : 11 (0)", 21.sp, Modifier.fillMaxWidth()) }
        } } }
        val layouts = mutableListOf<TextLayoutResult>()
        app.onNodeWithText("12 (1) : 11 (0)").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        assertFalse("size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
            "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}, " +
            "constraints=${layout.layoutInput.constraints}, style=${layout.layoutInput.style}, density=${layout.layoutInput.density}",
            layout.hasVisualOverflow)
    }
}
