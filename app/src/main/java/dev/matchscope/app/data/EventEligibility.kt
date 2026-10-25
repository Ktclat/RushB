package dev.matchscope.app.data

import java.time.LocalDate
import java.time.YearMonth

enum class EventTier(val label: String) { MAJOR("Major"), TIER_ONE("Tier 1") }
data class EventTierEvidence(val tier: EventTier, val url: String, val checkedOn: LocalDate?)

/** Exact event identities with an explicit published classification. Never infer tiers from
 * prize money, brand names, featured/isBig flags, or a participant's current ranking.
 * Unknown events remain available in the full directory. See docs/EVENT-TIERS.md. */
object EventEligibility {
    private val checked = LocalDate.parse("2026-10-19")
    private const val valveTierList = "https://liquipedia.net/counterstrike/Valve_Tier_1_Events"
    private val verified = mapOf(
        "8244" to EventTierEvidence(EventTier.TIER_ONE, "https://pro.eslgaming.com/tour/zh/details/esl-pro-league-season-24/", checked),
        "8932" to EventTierEvidence(EventTier.TIER_ONE, valveTierList, checked),
        "8050" to EventTierEvidence(EventTier.TIER_ONE, "https://www.pglesports.com/cs2/pgl-masters-bucharest-2026/", checked),
        "8245" to EventTierEvidence(EventTier.TIER_ONE, "https://liquipedia.net/counterstrike/Intel_Extreme_Masters/2026/Beijing", checked),
        "9303" to EventTierEvidence(EventTier.MAJOR, "https://www.hltv.org/events/9303/-", checked),
        "9304" to EventTierEvidence(EventTier.MAJOR, "https://www.hltv.org/events/9304/-", checked),
        "8302" to EventTierEvidence(EventTier.MAJOR, "https://www.hltv.org/events/8302/-", checked),
    )
    fun evidence(event: Tournament): EventTierEvidence? = event.tier?.let {
        EventTierEvidence(it, event.url, null)
    } ?: verified[event.id]
    fun important(event: Tournament) = evidence(event) != null
}

enum class EventOrder(val label: String) { DATE_ASC("日期升序"), DATE_DESC("日期降序"), NAME("赛事名称") }
enum class EventPhase(val label: String) { ALL("全部状态"), ONGOING("进行中"), UPCOMING("未开始"), FINISHED("已结束") }

fun calendarEvents(events: List<Tournament>, month: YearMonth, today: LocalDate, importantOnly: Boolean = true,
    query: String = "", phase: EventPhase = EventPhase.ALL, order: EventOrder = EventOrder.DATE_ASC,
    day: LocalDate? = null, followedTeamIds: Set<String>? = null): List<Tournament> {
    val result = eventsInMonth(events, month).filter { event ->
        (!importantOnly || EventEligibility.important(event)) && event.name.contains(query.trim(), true) &&
            (day == null || event.startDate!! <= day && event.endDate!! >= day) &&
            (followedTeamIds == null || event.content.teams.any { it.id in followedTeamIds }) && when (phase) {
                EventPhase.ALL -> true
                EventPhase.ONGOING -> event.startDate!! <= today && event.endDate!! >= today
                EventPhase.UPCOMING -> event.startDate!! > today
                EventPhase.FINISHED -> event.endDate!! < today
            }
    }
    return when (order) {
        EventOrder.DATE_ASC -> result
        EventOrder.DATE_DESC -> result.sortedWith(compareByDescending<Tournament> { it.startDate }.thenBy { it.name })
        EventOrder.NAME -> result.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
}
