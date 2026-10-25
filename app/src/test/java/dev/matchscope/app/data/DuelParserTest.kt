package dev.matchscope.app.data

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class DuelParserTest {
    @Test fun `unverified source content is not cached as a successful empty matrix`() {
        assertThrows(SourceUnavailable::class.java) {
            DuelParser.parse(Jsoup.parse("<html><body>Unavailable</body></html>",
                "https://www.hltv.org/stats/matches/performance/132134/falcons-vs-betboom"))
        }
    }

    @Test fun `performance routes use the actual stats identity and preserve query filters`() {
        assertEquals("https://www.hltv.org/stats/matches/performance/132134/falcons-vs-betboom",
            DuelParser.performanceUrl("https://www.hltv.org/stats/matches/132134/falcons-vs-betboom"))
        assertEquals("https://www.hltv.org/stats/matches/performance/mapstatsid/242012/betboom-vs-falcons?startDate=2026-01-01&context=Map%20stats",
            DuelParser.performanceUrl("https://hltv.org/stats/matches/mapstatsid/242012/betboom-vs-falcons?startDate=2026-01-01&context=Map%20stats"))
        val performance = "https://www.hltv.org/stats/matches/performance/mapstatsid/242030/falcons-vs-betboom"
        assertEquals(performance, DuelParser.performanceUrl(performance))
    }

    @Test fun `performance routes reject unrelated identities and unsafe urls`() {
        listOf(
            "https://www.hltv.org/matches/2399415/falcons-vs-betboom",
            "https://www.hltv.org/stats/players/19230/m0nesy",
            "https://www.hltv.org/stats/matches/economy/132134/falcons-vs-betboom",
            "http://www.hltv.org/stats/matches/132134/falcons-vs-betboom",
            "https://www.hltv.org.example/stats/matches/132134/falcons-vs-betboom",
            "https://user:password@www.hltv.org/stats/matches/132134/falcons-vs-betboom",
            "https://www.hltv.org:8443/stats/matches/132134/falcons-vs-betboom",
            "https://www.hltv.org/stats/matches/132134/falcons-vs-betboom#matrix",
            "https://www.hltv.org/stats/matches/mapstatsid%2f242012/betboom-vs-falcons",
            "https://www.hltv.org/stats/matches/../132134/falcons-vs-betboom",
            "https://www.hltv.org/stats/matches/132134/falcons-vs-betboom/extra",
            "/stats/matches/132134/falcons-vs-betboom",
        ).forEach { assertNull(it, DuelParser.performanceUrl(it)) }
    }
}
