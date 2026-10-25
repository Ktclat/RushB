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
import java.security.MessageDigest
import java.time.Instant

/** Opt-in. A rejected request fails the test and still saves a diagnostic receipt. */
class MobileStandaloneTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()
    @Test fun readRealMobileJsonWithoutRelay() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("mobileProbe") == "true")
        val mode = MobileTransport.valueOf(args.getString("mobileTransport", "HTTPS"))
        val request = MobileRequest.create(MobileRoute.MATCH, "2399415")
        lateinit var vm: ScopeViewModel
        app.runOnUiThread { vm = ViewModelProvider(app.activity)[ScopeViewModel::class.java]; vm.configureRelay(false, "") }
        assertFalse(vm.state.value.relayEnabled)
        val started = Instant.now()
        val proof = JSONObject().put("transport", mode.name).put("url", request.url).put("startedAt", started.toString())
            .put("relayEnabled", false).put("freshDirectory", true).put("version", BuildConfig.VERSION_NAME)
        val directory = File(app.activity.cacheDir, "mobile-live-${mode.name}-${System.nanoTime()}")
        val source = MobileJsonSource(directory, vm.browser, onResponse = { _, response ->
            proof.put("httpStatus", response.status).put("headers", JSONObject(response.headers))
                .put("bodyBytes", response.body.toByteArray().size)
                .put("bodySha256", MessageDigest.getInstance("SHA-256").digest(response.body.toByteArray()).joinToString("") { "%02x".format(it) })
        })
        supervisorScope {
            val work = async {
                try {
                    val result = withTimeout(100_000) { source.read(request, mode, refresh = true) { mobileMatchEnvelope(it, request.entityId!!) } }
                    assertEquals(DataOrigin.NETWORK, result.stamp.origin)
                    assertTrue(result.stamp.capturedAt >= started)
                    File(app.activity.filesDir, "mobile-${mode.name.lowercase()}-verified.json").writeText(result.value.toString())
                    proof.put("outcome", "PASSED").put("origin", result.stamp.origin.name).put("matchId", request.entityId)
                } catch (e: Throwable) {
                    proof.put("outcome", "FAILED").put("errorType", e.javaClass.simpleName).put("error", e.message)
                    if (e is MobileAccessException) proof.put("failure", e.reason.name).put("httpStatus", e.status)
                    throw e
                } finally {
                    proof.put("finishedAt", Instant.now().toString())
                    proof.put("cacheFiles", directory.walkTopDown().count { it.isFile })
                    File(app.activity.filesDir, "mobile-${mode.name.lowercase()}-proof.json").writeText(proof.toString(2))
                }
            }
            while (!work.isCompleted) { app.mainClock.advanceTimeBy(50); delay(50) }
            work.await()
        }
    }
}
