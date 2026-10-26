package dev.matchscope.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.matchscope.app.data.*

internal fun mapImageUrl(name: String): String? = MediaUrls.validated("https://www.hltv.org/img/static/maps/${name.lowercase().replace(" ", "")}.png")

@Composable
internal fun MapBackdrop(name: String, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(DarkColors.panelRaised), contentAlignment = Alignment.Center) {
        mapImageUrl(name)?.let { key(it, LocalArtworkRevision.current) { AsyncImage(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) } }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .15f), Color.Black.copy(alpha = .7f)))))
        content()
    }
}

@Composable
internal fun VetoCards(match: Match) {
    if (match.veto.isEmpty()) return
    val steps = remember(match.veto, match.left, match.right) { match.vetoSteps() }
    val picked = steps.filter { it.action in setOf(VetoAction.PICK, VetoAction.LEFT) }
    var expanded by remember(match.id) { mutableStateOf<VetoStep?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEach { step ->
            val banned = step.action == VetoAction.BAN
            // Map imagery remains dark in either app theme, so its labels use image-safe colors.
            val color = when (step.action) { VetoAction.BAN -> DarkColors.red; VetoAction.PICK -> DarkColors.green; else -> DarkColors.accent }
            MapBackdrop(step.map, Modifier.weight(1f).height(106.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))
                .border(if (banned) .5.dp else 1.2.dp, if (banned) Stroke else color.copy(alpha = .65f), RoundedCornerShape(6.dp))
                .clickable { expanded = step }.testTag("veto-${step.order}")
                .semantics { contentDescription = "${step.order}. ${step.description}" }) {
                if (banned) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .45f)))
                Column(Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                    Text("${step.order}", Modifier.align(Alignment.Start), fontSize = 9.sp, lineHeight = 11.sp, color = DarkColors.ink)
                    if (step.team != null) TeamLogo(step.team, 24.dp, darkSurface = true) else Glyph("maps", DarkColors.quiet, 20.dp)
                    Text(mapLabel(step.map), fontSize = 9.sp, lineHeight = 12.sp, color = if (banned) DarkColors.quiet else Color.White, minLines = 2, textAlign = TextAlign.Center)
                    Text(if (step.action == VetoAction.UNKNOWN) "—" else step.action.name,
                        Modifier.fillMaxWidth().background(color.copy(alpha = .18f)).padding(vertical = 3.dp),
                        fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = color)
                }
            }
        }
    }
    if (picked.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        picked.forEachIndexed { index, step ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(PanelRaised).clickable { expanded = step }.padding(7.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("图 ${index + 1} · ${mapLabel(step.map)}", fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(step.team?.name?.let { "$it 选图" } ?: if (picked.size > 1) "决胜图" else "LEFT", fontSize = 9.sp, lineHeight = 12.sp, color = Quiet, maxLines = 2)
            }
        }
    }
    }
    expanded?.let { step -> AlertDialog(onDismissRequest = { expanded = null }, containerColor = Panel,
        title = { Text("${step.order} · ${step.action.name}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(mapLabel(step.map), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            step.team?.let { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { TeamLogo(it, 30.dp); Text(it.name) } }
            Text(step.description, color = Quiet)
        } }, confirmButton = { TextButton({ expanded = null }) { Text("关闭") } }) }
}

@Composable
internal fun MatchDataPanel(match: Match, state: ScopeState, vm: ScopeViewModel, open: (String) -> Unit) {
    val selection = rememberStatisticsSelection(match.withSideStatistics(state.matchStatistics[match.id]))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { matchDataContent(match, state, vm, selection, open) }
}

internal fun LazyListScope.matchDataContent(match: Match, state: ScopeState, vm: ScopeViewModel, selection: StatisticsSelection, open: (String) -> Unit, onDuels: (() -> Unit)? = null) {
    val selected = selection.selected
    val mapKey = selection.mapKey ?: "all"
    if (match.veto.isNotEmpty()) item(key = "data-veto") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { SectionLabel("地图 BP"); VetoCards(match) } }
    item(key = "data-title") { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { SectionLabel(if (match.status == MatchStatus.LIVE) "即时数据" else "比赛数据") }
        if (onDuels != null) TextButton(onDuels, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("对位数据", fontSize = 12.sp); Glyph("next", Accent, 16.dp)
        }
    } }
    if (selection.maps.isNotEmpty()) item(key = "data-map-selector") { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        selection.maps.forEach { group ->
            val score = match.maps.firstOrNull { it.name.equals(group.mapName, true) }
            val selectedMap = mapKey == group.mapKey
            Column(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (selectedMap) AccentSurface else PanelRaised)
                .clickable { selection.selectMap(group.mapKey) }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(group.mapName?.let(::mapLabel) ?: "全部地图", fontSize = 10.sp, lineHeight = 12.sp, minLines = 2, textAlign = TextAlign.Center, color = if (selectedMap) Accent else Quiet)
                Text(if (group.mapKey == "all") match.score else "${score?.left ?: "—"} : ${score?.right ?: "—"}", fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, color = if (selectedMap) Accent else Ink)
            }
        }
    } }
    item(key = "data-controls") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { StatisticsControls(selection, showMapSelector = false) } }
    if (state.statisticsFailures[match.id] != null || selection.side != MatchSide.BOTH && selected == null) item(key = "data-side-status") {
        val loading = match.id in state.statisticsLoading
        Column {
            Text(if (loading) "正在加载 ${selection.side.name} 统计…" else if (selected != null) "阵营统计更新失败" else "${selection.side.name} 统计暂不可用", color = Quiet, fontSize = 11.sp)
            if (!loading) CompositionLocalProvider(LocalRecovery provides RecoveryActions(!state.busy, { vm.retryMatchStatistics(match.id) }) {
                vm.loginAndRetry("https://www.hltv.org/matches/${match.id}/-", false) { vm.retryMatchStatistics(match.id) }
            }) { RecoveryButtons(compact = true) }
        }
    }
    if (selected == null || selected.rows.isEmpty()) {
        if (selection.side == MatchSide.BOTH) item(key = "data-empty") { EmptyCard(if (match.status == MatchStatus.UPCOMING) "比赛尚未开始" else "暂无选手统计") }
    }
    else selected.rows.groupBy { it.teamId }.forEach { (teamId, rows) ->
        item(key = "stats-header:$teamId") { StatisticsTeamHeader(match, selected, teamId, rows, selection.eco) }
        item(key = "stats-rows:$teamId") { StatisticsTeamRows(rows.map { row ->
            val ref = row.player
            if (ref == null || ref.photoUrl != null) row else row.copy(player = ref.copy(photoUrl = state.playerRefs.firstOrNull { it.id == ref.id && it.photoUrl != null }?.photoUrl))
        }, selection.eco, open) }
    }
    selected?.statsUrl?.let { item(key = "data-stats-source") { SourceLink(it, "详细统计 ↗") } }
}
