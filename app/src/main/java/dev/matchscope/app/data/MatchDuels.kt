package dev.matchscope.app.data

/** Only played, fully covered maps may contribute to a series total. */
internal fun Match.withDuelTotals(): Match {
    if ("all" in duels || status != MatchStatus.FINISHED) return this
    val leftWins = leftScore ?: return this
    val rightWins = rightScore ?: return this
    val played = maps.filter { it.left != null && it.right != null && it.left >= 0 && it.right >= 0 && it.left != it.right }
    if (played.isEmpty() || played.map { it.name.lowercase() }.distinct().size != played.size ||
        played.count { it.left!! > it.right!! } != leftWins || played.count { it.right!! > it.left!! } != rightWins) return this
    val sources = played.map { map -> duels[map.name.lowercase()] ?: return this }
    // Never combine independently aged/source captures into an apparently current total.
    if (sources.map { it.sourceUrl }.distinct().size != 1) return this
    val totals = DuelMetric.entries.mapNotNull { metric ->
        val parts = sources.map { source -> source.matrices.singleOrNull { it.metric == metric } ?: return@mapNotNull null }
        val first = parts.first()
        val rows = first.rowPlayers.map { it.id }.toSet()
        val columns = first.columnPlayers.map { it.id }.toSet()
        val aligned = parts.map { part ->
            when {
                part.rowPlayers.map { it.id }.toSet() == rows && part.columnPlayers.map { it.id }.toSet() == columns -> part
                part.rowPlayers.map { it.id }.toSet() == columns && part.columnPlayers.map { it.id }.toSet() == rows -> part.transposed()
                else -> return@mapNotNull null
            }
        }
        if (aligned.any { !it.hasCompleteCells() }) return@mapNotNull null
        val indexed = aligned.map { part -> part.cells.associateBy { it.rowPlayerId to it.columnPlayerId } }
        val cells = first.cells.map { cell ->
            val entries = indexed.map { it.getValue(cell.rowPlayerId to cell.columnPlayerId) }
            val kills = entries.sumOf { it.rowKills.toLong() }
            val deaths = entries.sumOf { it.columnKills.toLong() }
            if (kills > Int.MAX_VALUE || deaths > Int.MAX_VALUE) return@mapNotNull null
            cell.copy(rowKills = kills.toInt(), columnKills = deaths.toInt())
        }
        first.copy(cells = cells)
    }
    return if (totals.isEmpty()) this else copy(duels = duels + ("all" to MatchDuels(sources.first().sourceUrl, totals)))
}

internal fun DuelMatrix.transposed(): DuelMatrix = copy(rowPlayers = columnPlayers, columnPlayers = rowPlayers,
    cells = cells.map { DuelCell(it.columnPlayerId, it.rowPlayerId, it.columnKills, it.rowKills) })

internal fun DuelMatrix.hasCompleteCells(): Boolean {
    val rows = rowPlayers.map { it.id }
    val columns = columnPlayers.map { it.id }
    if (rows.isEmpty() || columns.isEmpty() || rows.distinct().size != rows.size || columns.distinct().size != columns.size ||
        rows.any { it in columns }) return false
    val expected = rows.flatMap { row -> columns.map { row to it } }.toSet()
    return cells.size == expected.size && cells.map { it.rowPlayerId to it.columnPlayerId }.toSet() == expected &&
        cells.all { it.rowKills >= 0 && it.columnKills >= 0 }
}

internal data class DuelMapOption(val key: String, val name: String?, val score: MapScore? = null)

/** Matrix availability is independent of scoreboard groups and Both/T/CT selection. */
internal fun Match.duelMapOptions(): List<DuelMapOption> {
    val available = duels.filterValues { it.matrices.any { matrix -> matrix.rowPlayers.isNotEmpty() && matrix.columnPlayers.isNotEmpty() && matrix.cells.isNotEmpty() } }
    return buildList {
        if ("all" in available) add(DuelMapOption("all", null))
        maps.forEach { map -> if (map.name.lowercase() in available) add(DuelMapOption(map.name.lowercase(), map.name, map)) }
        available.keys.filter { key -> none { it.key == key } }.forEach { key ->
            val name = statGroups.firstOrNull { it.mapKey == key }?.mapName ?: key
            add(DuelMapOption(key, name, maps.firstOrNull { it.name.equals(name, true) }))
        }
    }.distinctBy { it.key }
}
