package dev.matchscope.app.data

import org.junit.Assert.*
import org.junit.Test

class HltvAccessTest {
    @Test fun `only HTTPS HLTV origin is accepted`() {
        assertTrue(HltvAccess.allowed("https://www.hltv.org/player/3741/niko"))
        listOf("http://www.hltv.org/matches", "https://www.hltv.org.evil.test/matches", "https://user@www.hltv.org/matches", "https://www.hltv.org:444/matches", "file:///etc/passwd").forEach {
            assertFalse(it, HltvAccess.allowed(it))
        }
    }
    @Test fun `canonical slug changes preserve ID but foreign page cannot satisfy request`() {
        assertTrue(HltvAccess.sameResource("https://www.hltv.org/player/3741/-", "https://www.hltv.org/player/3741/niko"))
        assertFalse(HltvAccess.sameResource("https://www.hltv.org/player/3741/-", "https://www.hltv.org/player/7998/s1mple"))
        assertFalse(HltvAccess.sameResource("https://www.hltv.org/player/3741/-", "https://www.hltv.org/team/3741/niko"))
        assertFalse(HltvAccess.sameResource("https://www.hltv.org/player/3741/-", "https://example.org/player/3741/niko"))
    }
    @Test fun `list redirects retain requested date filter and ranking path boundary`() {
        assertFalse(HltvAccess.sameResource("https://www.hltv.org/results?startDate=2026-10-17", "https://www.hltv.org/results"))
        assertTrue(HltvAccess.sameResource("https://www.hltv.org/ranking/teams", "https://www.hltv.org/ranking/teams/2026/october/12"))
        assertFalse(HltvAccess.sameResource("https://www.hltv.org/ranking/teams", "https://www.hltv.org/ranking/teams-other"))
    }
}
