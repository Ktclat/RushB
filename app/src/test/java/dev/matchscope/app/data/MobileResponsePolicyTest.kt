package dev.matchscope.app.data

import org.junit.Assert.*
import org.junit.Test

class MobileResponsePolicyTest {
    private val request = MobileRequest.create(MobileRoute.MATCH, "2399415")
    @Test fun integrityAndLoginFailuresAreDistinct() {
        assertEquals(MobileFailure.INTEGRITY_REQUIRED, MobileResponsePolicy.failure(401, mapOf("X-App-Integrity-Error" to "required")))
        assertEquals(MobileFailure.UNAUTHORIZED, MobileResponsePolicy.failure(401, emptyMap()))
        assertEquals(MobileFailure.BLOCKED, MobileResponsePolicy.failure(403, mapOf("X-App-Integrity-Error" to "required")))
    }
    @Test fun challengeAndRateLimitDoNotBecomeData() {
        assertEquals(MobileFailure.CHALLENGE, MobileResponsePolicy.failure(403, mapOf("cf-mitigated" to "challenge")))
        assertEquals(MobileFailure.RATE_LIMITED, MobileResponsePolicy.failure(429, emptyMap()))
        assertEquals(MobileFailure.REDIRECT, MobileResponsePolicy.failure(302, mapOf("Content-Type" to "application/json")))
    }
    @Test fun successfulCloudflareResponseIsAccepted() {
        val headers = mapOf("Content-Type" to "application/json; charset=utf-8", "CF-Ray" to "example", "Server" to "cloudflare")
        assertNull(MobileResponsePolicy.failure(200, headers))
        assertEquals("{}", MobileResponsePolicy.accept(request, MobileResponse(request.url, 200, headers, "{}")))
        assertNull(MobileResponsePolicy.failure(200, mapOf("content-type" to "application/problem+json")))
    }
    @Test fun htmlCannotMasqueradeAsJson() {
        assertEquals(MobileFailure.CONTENT_TYPE, MobileResponsePolicy.failure(200, mapOf("Content-Type" to "text/html")))
        assertEquals(MobileFailure.CONTENT_TYPE, MobileResponsePolicy.failure(200, emptyMap()))
    }
    @Test fun unexpectedOriginAndRedirectsAreRejected() {
        listOf("https://hltv.org/mobile/MatchScreen?matchId=2399415", "https://www.hltv.org/mobile/MatchScreen?matchId=1").forEach { url ->
            val e = runCatching { MobileResponsePolicy.accept(request, MobileResponse(url, 200, mapOf("Content-Type" to "application/json"), "{}")) }.exceptionOrNull()
            assertEquals(MobileFailure.WRONG_RESOURCE, (e as MobileAccessException).reason)
        }
    }
    @Test fun invalidEntityIdentifiersCannotConstructRequests() {
        listOf("0", "-1", "1&teamId=2", "1/2", "", "11111111111").forEach { id ->
            assertTrue(runCatching { MobileRequest.create(MobileRoute.MATCH, id) }.isFailure)
        }
        assertTrue(runCatching { MobileRequest.create(MobileRoute.MATCH) }.isFailure)
        assertTrue(runCatching { MobileRequest.create(MobileRoute.MATCHES, "1") }.isFailure)
        assertEquals("https://www.hltv.org/mobile/MatchesV4", MobileRequest.create(MobileRoute.MATCHES).url)
    }
    @Test fun retryAfterSecondsAndDatesHaveSafeBounds() {
        assertEquals(600_000L, MobileResponsePolicy.retryDelay("600"))
        assertEquals(300_000L, MobileResponsePolicy.retryDelay("-10"))
        assertEquals(300_000L, MobileResponsePolicy.retryDelay("bad date"))
        assertEquals(86_400_000L, MobileResponsePolicy.retryDelay("9999999999"))
        assertEquals(600_000L, MobileResponsePolicy.retryDelay("Sun, 18 Oct 2026 14:10:00 GMT", java.time.Instant.parse("2026-10-18T14:00:00Z").toEpochMilli()))
    }
    @Test fun rateLimitPropagatesCooldown() {
        val e = runCatching { MobileResponsePolicy.accept(request, MobileResponse(request.url, 429, mapOf("Retry-After" to "900"), "")) }.exceptionOrNull() as MobileAccessException
        assertEquals(900_000L, e.cooldownMs)
        assertEquals(429, e.status)
    }
    @Test fun byteLimitCountsUtf8RatherThanCharacters() {
        val body = "汉".repeat(MobileResponsePolicy.MAX_BYTES / 3 + 1)
        val e = runCatching { MobileResponsePolicy.accept(request, MobileResponse(request.url, 200, mapOf("Content-Type" to "application/json"), body)) }.exceptionOrNull() as MobileAccessException
        assertEquals(MobileFailure.OVERSIZE, e.reason)
    }
}
