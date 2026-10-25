package dev.matchscope.app.data

import org.junit.Assert.*
import org.junit.Test

class NewsParserTest {
    private val url = "https://www.hltv.org/news/45746/example"
    private val image = "https://img-cdn.hltv.org/gallerypicture/example.jpg?s=signed"

    @Test fun `real embedded match keeps teams maps and all ten statistics rows in their correct columns`() {
        val html = javaClass.getResource("/hltv/news-match-2026-10-24.html")!!.readText()
        val article = NewsParser.article(html, "https://www.hltv.org/news/45785/eternal-fire-keep-singapore-major-dream-alive-with-draculan-s7-victory")
        val match = article.blocks.single { it.kind == "match" }.match!!
        assertEquals("10577", match.left.id); assertEquals("11251", match.right.id)
        assertEquals("BO3", match.format); assertEquals(1, match.leftScore); assertEquals(2, match.rightScore)
        assertEquals(listOf(MapScore("Mirage", 13, 6), MapScore("Cache", 12, 16), MapScore("Ancient", 14, 16)), match.maps)
        assertTrue(match.url.contains("/matches/2399639/")); assertTrue(match.event!!.url.contains("/events/9312/"))
        val tables = article.blocks.filter { it.kind == "statistics" }
        assertEquals(2, tables.size); assertEquals(10, tables.sumOf { it.statistics.size })
        val player = tables.first().statistics.first()
        assertEquals("kisserek", player.name); assertEquals("23295", player.player!!.id)
        assertEquals("61-51", player.kd); assertEquals("85.8", player.adr); assertEquals("72.7%", player.kast)
        assertEquals("+0.67%", player.swing); assertEquals("1.21", player.rating); assertEquals("3.0", player.ratingVersion)
        assertEquals("10577", tables.first().team!!.id)
    }

    @Test fun `real homepage has distinct headlines without comment counts flags or guessed relative dates`() {
        val html = javaClass.getResource("/hltv/news-home-2026-10-24.html")!!.readText()
        val items = NewsParser.index(html)
        assertTrue(items.size >= 25)
        assertEquals("BIG sign up for EPIC49 as Major race enters decisive phase", items.first().title)
        assertTrue(items.all { it.publishedAt.isEmpty() && !it.url.contains('#') && !it.title.endsWith("comments") })
        assertEquals(items.size, items.map { it.url }.distinct().size)
        assertTrue(items.any { it.imageUrl?.contains("gallerypicture/") == true })
        assertFalse(items.any { it.imageUrl?.contains("flags/") == true })
    }

    @Test fun `real monthly archive preserves date precision source order and complete row count`() {
        val month = java.time.YearMonth.of(2026, 10)
        val url = NewsParser.archiveUrl(month)
        assertEquals(month, NewsParser.archiveMonth(url))
        val html = javaClass.getResource("/hltv/news-archive-2026-10-24.html")!!.readText()
        val items = NewsParser.index(html, url)
        assertEquals("Eternal Fire keep Singapore Major dream alive with DraculaN S7 victory", items.first().title)
        assertEquals("2026-10-24", items.first().publishedAt)
        assertTrue(items.size > 100)
        assertTrue(items.all { it.publishedAt.matches(Regex("2026-10-\\d{2}")) })
        assertThrows(SourceUnavailable::class.java) { NewsParser.index(html, NewsParser.archiveUrl(month.minusMonths(1))) }
    }

    @Test fun `non news documents cannot replace a verified index`() {
        for (html in listOf("<html>Just a moment</html>", "<div class='index'><h2 class='newsheader'>News</h2><a href='$url'>Sidebar</a></div>"))
            assertThrows(SourceUnavailable::class.java) { NewsParser.index(html) }
        assertNull(NewsParser.archiveMonth("https://example.com/news/archive/2026/october"))
        assertNull(NewsParser.archiveMonth("https://www.hltv.org/news/archive/2026/no-month"))
    }

    @Test fun `RSS parses media namespace time and title without double escaping`() {
        val rss = """<rss xmlns:media="http://search.yahoo.com/mrss/"><channel><item><title>A &amp; B</title><link>$url</link><pubDate>Sun, 18 Oct 2026 15:27:00 GMT</pubDate><media:content url="$image"/></item><item><title>wrong</title><link>https://example.com/news/1/x</link></item></channel></rss>"""
        val item = NewsParser.rss(rss).single()
        assertEquals("A & B", item.title); assertEquals(image, item.imageUrl)
        assertEquals("2026-10-18T15:27:00Z", item.publishedAt)
    }

    @Test fun `native article keeps ordered text images tables and entity links but excludes forms`() {
        val html = """<link rel="canonical" href="$url"><article class="newsitem"><h1 class="headline">Test headline</h1><div class="article-info"><a class="authorName">Writer</a><div class="date" data-unix="1792268760000"></div></div><div class="newstext-con"><p class="headertext">Lead.</p><div class="image-con"><picture><img src="$image"></picture><div class="caption">Caption</div></div><p class="news-block"><a href="/team/11283/falcons">Falcons</a> against <a href="/player/19230/m0nesy">m0NESY</a>.</p><table><tr><th>Team</th><th>Score</th></tr><tr><td>A</td><td>2</td></tr></table><form>private</form><script>secret()</script></div></article>"""
        val article = NewsParser.article(html, url)
        assertEquals("Writer", article.author)
        assertEquals(listOf("heading", "image", "paragraph", "table"), article.blocks.map { it.kind })
        assertEquals("Caption", article.blocks[1].text)
        assertEquals("https://www.hltv.org/team/11283/falcons", article.blocks[2].links.first().url)
        assertEquals(listOf("A", "2"), article.blocks.last().rows.last())
        assertFalse(article.blocks.any { "private" in it.text || "secret" in it.text })
    }

    @Test fun `wrong article challenge and unsafe links cannot become successful articles`() {
        assertFalse(NewsParser.allowed("https://www.hltv.org.evil.test/news/1/x"))
        assertFalse(NewsParser.allowed("https://www.hltv.org/news/archive"))
        assertThrows(SourceUnavailable::class.java) { NewsParser.article("<h1>Just a moment</h1>", url) }
        assertThrows(SourceUnavailable::class.java) { NewsParser.article("<link rel='canonical' href='https://www.hltv.org/news/999/other'>", url) }
    }

    @Test fun `line separated columns keep every metric in reading order without dropping inline links`() {
        val metrics = listOf("Round Win Shares", "Perfect KPR", "Trade Denials", "Net Kills", "Net Damage", "Tradeable Deaths", "Trade Opportunities", "Bait Kills %", "First Duel Rank", "First Duel Time", "Weapon Attacks", "Alive for Post Plant", "Last Alive in Team", "Rounds with a Sniper", "Rounds with an AWP", "AWP Kills %", "Smokes thrown", "Molotovs Thrown", "HE Grenades Thrown", "Traded Death Success%", "Trade Kill Success%", "Opening Swing", "Clutch Swing", "AWP Duel Win %", "AWP Swing", "Flash Swing", "Assists per Flash", "Grenade Damage Received", "Damage per HE thrown")
        val columns = metrics.chunked(15).joinToString("") { "<div class='twocol-col'>${it.joinToString("<br>")}</div>" }
        val html = """<article class="newsitem"><h1>Attributes</h1><div class="newstext-con"><h2>The 29 new metrics</h2><div class="twocol"><div class="twocol-grid">$columns</div></div><p>After the list.</p><div class="twocol-col"><a href="/team/11283/falcons">Falcons</a><br>Another line</div></div><div class="tooltip-con">Not article content</div></article>"""
        val blocks = NewsParser.article(html, url).blocks
        assertEquals("heading", blocks.first().kind)
        assertEquals(metrics, blocks.drop(1).take(29).map { it.text })
        assertTrue(blocks.drop(1).take(29).all { it.kind == "bullet" })
        assertEquals("After the list.", blocks[30].text)
        assertEquals("https://www.hltv.org/team/11283/falcons", blocks[31].links.single().url)
        assertTrue(blocks.last().links.isEmpty())
        assertFalse(blocks.any { "Not article content" in it.text })
    }
}
