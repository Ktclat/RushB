package dev.matchscope.app.data

import org.jsoup.Jsoup
import java.time.Instant
import java.time.Duration

data class LiveMatchScore(val leftMaps: Int, val rightMaps: Int, val leftRounds: Int? = null, val rightRounds: Int? = null,
    val mapName: String? = null, val stamp: SourceStamp)

/** MR12 with MR3 overtime. A completed map's last round score is not a new live map. */
internal fun completedCs2Map(left: Int, right: Int): Boolean {
    val high = maxOf(left, right); val low = minOf(left, right)
    return high == 13 && low <= 11 || high >= 16 && (high - 16) % 3 == 0 && low <= high - 2
}

internal fun LiveMatchScore.isFresh(now: Instant): Boolean = stamp.origin != DataOrigin.SNAPSHOT &&
    Duration.between(stamp.capturedAt, now).seconds in 0 until LIVE_FRESH_SECONDS

internal fun Match.displayScore(now: Instant): String {
    val live = liveScore?.takeIf { status == MatchStatus.LIVE && it.isFresh(now) }
    return if (live?.leftRounds != null && live.rightRounds != null) "${live.leftRounds} (${live.leftMaps}) : ${live.rightRounds} (${live.rightMaps})"
        else score
}

internal fun Match.compatibleLiveScore(candidate: LiveMatchScore?): LiveMatchScore? = candidate?.takeIf {
    status == MatchStatus.LIVE && (leftScore ?: 0) <= it.leftMaps && (rightScore ?: 0) <= it.rightMaps
}

internal object LiveScoreParser {
    fun parse(html: String, match: Match, stamp: SourceStamp): LiveMatchScore? {
        val doc = Jsoup.parse(html, stamp.url)
        if (doc.select(".match-wrapper[data-match-id]").isEmpty()) throw SourceUnavailable("比分列表未载入", 60_000)
        val row = doc.select(".match-wrapper[data-match-id]").firstOrNull { it.attr("data-match-id") == match.id } ?: return null
        if (row.attr("live") != "true") return null
        if (setOf(row.attr("team1"), row.attr("team2")) != setOf(match.left.id, match.right.id)) throw SourceUnavailable("比分战队不匹配", 60_000)
        fun score(attribute: String, id: String): Int? = row.select("[$attribute][data-livescore-team]")
            .firstOrNull { it.attr("data-livescore-team") == id }?.text()?.trim()?.toIntOrNull()?.takeIf { it in 0..100 }
        val leftMaps = score("data-livescore-maps-won-for", match.left.id) ?: return null
        val rightMaps = score("data-livescore-maps-won-for", match.right.id) ?: return null
        val left = score("data-livescore-current-map-score", match.left.id)
        val right = score("data-livescore-current-map-score", match.right.id)
        val underway = left != null && right != null && left + right > 0 && !completedCs2Map(left, right)
        val map = match.maps.getOrNull(leftMaps + rightMaps)?.name?.takeUnless { it.equals("TBA", true) || it.equals("Default", true) }
        return LiveMatchScore(leftMaps, rightMaps, left.takeIf { underway }, right.takeIf { underway }, map.takeIf { underway }, stamp)
    }
}
