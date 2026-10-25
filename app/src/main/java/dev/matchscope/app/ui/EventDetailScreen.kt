@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.matchscope.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.matchscope.app.data.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val eventLabels = mapOf("Group stage" to "小组赛", "Playoffs" to "淘汰赛", "Player share" to "选手奖金", "Club share" to "俱乐部分成", "VRS date" to "VRS 基准日期", "VRS weight" to "VRS 权重", "Event type" to "赛事类型")
internal fun bracketLabel(name: String) = when (name) {
    "Single Elimination Bracket" -> "淘汰赛"; "Group A" -> "A 组"; "Group B" -> "B 组"; "3rd Place Decider Match" -> "季军赛"
    "Semi-finals" -> "半决赛"; "Grand final" -> "总决赛"; "Quarter-finals" -> "四分之一决赛"; "Opening round" -> "首轮"
    "Winners' match" -> "胜者组决赛"; "Elimination match" -> "败者组首轮"; "Decider match" -> "出线决定战"; "Tiebreaker" -> "季军争夺战"
    else -> name
}

@Composable
internal fun EventDetailScreen(event: Tournament, state: ScopeState, vm: ScopeViewModel, open: (String) -> Unit) {
    var overviewGrid by rememberSaveable(event.id) { mutableStateOf(true) }
    var scheduleGrid by rememberSaveable(event.id) { mutableStateOf(false) }
    var bracketGrid by rememberSaveable(event.id) { mutableStateOf(true) }
    var statsGrid by rememberSaveable(event.id) { mutableStateOf(false) }
    var tab by rememberSaveable(event.id) { mutableStateOf("概览") }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val profile = state.eventProfiles[event.id]
    LazyColumn(state = list, modifier = Modifier.testTag("event-detail-list"), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { CardBlock { EventBanner(event) } }
        stickyHeader { Box(Modifier.fillMaxWidth().background(Background).padding(vertical = 4.dp)) { PageTabs(listOf("概览", "赛程", "对阵") + if (event.content.statistics.isNotEmpty()) listOf("数据") else emptyList(), tab) { next ->
            tab = next
            if (list.firstVisibleItemIndex > 1) scope.launch { list.scrollToItem(1) }
        } } }
        if (profile?.warning != null) item { UpdateWarning(if (profile.warning == "对阵更新失败") event.content.bracketStamps.values.minByOrNull { it.capturedAt } ?: profile.stamp else profile.stamp, state.zone, state.zoneLabel) }
        when (tab) {
            "概览" -> eventOverview(event, open)
            "数据" -> item { Column { LayoutHeading("选手数据", statsGrid) { statsGrid = it }; EventStatisticsPanel(event.content.statistics, statsGrid, open) } }
            "赛程" -> item { Column { LayoutHeading("比赛赛程", scheduleGrid) { scheduleGrid = it }; EventSchedule(event, state, vm::saveMatch, scheduleGrid) { vm.openMatch(it, open) } } }
            "对阵" -> item { Column { LayoutHeading("赛事对阵", bracketGrid) { bracketGrid = it }; EventBrackets(event.content.brackets, state.zone, state.allMatches, { slot -> slot.asMatch(event)?.let { vm.openMatch(it, open) } }, open, bracketGrid) } }
        }
        item { SourceLink(event.url) }
    }
}

private fun LazyListScope.eventOverview(event: Tournament, open: (String) -> Unit) {
    item { EventFacts("赛事信息", listOf("日期" to "${event.startDate ?: "—"} — ${event.endDate ?: "—"}", "地点" to (event.location ?: "—"),
        "参赛队伍" to (event.teamCount?.let { "$it 支" } ?: "—"), "总奖池" to (event.prize ?: "—")) +
        event.content.information.filter { it.first in eventLabels }.map { (label, value) -> eventLabels.getValue(label) to if (value == "Ranked") "VRS 计分赛事" else value }) }
    if (event.content.teams.isNotEmpty()) item { EventTeams(event.content.teams, open) }
    if (event.content.prizes.isNotEmpty()) item { EventPrizes(event.content.prizes, open) }
    if (event.content.formats.isNotEmpty()) {
        item { EventFacts("赛制", event.content.formats.map { (key, value) -> (eventLabels[key] ?: key) to value }) }
    }
    if (event.content.maps.isNotEmpty()) item { EventMaps(event.content.maps) }

}

internal fun eventSchedule(event: Tournament, known: List<Match>): List<Match> {
    val source = (event.content.matches + event.content.brackets.flatMap { it.rounds }.flatMap { it.slots }.mapNotNull { it.asMatch(event) }).distinctBy { it.id }
    val existing = known.filter { it.event.id == event.id }.associateBy { it.id }
    // Bracket times are scheduled times. A result-list timestamp must not replace them.
    return (source.map { bracket -> existing[bracket.id]?.let { it.copy(startsAt = it.startsAt ?: bracket.startsAt, format = if (it.format == "赛制待确认") bracket.format else it.format) } ?: bracket } +
        existing.values.filter { item -> source.none { it.id == item.id } }).sortedBy { it.startsAt ?: it.resultAt ?: Instant.MAX }
}

@Composable
internal fun EventSchedule(event: Tournament, state: ScopeState, save: (String) -> Unit, grid: Boolean = false, onMatch: (Match) -> Unit) {
    val matches = remember(event, state.allMatches) { eventSchedule(event, state.allMatches) }
    val days = remember(event, matches, state.zone) {
        val start = event.startDate; val end = event.endDate
        val range = if (start != null && end != null && !end.isBefore(start) && end.toEpochDay() - start.toEpochDay() < 366)
            (start.toEpochDay()..end.toEpochDay()).map(LocalDate::ofEpochDay) else emptyList()
        (range + matches.mapNotNull { it.day(state.zone) }).distinct().sorted()
    }
    var selected by rememberSaveable(event.id) { mutableStateOf(LocalDate.now(state.zone).toString()) }
    val day = days.firstOrNull { it.toString() == selected } ?: days.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        day?.let { selectedDay ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ActionIcon("back", "前一天", days.indexOf(selectedDay) > 0) { selected = days[days.indexOf(selectedDay) - 1].toString() }
                Box(Modifier.weight(1f)) { ChoiceMenu(selectedDay.format(DateTimeFormatter.ofPattern("M月d日")), days, { it.format(DateTimeFormatter.ofPattern("M月d日")) }) { selected = it.toString() } }
                ActionIcon("next", "后一天", days.indexOf(selectedDay) < days.lastIndex) { selected = days[days.indexOf(selectedDay) + 1].toString() }
            }
        }
        val today = matches.filter { it.day(state.zone) == day }
        if (today.isEmpty()) EmptyCard("当日暂无比赛")
        ContentGrid(today, if (grid) 2 else 1) { match -> if (grid) MiniMatchCard(match, state.zone) { onMatch(match) } else CardBlock { MatchRow(match, state, { onMatch(match) }, { save(match.id) }) } }
    }
}

@Composable
internal fun EventBrackets(brackets: List<EventBracket>, zone: ZoneId, known: List<Match>, onMatch: (EventSlot) -> Unit, open: (String) -> Unit, grid: Boolean = true) {
    val ordered = brackets.sortedBy { if (it.name.startsWith("3rd")) 1 else 0 }
    var selected by rememberSaveable(brackets.map { it.id }) { mutableStateOf(ordered.firstOrNull()?.id) }
    val bracket = ordered.firstOrNull { it.id == selected } ?: ordered.firstOrNull()
    if (bracket == null) { EmptyCard("对阵尚未公布"); return }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Chips(ordered.map { bracketLabel(it.name) }, bracketLabel(bracket.name)) { name -> selected = ordered.first { bracketLabel(it.name) == name }.id }
        Column(Modifier.testTag("event-bracket"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            bracket.rounds.sortedWith(compareBy<EventRound> { it.column }.thenBy { it.lane }).forEach { round ->
                SectionLabel(bracketLabel(round.name))
                ContentGrid(round.slots, if (grid) 2 else 1) { slot ->
                    val match = known.firstOrNull { it.id == slot.matchId }
                    fun score(team: Team?, fallback: Int?) = when (team?.id) { match?.left?.id -> match?.leftScore ?: fallback; match?.right?.id -> match?.rightScore ?: fallback; else -> fallback }
                    val shown = slot.copy(leftScore = score(slot.left, slot.leftScore), rightScore = score(slot.right, slot.rightScore), status = match?.status ?: slot.status)
                    Box(Modifier.height(120.dp)) { BracketMatchCard(shown, zone, onMatch, open) }
                }
            }
        }
    }
}

@Composable
private fun BracketMatchCard(slot: EventSlot, zone: ZoneId, onMatch: (EventSlot) -> Unit, open: (String) -> Unit) {
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(PanelRaised).clickable(enabled = slot.matchUrl != null) { onMatch(slot) }.padding(9.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth()) {
            Text(slot.time?.atZone(zone)?.format(DateTimeFormatter.ofPattern("MM.dd HH:mm")) ?: "时间待定", Modifier.weight(1f), fontSize = 9.sp, color = Quiet)
            Text(slot.format.orEmpty(), fontSize = 9.sp, color = Quiet)
        }
        listOf(Triple(slot.left, slot.leftLabel, slot.leftScore), Triple(slot.right, slot.rightLabel, slot.rightScore)).forEach { (team, label, score) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (team != null) Box(Modifier.clickable { open("team|${team.id}") }) { TeamLogo(team, 24.dp) } else Spacer(Modifier.width(24.dp))
                Text(team?.name ?: label, Modifier.weight(1f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(score?.toString() ?: "—", fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    color = if (score != null && slot.status == MatchStatus.FINISHED && score == maxOf(slot.leftScore ?: -1, slot.rightScore ?: -1)) Green else Quiet)
            }
        }
    }
}
