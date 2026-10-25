package dev.matchscope.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.matchscope.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.security.MessageDigest

/** Schema discovery only. The production adapters are validated in separate tests. */
class MobilePayloadCaptureTest {
    @Test fun captureReadOnlyPayloads() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mobileCapture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val proof = JSONArray()
        val source = MobileJsonSource(File(context.cacheDir, "mobile-discovery-${System.nanoTime()}"), BrowserSession(), onResponse = { _, response ->
            proof.put(JSONObject().put("url", response.url).put("status", response.status).put("headers", JSONObject(response.headers))
                .put("bytes", response.body.toByteArray().size).put("at", Instant.now().toString())
                .put("sha256", MessageDigest.getInstance("SHA-256").digest(response.body.toByteArray()).joinToString("") { "%02x".format(it) }))
        })
        try {
            for ((route, id) in listOf(MobileRoute.MATCHES to null, MobileRoute.EVENTS to null, MobileRoute.EVENT to "8932", MobileRoute.TEAM to "11283", MobileRoute.PLAYER to "19230")) {
                val result = source.read(MobileRequest.create(route, id), refresh = true) { root -> check(root.length() > 0); root }
                check(result.stamp.origin == DataOrigin.NETWORK)
                File(context.filesDir, "mobile-discovery-${route.name.lowercase()}.json").writeText(result.value.toString())
            }
        } finally { File(context.filesDir, "mobile-discovery-proof.json").writeText(proof.toString(2)) }
    }
}
