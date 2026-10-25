package dev.matchscope.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.matchscope.app.data.*
import dev.matchscope.app.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth

class ResponsiveUiTest {
    @get:Rule val app = createComposeRule()
    @org.junit.Before fun images() { MediaLoader.install() }
    private val players = (1..5).map { PlayerRef("$it", listOf("m0NESY", "karrigan", "NiKo", "TeSeS", "kyousuke")[it - 1]) }

    @Test fun allSevenVetoMapsAndFivePlayersFit320DpWithoutHorizontalScrolling() {
        val match = Match("1", Team("1", "Falcons"), Team("2", "BB"), Tournament("1", "Cup"), MatchStatus.FINISHED,
            veto = listOf("BB removed Inferno", "Falcons removed Cache", "BB picked Anubis", "Falcons picked Dust2", "BB removed Ancient", "Falcons removed Nuke", "Mirage was left over"))
        app.setContent { MatchScopeTheme { Column(Modifier.width(320.dp).background(Background).padding(16.dp).testTag("narrow")) {
            PageTabs(listOf("概括", "比赛", "赛事", "状态", "荣誉", "新闻"), "概括") {}
            Spacer(Modifier.height(8.dp)); VetoCards(match); Spacer(Modifier.height(20.dp)); RosterStrip(players) {}
        } } }
        listOf("炼狱小镇", "死城之谜", "阿努比斯", "炙热沙城Ⅱ", "远古遗迹", "核子危机", "荒漠迷城").forEach { app.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed() }
        listOf("概括", "比赛", "赛事", "状态", "荣誉", "新闻").forEach { app.onNodeWithText(it).assertIsDisplayed() }
        val bounds = app.onNodeWithTag("narrow").fetchSemanticsNode().boundsInRoot
        players.forEach { p ->
            val node = app.onNodeWithTag("roster-${p.id}").assertIsDisplayed().fetchSemanticsNode()
            assertTrue(node.boundsInRoot.left >= bounds.left); assertTrue(node.boundsInRoot.right <= bounds.right)
        }
        app.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange)).assertCountEquals(0)
        app.saveEvidence("v070-narrow-bp-roster")
    }

    @Test fun narrowLargeFontStatisticsRetainAllMetricsAndNavigation() {
        val match = Match("1", Team("1", "Falcons"), Team("2", "BB"), Tournament("1", "Cup"), MatchStatus.FINISHED,
            stats = players.map { PlayerStat(it.name, "1", "100-99", "101.0", "100.0%", "1.14", "3.0", it, swing = "+10.64%") })
        var opened = ""
        var phoneWidth by mutableStateOf(320.dp)
        var fontScale by mutableFloatStateOf(1.4f)
        app.setContent { val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { MatchScopeTheme {
                Box(Modifier.width(phoneWidth).background(Background).padding(12.dp)) { MatchStatistics(match) { opened = it } }
            } }
        }
        fun assertTable() {
            listOf("K-D", "ADR", "KAST", "Swing", "Rating 3.0").forEach { app.onAllNodesWithText(it).assertCountEquals(1) }
            players.forEach { ref ->
                val row = app.onNodeWithTag("stats-player-${ref.id}").assertIsDisplayed()
                val bounds = row.getUnclippedBoundsInRoot()
                assertTrue("${ref.name} statistics row is ${bounds.bottom - bounds.top}; it must stay compact", bounds.bottom - bounds.top <= 64.dp)
                val values = listOf("100-99", "101.0", "100.0%", "1.14", "+10.64%").map { value ->
                    val cell = app.onNode(hasText(value) and hasAnyAncestor(hasTestTag("stats-player-${ref.id}")), useUnmergedTree = true).assertIsDisplayed()
                    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    cell.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertTrue("Full value must fit: $value", layouts.isNotEmpty() && layouts.all { !it.hasVisualOverflow && it.lineCount == 1 })
                    cell.fetchSemanticsNode().boundsInRoot
                }
                assertTrue("Numeric cells must share the same row", values.maxOf { it.center.y } - values.minOf { it.center.y } < 2f)
            }
            app.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange)).assertCountEquals(0)
        }
        assertTable()
        app.saveEvidence("v092-320dp-large-font-statistics")
        app.runOnIdle { phoneWidth = 360.dp; fontScale = 1f }
        assertTable()
        app.onNodeWithText("m0NESY").performClick()
        app.runOnIdle { assertEquals("player|1", opened) }
        app.saveEvidence("v092-360dp-statistics")
    }

    @Test fun dateSelectionAnimatesThenKeepsSevenVisibleDays() {
        val zone = ZoneId.of("Asia/Hong_Kong")
        app.setContent { MatchScopeTheme { var day by remember { mutableStateOf(LocalDate.parse("2026-10-19")) }
            Box(Modifier.width(320.dp)) { DateStrip(day, zone) { day = it } }
        } }
        app.onNodeWithContentDescription("2026-10-20").performClick()
        app.mainClock.advanceTimeBy(300)
        app.onNodeWithContentDescription("2026-10-23").assertIsDisplayed()
        app.onNodeWithContentDescription("2026-10-16").assertDoesNotExist()
    }

    @Test fun eventTeamLayoutSwitchKeepsAllTeamsAndRoutes() {
        var opened = ""
        app.setContent { MatchScopeTheme { Box(Modifier.width(320.dp)) { EventTeams((1..8).map { Team("$it", "Team $it") }) { opened = it } } } }
        app.onNodeWithText("Team 8").assertIsDisplayed()
        app.onNodeWithContentDescription("参赛队伍列表显示").performClick()
        app.onNodeWithText("Team 2").performClick()
        app.runOnIdle { assertEquals("team|2", opened) }
    }

    @Test fun calendarShowsOverlappingCrossWeekRangesAndConfirmedLogos() {
        val events = listOf(Tournament("1", "EPL", LocalDate.parse("2026-10-03"), LocalDate.parse("2026-10-11")),
            Tournament("2", "TWC", LocalDate.parse("2026-10-14"), LocalDate.parse("2026-10-18")),
            Tournament("3", "PGL", LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-31")))
        app.setContent { MatchScopeTheme { Column(Modifier.width(360.dp).background(Background).padding(12.dp)) {
            EventMonth(YearMonth.of(2026, 10), events, LocalDate.parse("2026-10-19"), {}, {})
            CalendarEventCard(events[1], listOf(Team("1", "Falcons"), Team("2", "BB"))) {}
        } } }
        app.onNodeWithText("10.14 — 10.18").assertIsDisplayed()
        app.onAllNodesWithContentDescription("EPL 2026-10-03 至 2026-10-11").assertCountEquals(2)
        app.saveEvidence("v070-calendar-layout")
    }

    @Test fun failureActionsOfferLoginAndRetryWithoutLosingThePage() {
        var login = 0; var retry = 0
        app.setContent { MatchScopeTheme { CompositionLocalProvider(LocalRecovery provides RecoveryActions(true, { retry++ }, { login++ })) { RecoveryButtons() } } }
        app.onNodeWithText("打开 HLTV 验证").performClick(); app.onNodeWithText("重试").performClick()
        app.runOnIdle { assertEquals(1, login); assertEquals(1, retry) }
    }

    @Test fun deviceTimezoneChangesReplaceOldDisplayZone() {
        val original = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"))
            val vm = ScopeViewModel(ApplicationProvider.getApplicationContext<Application>())
            assertEquals(ZoneId.of("Asia/Tokyo"), vm.state.value.zone)
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
            vm.syncDeviceTimezone()
            assertEquals(ZoneId.of("America/New_York"), vm.state.value.zone)
        } finally { java.util.TimeZone.setDefault(original) }
    }
}
