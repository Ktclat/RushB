package dev.matchscope.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal object ProfileExtrasRecords {
    private fun JSONObject.s(key: String): String? = if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)
    fun write(value: ProfileContent, target: JSONObject) {
        target.put("schemaVersion", value.schemaVersion).put("newsStamp", value.newsStamp?.let(Records::stamp))
            .put("honors", JSONArray(value.honors.map { h -> JSONObject().put("category",h.category).put("title",h.title)
                .put("event",h.event?.let(Records::event)).put("year",h.year).put("placement",h.placement).put("image",h.imageUrl).put("news",h.newsUrl) }))
            .put("teammates", JSONArray(value.teammates.map(::peer))).put("similarPlayers",JSONArray(value.similarPlayers.map(::peer)))
            .put("earnings",JSONArray(value.earnings.map { e -> JSONObject().put("team",Records.team(e.team)).put("prize",e.prize).put("lastEvent",e.lastEvent?.toString()) }))
    }
    fun honors(j: JSONObject) = j.objects("honors").map { h -> ProfileHonor(h.getString("category"),h.getString("title"),h.optJSONObject("event")?.let(Records::event),
        h.s("year")?.toIntOrNull(),h.s("placement")?.toIntOrNull(),MediaUrls.validated(h.s("image")),h.s("news")?.takeIf(HltvAccess::allowed)) }
    private fun peer(p: ProfilePeer) = JSONObject().put("player",Records.player(p.player)).put("team",p.teamName).put("rating",p.rating).put("version",p.ratingVersion).put("roles",JSONArray(p.roles))
    fun peers(j: JSONObject, key: String) = j.objects(key).map { ProfilePeer(Records.player(it.getJSONObject("player")),it.s("team"),it.s("rating"),it.s("version"),it.strings("roles")) }
    fun earnings(j: JSONObject) = j.objects("earnings").map { TeamEarning(Records.team(it.getJSONObject("team")),it.getString("prize"),it.s("lastEvent")?.let(LocalDate::parse)) }
    fun statistics(groups: List<EventStatistics>) = JSONArray(groups.map { group -> JSONObject().put("key",group.key).put("label",group.label)
        .put("players",JSONArray(group.players.map { p -> JSONObject().put("player",Records.player(p.player)).put("team",p.teamName).put("logo",p.teamLogoUrl)
            .put("rating",p.rating).put("version",p.ratingVersion).put("maps",p.maps) })) })
    fun statistics(j: JSONObject) = j.objects("statistics").map { group -> EventStatistics(group.getString("key"),group.getString("label"),group.objects("players").map {
        EventPlayerStats(Records.player(it.getJSONObject("player")),it.s("team"),MediaUrls.validated(it.s("logo")),it.s("rating"),it.s("version"),it.s("maps")?.toIntOrNull()) }) }
}
