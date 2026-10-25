package dev.matchscope.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

enum class MatchRegion(val label: String) {
    ALL("不限地区"), ASIA("亚洲"), EUROPE("欧洲"), AMERICAS("美洲"),
    OCEANIA("大洋洲"), MIDDLE_EAST("中东"), AFRICA("非洲"), GLOBAL("跨地区"), UNKNOWN("地区未明"),
}

enum class MatchLevel(val label: String) {
    ALL("全部级别"), IMPORTANT("Major 或 Tier 1"), MAJOR("Major"), TIER_ONE("Tier 1"), OTHER("其他／未分级"),
}

enum class MatchOrder(val label: String, val shortLabel: String) {
    IMPORTANT_FIRST("重点赛事优先", "重点优先"), TIME_ASC("时间从早到晚", "时间升序"), TIME_DESC("时间从晚到早", "时间降序"),
    LIVE_FIRST("进行中优先", "实时优先"), NAME("赛事名称", "赛事名称"),
}

/** An explicit user choice overrides this policy. Old LIVE captures are not live evidence. */
fun defaultEventExpanded(matches: List<Match>, selectedDay: LocalDate, now: Instant, zone: ZoneId, stamp: SourceStamp): Boolean =
    matches.any { EventEligibility.important(it.event) } ||
        selectedDay == now.atZone(zone).toLocalDate() && matches.any { it.hasRecentLiveStatus(now, stamp) }

private val locationsByRegion = mapOf(
    MatchRegion.ASIA to setOf("asia", "亚洲", "china", "中国", "mongolia", "japan", "south korea", "korea",
        "hong kong", "taiwan", "macau", "singapore", "indonesia", "thailand", "vietnam", "philippines",
        "malaysia", "india", "pakistan", "bangladesh", "nepal", "sri lanka", "kazakhstan", "uzbekistan",
        "kyrgyzstan", "tajikistan", "turkmenistan"),
    MatchRegion.EUROPE to setOf("europe", "欧洲", "denmark", "sweden", "norway", "finland", "iceland", "france",
        "germany", "poland", "ukraine", "russia", "belarus", "serbia", "bosnia and herzegovina", "croatia",
        "united kingdom", "uk", "england", "ireland", "portugal", "spain", "italy", "greece", "malta",
        "kosovo", "bulgaria", "romania", "czech republic", "czechia", "slovakia", "slovenia", "hungary",
        "austria", "switzerland", "netherlands", "belgium", "luxembourg", "estonia", "latvia", "lithuania",
        "moldova", "albania", "montenegro", "north macedonia"),
    MatchRegion.AMERICAS to setOf("americas", "america", "美洲", "north america", "south america",
        "brazil", "argentina", "united states", "usa", "canada", "chile", "uruguay", "peru", "colombia",
        "mexico", "ecuador", "bolivia", "paraguay", "venezuela", "costa rica", "panama", "puerto rico"),
    MatchRegion.OCEANIA to setOf("oceania", "大洋洲", "australia", "new zealand"),
    MatchRegion.MIDDLE_EAST to setOf("middle east", "中东", "saudi arabia", "united arab emirates", "uae",
        "qatar", "bahrain", "kuwait", "oman", "israel", "jordan", "lebanon", "iran", "iraq", "turkey", "türkiye"),
    MatchRegion.AFRICA to setOf("africa", "非洲", "south africa", "egypt", "morocco", "tunisia", "algeria",
        "nigeria", "kenya", "namibia", "zimbabwe"),
    MatchRegion.GLOBAL to setOf("global", "worldwide", "international", "world", "全球"),
)
private val locationRegions = locationsByRegion.flatMap { (region, locations) -> locations.map { it to region } }.toMap()

/** The event's venue/online region, never the nationalities of the participating teams. */
fun eventRegion(event: Tournament): MatchRegion {
    val location = event.location?.replace(Regex("\\([^)]*\\)"), "")?.trim()?.lowercase(Locale.ROOT)
        ?.replace(Regex("\\s+"), " ") ?: return MatchRegion.UNKNOWN
    return locationRegions[location] ?: locationRegions[location.substringAfterLast(',').trim()] ?: MatchRegion.UNKNOWN
}

fun filterMatchEvents(matches: List<Match>, region: MatchRegion, level: MatchLevel): List<Match> = matches.filter { match ->
    (region == MatchRegion.ALL || eventRegion(match.event) == region) && when (level) {
        MatchLevel.ALL -> true
        MatchLevel.IMPORTANT -> EventEligibility.important(match.event)
        MatchLevel.MAJOR -> EventEligibility.evidence(match.event)?.tier == EventTier.MAJOR
        MatchLevel.TIER_ONE -> EventEligibility.evidence(match.event)?.tier == EventTier.TIER_ONE
        MatchLevel.OTHER -> !EventEligibility.important(match.event)
    }
}

/** Time sorting is global, so the same event can have separate consecutive sections.
 * Priority/name sorting keeps an event together. Unknown times always stay at the end. */
fun matchGroups(matches: List<Match>, order: MatchOrder = MatchOrder.IMPORTANT_FIRST,
    now: Instant = Instant.now(), fallbackStamp: SourceStamp? = null): List<List<Match>> {
    fun time(match: Match) = match.startsAt ?: match.resultAt
    fun live(match: Match) = if (match.stamp != null || fallbackStamp != null)
        match.hasRecentLiveStatus(now, fallbackStamp) else match.status == MatchStatus.LIVE
    fun importance(group: List<Match>) = group.minOf { match ->
        when (EventEligibility.evidence(match.event)?.tier) { EventTier.MAJOR -> 0; EventTier.TIER_ONE -> 1; null -> 2 }
    }
    val chronological = compareBy<Match> { time(it) == null }.thenBy { time(it) }.thenBy { it.id }
    if (order == MatchOrder.TIME_ASC || order == MatchOrder.TIME_DESC) {
        val sorted = matches.sortedWith(if (order == MatchOrder.TIME_ASC) chronological
            else compareBy<Match> { time(it) == null }.thenByDescending { time(it) }.thenBy { it.id })
        val sections = mutableListOf<MutableList<Match>>()
        sorted.forEach { match ->
            if (sections.lastOrNull()?.firstOrNull()?.event?.id != match.event.id) sections.add(mutableListOf())
            sections.last().add(match)
        }
        return sections
    }
    val groups = matches.groupBy { it.event.id }.values.map {
        it.sortedWith(compareByDescending<Match>(::live).then(chronological))
    }
    val firstTime = compareBy<List<Match>> { group -> group.minOf { time(it) ?: Instant.MAX } }
    val names = compareBy<List<Match>, String>(String.CASE_INSENSITIVE_ORDER) { it.first().event.name }.thenBy { it.first().event.id }
    val priority = compareBy<List<Match>>(::importance)
    val liveFirst = compareByDescending<List<Match>> { it.any(::live) }
    return groups.sortedWith(when (order) {
        MatchOrder.IMPORTANT_FIRST -> priority.then(liveFirst).then(firstTime).then(names)
        MatchOrder.LIVE_FIRST -> liveFirst.then(priority).then(firstTime).then(names)
        MatchOrder.NAME -> names
        else -> firstTime.then(names)
    })
}
