package dev.matchscope.app.data

import java.time.Duration
import java.time.Instant

internal const val LIVE_FRESH_SECONDS = 60L

internal fun Match.hasRecentLiveStatus(now: Instant, fallbackStamp: SourceStamp? = null): Boolean {
    val captured = liveScore?.stamp ?: stamp ?: fallbackStamp ?: return false
    return status == MatchStatus.LIVE && captured.origin != DataOrigin.SNAPSHOT &&
        Duration.between(captured.capturedAt, now).seconds in 0 until LIVE_FRESH_SECONDS
}

/** Detail completeness does not make a live score or a scheduled fixture permanently fresh. */
internal fun Match.canReuseDetails(now: Instant): Boolean {
    val captured = stamp ?: return false
    if (captured.origin == DataOrigin.SNAPSHOT || !captured.url.contains("/mobile/") || note != null || !detailed || !insights.complete) return false
    if (status == MatchStatus.FINISHED && statGroups.isEmpty()) return false
    if (status == MatchStatus.UPCOMING && startsAt?.let { !now.isBefore(it) && captured.capturedAt.isBefore(it) } == true) return false
    val age = Duration.between(captured.capturedAt, now).seconds
    val ttl = when (status) {
        MatchStatus.LIVE -> LIVE_FRESH_SECONDS
        MatchStatus.UPCOMING, MatchStatus.POSTPONED -> 300L
        MatchStatus.FINISHED -> if (resultAt?.let { Duration.between(it, now).toHours() >= 24 } == true) 21_600L else 900L
        MatchStatus.CANCELLED -> 21_600L
    }
    return age in 0 until ttl
}
