package dev.matchscope.app.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class MobileJsonSourceTest {
    private val request = MobileRequest.create(MobileRoute.MATCH, "2399415")
    private val json = """{"matchData":{"match":{"matchId":2399415,"score":0}}}"""
    private fun folder() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "mobile-test-${UUID.randomUUID()}")
    private fun decode(root: JSONObject): Int = mobileMatchEnvelope(root, request.entityId!!).getJSONObject("matchData").getJSONObject("match").getInt("score")

    @Test fun corruptResponseDoesNotOverwriteVerifiedCacheOrItsTime() = runBlocking {
        val directory = folder()
        var body = json
        var calls = 0
        val source = MobileJsonSource(directory, BrowserSession(), MobileFetcher { calls++; MobileResponse(request.url, 200, mapOf("Content-Type" to "application/json"), body) })
        try {
            val first = source.read(request, decode = ::decode)
            assertEquals(DataOrigin.NETWORK, first.stamp.origin)
            assertEquals(0, first.value)
            body = "{}"
            val second = source.read(request, refresh = true, decode = ::decode)
            assertEquals(DataOrigin.CACHE, second.stamp.origin)
            assertEquals(first.stamp.capturedAt, second.stamp.capturedAt)
            assertEquals(0, second.value)
            assertNotNull(second.warning)
            source.read(request, refresh = true, decode = ::decode)
            assertEquals(2, calls) // cooldown prevents a third request
            assertEquals(json, File(directory, "https").listFiles()!!.single { it.extension == "txt" }.readText())
        } finally { directory.deleteRecursively() }
    }
    @Test fun challengeAndIntegrityResponsesAreNeverCached() = runBlocking {
        for ((status, header, failure) in listOf(Triple(403, "cf-mitigated" to "challenge", MobileFailure.CHALLENGE), Triple(401, "X-App-Integrity-Error" to "required", MobileFailure.INTEGRITY_REQUIRED))) {
            val directory = folder()
            val source = MobileJsonSource(directory, BrowserSession(), MobileFetcher { MobileResponse(request.url, status, mapOf(header), json) })
            try {
                val error = runCatching { source.read(request, decode = ::decode) }.exceptionOrNull() as MobileAccessException
                assertEquals(failure, error.reason)
                assertTrue(directory.walkTopDown().none { it.isFile && it.extension == "txt" })
            } finally { directory.deleteRecursively() }
        }
    }
    @Test fun entityContractRejectsMissingAndMismatchedIds() {
        listOf("{}", """{"matchData":{"match":{"matchId":1}}}""", """{"matchData":null}""").forEach {
            assertTrue(runCatching { decode(JSONObject(it)) }.isFailure)
        }
        assertEquals(0, decode(JSONObject(json)))
    }
    @Test fun browserTransportValidatesEnvelopeAndKeepsResponseSeparate() = runBlocking {
        val directory = folder()
        val browser = BrowserSession()
        val source = MobileJsonSource(directory, browser, MobileFetcher { error("must not call HTTPS in browser mode") })
        try {
            val pending = async { source.read(request, MobileTransport.BROWSER, decode = ::decode) }
            val opened = withTimeout(5_000) { browser.request.first { it != null }!! }
            assertEquals(request.url, opened.url)
            assertEquals(request.url, opened.jsonUrl)
            withContext(Dispatchers.Main) { browser.complete(opened.id, JSONObject().put("state", "json-response").put("jsonUrl", request.url)
                .put("responseUrl", request.url).put("status", 200).put("headers", JSONObject().put("content-type", "application/json")).put("body", json).toString()) }
            assertEquals(DataOrigin.NETWORK, pending.await().stamp.origin)
            assertNull(browser.request.value)
            assertFalse(File(directory, "https").exists())
        } finally { directory.deleteRecursively() }
    }
    @Test fun persistedIntegrityStopsBothTransportsWithoutReplacingCachedData() = runBlocking {
        val directory=folder(); var calls=0
        val browser=BrowserSession()
        val fetcher=MobileFetcher { calls++; MobileResponse(request.url,401,mapOf("X-App-Integrity-Error" to "required"),"{}") }
        try {
            runCatching { MobileJsonSource(directory,browser,fetcher).read(request,decode=::decode) }
            val restarted=MobileJsonSource(directory,browser,fetcher,preferredTransport={MobileTransport.BROWSER})
            val error=runCatching { restarted.read(request,decode=::decode) }.exceptionOrNull()
            assertEquals(MobileFailure.INTEGRITY_REQUIRED,(error as MobileAccessException).reason)
            assertEquals(1,calls)
            assertNull(browser.request.value)
            assertTrue(directory.walkTopDown().none { it.extension=="txt" })
        } finally { directory.deleteRecursively() }
    }

    @Test fun backgroundBrowserReadsShareValidationAndCooldownWithoutOpeningAWindow() = runBlocking {
        val directory = folder(); val browser = BrowserSession(); var calls = 0
        var response = MobileResponse(request.url, 200, mapOf("content-type" to "application/json"), json)
        val source = MobileJsonSource(directory, browser, preferredTransport = { MobileTransport.BROWSER },
            backgroundBrowser = MobileFetcher { calls++; response })
        try {
            val first = source.read(request, background = true, decode = ::decode)
            assertEquals(DataOrigin.NETWORK, first.stamp.origin)
            response = MobileResponse(request.url, 403, mapOf("cf-mitigated" to "challenge"), "challenge")
            val failed = source.read(request, background = true, refresh = true, decode = ::decode)
            assertEquals(first.stamp.capturedAt, failed.stamp.capturedAt)
            assertEquals(DataOrigin.CACHE, failed.stamp.origin)
            assertNotNull(failed.warning)
            source.read(request, background = true, refresh = true, decode = ::decode)
            assertEquals(2, calls)
            assertNull(browser.request.value)
            assertEquals(json, File(directory, "browser").listFiles()!!.single { it.extension == "txt" }.readText())
        } finally { directory.deleteRecursively() }
    }

}
