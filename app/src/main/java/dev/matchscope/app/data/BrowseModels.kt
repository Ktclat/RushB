package dev.matchscope.app.data

import java.time.LocalDate
import java.time.YearMonth

/** Prefer detail records, while retaining explicit index metadata absent from older caches. */
fun mergedEvents(events: List<Tournament>): List<Tournament> = events.groupBy { it.id }.values.map { versions ->
    versions.first().copy(tier = versions.firstNotNullOfOrNull { it.tier },
        startDate = versions.firstNotNullOfOrNull { it.startDate }, endDate = versions.firstNotNullOfOrNull { it.endDate },
        location = versions.firstNotNullOfOrNull { it.location?.takeIf(String::isNotBlank) },
        logoUrl = versions.firstNotNullOfOrNull { it.logoUrl }, bannerUrl = versions.firstNotNullOfOrNull { it.bannerUrl })
}

/** Enrich old profile caches by match identity, aligning the score to the displayed team IDs. */
fun resolveProfileMatch(profile: ProfileMatch, known: List<Match>): ProfileMatch {
    val id = profile.asMatch()?.id ?: return profile
    val match = known.firstOrNull { it.id == id && setOf(it.left.id, it.right.id) == setOf(profile.left.id, profile.right.id) } ?: return profile
    fun score(team: Team) = if (team.id == match.left.id) match.leftScore else match.rightScore
    return profile.copy(leftScore = profile.leftScore ?: score(profile.left), rightScore = profile.rightScore ?: score(profile.right),
        time = profile.time ?: match.startsAt ?: match.resultAt,
        status = if (match.status == MatchStatus.FINISHED) MatchStatus.FINISHED else profile.status,
        event = match.event.takeUnless { it.id.startsWith("unknown:") } ?: profile.event)
}

/** Positions are clipped to a visible week; dates themselves are calendar dates, not instants. */
data class EventSpan(val event: Tournament, val start: Int, val end: Int, val lane: Int)

fun eventSpans(events: List<Tournament>, weekStart: LocalDate): List<EventSpan> {
    val ends = mutableListOf<Int>()
    return events.filter { it.startDate != null && it.endDate != null && it.endDate >= weekStart && it.startDate <= weekStart.plusDays(6) }
        .sortedWith(compareBy<Tournament> { it.startDate }.thenBy { it.id }).map { event ->
            val start = (event.startDate!!.toEpochDay() - weekStart.toEpochDay()).toInt().coerceAtLeast(0)
            val end = (event.endDate!!.toEpochDay() - weekStart.toEpochDay()).toInt().coerceAtMost(6)
            val lane = ends.indexOfFirst { it < start }.takeIf { it >= 0 } ?: ends.size.also { ends.add(-1) }
            ends[lane] = end
            EventSpan(event, start, end, lane)
        }
}

fun eventsInMonth(events: List<Tournament>, month: YearMonth): List<Tournament> = events.filter {
    it.startDate != null && it.endDate != null && it.startDate <= month.atEndOfMonth() && it.endDate >= month.atDay(1)
}.sortedWith(compareBy<Tournament> { it.startDate }.thenBy { it.name })

/** Keep ranking systems separate, and never invent a rank from the current list position. */
fun orderedTeams(teams: List<Team>, rankings: List<RankingEntry>): List<Team> {
    val ranks = rankings.associate { it.team.id to it.rank }
    return teams.sortedWith(compareBy<Team> { ranks[it.id] ?: Int.MAX_VALUE }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id })
}

fun teamRegion(team: Team, regional: Map<VrsRegion, Feed<RankingEntry>>): String {
    // VRS names may recur for different rosters. Only use a regional entry when its identity is unambiguous.
    val regions = regional.filterKeys { it != VrsRegion.GLOBAL }.filterValues { feed -> feed.items.any { entry ->
        entry.team.id == team.id || entry.team.name.equals(team.name, true) && team.roster.size >= 3 &&
            entry.roster.map(String::lowercase).intersect(team.roster.map(String::lowercase).toSet()).size >= 3
    } }.keys
    if (regions.size == 1) return regions.single().label
    return when (team.region.lowercase()) {
        "europe", "欧洲", "denmark", "sweden", "norway", "finland", "france", "germany", "poland", "ukraine", "russia", "serbia", "bosnia and herzegovina", "united kingdom", "portugal", "spain", "kosovo", "bulgaria", "romania", "czech republic", "slovakia", "hungary", "turkey", "kazakhstan" -> "欧洲"
        "asia", "亚洲", "china", "mongolia", "japan", "korea", "south korea", "singapore", "indonesia", "thailand", "vietnam", "india", "australia", "new zealand", "oceania", "south africa", "israel", "saudi arabia" -> "亚洲"
        "americas", "美洲", "north america", "south america", "brazil", "argentina", "united states", "canada", "chile", "uruguay", "peru", "colombia", "mexico" -> "美洲"
        else -> "未分类"
    }
}
