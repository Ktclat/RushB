package dev.matchscope.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal object EventRecords {
    private fun JSONObject.s(key: String) = if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)
    private fun JSONObject.n(key: String) = if (isNull(key)) null else optInt(key)
    private fun <T> list(array: JSONArray?, parse: (JSONObject) -> T): List<T> = if (array == null) emptyList() else (0 until array.length()).map { parse(array.getJSONObject(it)) }
    private fun pairs(value: List<Pair<String, String>>) = JSONArray(value.map { JSONObject().put("label", it.first).put("value", it.second) })
    private fun pairs(value: JSONArray?) = list(value) { it.getString("label") to it.getString("value") }
    fun content(c: EventContent): JSONObject = JSONObject().put("complete", c.complete).put("schemaVersion", c.schemaVersion)
        .put("statistics", ProfileExtrasRecords.statistics(c.statistics))
        .put("bracketStamps", JSONObject().apply { c.bracketStamps.forEach { (id, stamp) -> put(id, Records.stamp(stamp)) } })
        .put("teams", JSONArray(c.teams.map(Records::team)))
        .put("prizes", JSONArray(c.prizes.map { JSONObject().put("place", it.place).put("team", it.team?.let(Records::team)).put("player", it.playerPrize).put("club", it.clubPrize) }))
        .put("formats", pairs(c.formats)).put("information", pairs(c.information)).put("maps", JSONArray(c.maps))
        .put("matchItems", JSONArray(c.matches.map(Records::match))).put("bracketLinks", pairs(c.bracketLinks))
        .put("matches", c.matchesUrl).put("results", c.resultsUrl)
        .put("brackets", JSONArray(c.brackets.map { bracket -> JSONObject().put("id", bracket.id).put("name", bracket.name)
            .put("rounds", JSONArray(bracket.rounds.map { round -> JSONObject().put("name", round.name).put("column", round.column).put("lane", round.lane)
                .put("slots", JSONArray(round.slots.map(::slot))) })) }))

    fun content(j: JSONObject?): EventContent = if (j == null) EventContent() else EventContent(
        teams = list(j.optJSONArray("teams"), Records::team),
        schemaVersion = j.optInt("schemaVersion"), statistics = ProfileExtrasRecords.statistics(j),
        bracketStamps = j.optJSONObject("bracketStamps")?.let { stamps -> stamps.keys().asSequence().associateWith { Records.stamp(stamps.getJSONObject(it), true) } }.orEmpty(),
        prizes = list(j.optJSONArray("prizes")) { EventPrize(it.getString("place"), it.optJSONObject("team")?.let(Records::team), it.s("player"), it.s("club")) },
        brackets = list(j.optJSONArray("brackets")) { b -> EventBracket(b.getString("id"), b.getString("name"), list(b.optJSONArray("rounds")) { r ->
            EventRound(r.getString("name"), r.getInt("column"), r.optInt("lane"), list(r.optJSONArray("slots"), ::slot)) }) },
        formats = pairs(j.optJSONArray("formats")), information = pairs(j.optJSONArray("information")),
        maps = j.optJSONArray("maps")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(),
        matchesUrl = j.s("matches")?.takeIf(HltvAccess::allowed), resultsUrl = j.s("results")?.takeIf(HltvAccess::allowed), complete = j.optBoolean("complete", false),
        matches = list(j.optJSONArray("matchItems")) { Records.match(it, true) }, bracketLinks = pairs(j.optJSONArray("bracketLinks")),
    )

    private fun slot(s: EventSlot) = JSONObject().put("id", s.id).put("left", s.left?.let(Records::team)).put("right", s.right?.let(Records::team))
        .put("leftLabel", s.leftLabel).put("rightLabel", s.rightLabel).put("leftScore", s.leftScore).put("rightScore", s.rightScore)
        .put("matchId", s.matchId).put("url", s.matchUrl).put("time", s.time?.toString()).put("format", s.format).put("status", s.status.name)
        .put("entries", JSONArray(s.entries.map { JSONObject().put("slotId", it.slotId).put("loser", it.loser) }))

    private fun slot(s: JSONObject) = EventSlot(s.getString("id"), s.optJSONObject("left")?.let(Records::team), s.optJSONObject("right")?.let(Records::team),
        s.optString("leftLabel", "待定"), s.optString("rightLabel", "待定"), s.n("leftScore"), s.n("rightScore"),
        s.s("matchId"), s.s("url")?.takeIf(HltvAccess::allowed), s.s("time")?.let(Instant::parse), s.s("format"),
        MatchStatus.valueOf(s.getString("status")), list(s.optJSONArray("entries")) { EventEntry(it.getString("slotId"), it.optBoolean("loser")) })
}
