package dev.matchscope.app.data

import java.time.Instant

internal data class SearchMatchGroup(val title: String, val matches: List<Match>)

/** Scheduled fixtures are nearest first; expired status must not be presented as live. */
internal fun searchMatchGroups(matches: List<Match>, now: Instant, fallbackStamp: SourceStamp): List<SearchMatchGroup> {
    val groups = matches.groupBy { match ->
        when {
            match.statusLabel(now, fallbackStamp) == "待更新" -> "待更新"
            match.status == MatchStatus.LIVE -> "进行中"
            match.status == MatchStatus.UPCOMING -> "即将开始"
            match.status == MatchStatus.FINISHED -> "近期赛果"
            else -> "延期与取消"
        }
    }
    return listOf("进行中", "即将开始", "待更新", "近期赛果", "延期与取消").mapNotNull { title ->
        groups[title]?.let { rows ->
            val sorted = if (title in setOf("进行中", "即将开始")) rows.sortedBy { it.startsAt ?: Instant.MAX }
                else rows.sortedByDescending { it.resultAt ?: it.startsAt ?: Instant.MIN }
            SearchMatchGroup(title, sorted)
        }
    }
}
