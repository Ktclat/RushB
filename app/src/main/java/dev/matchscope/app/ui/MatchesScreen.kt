@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.matchscope.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.matchscope.app.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.Duration
import kotlinx.coroutines.delay

@Composable
internal fun MatchesScreen(
    state: ScopeState, date: LocalDate, selectDate: (LocalDate) -> Unit,
    refresh: () -> Unit, save: (String) -> Unit, open: (String) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf("全部") }
    var regionName by rememberSaveable { mutableStateOf(MatchRegion.ALL.name) }
    var levelName by rememberSaveable { mutableStateOf(MatchLevel.ALL.name) }
    var orderName by rememberSaveable { mutableStateOf(MatchOrder.IMPORTANT_FIRST.name) }
    val region = MatchRegion.valueOf(regionName)
    val level = MatchLevel.valueOf(levelName)
    val order = MatchOrder.valueOf(orderName)
    var filterOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var calendarOpen by remember { mutableStateOf(false) }
    var expansionChoices by rememberSaveable { mutableStateOf(mapOf<String, Boolean>()) }
    val dayMatches = remember(state.matches.items, state.events, date, state.zone) {
        val events = state.events.associateBy { it.id }
        visibleMatches(state.matches.items, date, state.zone, null, null).map { it.copy(event = events[it.event.id] ?: it.event) }
    }
    var statusTime by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(dayMatches, state.matches.stamp, state.zone) {
        statusTime = Instant.now()
        // Wake only at known status boundaries; this updates filters without polling the network.
        val boundaries = dayMatches.mapNotNull { match ->
            val captured = (match.stamp ?: state.matches.stamp).capturedAt
            when (match.status) {
                MatchStatus.LIVE -> captured.plusSeconds(LIVE_FRESH_SECONDS)
                MatchStatus.UPCOMING -> match.startsAt?.takeIf { captured < it }
                else -> null
            }
        }.distinct().sorted()
        while (true) {
            val now = Instant.now()
            val midnight = now.atZone(state.zone).toLocalDate().plusDays(1).atStartOfDay(state.zone).toInstant()
            val boundary = minOf(boundaries.firstOrNull { it > now } ?: midnight, midnight)
            delay(Duration.between(now, boundary).toMillis().coerceAtLeast(1))
            statusTime = Instant.now()
        }
    }
    val statusMatches = dayMatches.filter {
        when (filter) {
            "全部" -> true
            "已收藏" -> it.id in state.savedMatches
            else -> it.statusLabel(statusTime, state.matches.stamp) == filter
        }
    }
    val matches = filterMatchEvents(statusMatches, region, level)
    val groups = matchGroups(matches, order, statusTime, state.matches.stamp)
    val timeOrdered = order == MatchOrder.TIME_ASC || order == MatchOrder.TIME_DESC
    val filterCount = (if (region != MatchRegion.ALL) 1 else 0) + (if (level != MatchLevel.ALL) 1 else 0)
    LazyColumn(contentPadding = rootPagePadding, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("matches-list")) {
        item { DateStrip(date, state.zone, selectDate) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(date.format(DateTimeFormatter.ofPattern("M月d日")), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("${matches.size} 场", fontSize = 10.sp, color = Quiet)
                }
                MatchToolbarButton("filter", if (filterCount == 0) "筛选" else "筛选 $filterCount", "筛选比赛", filterCount > 0) { filterOpen = true }
                MatchToolbarButton("sort", order.shortLabel, "排序比赛", order != MatchOrder.IMPORTANT_FIRST) { sortOpen = true }
                ActionIcon("calendar", "选择日期") { calendarOpen = true }
                ActionIcon("refresh", "刷新比赛", !state.busy, onClick = refresh)
            }
            MatchFilters(filter) { filter = it }
            if (state.matches.warning != null && state.matches.stamp.origin != DataOrigin.SNAPSHOT)
                UpdateWarning(state.matches.stamp, state.zone, state.zoneLabel)
        }
        if (matches.isEmpty()) item {
            if (filter == "进行中" && filterMatchEvents(dayMatches, region, level).any { it.statusLabel(statusTime, state.matches.stamp) == "待更新" }) {
                CardBlock {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("比赛状态待更新", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilledTonalButton(onClick = refresh, enabled = !state.busy) { Text("刷新比赛") }
                            TextButton(onClick = { filter = "待更新" }) { Text("查看待更新") }
                        }
                    }
                }
            } else EmptyCard("没有符合条件的比赛", action = if (filter == "全部" && filterCount == 0) "刷新" else "清除筛选") {
                if (filter == "全部" && filterCount == 0) refresh()
                else { filter = "全部"; regionName = MatchRegion.ALL.name; levelName = MatchLevel.ALL.name }
            }
        }
        groups.forEach { group ->
            val event = group.first().event
            val expansionKey = "$date:${event.id}"
            val expanded = expansionChoices[expansionKey] ?: defaultEventExpanded(
                dayMatches.filter { it.event.id == event.id }, date, statusTime, state.zone, state.matches.stamp)
            val groupStatus = group.map { it.statusLabel(statusTime, state.matches.stamp) }.let { labels ->
                val liveCount = labels.count { it == "进行中" }
                if (liveCount > 0) "$liveCount 场进行中" else labels.distinct().singleOrNull()
            }
            item(key = "${event.id}:${group.first().id}") {
                CardBlock(Modifier.animateContentSize().testTag("event-group-${event.id}-${group.first().id}")) {
                    Row(Modifier.fillMaxWidth().background(PanelRaised).padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EventLogo(event, 26.dp)
                        Column(Modifier.weight(1f).clickable { open("event|${event.id}") }.padding(vertical = 9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(event.name, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                EventEligibility.evidence(event)?.tier?.let { tier ->
                                    Text(tier.label, Modifier.clip(RoundedCornerShape(3.dp)).background(AccentSurface).padding(horizontal = 4.dp, vertical = 2.dp),
                                        color = Accent, fontSize = 9.sp, lineHeight = 12.sp)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(listOfNotNull(event.location?.takeIf { it.isNotBlank() }, "${group.size} 场".takeUnless { timeOrdered }, groupStatus?.takeUnless { expanded }).joinToString(" · "),
                                    Modifier.weight(1f, fill = false), fontSize = 10.sp, color = Quiet, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("详情 ›", fontSize = 10.sp, color = Accent)
                            }
                        }
                        ActionIcon(if (expanded) "up" else "down", "${if (expanded) "收起" else "展开"} ${event.name}") {
                            expansionChoices = expansionChoices + (expansionKey to !expanded)
                        }
                    }
                    if (expanded) group.forEach { match ->
                        HorizontalDivider(color = Stroke, thickness = .5.dp)
                        MatchRow(match, state, { open("match|${match.id}") }, { save(match.id) })
                    }
                }
            }
        }
    }
    if (filterOpen) MatchFilterSheet(region, level, statusMatches, { filterOpen = false }) { newRegion, newLevel ->
        regionName = newRegion.name; levelName = newLevel.name; filterOpen = false
    }
    if (sortOpen) MatchSortSheet(order, { sortOpen = false }) { orderName = it.name; sortOpen = false }
    if (calendarOpen) {
        val picker = remember {
            DatePickerState(locale = java.util.Locale.forLanguageTag("zh-CN-u-fw-mon"),
                initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        }
        DatePickerDialog(onDismissRequest = { calendarOpen = false },
            confirmButton = { TextButton({
                picker.selectedDateMillis?.let { selectDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                calendarOpen = false
            }) { Text("确定") } },
            dismissButton = { TextButton({ calendarOpen = false }) { Text("取消") } },
        ) { DatePicker(picker, title = { Text("选择日期", Modifier.padding(start = 24.dp, top = 16.dp)) },
            headline = { Text(picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().format(DateTimeFormatter.ofPattern("yyyy年M月d日")) } ?: "选择日期",
                Modifier.padding(start = 24.dp, bottom = 12.dp), fontSize = 24.sp) }, showModeToggle = false) }
    }
}

@Composable
private fun MatchFilters(selected: String, select: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("全部", "进行中", "未开始", "已结束", "待更新", "已收藏").forEach { label ->
            val active = selected == label
            Row(Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(6.dp))
                .background(if (active) Accent else Panel)
                .border(.5.dp, if (active) Accent else Stroke, RoundedCornerShape(6.dp))
                .selectable(active, role = Role.Tab, onClick = { select(label) }).padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 11.sp, color = if (active) Background else Ink, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
internal fun ContextMatchCard(match: Match, state: ScopeState, onOpen: () -> Unit, onSave: () -> Unit) {
    CardBlock {
        Text(listOfNotNull(match.day(state.zone)?.format(DateTimeFormatter.ofPattern("yyyy.MM.dd")), match.event.name.takeUnless { it == "—" }).joinToString(" · "),
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 10.sp, color = Quiet, maxLines = 2)
        MatchRow(match, state, onOpen, onSave)
    }
}

@Composable
fun MatchRow(match: Match, state: ScopeState, onOpen: () -> Unit, onSave: () -> Unit) {
    val live = match.status == MatchStatus.LIVE
    val stamp = match.stamp ?: state.matches.stamp
    var recentLive by remember(live, stamp) { mutableStateOf(match.hasRecentLiveStatus(Instant.now(), stamp)) }
    LaunchedEffect(live, stamp) {
        if (recentLive) {
            delay(Duration.between(Instant.now(), stamp.capturedAt.plusSeconds(LIVE_FRESH_SECONDS)).toMillis().coerceAtLeast(1))
            recentLive = false
        }
    }
    val scored = match.status == MatchStatus.LIVE || match.status == MatchStatus.FINISHED
    Row(Modifier.fillMaxWidth().background(if (recentLive) LiveSurface else Color.Transparent)
        .border(if (recentLive) 1.dp else 0.dp, if (recentLive) Red.copy(alpha = .45f) else Color.Transparent)
        .clickable(onClick = onOpen).testTag("match-row-${match.id}")
        .padding(start = 10.dp, top = if (live) 10.dp else 7.dp, bottom = if (live) 10.dp else 7.dp, end = 0.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.width(46.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (recentLive) Text("LIVE", Modifier.clip(RoundedCornerShape(3.dp)).background(Red).padding(horizontal = 5.dp, vertical = 2.dp),
                fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.ExtraBold, color = Background)
            else Text((match.startsAt ?: match.resultAt)?.atZone(state.zone)?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "待定",
                fontSize = 12.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            if (recentLive) match.startsAt?.let { time -> Text(time.atZone(state.zone).format(DateTimeFormatter.ofPattern("HH:mm")),
                fontSize = 12.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold) }
            Text(match.format, fontSize = 10.sp, lineHeight = 12.sp, color = Quiet)
            if (!recentLive) Text(if (live) "待更新" else if (match.startsAt == null && match.resultAt != null) "赛果" else match.statusLabel(Instant.now(), state.matches.stamp), fontSize = 9.sp, lineHeight = 11.sp, color = Quiet)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(match.left to match.leftScore, match.right to match.rightScore).forEach { (team, score) ->
                val opponentScore = if (team.id == match.left.id) match.rightScore else match.leftScore
                val decided = match.status == MatchStatus.FINISHED && score != null && opponentScore != null && score != opponentScore
                val won = decided && score!! > opponentScore!!
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    TeamLogo(state.displayTeam(team), 22.dp)
                    Text(team.name, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (won) FontWeight.SemiBold else FontWeight.Medium, color = if (decided && !won) Quiet else Ink)
                    state.hltvRank(team.id)?.let { Text("#$it", fontSize = 9.sp, color = Quiet) }
                    Text(if (scored) score?.toString() ?: "—" else "—", Modifier.widthIn(min = 24.dp),
                        fontSize = if (live) 20.sp else 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        color = if (recentLive) Red else if (won) Green else if (decided || !scored) Quiet else Ink)
                }
            }
            // These are source map scores; the feed does not reliably identify the current live map.
            match.stage?.let { Text(it, fontSize = 10.sp, lineHeight = 13.sp, color = Quiet) }
            val maps = match.maps.filter { it.left != null && it.right != null }
            if (maps.isNotEmpty()) Text(maps.joinToString(" · ") { "${it.name} ${it.left}:${it.right}" },
                fontSize = 9.sp, lineHeight = 13.sp, color = Quiet)
            if (live && (!recentLive || match.leftScore == null || match.rightScore == null)) {
                val captured = stamp.capturedAt.atZone(state.zone).format(DateTimeFormatter.ofPattern("MM.dd HH:mm"))
                val missing = if (match.leftScore == null || match.rightScore == null) "比分待更新 · " else ""
                Text("${missing}上次更新 $captured", fontSize = 9.sp, lineHeight = 13.sp, color = Quiet)
            }
        }
        val saved = match.id in state.savedMatches
        IconButton(onSave, Modifier.size(44.dp).semantics { contentDescription = if (saved) "取消收藏比赛" else "收藏比赛" }) {
            Glyph("star", if (saved) Accent else Quiet, 18.dp, filled = saved)
        }
    }
}
