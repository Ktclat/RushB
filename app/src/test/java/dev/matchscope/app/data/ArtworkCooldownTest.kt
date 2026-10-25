package dev.matchscope.app.data

import org.junit.Assert.*
import org.junit.Test

class ArtworkCooldownTest {
    private val first = "https://img-cdn.hltv.org/teamlogo/sample.png?w=200&s=observed"
    private val variant = "https://img-cdn.hltv.org/teamlogo/sample.png?w=500&s=also-observed"
    @Test fun `refused asset stays blocked across signed variants until the cooldown expires`() {
        var time = 1_000L
        val gate = ArtworkCooldown { time }
        gate.record(first, "HTTPS", 403)
        assertTrue(gate.blocked(variant, "HTTPS"))
        assertFalse(gate.blocked(variant, "BROWSER"))
        time += 299_999
        assertTrue(gate.blocked(first, "HTTPS"))
        time++
        assertFalse(gate.blocked(first, "HTTPS"))
    }
    @Test fun `rate limiting applies across images and transports and honors retry after`() {
        var time = 1_000L
        val gate = ArtworkCooldown { time }
        gate.record(first, "HTTPS", 429, "600")
        val other = "https://img-cdn.hltv.org/gallerypicture/other.jpg"
        assertTrue(gate.blocked(other, "BROWSER"))
        time += 300_001
        assertTrue(gate.blocked(other, "BROWSER"))
        time += 300_000
        assertFalse(gate.blocked(other, "BROWSER"))
    }
}
