package dev.matchscope.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.matchscope.app.data.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first

@Composable
internal fun eventColor(id: String): Color = LocalRushColors.current.events.let { it[Math.floorMod(id.hashCode(), it.size)] }
@Composable
internal fun regionColor(region: String): Color = LocalRushColors.current.let {
    when (region) { "欧洲" -> it.europe; "亚洲" -> it.asia; "美洲" -> it.americas; else -> it.quiet }
}

@Composable
internal fun CalendarScreen(state: ScopeState, vm: ScopeViewModel, open: (String) -> Unit, selectDay: (LocalDate) -> Unit) {
    var monthString by rememberSaveable { mutableStateOf(YearMonth.now(state.zone).toString()) }
    var view by rememberSaveable { mutableStateOf("重点") }
    var query by rememberSaveable { mutableStateOf("") }
    var phase by rememberSaveable { mutableStateOf(EventPhase.ALL.name) }
    var order by rememberSaveable { mutableStateOf(EventOrder.DATE_ASC.name) }
    var selectedDay by rememberSaveable(monthString) { mutableStateOf<String?>(null) }
    var calendarExpanded by rememberSaveable { mutableStateOf(true) }
    val month = YearMonth.parse(monthString)
    val today = LocalDate.now(state.zone)
    val directory = remember(state.events, state.allMatches) { state.events.map { event ->
        event.copy(content = event.content.copy(teams = (event.content.teams + state.allMatches.filter { it.event.id == event.id }
            .flatMap { listOf(it.left, it.right) }).filterNot { it.id.startsWith("pending:") }.distinctBy { it.id }))
    } }
    val events = remember(directory, month, today, view, query, phase, order, selectedDay, state.followedTeams) {
        calendarEvents(directory, month, today, view == "重点", query, EventPhase.valueOf(phase), EventOrder.valueOf(order),
            selectedDay?.let(LocalDate::parse), state.followedTeams.takeIf { view == "关注战队" })
    }
    val active = LocalRootActive.current
    DisposableEffect(vm, active) { vm.calendarVisible(active); onDispose { vm.calendarVisible(false) } }
    LaunchedEffect(active) { if (active) vm.restoreCalendarDetails() }
    LazyColumn(contentPadding = rootPagePadding, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("calendar-list")) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${month.year}年 ${month.monthValue}月", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            TextButton({ monthString = YearMonth.from(today).toString(); selectedDay = null }) { Text("本月", fontSize = 11.sp) }
            ActionIcon("refresh", "刷新赛事", !state.busy) { vm.refreshEvents() }
            ActionIcon("back", "上个月") { monthString = month.minusMonths(1).toString() }
            ActionIcon("next", "下个月") { monthString = month.plusMonths(1).toString() }
        } }
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("重点赛事月历", Modifier.weight(1f), fontSize = 11.sp, color = Quiet)
            TextButton({ calendarExpanded = !calendarExpanded }) { Text(if (calendarExpanded) "收起月历" else "展开月历", fontSize = 11.sp) }
        } }
        item { AnimatedVisibility(calendarExpanded) { AnimatedContent(month, label = "calendar-month") { shown ->
            EventMonth(shown, eventsInMonth(directory, shown).filter(EventEligibility::important), today,
                { day -> selectedDay = day.toString().takeUnless { it == selectedDay } }, open, selectedDay?.let(LocalDate::parse))
        } } }
        item {
            Text("列表筛选", Modifier.padding(top = 4.dp, bottom = 6.dp), fontSize = 11.sp, color = Quiet)
            PageTabs(listOf("重点", "全部", "关注战队"), view) { view = it }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(selectedDay?.let { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("M月d日")) } ?: "本月赛事", Modifier.weight(1f), fontSize = 12.sp, color = Quiet)
                Text("${events.size} 项", fontSize = 11.sp, color = Quiet)
                ChoiceMenu(EventPhase.valueOf(phase).label, EventPhase.entries, { it.label }) { phase = it.name }
                ChoiceMenu(EventOrder.valueOf(order).label, EventOrder.entries, { it.label }) { order = it.name }
            }
            selectedDay?.let { date -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ selectedDay = null }) { Text("显示整月") }
                TextButton({ selectDay(LocalDate.parse(date)) }) { Text("查看当日比赛 ›") }
            } }
            SearchField(query, if (selectedDay == null) "搜索本月赛事" else "搜索当日赛事") { query = it }
        }
        if (events.isEmpty()) item { EmptyCard("没有符合条件的赛事", action = "查看全部") { view = "全部"; query = ""; phase = EventPhase.ALL.name; selectedDay = null } }
        items(events, key = { it.id }) { event ->
            LaunchedEffect(event.id) { vm.queueEventParticipants(event.id) }
            CalendarEventCard(event, event.content.teams.map(state::displayTeam)) { open("event|${event.id}") }
        }
    }
}

@Composable
internal fun EventMonth(month: YearMonth, events: List<Tournament>, today: LocalDate, selectDay: (LocalDate) -> Unit, open: (String) -> Unit, selected: LocalDate? = null) {
    CardBlock {
        Column(Modifier.padding(8.dp)) {
            Row { listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { i, text ->
                Text(text, Modifier.weight(1f).padding(vertical = 9.dp), fontSize = 11.sp, color = if (i >= 5) Red else Quiet, textAlign = TextAlign.Center)
            } }
            val offset = month.atDay(1).dayOfWeek.value - 1
            val weeks = (offset + month.lengthOfMonth() + 6) / 7
            repeat(weeks) { week ->
                val start = month.atDay(1).minusDays(offset.toLong()).plusWeeks(week.toLong())
                val spans = eventSpans(events, start)
                Row {
                    repeat(7) { day ->
                        val date = start.plusDays(day.toLong())
                        Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (date == selected) AccentSurface else if (date == today) Subtle else Color.Transparent)
                            .semantics { contentDescription = "日历日期 $date" }
                            .clickable(enabled = YearMonth.from(date) == month) { selectDay(date) }, contentAlignment = Alignment.Center) {
                            if (YearMonth.from(date) == month) Text(date.dayOfMonth.toString(), fontSize = 14.sp, color = if (date == today || date == selected) Accent else Ink,
                                fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
                val lanes = (spans.maxOfOrNull { it.lane } ?: -1) + 1
                BoxWithConstraints(Modifier.fillMaxWidth().height((lanes * 18 + 5).dp)) {
                    spans.forEach { span ->
                        Box(Modifier.offset(x = maxWidth * (span.start / 7f), y = (span.lane * 18).dp)
                            .width(maxWidth * ((span.end - span.start + 1) / 7f)).height(15.dp).padding(horizontal = 1.dp)
                            .clip(RoundedCornerShape(3.dp)).background(eventColor(span.event.id))
                            .clickable { open("event|${span.event.id}") }
                            .semantics { contentDescription = "${span.event.name} ${span.event.startDate} 至 ${span.event.endDate}" }, contentAlignment = Alignment.CenterStart) {
                            val title = span.event.name.removeSuffix(" ${month.year}")
                            val shortTitle = title.replace("ESL Pro League Season ", "EPL S").replace("Thunderpick World Championship", "TWC")
                                .replace("PGL Masters Bucharest", "PGL Masters").replace("IEM Beijing", "IEM")
                            Text(if (span.end - span.start < 2) shortTitle else title, Modifier.padding(horizontal = 4.dp),
                                fontSize = 9.sp, lineHeight = 11.sp, color = Background, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CalendarEventCard(event: Tournament, teams: List<Team>, onOpen: () -> Unit) {
    val color = eventColor(event.id)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = .07f))
        .border(1.dp, color.copy(alpha = .3f), RoundedCornerShape(8.dp)).clickable(onClick = onOpen).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(4.dp).height(34.dp).clip(RoundedCornerShape(4.dp)).background(color))
            EventLogo(event, 34.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(event.name, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                val format = DateTimeFormatter.ofPattern("MM.dd")
                Text(listOfNotNull("${event.startDate?.format(format) ?: "—"} — ${event.endDate?.format(format) ?: "—"}", EventEligibility.evidence(event)?.tier?.label).joinToString(" · "), fontSize = 11.sp, color = color)
            }
            Glyph("next", color, 16.dp)
        }
        if (teams.isNotEmpty()) ContentGrid(teams, 8) { team -> TeamLogo(team, 26.dp) }
    }
}

@Composable
internal fun <T> ChoiceMenu(title: String, values: List<T>, label: (T) -> String, select: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton({ expanded = true }) { Text("$title ▾", fontSize = 11.sp) }
        DropdownMenu(expanded, { expanded = false }) { values.forEach { value ->
            DropdownMenuItem(text = { Text(label(value), fontWeight = if (label(value) == title) FontWeight.SemiBold else FontWeight.Normal) },
                trailingIcon = { if (label(value) == title) Glyph("check", Accent, 18.dp) },
                onClick = { select(value); expanded = false })
        } }
    }
}
