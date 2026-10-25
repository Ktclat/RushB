package dev.matchscope.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.matchscope.app.data.HltvAccess
import dev.matchscope.app.ui.ScopeViewModel
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Opt-in evidence capture through the same visible, on-device reader as the app. */
class PhoneSourceCaptureTest {
    @get:Rule val app = createAndroidComposeRule<MainActivity>()

    @Test fun capturePublicSource() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("captureUrl")
        assumeTrue(url != null)
        require(HltvAccess.allowed(url!!))
        lateinit var vm: ScopeViewModel
        app.runOnUiThread {
            vm = ViewModelProvider(app.activity)[ScopeViewModel::class.java]
            vm.configureRelay(false, "")
        }
        val work = async {
            withTimeout(100_000) {
                val html = vm.browser.load(url)
                File(app.activity.filesDir, "captured-source.html").writeText(html)
                File(app.activity.filesDir, "captured-source.json").writeText(JSONObject()
                    .put("url", url).put("capturedAt", Instant.now().toString()).put("relayEnabled", false)
                    .put("length", html.length).toString(2))
            }
        }
        while (!work.isCompleted) { app.mainClock.advanceTimeBy(50); delay(50) }
        work.await()
    }
}
