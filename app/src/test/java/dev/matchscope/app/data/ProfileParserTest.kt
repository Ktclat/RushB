package dev.matchscope.app.data

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ProfileParserTest {
    private fun page(kind: String) = Jsoup.parse(javaClass.classLoader!!.getResourceAsStream("hltv/$kind-profile-2026-10-18.html")!!
        .bufferedReader().use { it.readText() }, "https://www.hltv.org")

    @Test fun `results pagination preserves filters and rejects inactive or unrelated links`() {
        fun parse(href: String, inactive: Boolean = false) = ProfileParser.resultsNextUrl(Jsoup.parse(
            "<a class='pagination-next ${if (inactive) "inactive" else ""}' href='$href'>Next</a>", "https://www.hltv.org/results?player=3741"))
        assertEquals("https://www.hltv.org/results?offset=100&player=3741&startDate=2025-01-01", parse("/results?offset=100&amp;player=3741&amp;startDate=2025-01-01"))
        assertNull(parse("/results?offset=100&team=11283", inactive = true))
        listOf("https://evil.example/results?offset=100", "http://www.hltv.org/results?offset=100", "https://user:pass@www.hltv.org/results", "/results-other?offset=100", "/matches/100/-", "/results#fragment").forEach { assertNull(it, parse(it)) }
        assertNull(ProfileParser.resultsNextUrl(Jsoup.parse("<a class='pagination-next inactive'>Next</a>")))
    }

    @Test fun `player tabs preserve source roles history matches and dated related news`() {
        val profile = ProfileParser.player(page("player"))
        assertTrue(profile.complete)
        assertEquals(listOf("Firepower", "Entrying", "Trading", "Opening", "Clutching", "Sniping", "Utility"), profile.roles.map { it.label })
        assertEquals(listOf(66, 39, 64, 45, 50, 0, 73), profile.roles.map { it.score })
        assertEquals(11, profile.matches.size)
        val next = profile.matches.single { it.status == MatchStatus.UPCOMING }
        assertEquals("11283", next.left.id)
        assertEquals("11861", next.right.id)
        assertEquals(Instant.ofEpochMilli(1792323000000), next.time)
        assertNull(next.leftScore)
        assertEquals("https://www.hltv.org/matches/2399416/aurora-vs-falcons-thunderpick-world-championship-2026", next.matchUrl)
        val latest = profile.matches.first { it.status == MatchStatus.FINISHED }
        assertEquals(1, latest.leftScore)
        assertEquals(2, latest.rightScore)
        assertNull(latest.matchUrl)
        assertEquals("https://www.hltv.org/stats/matches/132134/falcons-vs-betboom", latest.statisticsUrl)
        assertNull(latest.asMatch()) // A statistics ID is not a match ID.
        assertEquals(Instant.ofEpochMilli(1792267856000), latest.time)
        assertTrue(profile.matches.any { it.event.name == "ESL Pro League Season 24" })
        assertEquals("https://www.hltv.org/results?player=3741", profile.moreMatchesUrl)
        assertEquals("https://www.hltv.org/stats/players/events/3741/niko", profile.moreEventsUrl)
        assertEquals(10, profile.career.size)
        assertEquals("Falcons", profile.career.first().team.name)
        assertEquals(LocalDate.parse("2025-01-03"), profile.career.first().startDate)
        assertNull(profile.career.first().endDate)
        assertEquals(2, profile.career.first().trophies.size)
        assertEquals(setOf("参赛", "Major", "LAN", "冠军", "MVP", "EVP"), profile.events.map { it.category }.toSet())
        assertTrue(profile.events.any { it.event.id == "8301" && it.category == "Major" && it.placement == "1st" && it.team?.id == "11283" })
        assertTrue(profile.events.any { it.event.id == "8932" && it.upcoming })
        assertEquals(11, profile.news.size)
        assertEquals("BETBOOM stun Falcons to make TWC grand final", profile.news.first().title)
        assertEquals(Instant.ofEpochMilli(1792268760000).toString(), profile.news.first().publishedAt)
        assertTrue(profile.summary.contains("Born" to "16 February 1997"))
        assertTrue(profile.summary.contains("Prize money" to "$1,958,128"))
        assertTrue(profile.summary.contains("MVP medals" to "6")) // Six real rows retained in this cropped fixture.
        assertTrue(profile.summary.any { it.first == "Top 20 · 2025" && it.second.startsWith("#18") })
    }

    @Test fun `team tabs distinguish announced events from played events and preserve maps and roster`() {
        val profile = ProfileParser.team(page("team"))
        assertTrue(profile.complete)
        assertEquals(17, profile.matches.size)
        assertTrue(profile.matches.all { it.matchUrl != null })
        assertEquals(4, profile.events.count { it.upcoming })
        assertEquals(20, profile.events.count { !it.upcoming && it.category == "参赛" })
        val beijing = profile.events.single { it.event.id == "8245" }
        assertTrue(beijing.upcoming)
        assertEquals(LocalDate.parse("2026-11-02"), beijing.event.startDate)
        assertEquals(LocalDate.parse("2026-11-08"), beijing.event.endDate)
        assertTrue(beijing.event.logoUrl!!.contains("w=100"))
        val cologne = profile.events.first { it.event.id == "8301" && it.category == "参赛" }
        assertFalse(cologne.upcoming)
        assertEquals("1st", cologne.placement)
        assertEquals(5, profile.roster.size)
        val monesy = profile.roster.single { it.player.id == "19230" }
        assertEquals("m0NESY", monesy.player.name)
        assertEquals("Russia", monesy.country)
        assertEquals("STARTER", monesy.status)
        assertEquals("1 year 6 months", monesy.tenure)
        assertEquals(273, monesy.maps)
        assertEquals("1.28", monesy.rating)
        assertNotNull(monesy.player.photoUrl)
        val inferno = profile.maps.single { it.name == "Inferno" }
        assertEquals("80.0%", inferno.winRate)
        assertEquals(4, inferno.wins)
        assertEquals(0, inferno.draws)
        assertEquals(1, inferno.losses)
        assertTrue(inferno.statistics.contains("Pistolround win%" to "60.0%"))
        assertTrue(inferno.statistics.contains("Bans" to "30% of 46"))
        assertEquals(6, profile.maps.size)
        assertTrue(profile.summary.contains("Weeks in top30 for core" to "79"))
        assertTrue(profile.summary.contains("Average player age" to "26.4"))
        assertTrue(profile.summary.contains("Win rate · Last 3 months" to "61.1%"))
        assertEquals("https://www.hltv.org/results?team=11283", profile.moreMatchesUrl)
        assertEquals("https://www.hltv.org/stats/teams/events/11283/falcons", profile.moreEventsUrl)
    }

    @Test fun `missing tabs remain incomplete and missing scores do not become zero`() {
        val empty = ProfileParser.player(Jsoup.parse("<h1 class=playerNickname>Example</h1>"))
        assertFalse(empty.complete)
        assertTrue(empty.roles.isEmpty())
        assertTrue(empty.events.isEmpty())
        assertNull(empty.moreEventsUrl)
        assertTrue(ProfileParser.player(Jsoup.parse("<h1 class=playerNickname>Example</h1><div id=matchesBox></div><div id=newsBox></div>")).complete)
        assertTrue(ProfileParser.team(Jsoup.parse("<h1 class=profile-team-name>Example</h1><div id=rosterBox></div>")).complete)
        val doc = page("player")
        doc.select(".player-stat").first { it.text().contains("Firepower") }.select(".statsVal b").forEach { it.text("-") }
        doc.select(".player-stat").first { it.text().contains("Trading") }.select(".statsVal b").forEach { it.text("101") }
        val profile = ProfileParser.player(doc)
        assertEquals(5, profile.roles.size)
        assertEquals(0, profile.roles.single { it.label == "Sniping" }.score)
    }

    @Test fun `related news remains scoped dated and untruncated while rejecting a foreign base`() {
        val doc = page("player")
        val news = doc.selectFirst("#newsBox")!!
        repeat(125) { i -> news.append("<a class='subTab-newsArticle' href='/news/${50000 + i}/story'><span class='subTab-newsDate' data-unix='1792268760000'>18/10</span>Story $i</a>") }
        doc.body().append("<aside><a class='subTab-newsArticle' href='/news/999999/irrelevant'>Sidebar story</a></aside>")
        assertEquals(136, ProfileParser.player(doc).news.size)
        assertTrue(ProfileParser.player(doc).news.none { it.title == "Sidebar story" })
        val hostile = Jsoup.parse("<base href='https://evil.example/'><div id='newsBox'><a class='subTab-newsArticle' href='/news/123/x'>Wrong origin</a></div>", "https://www.hltv.org")
        assertTrue(ProfileParser.player(hostile).news.isEmpty())
    }
}
