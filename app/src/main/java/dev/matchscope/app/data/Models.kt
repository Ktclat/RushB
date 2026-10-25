package dev.matchscope.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class MatchStatus(val label: String) { UPCOMING("未开始"), LIVE("进行中"), FINISHED("已结束"), POSTPONED("已延期"), CANCELLED("已取消") }
enum class DataOrigin { SNAPSHOT, NETWORK, CACHE }

data class SourceStamp(
    val url: String,
    val capturedAt: Instant,
    val origin: DataOrigin,
    val publishedOn: String? = null,
)
data class Feed<T>(val items: List<T>, val stamp: SourceStamp, val warning: String? = null)
data class Team(
    val id: String,
    val name: String,
    val region: String = "未分类",
    val roster: List<String> = emptyList(),
    val url: String = "https://www.hltv.org/team/$id/-",
    val logoUrl: String? = null,
) { val monogram: String get() = teamFallbackLabel(name) }

/** A readable fallback, not an official team abbreviation. */
internal fun teamFallbackLabel(name: String): String {
    val words = name.trim().split(Regex("[^\\p{L}\\p{N}]+" )).filter(String::isNotEmpty)
    if (words.isEmpty()) return "?"
    val label = if (words.size == 1) words.first().take(4) else words.map { it.first() }.joinToString("").take(4)
    return label.uppercase(java.util.Locale.ROOT)
}
data class Tournament(
    val id: String,
    val name: String,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val location: String? = null,
    val prize: String? = null,
    val teamCount: Int? = null,
    val url: String = "https://www.hltv.org/events/$id/-",
    val logoUrl: String? = null,
    val content: EventContent = EventContent(),
    val bannerUrl: String? = null,
    val tier: EventTier? = null,
)
data class MapScore(val name: String, val left: Int? = null, val right: Int? = null, val halves: String? = null, val statsUrl: String? = null)
data class PlayerRef(val id: String, val name: String, val url: String = "https://www.hltv.org/player/$id/-", val photoUrl: String? = null)
data class TeamProfile(val team: Team, val players: List<PlayerRef>, val worldRanking: Int? = null, val coach: String? = null, val content: ProfileContent = ProfileContent(complete = false))
enum class MatchSide { BOTH, T, CT }
data class MatchStatGroup(val mapKey: String, val mapName: String?, val side: MatchSide, val rows: List<PlayerStat>, val statsUrl: String? = null)
data class PlayerStat(val name: String, val teamId: String, val kd: String?, val adr: String?, val kast: String?, val rating: String?, val ratingVersion: String?, val player: PlayerRef? = null,
    val swing: String? = null, val ecoKd: String? = null, val ecoAdr: String? = null, val ecoKast: String? = null)
data class ProfileMatch(val left: Team, val right: Team, val event: Tournament, val status: MatchStatus,
    val time: Instant? = null, val leftScore: Int? = null, val rightScore: Int? = null, val matchUrl: String? = null, val statisticsUrl: String? = null) {
    val key: String get() = matchUrl ?: statisticsUrl ?: "${left.id}:${right.id}:$time:${event.id}"
    fun asMatch(): Match? {
        val url = matchUrl ?: return null
        val id = Regex("/matches/(\\d+)(?:/|$)").find(url)?.groupValues?.get(1) ?: return null
        return Match(id, left, right, event, status, startsAt = time.takeUnless { status == MatchStatus.FINISHED }, resultAt = time.takeIf { status == MatchStatus.FINISHED }, leftScore = leftScore, rightScore = rightScore, url = url)
    }
}
data class ProfileEvent(val event: Tournament, val placement: String? = null, val team: Team? = null, val upcoming: Boolean = false, val category: String? = null, val statistics: List<Pair<String, String>> = emptyList())
data class CareerTeam(val team: Team, val startDate: LocalDate? = null, val endDate: LocalDate? = null, val period: String, val trophies: List<Tournament> = emptyList())
data class RosterMember(val player: PlayerRef, val country: String? = null, val status: String? = null, val tenure: String? = null, val maps: Int? = null, val rating: String? = null, val roles: List<String> = emptyList())
data class RoleStat(val label: String, val score: Int)
data class MapPerformance(val name: String, val winRate: String, val wins: Int? = null, val draws: Int? = null, val losses: Int? = null, val statistics: List<Pair<String, String>> = emptyList())
data class ProfileContent(
    val matches: List<ProfileMatch> = emptyList(), val events: List<ProfileEvent> = emptyList(),
    val news: List<NewsItem> = emptyList(), val career: List<CareerTeam> = emptyList(),
    val roles: List<RoleStat> = emptyList(), val roster: List<RosterMember> = emptyList(),
    val maps: List<MapPerformance> = emptyList(), val summary: List<Pair<String, String>> = emptyList(),
    val moreMatchesUrl: String? = null, val moreEventsUrl: String? = null, val complete: Boolean = true,
    val schemaVersion: Int = 0, val honors: List<ProfileHonor> = emptyList(),
    val teammates: List<ProfilePeer> = emptyList(), val similarPlayers: List<ProfilePeer> = emptyList(),
    val earnings: List<TeamEarning> = emptyList(), val newsStamp: SourceStamp? = null,
)
data class PlayerProfile(
    val player: PlayerRef,
    val realName: String? = null,
    val country: String? = null,
    val age: Int? = null,
    val team: Team? = null,
    val statistics: List<Pair<String, String>> = emptyList(),
    val statisticsPeriod: String? = null,
    val content: ProfileContent = ProfileContent(complete = false),
)
data class Sourced<T>(val value: T, val stamp: SourceStamp, val warning: String? = null)
data class Match(
    val id: String,
    val left: Team,
    val right: Team,
    val event: Tournament,
    val status: MatchStatus,
    val format: String = "赛制待确认",
    val startsAt: Instant? = null,
    val sourceDate: LocalDate? = null,
    val leftScore: Int? = null,
    val rightScore: Int? = null,
    val maps: List<MapScore> = emptyList(),
    val veto: List<String> = emptyList(),
    val stats: List<PlayerStat> = emptyList(),
    val url: String = "https://www.hltv.org/matches/$id/-",
    val players: Map<String, List<PlayerRef>> = emptyMap(),
    val stamp: SourceStamp? = null,
    val detailed: Boolean = false,
    val note: String? = null,
    val resultAt: Instant? = null,
    val statGroups: List<MatchStatGroup> = emptyList(),
    val insights: MatchInsights = MatchInsights(complete = false),
    val duels: Map<String, MatchDuels> = emptyMap(),
    val stage: String? = null,
    val liveScore: LiveMatchScore? = null,
) {
    fun day(zone: ZoneId): LocalDate? = (startsAt ?: resultAt)?.atZone(zone)?.toLocalDate() ?: sourceDate ?: if(status == MatchStatus.LIVE) stamp?.capturedAt?.atZone(zone)?.toLocalDate() else null
    val score: String get() = if (status == MatchStatus.LIVE && liveScore != null) "${liveScore.leftMaps} : ${liveScore.rightMaps}"
        else if (leftScore != null && rightScore != null) "$leftScore : $rightScore" else if(status == MatchStatus.UPCOMING) "VS" else "— : —"
}

/** Older mobile caches stored a match's placeholder title as a team name. */
fun Match.normalizePlaceholders(): Match {
    val pending = listOf(left, right).filter { it.id.startsWith("pending:") }
    val context = stage ?: pending.map { it.name }.filterNot { it in listOf("待定", "TBD", "TBA") }
        .distinct().joinToString(" · ").takeIf(String::isNotBlank)
    return copy(left = if (left in pending) left.copy(name = "待定", logoUrl = null) else left,
        right = if (right in pending) right.copy(name = "待定", logoUrl = null) else right,
        stage = context?.removePrefix("${event.name} - ")?.trim()?.takeIf(String::isNotBlank))
}

/** A pre-start capture cannot confirm the status after its scheduled start. */
fun Match.statusLabel(now: Instant, fallbackStamp: SourceStamp? = null): String {
    val captured = (stamp ?: fallbackStamp)?.capturedAt
    return if (status == MatchStatus.LIVE && !hasRecentLiveStatus(now, fallbackStamp)) "待更新"
    else if (status == MatchStatus.UPCOMING && startsAt != null && captured != null &&
        captured < startsAt && now >= startsAt) "待更新" else status.label
}
data class RankingEntry(val rank: Int, val team: Team, val points: Int, val change: Int? = null, val roster: List<String> = emptyList())
data class NewsItem(val title: String, val url: String, val publishedAt: String, val imageUrl: String? = null, val hasCachedBody: Boolean = false)

open class SourceUnavailable(message: String, val cooldownMs: Long = 0, val sharedCooldown: Boolean = false) : Exception(message)

/** A previously opened live detail must not hide a newer final score from the feed. */
fun preferredMatchRecords(records: List<Match>): List<Match> = records.groupBy { it.id }.values.map { copies ->
    val order = compareBy<Match> { it.stamp?.capturedAt ?: Instant.MIN }.thenBy { it.detailed }
    val latest = copies.maxWith(order)
    val detail = copies.filter { it.detailed }.maxWithOrNull(order)
    // Keep completed statistics at their own capture time until a newer result contradicts them.
    val selected = if(detail != null && detail.status == MatchStatus.FINISHED && latest.status == MatchStatus.FINISHED && detail.score == latest.score) detail else latest
    // Profile rows omit event/format fields. Missing fields do not contradict an exact match detail.
    val sameTeams = copies.filter { setOf(it.left.id, it.right.id) == setOf(selected.left.id, selected.right.id) }
    selected.copy(
        liveScore = selected.compatibleLiveScore(sameTeams.mapNotNull { record -> record.liveScore?.let { live ->
            if (record.left.id == selected.left.id) live else live.copy(leftMaps = live.rightMaps, rightMaps = live.leftMaps,
                leftRounds = live.rightRounds, rightRounds = live.leftRounds)
        } }.maxByOrNull { it.stamp.capturedAt }),
        event = if (selected.event.hasIdentity()) selected.event else sameTeams.filter { it.event.hasIdentity() }.maxWithOrNull(order)?.event ?: selected.event,
        format = if (selected.format != "赛制待确认" && selected.format.isNotBlank()) selected.format else
            sameTeams.filter { Regex("BO[1-9][0-9]?").matches(it.format) }.maxWithOrNull(order)?.format ?: selected.format,
    )
}

fun visibleMatches(matches: List<Match>, date: LocalDate, zone: ZoneId, status: MatchStatus?, followed: Set<String>? = null): List<Match> =
    matches.filter { it.day(zone) == date && (status == null || it.status == status) &&
        (followed == null || it.id in followed) }.sortedBy { it.startsAt ?: it.resultAt ?: Instant.MAX }
