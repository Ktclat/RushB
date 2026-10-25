package dev.matchscope.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PublicDocumentTest {
    private val url = "https://www.hltv.org/matches/2399415/-"
    @Test fun `canonical slug redirect reads the same match without changing JSON policy`() = runBlocking {
        val visited = mutableListOf<String>()
        val html = readPublicDocument(url) { request ->
            visited += request
            if (visited.size == 1) MobileResponse(request, 301, mapOf("location" to "/matches/2399415/falcons-vs-betboom"), "")
            else MobileResponse(request, 200, mapOf("content-type" to "text/html; charset=UTF-8"), "<html>match</html>")
        }
        assertEquals("<html>match</html>", html)
        assertEquals(listOf(url, "https://www.hltv.org/matches/2399415/falcons-vs-betboom"), visited)
    }
    @Test fun `cross match login external and insecure redirects are rejected before fetching`() = runBlocking {
        for (target in listOf("/matches/1/other", "/login", "https://example.com/matches/2399415/-", "http://www.hltv.org/matches/2399415/-")) {
            var calls = 0
            try {
                readPublicDocument(url) { calls++; MobileResponse(it, 302, mapOf("location" to target), "") }
                fail("Accepted $target")
            } catch (_: SourceUnavailable) { assertEquals(1, calls) }
        }
    }
    @Test fun `redirect loops are bounded and challenges are not HTML success`() = runBlocking {
        var calls = 0
        try {
            readPublicDocument(url) { calls++; MobileResponse(it, 301, mapOf("location" to url), "") }
            fail("Accepted loop")
        } catch (_: SourceUnavailable) { assertEquals(4, calls) }
        try {
            readPublicDocument(url) { MobileResponse(it, 403, mapOf("content-type" to "text/html"), "challenge") }
            fail("Accepted challenge")
        } catch (e: SourceUnavailable) { assertEquals(300_000L, e.cooldownMs) }
    }
}
