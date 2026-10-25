package dev.matchscope.app

import dev.matchscope.app.data.BrowserSession
import dev.matchscope.app.data.SourceUnavailable
import dev.matchscope.app.data.MobileRequest
import dev.matchscope.app.data.MobileRoute
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class BrowserSessionTest {
    @Test fun verifiedRssIsConsumedOnceWithoutRepeatingTheRefusedRequest() = runBlocking {
        withContext(Dispatchers.Main) {
            val session = BrowserSession()
            val url = "https://www.hltv.org/rss/news"
            val xml = "<rss><channel><item><title>Article</title><link>https://www.hltv.org/news/43123/title</link></item></channel></rss>"
            val manual = async(start = CoroutineStart.UNDISPATCHED) { session.browse(url) }
            session.complete(session.request.value!!.id, xml)
            assertEquals(xml, manual.await())
            assertEquals(xml, session.takeVerifiedDocument(url))
            assertNull(session.takeVerifiedDocument(url))
            val second = async(start = CoroutineStart.UNDISPATCHED) { session.browse(url) }
            session.complete(session.request.value!!.id, "<rss><channel><error>Access denied</error></channel></rss>")
            second.await()
            assertNull(session.takeVerifiedDocument(url))
        }
    }
    @Test fun verifiedDocumentIsReusedOnceForTheSameArticleWithoutAnotherNavigation() = runBlocking {
        withContext(Dispatchers.Main) {
            val session = BrowserSession()
            val url = "https://www.hltv.org/news/43123/title"
            val html = "<html><body>${"Public article text. ".repeat(40)}</body></html>"
            val manual = async(start = CoroutineStart.UNDISPATCHED) { session.browse(url) }
            session.complete(session.request.value!!.id, html)
            assertEquals(html, manual.await())
            val wrong = async(start = CoroutineStart.UNDISPATCHED) { session.load("https://www.hltv.org/news/43124/other") }
            assertNotNull(session.request.value)
            session.complete(session.request.value!!.id, "another article")
            assertEquals("another article", wrong.await())
            assertEquals(html, session.load("https://www.hltv.org/news/43123/new-title"))
            assertNull(session.request.value)
            val second = async(start = CoroutineStart.UNDISPATCHED) { session.load(url) }
            assertNotNull(session.request.value)
            second.cancelAndJoin()
        }
    }

    @Test fun verifiedDocumentExpiresAndCannotStandInForMobileJson() = runBlocking {
        withContext(Dispatchers.Main) {
            var time = 1_000L
            val session = BrowserSession(now = { time })
            val request = MobileRequest.create(MobileRoute.MATCH, "2399415")
            val html = "<html><body>${"Public page text. ".repeat(40)}</body></html>"
            val manual = async(start = CoroutineStart.UNDISPATCHED) { session.browse(request.url) }
            session.complete(session.request.value!!.id, html)
            manual.await()
            val json = async(start = CoroutineStart.UNDISPATCHED) { session.loadJson(request) }
            assertEquals(request.url, session.request.value!!.jsonUrl)
            assertFalse(json.isCompleted)
            session.complete(session.request.value!!.id, "json response")
            assertEquals("json response", json.await())
            time += 60_000
            val expired = async(start = CoroutineStart.UNDISPATCHED) { session.load(request.url) }
            assertNotNull(session.request.value)
            expired.cancelAndJoin()
        }
    }

    @Test fun workerThreadCancellationClearsRequestOnMainThread() = runBlocking {
        val session = BrowserSession()
        val pending = async(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) { session.load("https://www.hltv.org/matches") }
        withTimeout(3_000) { session.request.first { it != null } }
        withContext(Dispatchers.Main) { assertNotNull(session.request.value) }
        withContext(Dispatchers.Default) { pending.cancelAndJoin() }
        withTimeout(3_000) { session.request.first { it == null } }
        withContext(Dispatchers.Main) { assertNull(session.request.value) }
    }

    @Test fun cancelledPageCannotCompleteNextRequest() = runBlocking {
        withContext(Dispatchers.Main) {
            val session = BrowserSession()
            val first = async(start = CoroutineStart.UNDISPATCHED) { session.load("https://www.hltv.org/matches") }
            val oldId = session.request.value!!.id
            first.cancelAndJoin()
            assertNull(session.request.value)
            val second = async(start = CoroutineStart.UNDISPATCHED) { session.load("https://www.hltv.org/results") }
            session.complete(oldId, "old document")
            assertFalse(second.isCompleted)
            session.complete(session.request.value!!.id, "current document")
            assertEquals("current document", second.await())
            assertNull(session.request.value)
        }
    }

    @Test fun verificationFailurePropagatesCooldownAndReleasesSession() = runBlocking {
        withContext(Dispatchers.Main) {
            supervisorScope {
                val session = BrowserSession()
                val request = async(start = CoroutineStart.UNDISPATCHED) { session.load("https://www.hltv.org/matches") }
                session.fail(session.request.value!!.id, "verification", 300_000)
                val error = runCatching { request.await() }.exceptionOrNull()
                assertTrue(error is SourceUnavailable)
                assertEquals(300_000L, (error as SourceUnavailable).cooldownMs)
                assertNull(session.request.value)
            }
        }
    }
    @Test fun manualLoginSessionStaysOpenUntilUserFinishesAndCanBeCancelled() = runBlocking {
        withContext(Dispatchers.Main) {
            val session = BrowserSession()
            val task = async(start = CoroutineStart.UNDISPATCHED) { session.browse("https://www.hltv.org/") }
            assertTrue(session.request.value!!.interactive)
            assertFalse(task.isCompleted)
            session.complete(session.request.value!!.id, "done")
            assertEquals("done", task.await())
            val second = async(start = CoroutineStart.UNDISPATCHED) { session.browse("https://www.hltv.org/") }
            session.cancel(session.request.value!!.id)
            second.join()
            assertTrue(second.isCancelled)
            assertNull(session.request.value)
        }
    }

    @Test fun stalledReadTimesOutButManualLoginRemainsAvailable() = runBlocking {
        withContext(Dispatchers.Main) {
            supervisorScope {
                val session = BrowserSession(readTimeoutMs = 40)
                val pending = async(start = CoroutineStart.UNDISPATCHED) { session.load("https://www.hltv.org/rss/news") }
                val expiredId = session.request.value!!.id
                val error = runCatching { pending.await() }.exceptionOrNull()
                assertTrue(error is SourceUnavailable)
                assertEquals(30_000L, (error as SourceUnavailable).cooldownMs)
                assertNull(session.request.value)
                val manual = async(start = CoroutineStart.UNDISPATCHED) { session.browse("https://www.hltv.org/") }
                delay(80)
                session.complete(expiredId, "late document")
                assertFalse(manual.isCompleted)
                assertTrue(session.request.value!!.interactive)
                session.complete(session.request.value!!.id, "done")
                assertEquals("done", manual.await())
            }
        }
    }

}
