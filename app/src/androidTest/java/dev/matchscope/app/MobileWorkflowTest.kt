package dev.matchscope.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.matchscope.app.data.*
import dev.matchscope.app.ui.ScopeViewModel
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** Production ViewModel and repository, normal app-owned network stack, no injected payloads. */
class MobileWorkflowTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()
    @Test fun freshPhoneDataFlowsIntoNativeScreens() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mobileWorkflow") == "true")
        val mode = MobileTransport.valueOf(InstrumentationRegistry.getArguments().getString("mobileTransport", "HTTPS"))
        lateinit var vm: ScopeViewModel
        app.runOnUiThread { vm = ViewModelProvider(app.activity)[ScopeViewModel::class.java]; vm.configureRelay(false, ""); vm.configureMobileMode(mode) }
        val proof = JSONObject().put("startedAt", Instant.now().toString()).put("relayEnabled", false).put("version", BuildConfig.VERSION_NAME).put("transport",mode.name)
        suspend fun phase(name: String, action: () -> Unit, stamp: () -> SourceStamp) {
            val start = Instant.now()
            app.runOnUiThread(action)
            withTimeout(160_000) { while(vm.state.value.busy) { app.mainClock.advanceTimeBy(50); delay(50) } }
            val value = stamp()
            proof.put(name, JSONObject().put("url", value.url).put("at", value.capturedAt.toString()).put("origin", value.origin.name)
                .put("message", vm.state.value.message).put("accessIssue", vm.state.value.mobileIssue?.reason?.name))
            assertEquals(name, DataOrigin.NETWORK, value.origin)
            assertTrue("$name used old data: ${vm.state.value.message}", value.capturedAt >= start)
            assertNull(vm.browser.request.value)
        }
        try {
            phase("matches", { vm.refreshMatches(LocalDate.now(vm.state.value.zone)) }, { vm.state.value.matches.stamp })
            phase("events", vm::refreshEvents, { vm.state.value.eventIndex!!.stamp })
            phase("match", { vm.loadMatch("2399415", true) }, { vm.state.value.matchDetails.getValue("2399415").stamp!! })
            phase("team", { vm.loadTeam("11283", true) }, { vm.state.value.teamProfiles.getValue("11283").stamp })
            phase("player", { vm.loadPlayer("19230", true) }, { vm.state.value.players.getValue("19230").stamp })
            phase("event", { vm.loadEvent("8932", true) }, { vm.state.value.eventProfiles.getValue("8932").stamp })
            val state = vm.state.value
            assertTrue(state.matches.items.size >= 500)
            assertTrue(state.eventIndex!!.items.size >= 200)
            assertEquals(4, state.matchDetails.getValue("2399415").duels.size)
            assertTrue(state.players.getValue("19230").value.content.events.size >= 100)
            assertTrue(state.teamProfiles.getValue("11283").value.content.events.size >= 90)
            assertTrue(state.players.getValue("19230").value.content.news.isNotEmpty())
            assertTrue(state.teamProfiles.getValue("11283").value.content.news.isNotEmpty())
            assertEquals(4, state.eventProfiles.getValue("8932").value.content.brackets.size)
            assertEquals(14, state.eventProfiles.getValue("8932").value.content.matches.size)
            assertNotNull(state.eventProfiles.getValue("8932").value.bannerUrl)
            assertTrue(state.players.getValue("19230").value.content.honors.size >= 43)
            assertEquals(2,state.eventProfiles.getValue("8932").value.content.statistics.size)
            val news = listOf(state.players.getValue("19230").value.content.newsStamp, state.teamProfiles.getValue("11283").value.content.newsStamp)
            assertTrue("News was not freshly read", news.all { it?.origin == DataOrigin.NETWORK && it.capturedAt >= Instant.parse(proof.getString("startedAt")) })
            val brackets = state.eventProfiles.getValue("8932").value.content.bracketStamps
            assertEquals(4, brackets.size)
            proof.put("news",org.json.JSONArray(news.map { JSONObject().put("url",it!!.url).put("at",it.capturedAt.toString()).put("origin",it.origin.name) }))
            proof.put("bracketSources",JSONObject().apply { brackets.forEach { (id,stamp) -> put(id,JSONObject().put("url",stamp.url).put("at",stamp.capturedAt.toString()).put("origin",stamp.origin.name)) } })
            proof.put("coreJsonPassed",true)
            assertTrue("Brackets used cached responses", brackets.values.all { it.origin == DataOrigin.NETWORK && it.capturedAt >= Instant.parse(proof.getString("startedAt")) })
            proof.put("playerHonors",state.players.getValue("19230").value.content.honors.size).put("eventStatisticsGroups",2)
            proof.put("matchCount", state.matches.items.size).put("eventCount", state.eventIndex!!.items.size)
                .put("playerEvents", state.players.getValue("19230").value.content.events.size)
                .put("teamEvents", state.teamProfiles.getValue("11283").value.content.events.size)
                .put("eventSchedule",14).put("brackets",4).put("duelScopes",4)
            app.onNodeWithTag("nav-matches").performClick()
            app.onNodeWithText("18",substring=false).performClick()
            app.onAllNodesWithText("已结束", substring = false).onFirst().performClick()
            val targetMatch = hasText("Falcons") and hasText("BETBOOM", ignoreCase = true) and hasClickAction()
            app.onNode(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(targetMatch)
            app.onNode(targetMatch).performClick()
            app.onNodeWithText("数据", substring = false).performClick()
            app.onNodeWithText("CT", substring = false).assertDoesNotExist()
            for (attempt in 0 until 20) {
                if (app.onNodeWithContentDescription("Boombl4 对 kyousuke，7 次击杀，5 次被杀").isDisplayed()) break
                app.onNodeWithTag("match-detail-list").performTouchInput { swipeUp(startY = height * .8f, endY = height * .4f, durationMillis = 300) }
            }
            app.onNodeWithContentDescription("Boombl4 对 kyousuke，7 次击杀，5 次被杀").assertIsDisplayed()
            app.saveEvidence("mobile-real-duels")
            app.onNodeWithText("首杀", substring = false).performClick()
            app.saveEvidence("mobile-real-opening-duels")
            app.onNodeWithText("AWP 击杀", substring = false).performClick()
            app.saveEvidence("mobile-real-awp-duels")
            app.activityRule.scenario.recreate()
            app.onNodeWithText("数据", substring = false).assertExists()
            proof.put("outcome", "PASSED")
        } catch (e: Throwable) {
            proof.put("outcome", "FAILED").put("error", e.toString())
            throw e
        } finally {
            proof.put("finishedAt", Instant.now().toString())
            File(app.activity.filesDir, "mobile-workflow-${mode.name.lowercase()}-proof.json").writeText(proof.toString(2))
            app.runOnUiThread { vm.configureMobileMode(MobileTransport.HTTPS) }
        }
    }
}
