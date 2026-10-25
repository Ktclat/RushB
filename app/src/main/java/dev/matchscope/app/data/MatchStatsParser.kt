package dev.matchscope.app.data

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/** All map/side variants are already present in a completed match's DOM. */
object MatchStatsParser {
    private val entityId = Regex("/(team|player)/(\\d+)(?:/|$)")
    private val mapStatsId = Regex("^/stats/matches/mapstatsid/(\\d+)(?:/.*)?$")
    private val seriesStatsPath = Regex("^/stats/matches/\\d+(?:/.*)?$")

    /** The statistics-page ID is not the match ID; follow its explicit match-page link. */
    fun matchPageUrl(doc: Document): String? = doc.select("a[href]").asSequence()
        .filter { it.text().trim().equals("More info on match page", ignoreCase = true) }
        .map { it.absUrl("href") }
        .filter { url -> HltvAccess.allowed(url) && runCatching {
            Regex("^/matches/\\d+(?:/.*)?$").matches(URI(url).path)
        }.getOrDefault(false) }
        .distinct().toList().singleOrNull()

    fun parse(doc: Document): List<MatchStatGroup> {
        val photos = playerPhotos(doc)
        val mapNames = doc.select(".stats-menu-link .dynamic-map-name-full[id]").associate {
            it.id() to it.text().trim().takeIf(String::isNotBlank)
        }
        val mapLinks = doc.select(".mapholder").mapNotNull { holder ->
            val url = holder.selectFirst("a.results-stats[href]")?.statsUrl() ?: return@mapNotNull null
            val key = mapStatsId.matchEntire(URI(url).path)?.groupValues?.get(1) ?: return@mapNotNull null
            key to (holder.selectFirst(".mapname")?.text()?.trim()?.takeIf(String::isNotBlank) to url)
        }.toMap()
        val seriesUrl = doc.select(".stats-detailed-stats a[href]").mapNotNull { it.statsUrl() }
            .firstOrNull { seriesStatsPath.matches(URI(it).path) }

        return doc.select(".stats-content").flatMap { scope ->
            val tables = scope.select("table.totalstats, table.tstats, table.ctstats")
            if (tables.isEmpty()) return@flatMap emptyList()
            val mapKey = scope.id().takeIf { it.endsWith("-content") }?.removeSuffix("-content")
                ?.takeIf(String::isNotBlank) ?: throw SourceUnavailable("比赛地图统计标识缺失。")
            val mapName = if (mapKey == "all") null else mapNames[mapKey] ?: mapLinks[mapKey]?.first
            val statsUrl = if (mapKey == "all") seriesUrl else mapLinks[mapKey]?.second

            listOf(MatchSide.BOTH, MatchSide.T, MatchSide.CT).mapNotNull { side ->
                val tableClass = when (side) {
                    MatchSide.BOTH -> "totalstats"
                    MatchSide.T -> "tstats"
                    MatchSide.CT -> "ctstats"
                }
                val rows = tables.filter { it.hasClass(tableClass) }.flatMap { table -> parseRows(table, photos) }
                    .distinctBy { it.teamId to it.player?.id }
                if (rows.isEmpty()) null else MatchStatGroup(mapKey, mapName, side, rows, statsUrl)
            }
        }
    }

    private fun parseRows(table: Element, photos: Map<String, String>): List<PlayerStat> {
        val team = table.selectFirst(".header-row a[href]")?.idFor("team")
            ?: throw SourceUnavailable("统计表战队编号缺失。")
        val version = table.selectFirst(".header-row .ratingDesc")?.text()?.trim()?.takeIf(String::isNotBlank)
        return table.select("tr:not(.header-row)").mapNotNull { row ->
            val link = row.select("a[href]").firstOrNull { it.idFor("player") != null } ?: return@mapNotNull null
            val pid = link.idFor("player") ?: return@mapNotNull null
            val nickname = row.selectFirst(".player-nick")?.text()?.trim()?.takeIf(String::isNotBlank)
                ?: row.selectFirst(".smartphone-only.statsPlayerName")?.text()?.trim()?.takeIf(String::isNotBlank)
                ?: link.text().trim().takeIf(String::isNotBlank)
                ?: throw SourceUnavailable("统计表选手名称缺失。")
            fun cell(selector: String) = row.selectFirst(selector)?.text()?.trim()?.takeIf { it.isNotEmpty() && it != "-" }
            PlayerStat(
                name = nickname, teamId = team,
                kd = cell("td.kd:not(.eco-adjusted-data)"),
                adr = cell("td.adr:not(.eco-adjusted-data)"),
                kast = cell("td.kast:not(.eco-adjusted-data)"),
                rating = cell("td.rating"), ratingVersion = version,
                player = PlayerRef(pid, nickname, link.absUrl("href"), photos[pid]),
                swing = cell("td.roundSwing"), ecoKd = cell("td.kd.eco-adjusted-data"),
                ecoAdr = cell("td.adr.eco-adjusted-data"), ecoKast = cell("td.kast.eco-adjusted-data"),
            )
        }
    }

    private fun playerPhotos(doc: Document): Map<String, String> =
        doc.select("div.players .player-image").mapNotNull { row ->
            val identity = row.selectFirst("a[href], [data-player-id]") ?: return@mapNotNull null
            val pid = identity.attr("data-player-id").takeIf { it.toLongOrNull() != null }
                ?: identity.idFor("player") ?: return@mapNotNull null
            val image = row.select("img.player-photo").firstOrNull { !it.hasClass("night-only") } ?: return@mapNotNull null
            val retina = image.attr("srcset").split(',').firstOrNull { it.trim().endsWith(" 2x") }
                ?.trim()?.substringBefore(' ')
            (MediaUrls.validated(retina) ?: MediaUrls.validated(image.absUrl("src")))?.let { pid to it }
        }.toMap()

    private fun Element.idFor(kind: String): String? = entityId.find(attr("href"))
        ?.takeIf { it.groupValues[1] == kind }?.groupValues?.get(2)

    private fun Element.statsUrl(): String? = absUrl("href").takeIf { url ->
        HltvAccess.allowed(url) && runCatching {
            val path = URI(url).path
            mapStatsId.matches(path) || seriesStatsPath.matches(path)
        }.getOrDefault(false)
    }
}
