package dev.matchscope.app

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.matchscope.app.data.*
import dev.matchscope.app.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class PersonalizationUiTest {
    @get:Rule val app = createComposeRule()

    @Test fun floatingNavigationAndStartupPreferencesPersistWithoutTouchingBookmarks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("personalization-test", 0)
        try {
            prefs.edit().clear().putStringSet("matches", setOf("2399415")).commit()
            val store = UiPreferencesStore(prefs)
            assertEquals(UiPreferences(), store.read())
            store.write(UiPreferences(NavigationStyle.FLOATING, RootTab.RANKINGS))
            assertEquals(UiPreferences(NavigationStyle.FLOATING, RootTab.RANKINGS), UiPreferencesStore(prefs).read())
            assertEquals(setOf("2399415"), prefs.getStringSet("matches", emptySet()))
            prefs.edit().putString("startTab", "REMOVED_TEAMS_TAB").commit()
            assertEquals(RootTab.MATCHES, store.read().startTab)
            var state by mutableStateOf(ScopeState(uiPreferences = store.read()))
            var destination by mutableStateOf("")
            app.setContent { MatchScopeTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                    Column(Modifier.width(320.dp).height(760.dp)) {
                        Box(Modifier.weight(1f)) {
                            if (destination == "appearance") AppearanceScreen(state.uiPreferences) { store.write(it); state = state.copy(uiPreferences = it) }
                            else ProfileContent(state, { store.write(it); state = state.copy(uiPreferences = it) }, {}, { destination = it })
                        }
                        ToolNavigation(RootTab.PROFILE, state.uiPreferences.navigationStyle) {}
                    }
                }
            } }
            app.onNodeWithTag("navigation-floating").assertIsDisplayed()
            app.onNodeWithText("启动页面").assertDoesNotExist()
            app.onNodeWithText("外观", substring = false).performClick()
            app.onNodeWithText("启动页面").assertIsDisplayed()
            app.onNodeWithTag("appearance-navigation").performClick()
            app.onNodeWithText("固定", substring = false).performClick()
            app.onNodeWithTag("navigation-fixed").assertIsDisplayed()
            assertEquals(NavigationStyle.FIXED, UiPreferencesStore(prefs).read().navigationStyle)
            app.onNodeWithTag("appearance-navigation").performClick()
            app.onNodeWithText("悬浮", substring = false).performClick()
            app.onNodeWithTag("navigation-floating").assertIsDisplayed()
            app.onNodeWithTag("appearance-start").performClick()
            app.onNodeWithTag("appearance-option-start-排名").performClick()
            assertEquals(RootTab.RANKINGS, UiPreferencesStore(prefs).read().startTab)
            assertEquals(setOf("2399415"), prefs.getStringSet("matches", emptySet()))
            app.runOnIdle { destination = "" }
            app.onNodeWithText("收藏比赛").performScrollTo().performClick()
            assertEquals("saved", destination)
            app.onNodeWithTag("nav-teams").assertDoesNotExist()
            app.saveEvidence("v0100-profile-large-text")
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun regionAnimationKeepsIndependentScrollAndSearchDoesNotAlterRankNumbers() {
        MediaLoader.install()
        val stamp = SeedSnapshot.rankings.stamp.copy(publishedOn = "2026-10-05")
        fun rows(region: String) = (1..12).map { rank ->
            RankingEntry(rank, Team("$region-$rank", "$region $rank"), 2000 - rank,
                change = when (rank) { 1 -> 0; 2 -> -2; else -> 3 }, roster = listOf("$region-a", "$region-b", "$region-c"))
        }
        val europe = rows("EU"); val asia = rows("AS"); val americas = rows("AM")
        val state = ScopeState(rankings = Feed(europe, stamp), rankingFailure = "HLTV request failed", vrs = mapOf(
            VrsRegion.GLOBAL to Feed(europe + asia.map { it.copy(rank = it.rank + 12) } + americas.map { it.copy(rank = it.rank + 24) }, stamp),
            VrsRegion.EUROPE to Feed(europe, stamp), VrsRegion.ASIA to Feed(asia, stamp), VrsRegion.AMERICAS to Feed(americas, stamp)))
        val restoration = StateRestorationTester(app)
        restoration.setContent { MatchScopeTheme { Box(Modifier.width(320.dp).height(640.dp)) { RankingContent(state, {}, {}, {}) } } }
        app.onNodeWithContentDescription("排名无变化 0", useUnmergedTree = true).assertIsDisplayed()
        app.onNodeWithContentDescription("下降 2 名", useUnmergedTree = true).assertIsDisplayed()
        app.onAllNodesWithText("欧洲", substring = false).onFirst().assertIsDisplayed()
        app.onAllNodesWithContentDescription("上升 3 名", useUnmergedTree = true).onFirst().assertIsDisplayed()
        app.onNodeWithText("Valve VRS").performClick()
        app.onNodeWithText("更新失败", substring = true).assertDoesNotExist()
        app.onNodeWithTag("region-asia").performClick()
        app.onNodeWithTag("ranking-list-ASIA").performScrollToIndex(9)
        app.onNodeWithTag("region-europe").performClick()
        app.onNodeWithText("EU 1").assertIsDisplayed()
        app.onNodeWithTag("region-asia").performClick()
        app.onNodeWithText("AS 9").assertIsDisplayed()
        app.onNodeWithTag("ranking-list-ASIA").performScrollToIndex(0)
        app.onNodeWithContentDescription("搜索排名").performClick()
        app.onNode(hasSetTextAction()).performTextInput("AS 12")
        app.onNodeWithTag("ranking-12").assertIsDisplayed()
        app.onNodeWithTag("ranking-1").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        app.onNodeWithTag("region-asia").assertIsSelected()
        app.onNodeWithTag("ranking-12").assertIsDisplayed()
    }

    @Test fun manualEventExpansionIsSeparatePerDateAndSurvivesRestoration() {
        MediaLoader.install()
        val day = LocalDate.parse("2026-10-18")
        var selected by mutableStateOf(day)
        val event = Tournament("ordinary", "Ordinary Cup")
        val matches = (0L..1L).map { n ->
            Match("m$n", Team("a", "A"), Team("b", "B"), event, MatchStatus.FINISHED,
                startsAt = day.plusDays(n).atStartOfDay(java.time.ZoneOffset.UTC).toInstant())
        }
        val state = ScopeState(matches = Feed(matches, SeedSnapshot.matches.stamp), timezone = "UTC")
        val restoration = StateRestorationTester(app)
        restoration.setContent { MatchScopeTheme { Box(Modifier.width(360.dp)) { MatchesScreen(state, selected, { selected = it }, {}, {}, {}) } } }
        app.onNodeWithTag("match-row-m0").assertDoesNotExist()
        app.onNodeWithContentDescription("展开 Ordinary Cup").performClick()
        app.onNodeWithTag("match-row-m0").assertIsDisplayed()
        app.runOnIdle { selected = day.plusDays(1) }
        app.onNodeWithTag("match-row-m1").assertDoesNotExist()
        app.runOnIdle { selected = day }
        app.onNodeWithTag("match-row-m0").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        app.onNodeWithTag("match-row-m0").assertIsDisplayed()
    }
}
