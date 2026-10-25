package dev.matchscope.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/** Optional fields keep records written by older app versions readable. */
internal object ProfileRecords {
    private fun JSONObject.s(k: String) = if (isNull(k)) null else optString(k).takeIf(String::isNotBlank)
    private fun JSONObject.n(k: String) = if (!has(k) || isNull(k)) null else optInt(k)
    private fun <T> JSONObject.list(k: String, decode: (JSONObject) -> T): List<T> = optJSONArray(k)?.let { a ->
        (0 until a.length()).map { decode(a.getJSONObject(it)) }
    }.orEmpty()
    private fun pairs(values: List<Pair<String, String>>) = JSONArray(values.map { JSONObject().put("label", it.first).put("value", it.second) })
    private fun JSONObject.pairs(k: String) = list(k) { it.getString("label") to it.getString("value") }

    fun content(p: ProfileContent): JSONObject = JSONObject()
        .put("complete", p.complete).put("moreMatches", p.moreMatchesUrl).put("moreEvents", p.moreEventsUrl).put("summary", pairs(p.summary))
        .put("matches", JSONArray(p.matches.map { m -> JSONObject().put("left", Records.team(m.left)).put("right", Records.team(m.right))
            .put("event", Records.event(m.event)).put("status", m.status.name).put("time", m.time?.toString())
            .put("leftScore", m.leftScore).put("rightScore", m.rightScore).put("matchUrl", m.matchUrl).put("statisticsUrl", m.statisticsUrl) }))
        .put("events", JSONArray(p.events.map { e -> JSONObject().put("event", Records.event(e.event)).put("placement", e.placement)
            .put("team", e.team?.let(Records::team)).put("upcoming", e.upcoming).put("category", e.category).put("statistics", pairs(e.statistics)) }))
        .put("news", JSONArray(p.news.map { JSONObject().put("title", it.title).put("url", it.url).put("published", it.publishedAt).put("image", it.imageUrl) }))
        .put("career", JSONArray(p.career.map { c -> JSONObject().put("team", Records.team(c.team)).put("start", c.startDate?.toString())
            .put("end", c.endDate?.toString()).put("period", c.period).put("trophies", JSONArray(c.trophies.map(Records::event))) }))
        .put("roles", JSONArray(p.roles.map { JSONObject().put("label", it.label).put("score", it.score) }))
        .put("roster", JSONArray(p.roster.map { r -> JSONObject().put("player", Records.player(r.player)).put("country", r.country)
            .put("status", r.status).put("tenure", r.tenure).put("maps", r.maps).put("rating", r.rating).put("roles", JSONArray(r.roles)) }))
        .put("maps", JSONArray(p.maps.map { m -> JSONObject().put("name", m.name).put("winRate", m.winRate)
            .put("wins", m.wins).put("draws", m.draws).put("losses", m.losses).put("stats", pairs(m.statistics)) }))
        .also { ProfileExtrasRecords.write(p, it) }

    fun content(j: JSONObject?): ProfileContent {
        if (j == null) return ProfileContent(complete = false)
        return ProfileContent(
            matches = j.list("matches") { ProfileMatch(Records.team(it.getJSONObject("left")), Records.team(it.getJSONObject("right")), Records.event(it.getJSONObject("event")),
                MatchStatus.valueOf(it.getString("status")), it.s("time")?.let(Instant::parse), it.n("leftScore"), it.n("rightScore"), it.s("matchUrl"), it.s("statisticsUrl")) },
            events = j.list("events") { ProfileEvent(Records.event(it.getJSONObject("event")), it.s("placement"), it.optJSONObject("team")?.let(Records::team), it.optBoolean("upcoming"), it.s("category"), it.pairs("statistics")) },
            news = j.list("news") { NewsItem(it.getString("title"), it.getString("url"), it.getString("published"), MediaUrls.validated(it.s("image"))) },
            career = j.list("career") { CareerTeam(Records.team(it.getJSONObject("team")), it.s("start")?.let(LocalDate::parse), it.s("end")?.let(LocalDate::parse), it.getString("period"), it.list("trophies", Records::event)) },
            roles = j.list("roles") { RoleStat(it.getString("label"), it.getInt("score")) },
            roster = j.list("roster") { RosterMember(Records.player(it.getJSONObject("player")), it.s("country"), it.s("status"), it.s("tenure"), it.n("maps"), it.s("rating"), it.strings("roles")) },
            maps = j.list("maps") { MapPerformance(it.getString("name"), it.getString("winRate"), it.n("wins"), it.n("draws"), it.n("losses"), it.pairs("stats")) },
            summary = j.pairs("summary"), moreMatchesUrl = j.s("moreMatches"), moreEventsUrl = j.s("moreEvents"), complete = j.optBoolean("complete"),
            schemaVersion = j.optInt("schemaVersion"), honors = ProfileExtrasRecords.honors(j), teammates = ProfileExtrasRecords.peers(j,"teammates"),
            similarPlayers = ProfileExtrasRecords.peers(j,"similarPlayers"), earnings = ProfileExtrasRecords.earnings(j), newsStamp = j.optJSONObject("newsStamp")?.let { Records.stamp(it, true) },
        )
    }

    fun stat(s: PlayerStat): JSONObject = JSONObject().put("name", s.name).put("team", s.teamId).put("kd", s.kd).put("adr", s.adr)
        .put("kast", s.kast).put("rating", s.rating).put("version", s.ratingVersion).put("player", s.player?.let(Records::player))
        .put("swing", s.swing).put("ecoKd", s.ecoKd).put("ecoAdr", s.ecoAdr).put("ecoKast", s.ecoKast)
    fun stat(j: JSONObject) = PlayerStat(j.getString("name"), j.getString("team"), j.s("kd"), j.s("adr"), j.s("kast"), j.s("rating"), j.s("version"),
        j.optJSONObject("player")?.let(Records::player), j.s("swing"), j.s("ecoKd"), j.s("ecoAdr"), j.s("ecoKast"))
    fun group(g: MatchStatGroup) = JSONObject().put("key", g.mapKey).put("map", g.mapName).put("side", g.side.name)
        .put("url", g.statsUrl).put("rows", JSONArray(g.rows.map(::stat)))
    fun group(j: JSONObject) = MatchStatGroup(j.getString("key"), j.s("map"), MatchSide.valueOf(j.getString("side")), j.list("rows", ::stat), j.s("url"))
}
