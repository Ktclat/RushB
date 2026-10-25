package dev.matchscope.app.data

/** Attach a separately captured website table without changing the mobile record's age.
 * Map-statistics IDs and mobile map names are different namespaces. Match by map name
 * and verify every player/team/K-D total before attaching the two side breakdowns.
 */
fun Match.withSideStatistics(website: Match?): Match {
    if (website == null || website.id != id || website.left.id != left.id || website.right.id != right.id) return this
    val base = statGroups.ifEmpty { if (stats.isEmpty()) emptyList() else listOf(MatchStatGroup("all", null, MatchSide.BOTH, stats)) }
    val photos = (players.values.flatten() + base.flatMap { it.rows.mapNotNull(PlayerStat::player) }).associateBy { it.id }
    fun identity(rows: List<PlayerStat>) = rows.map { it.teamId to it.player?.id }
    fun totals(rows: List<PlayerStat>) = rows.associate { (it.teamId to it.player?.id) to it.kd?.replace(" ", "") }
    fun sameMap(a: MatchStatGroup, b: MatchStatGroup) = if (a.mapKey == "all") b.mapKey == "all"
        else a.mapName != null && a.mapName.equals(b.mapName, true)
    val merged = base.groupBy { it.mapKey }.values.flatMap { current ->
        val both = current.singleOrNull { it.side == MatchSide.BOTH } ?: return@flatMap current
        val source = website.statGroups.filter { sameMap(both, it) }
        if (source.size != 3 || source.map { it.side }.toSet() != MatchSide.entries.toSet()) return@flatMap current
        val expected = identity(both.rows)
        if (expected.isEmpty() || expected.any { it.second == null } || expected.distinct().size != expected.size ||
            source.any { identity(it.rows).size != expected.size || identity(it.rows).toSet() != expected.toSet() }) return@flatMap current
        val sourceBoth = source.single { it.side == MatchSide.BOTH }
        if (totals(both.rows).values.any { it == null } || totals(both.rows) != totals(sourceBoth.rows)) return@flatMap current
        source.sortedBy { it.side.ordinal }.map { group -> group.copy(mapKey = both.mapKey, mapName = both.mapName,
            rows = group.rows.map { row -> row.copy(player = row.player?.let { it.copy(photoUrl = photos[it.id]?.photoUrl ?: it.photoUrl) }) }) }
    }
    return copy(statGroups = merged)
}

fun Match.hasCompleteSideStatistics(): Boolean = statGroups.isNotEmpty() && statGroups.groupBy { it.mapKey }.values.all { groups ->
    groups.map { it.side }.toSet() == MatchSide.entries.toSet() && groups.all { it.rows.isNotEmpty() }
}
