package dev.matchscope.app.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.time.Instant
import java.time.LocalDate

/** HTML adapters are deliberately strict: an unfamiliar page is not an empty feed. */
object HltvParser {
    private fun page(html: String) = Jsoup.parse(html, "https://www.hltv.org").also {
        if (it.title().contains("Just a moment", true) || it.title().contains("Attention Required", true) || it.title().contains("请稍候") ||
            it.select("#challenge-running, #cf-challenge-running, #challenge-stage").isNotEmpty())
            throw SourceUnavailable("来源仍在验证浏览器，请稍后在手机中重试。", 300_000)
    }
    private fun Element.value(selector: String) = selectFirst(selector)?.text()?.trim().orEmpty()
    private fun Element.artwork(selector: String): String? = select(selector).firstOrNull { !it.hasClass("night-only") }?.let { image ->
        val retina = image.attr("srcset").split(',').firstOrNull { it.trim().endsWith(" 2x") }?.trim()?.substringBefore(' ')
        MediaUrls.validated(retina) ?: MediaUrls.validated(image.absUrl("src"))
    }
    private fun id(url: String, kind: String) = Regex("/$kind/(\\d+)").find(url)?.groupValues?.get(1)
    private fun Element.epoch(): Instant? = selectFirst("[data-unix], [data-zonedgrouping-entry-unix]")?.let {
        (it.attr("data-unix").toLongOrNull() ?: it.attr("data-zonedgrouping-entry-unix").toLongOrNull())?.let(Instant::ofEpochMilli)
    } ?: attr("data-zonedgrouping-entry-unix").toLongOrNull()?.let(Instant::ofEpochMilli)

    fun matches(html: String): List<Match> {
        val doc = page(html)
        val rows = doc.select(".match-wrapper[data-match-id], .upcomingMatch, .liveMatch-container")
        if (rows.isEmpty() && !doc.text().contains("No upcoming matches", true))
            throw SourceUnavailable("比赛页结构暂不支持，未替换本地资料。")
        return rows.map { row ->
            val link = row.selectFirst("a[href^=/matches/]") ?: throw SourceUnavailable("比赛链接缺失")
            val matchId = id(link.attr("href"), "matches") ?: throw SourceUnavailable("比赛编号缺失")
            val names = row.select(".match-teamname, .matchTeamName")
            fun team(index: Int): Team {
                val name = names.getOrNull(index)?.text()?.takeIf(String::isNotBlank)
                    ?: row.value(".team${index + 1} .team").ifBlank { "待定" }
                val tid = row.attr("team${index + 1}").takeIf { it.toLongOrNull() != null }
                val logos = row.select(".match-team-logo:not(.night-only), .matchTeamLogo:not(.night-only)")
                return Team(tid ?: "pending-$matchId-$index", name, url = tid?.let { "https://www.hltv.org/team/$it/-" }.orEmpty(),
                    logoUrl = logos.firstOrNull { it.attr("title") == name || it.attr("alt") == name }?.let { MediaUrls.validated(it.absUrl("src")) })
            }
            val eventLink = row.selectFirst("a[href^=/events/]")
            val eventName = row.selectFirst("[data-event-headline]")?.attr("data-event-headline")?.takeIf(String::isNotBlank)
                ?: row.selectFirst(".matchEventLogo")?.attr("title")?.takeIf(String::isNotBlank)
                ?: row.value(".matchEventName").ifBlank { "赛事待确认" }
            val eventId = row.attr("data-event-id").takeIf { it.toLongOrNull() != null }
                ?: eventLink?.let { id(it.attr("href"), "events") } ?: "match-$matchId"
            val isLive = row.attr("live") == "true" || row.hasClass("liveMatch-container") || row.value(".matchLive").equals("LIVE", true)
            val series = row.select("[data-livescore-maps-won-for]").take(2).map { it.text().toIntOrNull() }
            Match(matchId, team(0), team(1), Tournament(eventId, eventName, url = eventLink?.absUrl("href") ?: if(eventId.toLongOrNull() != null) "https://www.hltv.org/events/$eventId/-" else "", logoUrl = row.artwork(".match-event-logo, .matchEventLogo")),
                if (isLive) MatchStatus.LIVE else MatchStatus.UPCOMING,
                format = row.value(".match-meta:not(.match-meta-live), .matchMeta").uppercase().ifBlank { "赛制待确认" }, startsAt = row.epoch(),
                // Do not invent series scores from round counters.
                leftScore = if (isLive) series.getOrNull(0) else null, rightScore = if(isLive) series.getOrNull(1) else null,
                url = link.absUrl("href"))
        }.distinctBy { it.id }
    }

    fun match(html: String, expectedId: String): Match {
        require(expectedId.matches(Regex("\\d+")))
        val doc = page(html)
        val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href")
        if (canonical != null && id(canonical, "matches") != expectedId) throw SourceUnavailable("比赛详情编号不一致。")
        fun side(number: Int): Team {
            val section = doc.selectFirst(".team$number-gradient") ?: throw SourceUnavailable("比赛详情战队缺失。")
            val link = section.selectFirst("a[href^=/team/]")
            val name = section.value(".teamName").ifBlank { "待定" }
            val tid = link?.let { id(it.attr("href"), "team") }
            return Team(tid ?: "pending-$expectedId-$number", name, url = link?.absUrl("href").orEmpty(), logoUrl = section.artwork("img.logo"))
        }
        val left = side(1); val right = side(2)
        val eventLink = doc.selectFirst(".timeAndEvent .event a[href^=/events/]") ?: throw SourceUnavailable("比赛详情赛事缺失。")
        val eventId = id(eventLink.attr("href"), "events") ?: throw SourceUnavailable("赛事编号缺失。")
        val countdown = doc.value(".countdown").lowercase()
        val status = when {
            countdown == "live" -> MatchStatus.LIVE
            countdown.contains("match over") -> MatchStatus.FINISHED
            countdown.contains("postponed") -> MatchStatus.POSTPONED
            countdown.contains("deleted") || countdown.contains("cancelled") -> MatchStatus.CANCELLED
            else -> MatchStatus.UPCOMING
        }
        val format = Regex("Best of (\\d+)", RegexOption.IGNORE_CASE).find(doc.value(".veto-box"))?.groupValues?.get(1)?.let { "BO$it" } ?: "赛制待确认"
        val maps = doc.select(".mapholder").map { map -> MapScore(map.value(".mapname"), map.value(".results-left .results-team-score").toIntOrNull(),
            map.value(".results-right .results-team-score").toIntOrNull(), map.value(".results-center-half-score").takeIf(String::isNotBlank),
            map.selectFirst("a[href^=/stats/matches/mapstatsid/]")?.absUrl("href")) }
        val veto = doc.select(".veto-box .padding > div").map { it.text().replace(Regex("^\\d+\\.\\s*"), "") }.filter(String::isNotBlank)
        val photos = doc.select("div.players .player-image").mapNotNull { row ->
            val identity = row.selectFirst("a[href^=/player/], [data-player-id]") ?: return@mapNotNull null
            val pid = identity.attr("data-player-id").takeIf { it.toLongOrNull() != null }
                ?: id(identity.attr("href"), "player") ?: return@mapNotNull null
            row.artwork("img.player-photo")?.let { pid to it }
        }.toMap()
        val statGroups = MatchStatsParser.parse(doc)
        val stats = statGroups.firstOrNull { it.mapKey == "all" && it.side == MatchSide.BOTH }?.rows ?: doc.select("#all-content table.totalstats").flatMap { table ->
            val teamId = table.selectFirst(".header-row a[href^=/team/]")?.let { id(it.attr("href"), "team") }
                ?: throw SourceUnavailable("统计表战队编号缺失。")
            val version = table.value(".header-row .ratingDesc").takeIf(String::isNotBlank)
            table.select("tr:not(.header-row)").mapNotNull { row ->
                val link = row.selectFirst("a[href^=/player/]") ?: return@mapNotNull null
                val pid = id(link.attr("href"), "player") ?: return@mapNotNull null
                val nick = row.value(".player-nick").ifBlank { row.value(".smartphone-only.statsPlayerName") }.ifBlank { link.text() }
                fun cell(cls: String) = row.value("td.$cls:not(.eco-adjusted-data)").takeIf { it.isNotBlank() && it != "-" }
                PlayerStat(nick, teamId, cell("kd"), cell("adr"), cell("kast"), cell("rating"), version, PlayerRef(pid, nick, link.absUrl("href"), photos[pid]))
            }
        }.distinctBy { it.player?.id }
        val rosters = listOf(left, right).mapIndexed { index, team ->
            val fromStats = stats.filter { it.teamId == team.id }.mapNotNull { it.player }
            val lineup = doc.select("div.players").getOrNull(index)?.select(".flagAlign[data-player-id]")?.mapNotNull { row ->
                val pid = row.attr("data-player-id").takeIf { it.toLongOrNull() != null } ?: return@mapNotNull null
                PlayerRef(pid, row.value(".text-ellipsis"), photoUrl = photos[pid])
            }.orEmpty()
            team.id to (fromStats + lineup).distinctBy { it.id }
        }.toMap()
        val scoreVisible = status == MatchStatus.FINISHED || status == MatchStatus.LIVE
        return Match(expectedId, left, right, Tournament(eventId, eventLink.text(), url = eventLink.absUrl("href")), status, format,
            startsAt = doc.selectFirst(".timeAndEvent [data-unix]")?.attr("data-unix")?.toLongOrNull()?.let(Instant::ofEpochMilli),
            leftScore = if(scoreVisible) doc.value(".team1-gradient .won, .team1-gradient .lost").toIntOrNull() else null,
            rightScore = if(scoreVisible) doc.value(".team2-gradient .won, .team2-gradient .lost").toIntOrNull() else null,
            maps = maps, veto = veto, stats = stats, url = canonical ?: "https://www.hltv.org/matches/$expectedId/-", players = rosters, detailed = true, statGroups = statGroups,
            insights = MatchInsightsParser.parse(doc))
    }

    fun player(html: String, expectedId: String): PlayerProfile {
        require(expectedId.matches(Regex("\\d+")))
        val doc = page(html)
        val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href")
        if(canonical != null && id(canonical, "player") != expectedId) throw SourceUnavailable("选手档案编号不一致。")
        val nick = doc.value(".playerNickname").takeIf(String::isNotBlank) ?: throw SourceUnavailable("选手档案结构暂不支持。")
        val teamLink = doc.selectFirst(".playerTeam a[href^=/team/]")
        val team = teamLink?.let { link -> id(link.attr("href"), "team")?.let { Team(it, link.text(), url = link.absUrl("href"), logoUrl = link.artwork("img")) } }
        val stats = doc.select(".playerpage-container .player-stat").mapNotNull { row ->
            val label = row.value("b")
            val value = row.value(".statsVal").replace(" /", "/")
            if (label.isBlank() || value.isBlank()) null else label to value
        }.distinctBy { it.first }
        return PlayerProfile(PlayerRef(expectedId, nick, canonical ?: "https://www.hltv.org/player/$expectedId/-", doc.artwork(".player-summary-stat-box-left-bodyshot, .playerBodyshot")),
            doc.value(".playerRealname").takeIf(String::isNotBlank),
            doc.selectFirst(".player-summary-stat-box-left-flag .flag, .playerNationality .flag, .playerRealname .flag, .playerFlag .flag")?.attr("alt"),
            Regex("^\\d+").find(doc.value(".playerAge .listRight"))?.value?.toIntOrNull(), team, stats,
            doc.value(".stats-window").takeIf(String::isNotBlank), ProfileParser.player(doc))
    }

    fun team(html: String, expectedId: String): TeamProfile {
        require(expectedId.matches(Regex("\\d+")))
        val doc = page(html)
        val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href")
        if (canonical != null && id(canonical, "team") != expectedId) throw SourceUnavailable("战队档案编号不一致。")
        val name = doc.value(".profile-team-name").takeIf(String::isNotBlank) ?: throw SourceUnavailable("战队档案结构暂不支持。")
        val players = doc.select(".bodyshot-team a[href^=/player/]").mapNotNull { link ->
            val pid = id(link.attr("href"), "player") ?: return@mapNotNull null
            val nick = link.attr("title").ifBlank { link.value(".playerFlagName .bold") }
            if(nick.isBlank()) null else PlayerRef(pid, nick, link.absUrl("href"), link.artwork(".bodyshot-team-img"))
        }.distinctBy { it.id }
        val country = doc.value(".team-country").ifBlank { "未分类" }
        val region = when(country) { "Europe" -> "欧洲"; "Asia" -> "亚洲"; "North America", "South America" -> "美洲"; else -> country }
        val team = Team(expectedId, name, region, players.map { it.name },
            canonical ?: "https://www.hltv.org/team/$expectedId/-", doc.artwork(".profile-team-logo-container img"))
        val stats = doc.select(".profile-team-stat")
        val rank = stats.firstOrNull { it.value("b") == "World ranking" }?.value("a[href^=/ranking/teams/]")?.removePrefix("#")?.toIntOrNull()
        val coach = stats.firstOrNull { it.value("b") == "Coach" }?.value(".right")?.takeIf(String::isNotBlank)
        return TeamProfile(team, players, rank, coach, ProfileParser.team(doc))
    }

    fun event(html: String, expectedId: String): Tournament {
        require(expectedId.matches(Regex("\\d+")))
        val doc = page(html)
        val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href")
        if(canonical != null && id(canonical, "events") != expectedId) throw SourceUnavailable("赛事档案编号不一致。")
        val name = doc.value(".event-sidebar .sidebar-headline").takeIf(String::isNotBlank) ?: throw SourceUnavailable("赛事档案结构暂不支持。")
        val info = doc.selectFirst("table.info") ?: throw SourceUnavailable("赛事基本资料缺失。")
        val dates = info.select("td.eventdate [data-unix]").mapNotNull { it.attr("data-unix").toLongOrNull()?.let(Instant::ofEpochMilli)?.atZone(java.time.ZoneOffset.UTC)?.toLocalDate() }
        return Tournament(expectedId, name, dates.firstOrNull(), dates.lastOrNull(), info.value("td.location").takeIf(String::isNotBlank),
            info.value("td.prizepool").takeIf(String::isNotBlank), info.value("td.teamsNumber").toIntOrNull(),
            canonical ?: "https://www.hltv.org/events/$expectedId/-", doc.artwork(".event-sidebar .event-logo"), EventContentParser.parse(doc, expectedId))
    }

    fun results(html: String): List<Match> {
        val doc = page(html)
        val rows = doc.select(".result-con")
        if (rows.isEmpty() && !doc.text().contains("No results", true)) throw SourceUnavailable("赛果页结构暂不支持。")
        return rows.map { row ->
            val link = row.selectFirst("a[href^=/matches/]") ?: throw SourceUnavailable("赛果链接缺失")
            val mid = id(link.attr("href"), "matches") ?: throw SourceUnavailable("赛果编号缺失")
            val names = row.select(".team")
            val teams = (0..1).map { index ->
                val name = names.getOrNull(index)?.text()?.takeIf(String::isNotBlank) ?: throw SourceUnavailable("赛果战队缺失")
                val tid = names[index].closest("[data-team-id]")?.attr("data-team-id")
                Team(tid ?: "name:${name.lowercase()}", name, url = tid?.let { "https://www.hltv.org/team/$it/-" }.orEmpty(), logoUrl = row.artwork(".team${index + 1} .team-logo"))
            }
            val score = Regex("(\\d+)\\s*[-:]\\s*(\\d+)").find(row.value(".result-score"))
                ?: throw SourceUnavailable("赛果比分缺失")
            val eventName = row.value(".event-name").ifBlank { "赛事待确认" }
            Match(mid, teams[0], teams[1], Tournament("result-$mid", eventName, url = "", logoUrl = row.artwork(".event-logo")), MatchStatus.FINISHED,
                row.value(".map-text").uppercase().ifBlank { "赛制待确认" },
                leftScore = score.groupValues[1].toInt(), rightScore = score.groupValues[2].toInt(), url = link.absUrl("href"),
                // Result-list timestamps describe the result, not the scheduled start.
                resultAt = row.epoch())
        }.groupBy { it.id }.values.map { copies -> copies.firstOrNull { it.resultAt != null } ?: copies.first() }
    }

    fun rankings(html: String): List<RankingEntry> {
        val doc = page(html)
        val result = doc.select(".ranked-team").map { row ->
            val link = row.selectFirst("a[href^=/team/]") ?: throw SourceUnavailable("榜单战队链接缺失")
            val tid = id(link.attr("href"), "team") ?: throw SourceUnavailable("榜单编号缺失")
            val position = row.value(".position").removePrefix("#").toIntOrNull() ?: throw SourceUnavailable("榜单名次缺失")
            val points = row.value(".points").filter(Char::isDigit).toIntOrNull() ?: throw SourceUnavailable("榜单积分缺失")
            val roster = row.select(".playersBox .playerNick, .rankingNicknames span").map { it.text() }.distinct()
            val changeNode = row.selectFirst(".change")
            val changeText = changeNode?.text()?.trim()
            val change = changeText?.toIntOrNull()
                ?: 0.takeIf { changeNode?.hasClass("neutral") == true && changeText in setOf("-", "—", "0") }
            RankingEntry(position, Team(tid, row.value(".name"), roster = roster, url = link.absUrl("href"), logoUrl = row.artwork(".team-logo img, img.team-logo")), points,
                change, roster)
        }
        if (result.isEmpty() || result.any { it.rank <= 0 || it.team.name.isBlank() } || result.distinctBy { it.rank }.size != result.size || result.distinctBy { it.team.id }.size != result.size) throw SourceUnavailable("排名页结构暂不支持。")
        return result.sortedBy { it.rank }
    }

    fun rankingDate(html: String): String? {
        val text = Jsoup.parse(html).selectFirst(".regional-ranking-header-text")?.text()?.substringAfter(" on ", "") ?: return null
        val clean = text.replace(Regex("(?<=\\d)(st|nd|rd|th)"), "")
        return runCatching { LocalDate.parse(clean, java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy", java.util.Locale.ENGLISH)).toString() }.getOrNull()
    }

    fun news(xml: String): List<NewsItem> = NewsParser.rss(xml)

}

object VrsParser {
    fun publishedDate(markdown: String): String = Regex("Standings(?: for [^\\r\\n]+?)? as of (\\d{4})_(\\d{2})_(\\d{2})")
        .find(markdown)?.groupValues?.drop(1)?.joinToString("-")
        ?.also { LocalDate.parse(it) } ?: throw SourceUnavailable("VRS 发布日期缺失。")

    fun parse(markdown: String, sourceUrl: String): List<RankingEntry> {
        publishedDate(markdown)
        val rows = markdown.lineSequence().filter { it.trim().startsWith("|") }.mapNotNull { line ->
            val cells = line.split('|').map(String::trim)
            val rank = cells.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            val points = cells.getOrNull(2)?.toIntOrNull() ?: throw SourceUnavailable("VRS 积分格式改变。")
            val name = cells.getOrNull(3)?.takeIf(String::isNotBlank) ?: throw SourceUnavailable("VRS 战队名缺失。")
            val roster = cells.getOrNull(4).orEmpty().split(',').map(String::trim).filter(String::isNotBlank)
            // VRS roster identities and points remain independent from HLTV's team ranking.
            RankingEntry(rank, Team("vrs:${name.lowercase()}", name, roster = roster, url = sourceUrl), points, roster = roster)
        }.toList()
        if (rows.isEmpty() || rows.map { it.rank }.distinct().size != rows.size) throw SourceUnavailable("VRS 榜单为空或名次重复。")
        return rows.sortedBy { it.rank }
    }
}
