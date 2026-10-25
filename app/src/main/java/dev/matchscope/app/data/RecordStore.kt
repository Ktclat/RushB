package dev.matchscope.app.data

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate

/** Versioned, atomic records keep bookmarks independent of today's upstream list. */
class RecordStore(private val directory: File) {
    @Synchronized fun names(): List<String> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.extension == "json" && Regex("[a-z0-9-]+").matches(it.nameWithoutExtension) }
        .map { it.nameWithoutExtension }.sorted()
    private fun file(name: String): AtomicFile {
        require(Regex("[a-z0-9-]+").matches(name))
        directory.mkdirs()
        return AtomicFile(File(directory, "$name.json"))
    }
    @Synchronized fun write(name: String, value: JSONObject) {
        val target = file(name)
        val stream = target.startWrite()
        try { stream.write(value.put("schema", 1).toString().toByteArray(Charsets.UTF_8)); target.finishWrite(stream) }
        catch (e: Exception) { target.failWrite(stream); throw e }
    }
    @Synchronized fun read(name: String): JSONObject? = runCatching {
        val target = file(name)
        if (target.baseFile.length() > 8 * 1024 * 1024) return null
        JSONObject(String(target.readFully(), Charsets.UTF_8)).takeIf { it.optInt("schema") == 1 }
    }.getOrNull()
    @Synchronized fun remove(name: String) { file(name).delete() }
}

internal object Records {
    private fun JSONObject.s(key: String) = if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)
    private fun JSONObject.n(key: String) = if (isNull(key) || !has(key)) null else getInt(key)
    private fun <T> list(array: JSONArray?, decode: (JSONObject) -> T): List<T> =
        if (array == null) emptyList() else (0 until array.length()).map { decode(array.getJSONObject(it)) }
    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map(array::getString)

    fun stamp(s: SourceStamp) = JSONObject().put("url", s.url).put("at", s.capturedAt.toString()).put("origin", s.origin.name).put("published", s.publishedOn)
    fun stamp(j: JSONObject, cached: Boolean = false) = SourceStamp(j.getString("url"), Instant.parse(j.getString("at")),
        if (cached && j.getString("origin") != DataOrigin.SNAPSHOT.name) DataOrigin.CACHE else DataOrigin.valueOf(j.getString("origin")), j.s("published"))
    fun team(t: Team) = JSONObject().put("id", t.id).put("name", t.name).put("region", t.region).put("url", t.url).put("roster", JSONArray(t.roster)).put("logo", t.logoUrl)
    fun team(j: JSONObject) = Team(j.getString("id"), j.getString("name"), j.optString("region", "未分类"), strings(j.optJSONArray("roster")), j.optString("url"), MediaUrls.validated(j.s("logo")))
    fun player(p: PlayerRef) = JSONObject().put("id", p.id).put("name", p.name).put("url", p.url).put("photo", p.photoUrl)
    fun player(j: JSONObject) = PlayerRef(j.getString("id"), j.getString("name"), j.getString("url"), MediaUrls.validated(j.s("photo")))
    fun event(e: Tournament) = JSONObject().put("id", e.id).put("name", e.name).put("start", e.startDate?.toString())
        .put("end", e.endDate?.toString()).put("location", e.location).put("prize", e.prize).put("count", e.teamCount).put("url", e.url).put("logo", e.logoUrl)
        .put("banner", e.bannerUrl).put("tier", e.tier?.name)
        .put("content", if (e.content.complete) EventRecords.content(e.content) else null)
    fun event(e: JSONObject) = Tournament(e.getString("id"), e.getString("name"), e.s("start")?.let(LocalDate::parse), e.s("end")?.let(LocalDate::parse),
        e.s("location"), e.s("prize"), e.n("count"), e.getString("url"), MediaUrls.validated(e.s("logo")), EventRecords.content(e.optJSONObject("content")), MediaUrls.validated(e.s("banner")),
        EventTier.entries.firstOrNull { it.name == e.s("tier") })
    fun teamProfile(p: Sourced<TeamProfile>) = JSONObject().put("stamp", stamp(p.stamp)).put("warning", p.warning).put("team", team(p.value.team))
        .put("players", JSONArray(p.value.players.map(::player))).put("rank", p.value.worldRanking).put("coach", p.value.coach).put("content", ProfileRecords.content(p.value.content))
    fun teamProfile(j: JSONObject) = Sourced(TeamProfile(team(j.getJSONObject("team")), list(j.optJSONArray("players"), ::player), j.n("rank"), j.s("coach"), ProfileRecords.content(j.optJSONObject("content"))),
        stamp(j.getJSONObject("stamp"), true), j.s("warning"))
    fun eventProfile(p: Sourced<Tournament>) = JSONObject().put("stamp", stamp(p.stamp)).put("warning", p.warning).put("event", event(p.value))
    fun eventProfile(j: JSONObject) = Sourced(event(j.getJSONObject("event")), stamp(j.getJSONObject("stamp"), true), j.s("warning"))
    fun match(m: Match): JSONObject {
        val e = m.event
        return JSONObject().put("id", m.id).put("left", team(m.left)).put("right", team(m.right))
            .put("event", event(e))
            .put("status", m.status.name).put("format", m.format).put("startsAt", m.startsAt?.toString()).put("date", m.sourceDate?.toString()).put("resultAt", m.resultAt?.toString())
            .put("leftScore", m.leftScore).put("rightScore", m.rightScore).put("url", m.url).put("detailed", m.detailed).put("note", m.note)
            .put("stamp", m.stamp?.let(::stamp)).put("veto", JSONArray(m.veto)).put("stage", m.stage)
            .put("liveScore", m.liveScore?.let { JSONObject().put("leftMaps", it.leftMaps).put("rightMaps", it.rightMaps)
                .put("leftRounds", it.leftRounds).put("rightRounds", it.rightRounds).put("mapName", it.mapName).put("stamp", stamp(it.stamp)) })
            .put("maps", JSONArray(m.maps.map { JSONObject().put("name", it.name).put("left", it.left).put("right", it.right).put("halves", it.halves).put("statsUrl", it.statsUrl) }))
            .put("stats", JSONArray(m.stats.map(ProfileRecords::stat)))
            .put("statGroups", JSONArray(m.statGroups.map(ProfileRecords::group)))
            .put("insights", MatchInsightRecords.insights(m.insights))
            .put("duels", JSONObject().apply { m.duels.forEach { (key, value) -> m.stamp?.let { put(key, MatchInsightRecords.duels(Sourced(value, it))) } } })
            .put("players", JSONObject().apply { m.players.forEach { (id, roster) -> put(id, JSONArray(roster.map(::player))) } })
    }
    fun match(j: JSONObject, cached: Boolean = false): Match {
        val event = event(j.getJSONObject("event"))
        val players = j.optJSONObject("players") ?: JSONObject()
        return Match(j.getString("id"), team(j.getJSONObject("left")), team(j.getJSONObject("right")), event, MatchStatus.valueOf(j.getString("status")),
            j.getString("format"), j.s("startsAt")?.let(Instant::parse), j.s("date")?.let(LocalDate::parse), j.n("leftScore"), j.n("rightScore"),
            list(j.optJSONArray("maps")) { MapScore(it.getString("name"), it.n("left"), it.n("right"), it.s("halves"), it.s("statsUrl")) }, strings(j.optJSONArray("veto")),
            list(j.optJSONArray("stats"), ProfileRecords::stat),
            j.getString("url"), players.keys().asSequence().associateWith { list(players.getJSONArray(it), ::player) },
            j.optJSONObject("stamp")?.let { stamp(it, cached) }, j.optBoolean("detailed"), j.s("note"), j.s("resultAt")?.let(Instant::parse), list(j.optJSONArray("statGroups"), ProfileRecords::group),
            MatchInsightRecords.insights(j.optJSONObject("insights")),
            j.optJSONObject("duels")?.let { values -> values.keys().asSequence().associateWith { MatchInsightRecords.duels(values.getJSONObject(it)).value } }.orEmpty(), j.s("stage"),
            j.optJSONObject("liveScore")?.let { LiveMatchScore(it.getInt("leftMaps"), it.getInt("rightMaps"), it.n("leftRounds"), it.n("rightRounds"), it.s("mapName"), stamp(it.getJSONObject("stamp"), cached)) })
            .normalizePlaceholders()
    }
    fun feed(f: Feed<Match>) = JSONObject().put("stamp", stamp(f.stamp)).put("warning", f.warning).put("items", JSONArray(f.items.map(::match)))
    fun feed(j: JSONObject) = Feed(list(j.getJSONArray("items")) { match(it, true) }, stamp(j.getJSONObject("stamp"), true),
        j.s("warning"))
    fun profile(p: Sourced<PlayerProfile>): JSONObject = JSONObject().put("stamp", stamp(p.stamp)).put("warning", p.warning).put("profile", JSONObject()
        .put("player", player(p.value.player)).put("realName", p.value.realName).put("country", p.value.country).put("age", p.value.age)
        .put("team", p.value.team?.let(::team)).put("period", p.value.statisticsPeriod).put("content", ProfileRecords.content(p.value.content))
        .put("stats", JSONArray(p.value.statistics.map { JSONObject().put("label", it.first).put("value", it.second) })))
    fun profile(j: JSONObject): Sourced<PlayerProfile> {
        val p = j.getJSONObject("profile")
        return Sourced(PlayerProfile(player(p.getJSONObject("player")), p.s("realName"), p.s("country"), p.n("age"), p.optJSONObject("team")?.let(::team),
            list(p.optJSONArray("stats")) { it.getString("label") to it.getString("value") }, p.s("period"), ProfileRecords.content(p.optJSONObject("content"))), stamp(j.getJSONObject("stamp"), true), j.s("warning"))
    }
}
