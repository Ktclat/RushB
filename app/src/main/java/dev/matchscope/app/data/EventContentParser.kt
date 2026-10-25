package dev.matchscope.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant

/** Reads the same public bracket JSON used to render HLTV's event page. */
object EventContentParser {
    fun parse(doc: Document, id: String): EventContent {
        val teams = doc.select(".teams-attending .team-box").mapNotNull { box ->
            val link = box.selectFirst(".team-name a[href]") ?: return@mapNotNull null
            team(link, box.selectFirst(".logo-box img")?.absUrl("src"), link.selectFirst(".text")?.text())
        }.distinctBy { it.id }
        val prizes = doc.select(".placements .placement").mapNotNull { box ->
            val place = box.children().firstOrNull { it.tagName() == "div" && it.className().isBlank() }?.text()?.trim()
                ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val link = box.selectFirst(".team a[href]")
            val t = link?.let { team(it, box.selectFirst(".team-logo img")?.absUrl("src")) }
            EventPrize(place, t?.let { value -> teams.firstOrNull { it.id == value.id } ?: value },
                box.select(".prize:not(.club-share):not(.spot-prizes)").firstOrNull { it.text().isNotBlank() }?.text(),
                box.selectFirst(".club-share")?.text()?.substringAfter(':')?.trim()?.takeIf(String::isNotBlank))
        }
        val brackets = doc.select("[data-slotted-bracket-json]").map { node ->
            try { bracket(JSONObject(node.attr("data-slotted-bracket-json"))) }
            catch (_: Exception) { throw SourceUnavailable("赛事对阵格式暂不支持") }
        }
        fun table(selector: String) = doc.select("$selector tr").mapNotNull { row ->
            val key = row.selectFirst("th")?.text()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val value = row.selectFirst("td")?.text()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            key to value
        }
        fun link(path: String) = doc.select("a[href]").firstOrNull { it.attr("href") == path }?.absUrl("href")?.takeIf(HltvAccess::allowed)
        return EventContent(teams, prizes, brackets, table("table.formats"), table("table.eventMeta"),
            doc.select(".map-pool-map-name").map { it.text() }.distinct(),
            link("/events/$id/matches"), link("/results?event=$id"), complete = doc.selectFirst(".event-page") != null)
    }

    private fun team(link: Element, logo: String?, name: String? = null): Team? {
        val url = link.absUrl("href")
        if (!HltvAccess.allowed(url)) return null
        val id = Regex("^/team/([1-9][0-9]*)/.*$").matchEntire(URI(url).path)?.groupValues?.get(1) ?: return null
        return Team(id, name?.takeIf(String::isNotBlank) ?: link.text(), url = url, logoUrl = MediaUrls.validated(logo))
    }

    internal fun bracket(json: JSONObject): EventBracket {
        val id = json.getJSONObject("bracketId").get("id").toString()
        val rounds = mutableListOf<EventRound>()
        fun round(value: JSONObject, column: Int, lane: Int) {
            val slots = value.keys().asSequence().filter { Regex("slot\\d+").matches(it) }.sortedBy { it.removePrefix("slot").toInt() }
                .mapNotNull { key -> value.optJSONObject(key)?.takeUnless { it.optBoolean("hidden", false) }?.let(::slot) }.toList()
            if (slots.isNotEmpty()) rounds += EventRound(value.optJSONObject("roundName")?.optString("name").orEmpty(), column, lane, slots)
        }
        val array = json.optJSONArray("rounds")
        if (array != null) (0 until array.length()).forEach { round(array.getJSONObject(it), it, 0) }
        else {
            // Source DoubleElimination4 layout; loser paths are retained in each slot entry.
            listOf(Triple("upperRound1", 0, 0), Triple("upperFinal", 1, 0), Triple("lowerRound1", 1, 1),
                Triple("consolidationFinal", 2, 1), Triple("grandFinal", 3, 0)).forEach { (key, column, lane) ->
                json.optJSONObject(key)?.let { round(it, column, lane) }
            }
            val known = setOf("upperRound1", "upperFinal", "lowerRound1", "consolidationFinal", "grandFinal")
            if (json.keys().asSequence().any { it !in known && json.optJSONObject(it)?.has("roundName") == true })
                throw SourceUnavailable("赛事对阵格式暂不支持")
        }
        require(rounds.isNotEmpty())
        require(rounds.flatMap { it.slots }.map { it.id }.distinct().size == rounds.sumOf { it.slots.size })
        return EventBracket(id, json.getString("name"), rounds)
    }

    private fun slot(json: JSONObject): EventSlot {
        val matchup = json.optJSONObject("matchup")
        fun team(side: String): Team? {
            val value = matchup?.optJSONObject(side) ?: return null
            val id = value.optJSONObject("id")?.optLong("teamId", -1)?.takeIf { it > 0 }?.toString() ?: return null
            val name = value.optString("name").takeIf(String::isNotBlank) ?: return null
            return Team(id, name, logoUrl = MediaUrls.validated(value.optJSONObject("logo")?.optString("dayLogoURL")))
        }
        val match = matchup?.optJSONObject("match")
        val mid = match?.optLong("matchId", -1)?.takeIf { it > 0 }?.toString()
        val url = match?.optString("matchPageURL")?.takeIf(String::isNotBlank)?.let { URI("https://www.hltv.org").resolve(it).toString() }
            ?.takeIf { HltvAccess.allowed(it) && URI(it).path.startsWith("/matches/$mid/") }
        require(mid == null || url != null)
        val score = matchup?.optJSONObject("score")
        fun n(key: String) = score?.takeIf { it.has(key) && !it.isNull(key) }?.optInt(key, -1)?.takeIf { it >= 0 }
        val status = when {
            match?.optString("status").equals("Postponed", true) -> MatchStatus.POSTPONED
            match?.optString("status").equals("Cancelled", true) -> MatchStatus.CANCELLED
            matchup?.optJSONObject("resultId") != null -> MatchStatus.FINISHED
            match?.optString("status").equals("Live", true) -> MatchStatus.LIVE
            else -> MatchStatus.UPCOMING
        }
        return EventSlot(json.getJSONObject("slotId").getString("id"), team("team1"), team("team2"),
            matchup?.optJSONObject("team1")?.optString("name")?.takeIf(String::isNotBlank) ?: "待定",
            matchup?.optJSONObject("team2")?.optString("name")?.takeIf(String::isNotBlank) ?: "待定",
            n("team1Score"), n("team2Score"), mid, url,
            match?.optDouble("startTime", Double.NaN)?.takeIf { it.isFinite() && it > 0 }?.toLong()?.let(Instant::ofEpochMilli),
            match?.optInt("numberOfMaps", 0)?.takeIf { it > 0 }?.let { "BO$it" }, status,
            (1..2).mapNotNull { side -> json.optJSONObject("slotEntry$side")?.let { entry ->
                entry.optJSONObject("slotId")?.optString("id")?.takeIf(String::isNotBlank)?.let { EventEntry(it, entry.optString("type") == "LoserOf") }
            } })
    }
}
