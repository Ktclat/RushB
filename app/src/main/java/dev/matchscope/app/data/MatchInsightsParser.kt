package dev.matchscope.app.data

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant

/** Reads published match context; absent pre-match modules stay absent after the match. */
object MatchInsightsParser {
    private val series = Regex("bo[1-9]\\d*", RegexOption.IGNORE_CASE)
    private val score = Regex("^(\\d+)\\s*[-:]\\s*(\\d+)$")

    fun parse(doc: Document): MatchInsights {
        val left = doc.selectFirst(".team1-gradient a[href]")?.team() ?: return MatchInsights()
        val right = doc.selectFirst(".team2-gradient a[href]")?.team() ?: return MatchInsights()
        val teams = listOf(left, right)
        return MatchInsights(
            vrs = vrs(doc, teams), mapPools = mapPools(doc, left, right),
            comparisons = comparisons(doc, left, right), headToHead = headToHead(doc, left, right),
            recentForm = recentForm(doc, teams),
        )
    }

    private fun vrs(doc: Document, teams: List<Team>): MatchVrs? {
        val box = doc.selectFirst(".vrs-forecast-container") ?: return null
        val header = box.value(".vrs-forecast-middle .vrs-forecast-header")
        val mode = when {
            header.equals("result", true) -> MatchVrsMode.RESULT
            header.contains("team wins", true) && box.value(".vrs-forecast-right .vrs-forecast-header").contains("team loses", true) -> MatchVrsMode.FORECAST
            else -> return null
        }
        val names = box.select(".vrs-forecast-team-name").map { it.text().trim() }
        val before = box.select(".vrs-forecast-left-numbers > .vrs-forecast-numbers-wrapper")
        val middle = box.select(".vrs-forecast-middle > .vrs-forecast-numbers-wrapper")
        val afterLoss = box.select(".vrs-forecast-right > .vrs-forecast-numbers-wrapper")
        val values = names.mapIndexedNotNull { index, name ->
            val team = teams.singleOrNull { it.name.equals(name, true) } ?: return@mapIndexedNotNull null
            val current = before.getOrNull(index)
            MatchVrsTeam(team.id, current?.value(".vrs-forecast-points")?.points(), current?.value(".vrs-forecast-ranking")?.rank(),
                ifWin = middle.getOrNull(index)?.impact().takeIf { mode == MatchVrsMode.FORECAST },
                ifLoss = afterLoss.getOrNull(index)?.impact().takeIf { mode == MatchVrsMode.FORECAST },
                result = middle.getOrNull(index)?.impact().takeIf { mode == MatchVrsMode.RESULT })
        }
        return values.takeIf { it.isNotEmpty() }?.let { MatchVrs(mode, it) }
    }

    private fun Element.impact(): MatchVrsImpact? {
        val points = value(".vrs-forecast-points").points()
        val rank = value(".vrs-forecast-ranking").rank()
        val change = value(".vrs-forecast-small-points").toIntOrNull()
        return if (points == null && rank == null && change == null) null else MatchVrsImpact(points, rank, change)
    }

    private fun mapPools(doc: Document, left: Team, right: Team): List<MatchMapPool> {
        val box = doc.selectFirst(".map-stats-infobox") ?: return emptyList()
        val first = box.selectFirst(".map-stats-infobox-header .team:not(.team2)") ?: return emptyList()
        val second = box.selectFirst(".map-stats-infobox-header .team.team2") ?: return emptyList()
        val firstName = first.value(".team-name"); val secondName = second.value(".team-name")
        val reversed = when {
            firstName.equals(left.name, true) && secondName.equals(right.name, true) -> false
            firstName.equals(right.name, true) && secondName.equals(left.name, true) -> true
            else -> return emptyList()
        }
        fun rate(cell: Element?): MatchMapRate {
            val value = cell?.value(".map-stats-infobox-winpercentage")
            val percentage = value?.takeIf { it.endsWith('%') }?.dropLast(1)?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }
            val samples = cell?.value(".map-stats-infobox-maps-played")?.let { Regex("^(\\d+)\\s+(?:maps?|possibilit(?:y|ies))$").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            return MatchMapRate(percentage, samples, cell?.selectFirst(".map-stats-infobox-winpercentage a[href]")?.url("^/stats/(?:lineup|teams)/map/\\d+(?:/.*)?$"))
        }
        return listOf("wins" to MatchMapMetric.WIN, "picks" to MatchMapMetric.PICK, "bans" to MatchMapMetric.BAN).mapNotNull { (key, metric) ->
            val content = box.selectFirst("[map-stats-infobox=$key]") ?: return@mapNotNull null
            val maps = content.select(".map-stats-infobox-maps[data-mapname]").mapNotNull { row ->
                val mapKey = row.attr("data-mapname").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                val name = row.value(".mapname").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val a = rate(row.selectFirst(".map-stats-infobox-stats:not(.team2)"))
                val b = rate(row.selectFirst(".map-stats-infobox-stats.team2"))
                MatchMapComparison(mapKey, name, if (reversed) b else a, if (reversed) a else b)
            }.distinctBy { it.mapKey }
            val description = box.selectFirst("[data-spider-tab-toggle-value=$key]")?.attr("data-spider-detail-text")?.takeIf(String::isNotBlank)
            val a = first.selectFirst("a[href]")?.url("^/stats/(?:lineup|teams)/maps(?:/.*)?$")
            val b = second.selectFirst("a[href]")?.url("^/stats/(?:lineup|teams)/maps(?:/.*)?$")
            maps.takeIf { it.isNotEmpty() }?.let { MatchMapPool(metric, description, it, if (reversed) b else a, if (reversed) a else b) }
        }
    }

    private fun comparisons(doc: Document, left: Team, right: Team): List<MatchComparison> {
        val lineups = doc.select(".lineup").mapNotNull { lineup ->
            lineup.selectFirst(".box-headline a[href]")?.team()?.let { it.id to lineup }
        }.toMap()
        return listOf("HLTV 排名" to ".hltv-ranking", "VRS 排名" to ".vrs-ranking").mapNotNull { (label, selector) ->
            fun value(id: String) = lineups[id]?.value(".teamRanking $selector")?.let { Regex("#\\d+").find(it)?.value }
            val a = value(left.id); val b = value(right.id)
            if (a == null && b == null) null else MatchComparison(label, a, b)
        }
    }

    private fun headToHead(doc: Document, left: Team, right: Team): MatchHeadToHead? {
        val summary = doc.selectFirst(".head-to-head")
        val listing = doc.selectFirst(".head-to-head-listing")
        if (summary == null && listing == null) return null
        val firstTeam = summary?.selectFirst(".team1 a[href]")?.team()
        val secondTeam = summary?.selectFirst(".team2 a[href]")?.team()
        val counts = summary?.select(".flexbox-column")?.map { it.value(".bold").toIntOrNull() }.orEmpty()
        val order = when {
            firstTeam?.id == left.id && secondTeam?.id == right.id -> false
            firstTeam?.id == right.id && secondTeam?.id == left.id -> true
            else -> null
        }
        val results = listing?.select("tr")?.mapNotNull { row ->
            val link = row.selectFirst("td.date a[href]") ?: return@mapNotNull null
            val url = link.url("^/matches/\\d+(?:/.*)?$") ?: return@mapNotNull null
            val a = row.selectFirst("td.team1 a[href]")?.team() ?: return@mapNotNull null
            val b = row.selectFirst("td.team2 a[href]")?.team() ?: return@mapNotNull null
            if (setOf(a.id, b.id) != setOf(left.id, right.id)) return@mapNotNull null
            val map = row.value("td.map .dynamic-map-name-full").ifBlank { row.value("td.map") }.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val scores = score.matchEntire(row.value("td.result"))
            val eventLink = row.selectFirst("td.event a[href]")
            val event = eventLink?.url("^/events/\\d+(?:/.*)?$")?.let { Tournament(entityId(it, "events")!!, eventLink.text(), url = it) }
            val unit = if (series.matches(map)) MatchResultUnit.SERIES else MatchResultUnit.MAP
            MatchHistoryResult(entityId(url, "matches")!!, url, a, b, unit,
                format = map.uppercase().takeIf { unit == MatchResultUnit.SERIES }, mapName = map.takeIf { unit == MatchResultUnit.MAP },
                leftScore = scores?.groupValues?.get(1)?.toIntOrNull(), rightScore = scores?.groupValues?.get(2)?.toIntOrNull(),
                playedAt = row.selectFirst("td.date [data-unix]")?.attr("data-unix")?.toLongOrNull()?.let(Instant::ofEpochMilli),
                dateLabel = row.value("td.date").takeIf(String::isNotBlank), event = event)
        }.orEmpty().distinctBy { listOf(it.matchId, it.unit.name, it.mapName, it.left.id, it.right.id) }
        val more = listing?.select("a[href]")?.firstNotNullOfOrNull { it.url("^/results/?$") }
            // The official results filter uses a present, empty requireAllTeams switch for intersection.
            ?: "https://www.hltv.org/results?team=${left.id}&team=${right.id}&requireAllTeams="
        return MatchHeadToHead(left.id, right.id,
            leftMapWins = order?.let { counts.getOrNull(if (it) 2 else 0) },
            rightMapWins = order?.let { counts.getOrNull(if (it) 0 else 2) }, overtimes = counts.getOrNull(1).takeIf { order != null },
            results = results, moreUrl = more)
    }

    private fun recentForm(doc: Document, teams: List<Team>): List<MatchRecentForm> {
        val period = doc.selectFirst(".past-matches-header .headline")?.text()?.trim()?.takeIf(String::isNotBlank)
        return doc.select(".past-matches-grid[data-past-matches-core], .past-matches-grid[data-past-matches-team]").flatMap { grid ->
            val scope = if (grid.hasAttr("data-past-matches-core")) MatchHistoryScope.CORE else MatchHistoryScope.TEAM
            grid.select(".past-matches-box").mapNotNull { box ->
                val owner = box.selectFirst(".past-matches-headline a[href]")?.team()?.let { source -> teams.firstOrNull { it.id == source.id } ?: source }
                    ?: return@mapNotNull null
                val rows = box.select(".past-matches-table tr").mapNotNull { row ->
                    val opponent = row.selectFirst(".past-matches-team a[href]")?.team() ?: return@mapNotNull null
                    val url = row.selectFirst(".past-matches-score a[href]")?.url("^/matches/\\d+(?:/.*)?$") ?: return@mapNotNull null
                    val scores = score.matchEntire(row.value(".past-matches-score"))
                    val format = row.value(".past-matches-map").takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val unit = if (series.matches(format)) MatchResultUnit.SERIES else MatchResultUnit.MAP
                    MatchHistoryResult(entityId(url, "matches")!!, url, owner, opponent, unit,
                        format = format.uppercase().takeIf { unit == MatchResultUnit.SERIES }, mapName = format.takeIf { unit == MatchResultUnit.MAP },
                        leftScore = scores?.groupValues?.get(1)?.toIntOrNull(), rightScore = scores?.groupValues?.get(2)?.toIntOrNull(),
                        dateLabel = row.value(".past-matches-time-ago").takeIf(String::isNotBlank))
                }.distinctBy { it.matchId to it.mapName }
                val explicitMore = box.select("a[href]").firstNotNullOfOrNull { it.url("^/results/?$") }
                // The team-results route is public; it must not masquerade as a lineup-core filter.
                val more = explicitMore ?: if (scope == MatchHistoryScope.TEAM) "https://www.hltv.org/results?team=${owner.id}" else null
                MatchRecentForm(owner, scope, period, rows, more)
            }
        }.distinctBy { it.team.id to it.scope }
    }

    private fun Element.value(selector: String) = selectFirst(selector)?.text()?.trim().orEmpty()
    private fun String.points() = Regex("^([+-]?\\d+)\\s*pt$").matchEntire(this)?.groupValues?.get(1)?.toIntOrNull()
    private fun String.rank() = Regex("^#(\\d+)$").matchEntire(this)?.groupValues?.get(1)?.toIntOrNull()
    private fun entityId(url: String, kind: String) = Regex("^/$kind/(\\d+)(?:/.*)?$").matchEntire(URI(url).path)?.groupValues?.get(1)
    private fun Element.url(pathPattern: String): String? = absUrl("href").takeIf { raw -> HltvAccess.allowed(raw) && runCatching { Regex(pathPattern).matches(URI(raw).path) }.getOrDefault(false) }
    private fun Element.team(): Team? {
        val url = url("^/team/\\d+(?:/.*)?$") ?: return null
        val name = selectFirst(".teamName")?.text()?.trim()?.takeIf(String::isNotBlank) ?: text().trim().takeIf(String::isNotBlank) ?: return null
        val image = select("img").firstOrNull { !it.hasClass("night-only") } ?: parent()?.selectFirst("img.logo")
        val logo = image?.absUrl("src")?.let(MediaUrls::validated)
        return Team(entityId(url, "team")!!, name, url = url, logoUrl = logo)
    }
}
