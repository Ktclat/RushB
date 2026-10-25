package dev.matchscope.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.matchscope.app.data.*
import dev.matchscope.app.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ThemeUiTest {
    @get:Rule val app = createComposeRule()

    @Test fun appearanceChangesImmediatelyPersistsAndOnlySystemModeFollowsTheDevice() {
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("theme-ui-test", 0)
        try {
            prefs.edit().clear().putStringSet("matches", setOf("2399415")).commit()
            val store = UiPreferencesStore(prefs)
            assertEquals(ThemeMode.DARK, store.read().themeMode)
            var preferences by mutableStateOf(store.read().copy(navigationStyle = NavigationStyle.FLOATING, startTab = RootTab.RANKINGS))
            var systemDark by mutableStateOf(false)
            app.setContent { MatchScopeTheme(preferences.themeMode.isDark(systemDark)) {
                Box(Modifier.fillMaxSize().background(Background).testTag("theme-background")) {
                    AppearanceScreen(preferences) { preferences = it; store.write(it) }
                }
            } }
            fun assertBackground(expected: Color) {
                val pixels = app.onNodeWithTag("theme-background").captureToImage().toPixelMap()
                assertEquals(expected, pixels[2, pixels.height - 2])
            }
            assertBackground(DarkColors.background)
            app.onNodeWithTag("appearance-theme").performClick()
            app.onNodeWithText("浅色", substring = false).performClick()
            assertBackground(LightColors.background)
            assertEquals(preferences, UiPreferencesStore(prefs).read())
            assertEquals(RootTab.RANKINGS, store.read().startTab)
            assertEquals(NavigationStyle.FLOATING, store.read().navigationStyle)
            assertEquals(setOf("2399415"), prefs.getStringSet("matches", emptySet()))
            app.runOnIdle { systemDark = true }
            assertBackground(LightColors.background)
            app.onNodeWithTag("appearance-theme").performClick()
            app.onNodeWithText("跟随系统", substring = false).performClick()
            assertBackground(DarkColors.background)
            app.runOnIdle { systemDark = false }
            assertBackground(LightColors.background)
            app.onNodeWithTag("appearance-theme").performClick()
            app.onNodeWithText("深色", substring = false).performClick()
            assertBackground(DarkColors.background)
            app.onNodeWithTag("appearance-theme").performClick()
            app.onNodeWithTag("appearance-option-theme-深色").assertIsSelected()
            app.onNodeWithContentDescription("关闭主题").performClick()
            assertEquals(ThemeMode.DARK, store.read().themeMode)
            prefs.edit().putString("themeMode", "unknown-future-value").commit()
            assertEquals(ThemeMode.DARK, store.read().themeMode)
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun textStatusRegionAndCalendarColorsKeepReadableContrastInBothThemes() {
        fun contrast(a: Color, b: Color): Float {
            val first = a.compositeOver(b).luminance(); val second = b.luminance()
            return (maxOf(first, second) + .05f) / (minOf(first, second) + .05f)
        }
        listOf(DarkColors, LightColors).forEach { palette ->
            listOf(palette.background, palette.panel, palette.panelRaised).forEach { surface ->
                listOf(palette.ink, palette.quiet, palette.accent, palette.red, palette.green,
                    palette.europe, palette.asia, palette.americas).forEach { foreground ->
                    assertTrue("${palette.dark}: $foreground on $surface", contrast(foreground, surface) >= 4.5f)
                }
            }
            assertTrue(contrast(palette.accent, palette.accentSurface) >= 4.5f)
            assertTrue(contrast(palette.red, palette.liveSurface) >= 4.5f)
            palette.events.forEach { color ->
                assertTrue("Timeline labels must remain readable", contrast(palette.background, color) >= 4.5f)
                assertTrue("Calendar card dates must remain readable", contrast(color, color.copy(alpha = .07f).compositeOver(palette.background)) >= 4.5f)
            }
        }
    }

    @Test fun changingThemeKeepsMapSideSelectionAndLongNamesFitTheLightTable() {
        val names = listOf("SenzuTheMongolZ", "electroNic", "Techno4K", "m0NESY", "kyousuke")
        val rows = names.mapIndexed { index, name -> PlayerStat(name, "1", "100-99", "101.0", "100.0%", "1.14", "3.0", PlayerRef("$index", name), "+10.64%") }
        val groups = listOf("all", "anubis").flatMap { map -> MatchSide.entries.map { side ->
            MatchStatGroup(map, "Anubis".takeIf { map != "all" }, side, rows)
        } }
        val match = Match("1", Team("1", "Falcons"), Team("2", "BB"), Tournament("1", "Cup"), MatchStatus.FINISHED, statGroups = groups)
        var dark by mutableStateOf(true)
        var selected: StatisticsSelection? = null
        app.setContent { MatchScopeTheme(dark) {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1.4f)) {
                Column(Modifier.width(320.dp).background(Background).padding(12.dp)) {
                    val selection = rememberStatisticsSelection(match)
                    SideEffect { selected = selection }
                    StatisticsControls(selection)
                    StatisticsTeamHeader(match, selection.selected!!, "1", rows, false)
                    StatisticsTeamRows(rows, false, {})
                }
            }
        } }
        app.onNodeWithText("Anubis").performClick()
        app.onNodeWithText("CT", substring = false).performClick()
        app.runOnIdle { dark = false }
        app.onNodeWithText("CT", substring = false).assertIsSelected()
        app.runOnIdle { assertEquals("anubis", selected?.mapKey); assertEquals(MatchSide.CT, selected?.side) }
        names.forEach { name ->
            val layouts = mutableListOf<TextLayoutResult>()
            app.onNodeWithText(name, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(name, layouts.single().lineCount == 1 && !layouts.single().hasVisualOverflow)
        }
        app.saveEvidence("v0105-light-large-font-statistics")
    }

    @Test fun nestedThemesDoNotLeakAndMaterialControlsUseTheSamePalette() {
        var outer: Color? = null
        var inner: Color? = null
        var after: Color? = null
        app.setContent { MatchScopeTheme(false) {
            val first = Ink
            assertEquals(LightColors.panel, MaterialTheme.colorScheme.surface)
            SideEffect { outer = first }
            MatchScopeTheme(true) {
                val second = Ink
                assertEquals(DarkColors.background, MaterialTheme.colorScheme.background)
                SideEffect { inner = second }
            }
            val third = Ink
            SideEffect { after = third }
        } }
        app.runOnIdle {
            assertEquals(LightColors.ink, outer); assertEquals(DarkColors.ink, inner); assertEquals(LightColors.ink, after)
        }
    }
}
