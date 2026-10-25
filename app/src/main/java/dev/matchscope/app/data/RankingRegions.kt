package dev.matchscope.app.data

import java.time.LocalDate

/** Only complete editions can supply a global ranking with regional membership. */
fun completeVrsEditions(names: List<String>): List<String> {
    val pattern = Regex("standings_(global|europe|americas|asia)_(\\d{4}_\\d{2}_\\d{2})\\.md")
    return names.mapNotNull { pattern.matchEntire(it) }
        .groupBy { it.groupValues[2] }
        .filter { (date, files) ->
            runCatching { LocalDate.parse(date.replace('_', '-')) }.isSuccess &&
                files.map { it.groupValues[1] }.toSet() == VrsRegion.entries.map { it.key }.toSet()
        }.keys.sortedDescending()
}

fun rankingRegion(entry: RankingEntry, selected: VrsRegion, feeds: Map<VrsRegion, Feed<RankingEntry>>): String {
    if (selected != VrsRegion.GLOBAL) return selected.label
    val edition = feeds[VrsRegion.GLOBAL]?.stamp?.publishedOn ?: return "未分类"
    val roster = entry.roster.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
    val regions = feeds.filterKeys { it != VrsRegion.GLOBAL }.filterValues { feed ->
        feed.stamp.publishedOn == edition && feed.items.any {
            val candidate = it.roster.map { name -> name.lowercase(java.util.Locale.ROOT) }.toSet()
            it.team.name.equals(entry.team.name, true) && roster.isNotEmpty() &&
                (candidate == roster || roster.size >= 3 && candidate.intersect(roster).size >= 3)
        }
    }.keys
    return regions.singleOrNull()?.label ?: "未分类"
}

/** Match the ranking's roster, not the organization's home country. */
fun hltvRankingRegion(entry: RankingEntry, feeds: Map<VrsRegion, Feed<RankingEntry>>): String {
    val global = feeds[VrsRegion.GLOBAL] ?: return "未分类"
    val roster = (entry.roster.ifEmpty { entry.team.roster }).map { it.lowercase(java.util.Locale.ROOT) }.toSet()
    val candidates = global.items.filter { candidate ->
        val members = (candidate.roster.ifEmpty { candidate.team.roster }).map { it.lowercase(java.util.Locale.ROOT) }.toSet()
        (candidate.team.id == entry.team.id || candidate.team.name.equals(entry.team.name, true)) &&
            roster.size >= 3 && members.intersect(roster).size >= 3
    }
    return candidates.singleOrNull()?.let { rankingRegion(it, VrsRegion.GLOBAL, feeds) } ?: "未分类"
}
