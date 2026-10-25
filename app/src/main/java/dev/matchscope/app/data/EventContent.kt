package dev.matchscope.app.data

import java.time.Instant

data class EventPrize(val place: String, val team: Team?, val playerPrize: String?, val clubPrize: String?)
data class EventEntry(val slotId: String, val loser: Boolean = false)
data class EventSlot(
    val id: String, val left: Team?, val right: Team?,
    val leftLabel: String = "待定", val rightLabel: String = "待定",
    val leftScore: Int? = null, val rightScore: Int? = null,
    val matchId: String? = null, val matchUrl: String? = null,
    val time: Instant? = null, val format: String? = null,
    val status: MatchStatus = MatchStatus.UPCOMING,
    val entries: List<EventEntry> = emptyList(),
) {
    fun asMatch(event: Tournament): Match? {
        if (matchId == null || matchUrl == null || left == null || right == null) return null
        return Match(matchId, left, right, event.copy(content = EventContent()), status,
            format = format ?: "赛制待确认", startsAt = time, leftScore = leftScore, rightScore = rightScore, url = matchUrl)
    }
}
data class EventRound(val name: String, val column: Int, val lane: Int = 0, val slots: List<EventSlot>)
data class EventBracket(val id: String, val name: String, val rounds: List<EventRound>)
data class EventContent(
    val teams: List<Team> = emptyList(), val prizes: List<EventPrize> = emptyList(),
    val brackets: List<EventBracket> = emptyList(), val formats: List<Pair<String, String>> = emptyList(),
    val information: List<Pair<String, String>> = emptyList(), val maps: List<String> = emptyList(),
    val matchesUrl: String? = null, val resultsUrl: String? = null, val complete: Boolean = false,
    val matches: List<Match> = emptyList(), val bracketLinks: List<Pair<String, String>> = emptyList(),
    val schemaVersion: Int = 0, val statistics: List<EventStatistics> = emptyList(),
    val bracketStamps: Map<String, SourceStamp> = emptyMap(),
)
