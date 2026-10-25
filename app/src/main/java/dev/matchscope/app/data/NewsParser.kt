package dev.matchscope.app.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.Month
import java.util.Locale
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class NewsLink(val text: String, val url: String)
data class NewsMatchSummary(val url: String, val event: NewsLink?, val format: String,
    val left: Team, val right: Team, val leftScore: Int?, val rightScore: Int?, val maps: List<MapScore>)
data class NewsBlock(val kind: String, val text: String = "", val imageUrl: String? = null,
    val links: List<NewsLink> = emptyList(), val rows: List<List<String>> = emptyList(),
    val match: NewsMatchSummary? = null, val statistics: List<PlayerStat> = emptyList(), val team: Team? = null)
data class NewsArticle(val title: String, val url: String, val author: String?, val publishedAt: String,
    val blocks: List<NewsBlock>, val related: List<NewsLink> = emptyList(), val coverUrl: String? = null)

object NewsParser {
    const val HOME = "https://www.hltv.org/"
    const val RSS = "https://www.hltv.org/rss/news"
    fun archiveUrl(month: YearMonth) = "https://www.hltv.org/news/archive/${month.year}/${month.month.name.lowercase(Locale.ROOT)}"

    fun archiveMonth(url: String): YearMonth? = runCatching {
        if (!HltvAccess.allowed(url)) return null
        val path = Regex("^/news/archive/(\\d{4})/([a-z]+)$").matchEntire(URI(url).path) ?: return null
        YearMonth.of(path.groupValues[1].toInt(), Month.valueOf(path.groupValues[2].uppercase(Locale.ROOT)))
    }.getOrNull()

    fun allowed(url: String): Boolean = HltvAccess.allowed(url) && runCatching {
        Regex("^/news/[1-9][0-9]*/[^/]+/?$").matches(URI(url).path)
    }.getOrDefault(false)

    fun instant(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()

    fun rss(xml: String): List<NewsItem> {
        val doc = Jsoup.parse(xml, "https://www.hltv.org", Parser.xmlParser())
        if (doc.select("rss channel").isEmpty()) throw SourceUnavailable("资讯源未返回 RSS 内容")
        return doc.select("item").mapNotNull { item ->
            val url = item.selectFirst("link")?.text().orEmpty()
            val title = item.selectFirst("title")?.text()?.trim().orEmpty()
            if (!allowed(url) || title.isBlank()) null else {
                val published = item.selectFirst("pubDate")?.text().orEmpty()
                val image = item.children().firstOrNull { it.tagName() in setOf("enclosure", "media:content", "media:thumbnail") }?.attr("url")
                    ?: Jsoup.parse(item.selectFirst("description")?.text().orEmpty()).selectFirst("img")?.attr("src")
                NewsItem(title, url, instant(published)?.toString() ?: published, MediaUrls.validated(image))
            }
        }.distinctBy { URI(it.url).path.split('/').getOrNull(2) }.also {
            if (it.isEmpty()) throw SourceUnavailable("暂无可读取的新闻")
        }
    }

    /** Only the site's actual news rows are accepted; sidebars, ads and relative dates are excluded. */
    fun index(html: String, url: String = HOME): List<NewsItem> {
        val month = archiveMonth(url)
        require(url == HOME || month != null)
        val doc = Jsoup.parse(html, url)
        val canonical = doc.selectFirst("link[rel=canonical]")?.absUrl("href")
        if (canonical != null && !HltvAccess.sameResource(url, canonical)) throw SourceUnavailable("新闻列表地址不匹配")
        val index = doc.selectFirst(".index") ?: throw SourceUnavailable("新闻列表尚未载入")
        if (index.select(".newsheader").isEmpty()) throw SourceUnavailable("新闻列表尚未载入")
        return index.select("a.newsline.article[href]").mapNotNull { row ->
            val link = row.absUrl("href").substringBefore('#')
            val title = row.selectFirst(".newstext, .featured-newstext")?.text()?.trim().orEmpty()
            if (!allowed(link) || title.isBlank()) return@mapNotNull null
            val date = row.selectFirst(".newsrecent")?.text()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (month != null && (date == null || YearMonth.from(date) != month)) return@mapNotNull null
            val image = row.selectFirst("img.featured-newsimage")?.let { MediaUrls.validated(it.absUrl("src")) ?: MediaUrls.validated(it.absUrl("data-src")) }
            NewsItem(title, link, date?.toString().orEmpty(), image)
        }.distinctBy { URI(it.url).path.split('/').getOrNull(2) }.also {
            if (it.isEmpty()) throw SourceUnavailable("暂无可读取的新闻")
        }
    }

    fun article(html: String, url: String): NewsArticle {
        require(allowed(url))
        val doc = Jsoup.parse(html, url)
        val canonical = doc.selectFirst("link[rel=canonical]")?.absUrl("href")
        if (canonical != null && !HltvAccess.sameResource(url, canonical)) throw SourceUnavailable("新闻地址不匹配")
        val article = doc.selectFirst("article.newsitem, article[itemtype*=NewsArticle]") ?: throw SourceUnavailable("新闻尚未载入，请打开网页重试")
        val title = article.selectFirst("h1.headline, h1")?.text()?.trim()?.takeIf(String::isNotEmpty) ?: throw SourceUnavailable("新闻标题缺失")
        val author = article.selectFirst(".authorName, .author")?.text()?.trim()
        val published = article.selectFirst(".article-info .date[data-unix], time[data-unix]")?.attr("data-unix")?.toLongOrNull()?.let { Instant.ofEpochMilli(it).toString() }
            ?: doc.selectFirst("meta[property=article:published_time]")?.attr("content").orEmpty()
        val body = article.selectFirst(".newstext-con, .news-body, [itemprop=articleBody]") ?: throw SourceUnavailable("新闻正文缺失")
        val blocks = mutableListOf<NewsBlock>()
        fun links(node: Element) = node.select("a[href]").mapNotNull { a ->
            a.absUrl("href").takeIf { HltvAccess.allowed(it) && a.text().isNotBlank() }?.let { NewsLink(a.text(), it) }
        }.distinctBy { it.url }
        fun team(node: Element): Team? {
            val link = node.selectFirst("a[href^=/team/]") ?: return null
            val teamUrl = link.absUrl("href")
            val id = Regex("^/team/(\\d+)/").find(URI(teamUrl).path)?.groupValues?.get(1) ?: return null
            return Team(id, link.text(), url = teamUrl, logoUrl = node.selectFirst("img[src]")?.let { MediaUrls.validated(it.absUrl("src")) })
        }
        fun match(node: Element): NewsMatchSummary? {
            val matchUrl = node.selectFirst("a[href^=/matches/]")?.absUrl("href") ?: return null
            val teams = node.select(".newsitem-match-result-team").mapNotNull(::team)
            if (teams.size != 2 || !HltvAccess.allowed(matchUrl)) return null
            val scores = node.select(".newsitem-match-result-score").mapNotNull { it.text().toIntOrNull() }
            val event = node.selectFirst(".newsitem-match-result-top a[href^=/events/]")?.let { NewsLink(it.text(), it.absUrl("href")) }
            val maps = node.select(".newsitem-match-result-map").mapNotNull { row ->
                val name = row.selectFirst(".newsitem-match-result-map-name")?.text() ?: return@mapNotNull null
                val values = row.children().filter { it.tagName() == "div" }.map { it.text().toIntOrNull() }
                MapScore(name, values.getOrNull(0), values.getOrNull(1))
            }
            val format = node.selectFirst(".newsitem-match-type")?.text().orEmpty().replace(Regex("Best of (\\d+)", RegexOption.IGNORE_CASE), "BO$1")
            return NewsMatchSummary(matchUrl, event, format, teams[0], teams[1], scores.getOrNull(0), scores.getOrNull(1), maps)
        }
        fun statistics(node: Element): NewsBlock? {
            val team = node.selectFirst("thead")?.let(::team) ?: return null
            val version = node.selectFirst(".ratingDesc")?.text()
            val stats = node.select("tbody tr.newsitem-match-stats-row").mapNotNull { row ->
                val link = row.selectFirst("a.smartphone-only[href^=/player/]") ?: row.selectFirst("a[href^=/player/]") ?: return@mapNotNull null
                val playerUrl = link.absUrl("href")
                val id = Regex("^/player/(\\d+)/").find(URI(playerUrl).path)?.groupValues?.get(1) ?: return@mapNotNull null
                val name = link.selectFirst(".bold")?.text()?.trim(' ', '\'') ?: link.text()
                fun value(suffix: String) = row.selectFirst(".newsitem-match-stats-$suffix")?.text()?.takeIf(String::isNotBlank)
                PlayerStat(name, team.id, value("kd")?.replace(Regex("\\s*-\\s*"), "-"), value("adr"), value("kast"), value("rating"), version,
                    PlayerRef(id, name, playerUrl, row.selectFirst("img[src*=playerbodyshot], img[src*=playerimages]")?.let { MediaUrls.validated(it.absUrl("src")) }), swing = value("roundSwing"))
            }
            return stats.takeIf { it.isNotEmpty() }?.let { NewsBlock("statistics", statistics = it, team = team) }
        }
        fun visit(node: Element) {
            when {
                node.tagName() in setOf("script", "style", "form", "input", "textarea", "noscript", "iframe") -> Unit
                node.hasClass("image-con") || node.tagName() == "figure" -> {
                    val image = node.selectFirst("img")
                    val src = image?.let { MediaUrls.validated(it.absUrl("src")) ?: MediaUrls.validated(it.absUrl("data-src")) }
                    if (src != null) blocks.add(NewsBlock("image", node.selectFirst(".image-caption, .caption, figcaption")?.text().orEmpty(), src))
                }
                node.tagName() == "table" -> blocks.add((if (node.hasClass("newsitem-match-stats-table")) statistics(node) else null)
                    ?: NewsBlock("table", links = links(node), rows = node.select("tr").map { row -> row.select("th,td").map { it.text() } }.filter { it.isNotEmpty() }))
                node.hasClass("twocol-col") -> {
                    // HLTV also publishes lists as plain text separated by <br> inside columns.
                    // Keep their reading order without forcing a wide two-column layout on phones.
                    val columnLinks = links(node)
                    node.wholeText().lineSequence().map { it.trim() }.filter(String::isNotEmpty).forEach { line ->
                        blocks.add(NewsBlock("bullet", line, links = columnLinks.filter { it.text in line }))
                    }
                }
                node.hasClass("newsitem-match-result") -> blocks.add(NewsBlock("match", node.text(), links = links(node), match = match(node)))
                node.tagName() in setOf("p", "li", "blockquote", "h2", "h3", "h4") -> {
                    if (node.text().isNotBlank()) blocks.add(NewsBlock(when {
                        node.tagName().startsWith("h") || node.hasClass("headertext") -> "heading"
                        node.tagName() == "blockquote" -> "quote"
                        node.tagName() == "li" -> "bullet"
                        else -> "paragraph"
                    }, node.text(), links = links(node)))
                }
                node.tagName() == "img" -> MediaUrls.validated(node.absUrl("src"))?.let { blocks.add(NewsBlock("image", node.attr("alt"), it)) }
                else -> node.children().forEach(::visit)
            }
        }
        body.children().forEach(::visit)
        if (blocks.none { it.kind in setOf("paragraph", "heading", "bullet") && it.text.isNotBlank() }) throw SourceUnavailable("新闻正文尚未载入")
        val related = article.select(".related-news a[href], .related-article a[href]").mapNotNull { a ->
            a.absUrl("href").takeIf(::allowed)?.let { NewsLink(a.text(), it) }
        }.distinctBy { it.url }
        val cover = MediaUrls.validated(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: blocks.firstNotNullOfOrNull { it.imageUrl }
        return NewsArticle(title, url, author, published, blocks, related, cover)
    }
}
