@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.matchscope.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.matchscope.app.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun compactMatchLabel(match: Match, zone: ZoneId): String = when (match.status) {
    MatchStatus.UPCOMING -> match.startsAt?.atZone(zone)?.format(DateTimeFormatter.ofPattern("MM.dd HH:mm")) ?: "时间待定"
    MatchStatus.POSTPONED, MatchStatus.CANCELLED -> match.status.label
    else -> match.displayScore(Instant.now())
}

@Composable
internal fun CompactMatchTitle(match: Match, zone: ZoneId, modifier: Modifier = Modifier) {
    val now by matchClock(match)
    val current = match.liveScore?.takeIf { match.status == MatchStatus.LIVE && it.isFresh(now) }
    Row(modifier.testTag("compact-match-title"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
        TeamLogo(match.left, 25.dp)
        Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.CenterHorizontally) {
            FittedMatchScore(if (match.status == MatchStatus.LIVE) match.displayScore(now) else compactMatchLabel(match, zone),
                17.sp, Modifier.fillMaxWidth(), if (match.hasRecentLiveStatus(now)) Red else Ink)
            if (match.status == MatchStatus.LIVE) Text(
                listOfNotNull(current?.mapName?.let(::mapLabel), match.statusLabel(now)).joinToString(" · "),
                fontSize = 10.sp, lineHeight = 13.sp, color = Quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TeamLogo(match.right, 25.dp)
    }
}

@Composable
internal fun MatchDetailScreen(match: Match, state: ScopeState, vm: ScopeViewModel, open: (String) -> Unit, list: LazyListState) {
    val now by matchClock(match)
    val liveRounds = match.liveScore?.takeIf { match.status == MatchStatus.LIVE && it.isFresh(now) && it.leftRounds != null && it.rightRounds != null }
    var tab by rememberSaveable(match.id) { mutableStateOf("分析") }
    val statisticsMatch = remember(match, state.matchStatistics[match.id]) { match.withSideStatistics(state.matchStatistics[match.id]) }
    val statistics = rememberStatisticsSelection(statisticsMatch)
    LaunchedEffect(tab, match.id, match.stamp) {
        if (tab == "数据") {
            vm.loadMatchStatistics(match.id)
        }
    }
    var tabHeight by remember { mutableIntStateOf(0) }
    var statisticsHeight by remember { mutableIntStateOf(0) }
    val pinnedTeamId by remember(list) { derivedStateOf {
        val key = list.layoutInfo.visibleItemsInfo.filter { it.key != "tabs" && it.offset <= tabHeight }
            .maxByOrNull { it.index }?.key as? String
        key?.takeIf { it.startsWith("stats-header:") || it.startsWith("stats-rows:") }?.substringAfter(':')
    } }
    val nextStatisticsTop by remember(list) { derivedStateOf {
        list.layoutInfo.visibleItemsInfo.filter { info ->
            val key = info.key as? String
            info.offset > tabHeight && (key?.startsWith("stats-header:") == true || key in setOf("data-stats-source", "data-duels-title"))
        }.minOfOrNull { it.offset }
    } }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
    LazyColumn(state = list, modifier = Modifier.testTag("match-detail-list"), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "hero") {
            CardBlock {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(match.event.name, Modifier.clickable { open("event|${match.event.id}") }, color = Quiet, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        MatchHeroTeam(state.displayTeam(match.left), Modifier.weight(1f)) { open("team|${match.left.id}") }
                        Column(Modifier.width(if (liveRounds != null) 176.dp else 110.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FittedMatchScore(if (match.status == MatchStatus.UPCOMING) "— : —" else match.displayScore(now),
                                if (liveRounds != null) 21.sp else 30.sp, Modifier.fillMaxWidth(),
                                if (match.hasRecentLiveStatus(now, state.matches.stamp)) Red else Ink)
                            Text(listOfNotNull(match.format, liveRounds?.mapName?.let(::mapLabel), match.statusLabel(now, state.matches.stamp)).joinToString(" · "), color = Quiet, fontSize = 10.sp, lineHeight = 12.sp)
                        }
                        MatchHeroTeam(state.displayTeam(match.right), Modifier.weight(1f)) { open("team|${match.right.id}") }
                    }
                    match.stage?.let { Text(it, color = Quiet, fontSize = 11.sp, textAlign = TextAlign.Center) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text((match.startsAt ?: match.resultAt)?.atZone(state.zone)?.format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm"))
                            ?: "时间待定", Modifier.weight(1f), fontSize = 11.sp, lineHeight = 16.sp, color = Quiet)
                        TextButton({ vm.saveMatch(match.id) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Glyph(if (match.id in state.savedMatches) "check" else "star", if (match.id in state.savedMatches) Accent else Quiet, 15.dp)
                            Spacer(Modifier.width(6.dp)); Text(if (match.id in state.savedMatches) "已收藏比赛" else "收藏比赛", fontSize = 11.sp)
                        }
                    }
                    if (state.networkAvailable == false) Text("离线 · 仅显示本机记录", Modifier.align(Alignment.Start), color = Quiet, fontSize = 11.sp)
                    else if (match.status == MatchStatus.LIVE) state.liveScoreFailures[match.id]?.let { reason ->
                        Column(Modifier.fillMaxWidth()) {
                            Text("比分更新失败", color = Quiet, fontSize = 11.sp)
                            val restricted = listOf("403", "429", "限制", "验证", "冷却").any { it in reason }
                            CompositionLocalProvider(LocalRecovery provides RecoveryActions(!state.busy, { vm.retryLiveScore(match.id) }) {
                                vm.loginAndRetry("https://www.hltv.org/matches", false) { vm.retryLiveScore(match.id) }
                            }) { RecoveryButtons(compact = true, verifyFirst = restricted) }
                        }
                    }
                }
            }
        }
        stickyHeader(key = "tabs") {
            Box(Modifier.fillMaxWidth().background(Background).onSizeChanged { tabHeight = it.height }.padding(vertical = 4.dp)) {
                PageTabs(listOf("分析", "数据", "阵容"), tab) { next ->
                    tab = next
                    if (next == "数据" || list.firstVisibleItemIndex > 1) scope.launch { list.scrollToItem(1) }
                }
            }
        }
        val stamp = match.stamp ?: state.matches.stamp
        if (stamp.origin != DataOrigin.SNAPSHOT && match.note != null) item { UpdateWarning(stamp, state.zone, state.zoneLabel) }
        when (tab) {
            "分析" -> matchAnalysis(match, state, vm, open)
            "数据" -> matchDataContent(match, state, vm, statistics, open)
            "阵容" -> {
                listOf(match.left, match.right).forEach { team ->
                    item { SectionLabel(team.name) }
                    item {
                        val players = match.players[team.id].orEmpty()
                        if (players.isEmpty()) Text("阵容待公布", Modifier.padding(16.dp), fontSize = 12.sp, color = Quiet)
                        else RosterStrip(players, open = open)
                    }
                }
            }
        }
        item { SourceLink(match.url) }
    }
    val group = statistics.selected.takeIf { tab == "数据" }
    val rows = group?.rows.orEmpty().filter { it.teamId == pinnedTeamId }
    if (group != null && rows.isNotEmpty()) {
        val push = minOf(0, (nextStatisticsTop ?: Int.MAX_VALUE) - tabHeight - statisticsHeight)
        // Draw inside a full-screen parent: placing text outside a short LazyColumn
        // item can cull its glyphs even when semantics and backgrounds remain visible.
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).offset { IntOffset(0, tabHeight + push) }
            .testTag("pinned-statistics").onSizeChanged { statisticsHeight = it.height }
            .drawWithContent { clipRect(top = (-push).toFloat()) { this@drawWithContent.drawContent() } }) {
            StatisticsTeamHeader(match, group, pinnedTeamId!!, rows, statistics.eco)
        }
    }
    }
}

@Composable
internal fun FittedMatchScore(value: String, size: TextUnit, modifier: Modifier = Modifier, color: Color = Ink) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val style = LocalTextStyle.current.copy(fontSize = size, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.ExtraBold, letterSpacing = 0.sp, textAlign = TextAlign.Center)
        val available = with(density) { maxWidth.toPx() } - 2f
        val fittedStyle = remember(value, style, available, measurer) {
            fun width(fontSize: Float) = measurer.measure(value, style.copy(fontSize = fontSize.sp),
                softWrap = false, maxLines = 1).size.width
            if (width(size.value) <= available) style else {
                var lower = .1f
                var upper = size.value
                repeat(12) {
                    val candidate = (lower + upper) / 2f
                    if (width(candidate) <= available) lower = candidate else upper = candidate
                }
                style.copy(fontSize = lower.sp)
            }
        }
        Text(value, Modifier.fillMaxWidth(), style = fittedStyle, color = color, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun matchClock(match: Match): State<Instant> = produceState(Instant.now(), match.status) {
    while (match.status == MatchStatus.LIVE && isActive) { delay(5_000); value = Instant.now() }
}

@Composable
private fun MatchHeroTeam(team: Team, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(enabled = !team.id.startsWith("pending:"), onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TeamLogo(team, 36.dp)
        Text(team.name, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun MatchHistoryScreen(match: Match, teamId: String?, state: ScopeState, vm: ScopeViewModel, open: (String) -> Unit) {
    val key = "${match.id}|${teamId ?: "h2h"}"
    val page = state.matchHistories[key]
    val source = if (teamId == null) match.insights.headToHead?.moreUrl else
        match.insights.recentForm.firstOrNull { it.team.id == teamId && it.scope == MatchHistoryScope.TEAM }?.moreUrl
    LaunchedEffect(key) { vm.state.first { !it.busy }; vm.loadMatchHistory(match.id, teamId) }
    val preview = if (teamId == null) MatchAnalysisCalculations.headToHeadMaps(match) else
        match.insights.recentForm.firstOrNull { it.team.id == teamId && it.scope == MatchHistoryScope.TEAM }?.results.orEmpty()
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            if (teamId == null) CardBlock { ComparisonTeams(match) }
            else listOf(match.left, match.right).firstOrNull { it.id == teamId }?.let { team ->
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TeamLogo(team, 34.dp); Text(team.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        state.historyFailures[key]?.let { reason -> item { CardBlock { Column(Modifier.padding(14.dp)) {
            val restricted = listOf("403", "429", "限制", "拒绝", "验证", "冷却").any { it in reason }
            Text(if (restricted) "网站限制访问 · 更多交战未载入" else "加载失败 · 更多交战未载入", fontSize = 12.sp, color = Quiet)
            RecoveryButtons(verifyFirst = restricted)
        } } } }
        if (page == null) {
            if (preview.isNotEmpty()) item { SectionLabel(if (teamId == null) "地图交战" else "近期比赛", if (teamId == null && match.status == MatchStatus.FINISHED) "含本场" else "") }
            items(preview) { row -> CardBlock { HistoryResultRow(row, state.zone) { vm.openHistoryMatch(row, open) } } }
            if (source != null && state.historyFailures[key] == null) item { TextButton({ vm.loadMatchHistory(match.id, teamId) }, Modifier.fillMaxWidth(), enabled = !state.busy) { Text(if (state.busy) "加载中…" else "加载更多交战") } }
            else if (preview.isEmpty()) item { EmptyCard("暂无交战数据") }
        } else {
            item { SectionLabel("系列赛", "${page.value.items.size} 场") }
            if (page.warning != null) item { UpdateWarning(page.stamp, state.zone, state.zoneLabel) }
            if (page.value.items.isEmpty()) item { EmptyCard("暂无比赛") }
            items(page.value.items, key = { it.id }) { row -> CardBlock { MatchRow(row, state, { vm.openMatch(row, open) }, { vm.saveMatch(row.id) }) } }
            if (page.value.nextUrl != null) item { TextButton({ vm.loadMatchHistory(match.id, teamId, more = true) }, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("更多比赛") } }
        }
        source?.let { item { SourceLink(it) } }
    }
}
