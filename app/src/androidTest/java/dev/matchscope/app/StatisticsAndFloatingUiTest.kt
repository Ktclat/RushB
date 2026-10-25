package dev.matchscope.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.matchscope.app.data.*
import dev.matchscope.app.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StatisticsAndFloatingUiTest {
    @get:Rule val app = createComposeRule()
    @Test fun longHandlesAndEveryMetricRemainSingleLineOnNarrowLargeFontScreens() {
        val names = listOf("bLitz", "electroNic", "insani", "SenzuTheMongolZ", "Techno4K")
        val rows = names.mapIndexed { index, name -> PlayerStat(name, "1", "100-99", "101.0", "100.0%", "1.14", "3.0", PlayerRef("$index", name), "+10.64%") }
        app.setContent { MatchScopeTheme { CompositionLocalProvider(LocalDensity provides Density(2f, 1.4f)) {
            Box(Modifier.width(296.dp)) { StatisticsTeamRows(rows, false, {}) }
        } } }
        names.forEach { name ->
            val layouts = mutableListOf<TextLayoutResult>()
            app.onNodeWithText(name, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(name, layouts.single().lineCount == 1 && !layouts.single().hasVisualOverflow)
        }
    }
    @Test fun selectedSidePersistsAcrossMapsAndNeverFallsBackToBothWhenMissing() {
        val rows = listOf(PlayerStat("m0NESY", "1", "55-47", "81.0", "67.6%", "1.14", "3.0", PlayerRef("1", "m0NESY")))
        val groups = listOf("all", "anubis").flatMap { key -> MatchSide.entries.map { side ->
            MatchStatGroup(key, "Anubis".takeIf { key != "all" }, side, rows.map { it.copy(kd = "$key-${side.name}") })
        } }
        var match by mutableStateOf(Match("1", Team("1", "A"), Team("2", "B"), Tournament("1", "Cup"), MatchStatus.FINISHED, statGroups = groups))
        app.setContent { MatchScopeTheme { Column { val selection = rememberStatisticsSelection(match)
            StatisticsControls(selection)
            Text(selection.selected?.rows?.first()?.kd ?: "unavailable")
        } } }
        app.onNodeWithText("T", substring = false).performClick()
        app.onNodeWithText("all-T").assertExists()
        app.onNodeWithText("Anubis").performClick()
        app.onNodeWithText("anubis-T").assertExists()
        app.onNodeWithText("CT", substring = false).performClick()
        app.onNodeWithText("anubis-CT").assertExists()
        app.runOnIdle { match = match.copy(statGroups = groups.filter { it.mapKey != "anubis" || it.side == MatchSide.BOTH }) }
        app.onNodeWithText("unavailable").assertExists()
        app.onNodeWithText("Both").performClick()
        app.onNodeWithText("anubis-BOTH").assertExists()
    }
    @Test fun floatingPillOverlaysContentButMarginsPassTapsAndLastRowCanClearIt() {
        var clicked = -1
        var style by mutableStateOf(NavigationStyle.FLOATING)
        app.setContent { MatchScopeTheme { Box(Modifier.width(360.dp).height(600.dp).testTag("viewport")) {
            RootPageFrame(RootTab.MATCHES, style, {}) {
                LazyColumn(Modifier.fillMaxSize().testTag("underlying-list"), contentPadding = PaddingValues(bottom = 16.dp + LocalRootBottomInset.current)) {
                    items(30) { index -> Text("Row $index", Modifier.fillMaxWidth().height(60.dp).background(PanelRaised)
                        .testTag("row-$index").clickable { clicked = index }) }
                }
            }
        } } }
        val viewport = app.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val list = app.onNodeWithTag("underlying-list").fetchSemanticsNode().boundsInRoot
        val pill = app.onNodeWithTag("navigation-pill").fetchSemanticsNode().boundsInRoot
        assertEquals(viewport.bottom, list.bottom, 1f)
        app.onNodeWithTag("viewport").performTouchInput { click(Offset(2f, pill.center.y - viewport.top)) }
        app.runOnIdle { assertTrue("The margin alongside the pill must reach the page", clicked >= 0) }
        app.onNodeWithTag("underlying-list").performScrollToIndex(29)
        val last = app.onNodeWithTag("row-29").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Last item must be reachable above the floating pill", last.bottom <= pill.top)
        app.runOnIdle { style = NavigationStyle.FIXED }
        val fixedList = app.onNodeWithTag("underlying-list").fetchSemanticsNode().boundsInRoot
        val fixedNav = app.onNodeWithTag("navigation-pill").fetchSemanticsNode().boundsInRoot
        assertTrue("Fixed mode still reserves its own height", fixedList.bottom <= fixedNav.top)
    }
}
