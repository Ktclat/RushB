package dev.matchscope.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import dev.matchscope.app.data.*
import dev.matchscope.app.ui.ScopeViewModel
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/** Opt-in actual network reads. Never inject samples into the production cache. */
class PhonePreviewProbeTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()
    private fun model(): ScopeViewModel {
        lateinit var vm: ScopeViewModel
        app.runOnUiThread { vm = ViewModelProvider(app.activity)[ScopeViewModel::class.java] }
        assertFalse(vm.state.value.relayEnabled)
        return vm
    }
    @Test fun visibleNewsGetRealCoversWithoutOpeningVerification() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phonePreview") == "true")
        val vm = model()
        val proof = JSONObject().put("startedAt", Instant.now().toString()).put("version", BuildConfig.VERSION_NAME)
        val rows = JSONArray(); proof.put("articles", rows)
        try {
            withTimeout(20_000) { while (vm.state.value.news == null) delay(100) }
            val items = vm.state.value.news!!.items.sortedBy { it.imageUrl != null }.take(3)
            assertEquals(3, items.size)
            val repository = MatchRepository(app.activity, vm.browser) { RelayConfig(false, "") }
            for (item in items) {
                val row = JSONObject().put("url", item.url).put("coverAlreadyKnown", item.imageUrl != null); rows.put(row)
                val result = repository.articlePreview(item.url)
                val cover = result.value.coverUrl
                row.put("stamp", Records.stamp(result.stamp)).put("cover", cover).put("title", result.value.title)
                assertNotNull("Article needs a source-provided cover", cover)
                val image = SingletonImageLoader.get(app.activity).execute(ImageRequest.Builder(app.activity).data(cover).size(264, 222).build())
                row.put("image", image.javaClass.simpleName).put("imageSource", (image as? SuccessResult)?.dataSource?.name)
                assertTrue("Cover failed: ${item.title}", image is SuccessResult)
            }
            vm.prepareNewsPreviews(items.map { it.url })
            assertNull(vm.browser.request.value)
            assertTrue(items.all { vm.state.value.articles[it.url]?.value?.coverUrl != null })
            proof.put("outcome", "PASSED")
        } catch (e: Throwable) { proof.put("outcome", "FAILED").put("error", e.toString()); throw e }
        finally {
            proof.put("finishedAt", Instant.now().toString())
            File(app.activity.filesDir, "news-covers-v0103-proof.json").writeText(proof.toString(2))
        }
    }
    @Test fun repeatedLiveScoresComeFromThePhoneAndPreserveSeriesOwnership() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveProbe") == "true")
        val vm = model()
        val proof = JSONObject().put("startedAt", Instant.now().toString()).put("version", BuildConfig.VERSION_NAME)
        val samples = JSONArray(); proof.put("samples", samples)
        try {
            val raw = PhoneDocumentReader(app.activity).read("https://www.hltv.org/matches")
            File(app.activity.filesDir, "live-score-source-v0103.html").writeText(raw)
            val sourceStamp = SourceStamp("https://www.hltv.org/matches", Instant.now(), DataOrigin.NETWORK)
            val match = HltvParser.matches(raw).filter { it.status == MatchStatus.LIVE }.mapNotNull { match ->
                LiveScoreParser.parse(raw, match, sourceStamp)?.takeIf { it.leftRounds != null }?.let { match to it }
            }.maxByOrNull { it.second.leftMaps + it.second.rightMaps }?.first ?: error("No active round score available")
            proof.put("matchId", match.id).put("left", match.left.name).put("right", match.right.name)
            val repository = MatchRepository(app.activity, vm.browser) { RelayConfig(false, "") }
            repeat(4) { index ->
                if (index > 0) delay(20_000)
                val started = Instant.now()
                val score = repository.liveScore(match) ?: error("Match left the live list")
                samples.put(JSONObject().put("stamp", Records.stamp(score.stamp)).put("leftMaps", score.leftMaps).put("rightMaps", score.rightMaps)
                    .put("leftRounds", score.leftRounds).put("rightRounds", score.rightRounds).put("display", match.copy(liveScore = score).displayScore(Instant.now())))
                assertEquals(DataOrigin.NETWORK, score.stamp.origin)
                assertTrue(score.stamp.capturedAt >= started)
                assertNull(vm.browser.request.value)
            }
            proof.put("outcome", "PASSED")
            proof.put("observedScoreChange", (0 until samples.length()).map { samples.getJSONObject(it).getString("display") }.distinct().size > 1)
        } catch (e: Throwable) { proof.put("outcome", "FAILED").put("error", e.toString()); throw e }
        finally {
            proof.put("finishedAt", Instant.now().toString())
            File(app.activity.filesDir, "live-score-v0103-proof.json").writeText(proof.toString(2))
        }
    }
    @Test fun backgroundMatchDetailsRefreshWithoutOpeningVerification() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveProbe") == "true")
        val vm = model()
        val proof = JSONObject().put("startedAt", Instant.now().toString()).put("version", BuildConfig.VERSION_NAME)
        try {
            val source = File(app.activity.filesDir, "live-score-source-v0103.html").readText()
            val match = HltvParser.matches(source).first { it.status == MatchStatus.LIVE }
            proof.put("matchId", match.id)
            val repository = MatchRepository(app.activity, vm.browser, mobileMode = { MobileTransport.BROWSER }) { RelayConfig(false, "") }
            val result = repository.match(match.id, match.url, true, background = true)
            proof.put("stamp", result.stamp?.let(Records::stamp)).put("status", result.status.name).put("score", result.score).put("warning", result.note)
            assertEquals(match.id, result.id)
            assertEquals(DataOrigin.NETWORK, result.stamp?.origin)
            assertNull(vm.browser.request.value)
            proof.put("outcome", "PASSED")
        } catch (e: Throwable) { proof.put("outcome", "FAILED").put("error", e.toString()); throw e }
        finally { File(app.activity.filesDir, "background-details-v0103-proof.json").writeText(proof.put("finishedAt", Instant.now().toString()).toString(2)) }
    }
}
