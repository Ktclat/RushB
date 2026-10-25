package dev.matchscope.app

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.matchscope.app.data.*
import dev.matchscope.app.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId

class EventDetailUiTest {
    @get:Rule val app = createComposeRule()
    private fun event() = HltvParser.event(InstrumentationRegistry.getInstrumentation().context.assets.open("hltv/event-brackets-2026-10-18.html").bufferedReader().use { it.readText() }, "8932")

    @Test fun overviewShowsAttendeesAndOpensTheActualTeam() {
        val event = event()
        val vm = ScopeViewModel(ApplicationProvider.getApplicationContext<Application>())
        var opened: String? = null
        MediaLoader.install()
        app.setContent { MatchScopeTheme { Box(Modifier.fillMaxSize().background(Background)) { EventDetailScreen(event, ScopeState(timezone = "Asia/Hong_Kong"), vm) { opened = it } } } }
        app.onNodeWithText("8 支").assertExists()
        app.onNodeWithText("FURIA").performScrollTo().assertIsDisplayed()
        app.saveEvidence("event-overview")
        app.onNodeWithText("FURIA").performClick()
        app.runOnIdle { assertEquals("team|8297", opened) }
        app.onNodeWithTag("event-detail-list").performScrollToNode(hasText("奖金分配"))
        app.saveEvidence("event-prizes")
        app.onNodeWithText("对阵").performClick()
        app.onNodeWithText("半决赛").assertIsDisplayed()
        app.saveEvidence("event-detail-bracket")
        app.onNodeWithText("赛程").performClick()
        app.onNode(hasText("10月14日 ▾")).performClick()
        app.onNodeWithText("10月18日").performClick()
        app.onNodeWithTag("match-row-2399415").assertIsDisplayed()
            .assert(hasText("Falcons") and hasText("BetBoom", ignoreCase = true) and hasText("1") and hasText("2"))
        app.saveEvidence("event-detail-schedule")
    }

    @Test fun dailyScheduleUsesLocalStartDateAndOpensTheSelectedMatch() {
        val event = event()
        var opened: String? = null
        app.setContent { MatchScopeTheme { EventSchedule(event, ScopeState(timezone = "Asia/Hong_Kong"), {}) { opened = it.id } } }
        app.onNode(hasText("10月14日 ▾")).performClick()
        app.onNodeWithText("10月18日").performClick()
        app.onNodeWithTag("match-row-2399415")
            .assert(hasText("Falcons") and hasText("BetBoom", ignoreCase = true) and hasText("1") and hasText("2"))
        app.saveEvidence("event-schedule")
        app.onNodeWithContentDescription("前一天").performClick()
        app.onNodeWithText("Falcons").assertDoesNotExist()
        app.onNodeWithText("FURIA").performClick()
        app.runOnIdle { assertEquals("2399414", opened) }
    }

    @Test fun bracketSwitchKeepsNativeMatchRoutes() {
        val event = event()
        var opened: String? = null
        app.setContent { MatchScopeTheme { EventBrackets(event.content.brackets, ZoneId.of("Asia/Hong_Kong"), emptyList(), { opened = it.matchId }, {}) } }
        app.onNodeWithText("半决赛").assertExists()
        app.saveEvidence("event-bracket")
        app.onNodeWithText("Falcons").performClick()
        app.runOnIdle { assertEquals("2399415", opened) }
        app.onNodeWithText("A 组").performClick()
        app.onNodeWithText("首轮").assertExists()
        app.saveEvidence("event-group")
        app.onNodeWithText("季军赛").performClick()
        app.onNodeWithText("Aurora").performClick()
        app.runOnIdle { assertEquals("2399416", opened) }
    }
}
