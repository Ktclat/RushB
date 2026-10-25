package dev.matchscope.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

class PhoneSideStatisticsTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()
    @Test fun inspectPublicMatchResponse() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inspectSides") == "true")
        val result = PhoneDocumentReader(app.activity).response("https://www.hltv.org/matches/2399415/-", "text/html")
        File(app.activity.filesDir, "side-response-v0104.json").writeText(JSONObject().put("status", result.status)
            .put("url", result.url).put("contentType", result.headers["content-type"]).put("location", result.headers["location"])
            .put("length", result.body.length).put("title", org.jsoup.Jsoup.parse(result.body).title()).toString(2))
    }
    @Test fun phoneReadsAllMapSidesAndRestoresThemWithTheirOriginalTimestamp() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phoneSides") == "true")
        lateinit var vm: ScopeViewModel
        app.runOnUiThread { vm = ViewModelProvider(app.activity)[ScopeViewModel::class.java] }
        assertFalse(vm.state.value.relayEnabled)
        val proof = JSONObject().put("version", BuildConfig.VERSION_NAME).put("startedAt", Instant.now().toString()).put("relay", false)
        try {
            val repository = MatchRepository(app.activity, vm.browser) { RelayConfig(false, "") }
            val mobile = repository.match("2399415", "https://www.hltv.org/matches/2399415/-", true, background = true)
            val website = repository.matchStatistics(mobile, true)
            val combined = mobile.withSideStatistics(website)
            assertEquals(DataOrigin.NETWORK, website.stamp!!.origin)
            assertEquals(12, combined.statGroups.size)
            assertTrue(combined.hasCompleteSideStatistics())
            val restored = MatchRepository(app.activity, vm.browser) { RelayConfig(false, "") }.restoredMatchStatistics(mobile.id)!!
            assertEquals(DataOrigin.CACHE, restored.stamp!!.origin)
            assertEquals(website.stamp!!.capturedAt, restored.stamp!!.capturedAt)
            assertEquals(combined.statGroups, mobile.withSideStatistics(restored).statGroups)
            assertEquals("34-20", combined.statGroups.single { it.mapKey == "all" && it.side == MatchSide.CT }.rows.single { it.name == "m0NESY" }.kd)
            assertNull(vm.browser.request.value)
            proof.put("mobileStamp", Records.stamp(mobile.stamp!!)).put("websiteStamp", Records.stamp(website.stamp!!))
                .put("restoredStamp", Records.stamp(restored.stamp!!)).put("groups", Records.match(combined).getJSONArray("statGroups")).put("outcome", "PASSED")
        } catch (e: Throwable) { proof.put("outcome", "FAILED").put("error", e.toString()); throw e }
        finally { File(app.activity.filesDir, "side-statistics-v0104-proof.json").writeText(proof.put("finishedAt", Instant.now().toString()).toString(2)) }
    }
}
