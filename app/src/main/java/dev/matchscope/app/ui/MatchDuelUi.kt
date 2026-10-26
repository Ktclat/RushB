package dev.matchscope.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.matchscope.app.data.*
import kotlin.math.abs

@Composable
internal fun MatchDuelPanel(match: Match, state: ScopeState, vm: ScopeViewModel, mapKey: String,
    onMapSelected: (String) -> Unit, open: (String) -> Unit) {
    val groups = match.statGroups.filter { it.side == MatchSide.BOTH }
    val sources = groups.mapNotNull { it.statsUrl }.distinct()
    LaunchedEffect(match.id, sources) { sources.forEach(vm::restoreDuels) }
    val cached = groups.mapNotNull { group ->
        val url = group.statsUrl?.let(DuelParser::performanceUrl) ?: return@mapNotNull null
        state.matchDuels[url]?.let { (group.mapName?.lowercase() ?: group.mapKey) to it }
    }.toMap()
    // Keep independently captured web matrices separate; only the mobile capture may be summed.
    val detail = match.withDuelTotals().let { it.copy(duels = cached.mapValues { entry -> entry.value.value } + it.duels) }
    val options = detail.duelMapOptions()
    val selected = options.firstOrNull { it.key == mapKey } ?: options.firstOrNull()
    LaunchedEffect(selected?.key) { selected?.let { if (it.key != mapKey) onMapSelected(it.key) } }
    Column(Modifier.fillMaxWidth().testTag("match-duel-panel"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (selected == null) {
            CardBlock { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (match.status == MatchStatus.UPCOMING) "比赛尚未开始" else "暂无对位数据", color = Quiet, fontSize = 13.sp)
                if (match.status != MatchStatus.UPCOMING) CompositionLocalProvider(LocalRecovery provides RecoveryActions(!state.busy,
                    { vm.loadMatch(match.id, true) }, { vm.loginAndRetry(match.url) { vm.loadMatch(match.id, true) } })) {
                    RecoveryButtons(compact = true)
                }
            } }
            return@Column
        }
        val columns = if (options.size > 4) 3 else options.size
        options.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { option ->
                    val active = option.key == selected.key
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (active) AccentSurface else PanelRaised)
                        .testTag("duel-map-${option.key}").selectable(active, role = Role.Tab) { onMapSelected(option.key) }
                        .padding(horizontal = 3.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(option.name?.let(::mapLabel) ?: "全部地图", color = if (active) Accent else Quiet,
                            fontSize = 10.sp, lineHeight = 13.sp, minLines = 2, textAlign = TextAlign.Center)
                        FittedMatchScore(if (option.key == "all") match.score else "${option.score?.left ?: "—"} : ${option.score?.right ?: "—"}",
                            14.sp, Modifier.fillMaxWidth(), if (active) Accent else Ink)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        val data = detail.duels.getValue(selected.key)
        val playerTeams = buildMap {
            listOf(match.left, match.right).forEach { team ->
                match.players[team.id].orEmpty().forEach { put(it.id, state.displayTeam(team)) }
                (match.stats + groups.flatMap { it.rows }).filter { it.teamId == team.id }.forEach { row ->
                    row.player?.let { put(it.id, state.displayTeam(team)) }
                }
            }
        }
        if (selected.key !in match.duels) cached[selected.key]?.takeIf { it.warning != null }?.let {
            UpdateWarning(it.stamp, state.zone, state.zoneLabel)
        }
        // Stable lineup order across maps and metrics. Portrait taps still open the player's profile.
        val order = listOf(match.left, match.right).flatMap { match.players[it.id].orEmpty().map(PlayerRef::id) }
            .ifEmpty { match.stats.mapNotNull { it.player?.id } }
        DuelTable(data, state.playerRefs.associateBy { it.id }, playerTeams, order, open)
    }
}

private fun duelMetricLabel(metric: DuelMetric) = when (metric) {
    DuelMetric.ALL_KILLS -> "全部击杀"
    DuelMetric.OPENING_KILLS -> "首杀"
    DuelMetric.AWP_KILLS -> "AWP 击杀"
}

@Composable
internal fun DuelTable(data: MatchDuels, knownPlayers: Map<String, PlayerRef>, playerTeams: Map<String, Team> = emptyMap(),
    playerOrder: List<String> = emptyList(), open: (String) -> Unit) {
    var metric by rememberSaveable(data.sourceUrl) { mutableStateOf(DuelMetric.ALL_KILLS) }
    var selectedCell by remember(data, metric) { mutableStateOf<DuelCell?>(null) }
    val matrices = data.matrices.filter { it.columnPlayers.isNotEmpty() && it.rowPlayers.isNotEmpty() }
    val matrix = matrices.firstOrNull { it.metric == metric } ?: matrices.firstOrNull() ?: return
    val order = playerOrder.withIndex().associate { it.value to it.index }
    // A known name without a portrait must not mask the portrait in the match response.
    fun enriched(player: PlayerRef) = player.copy(photoUrl = player.photoUrl ?: knownPlayers[player.id]?.photoUrl)
    val rows = matrix.rowPlayers.sortedBy { order[it.id] ?: Int.MAX_VALUE }.map(::enriched)
    val columns = matrix.columnPlayers.sortedBy { order[it.id] ?: Int.MAX_VALUE }.map(::enriched)
    val cells = remember(matrix) { matrix.cells.associateBy { it.rowPlayerId to it.columnPlayerId } }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val numberStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
    val rowTeam = rows.mapNotNull { playerTeams[it.id] }.distinctBy { it.id }.singleOrNull()
    val columnTeam = columns.mapNotNull { playerTeams[it.id] }.distinctBy { it.id }.singleOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(PanelRaised).padding(3.dp).selectableGroup()) {
            matrices.distinctBy { it.metric }.forEach { part ->
                val active = part.metric == matrix.metric
                Box(Modifier.weight(1f).heightIn(min = 42.dp).clip(RoundedCornerShape(4.dp)).background(if (active) AccentSurface else Color.Transparent)
                    .selectable(active, role = Role.Tab) { metric = part.metric }.padding(horizontal = 2.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(duelMetricLabel(part.metric), color = if (active) Accent else Quiet,
                        fontSize = 12.sp, lineHeight = 16.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DuelAxisTeam(rowTeam, "纵向", Modifier.weight(1f))
            DuelAxisTeam(columnTeam, "横向", Modifier.weight(1f), reverse = true)
        }
        CardBlock {
            BoxWithConstraints(Modifier.fillMaxWidth().testTag("duel-matrix").semantics { collectionInfo = CollectionInfo(rows.size + 1, columns.size + 1) }) {
                val cellWidth = maxWidth / (columns.size + 1)
                val height = 58.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.3f)
                val numberSize = remember(matrix, cellWidth, density, numberStyle, measurer) {
                    val available = with(density) { (cellWidth - 6.dp).toPx() } - 2f
                    var lower = .1f
                    var upper = 11f
                    repeat(12) {
                        val candidate = (lower + upper) / 2
                        val fits = matrix.cells.all { cell -> measurer.measure("${cell.rowKills} : ${cell.columnKills}",
                            numberStyle.copy(fontSize = candidate.sp), softWrap = false, maxLines = 1).size.width <= available }
                        if (fits) lower = candidate else upper = candidate
                    }
                    lower.sp
                }
                val grid = Stroke
                val separators = Modifier.drawWithContent {
                    drawContent()
                    for (i in 1..columns.size) {
                        val x = size.width * i / (columns.size + 1)
                        drawLine(grid, Offset(x, 0f), Offset(x, size.height), .5.dp.toPx())
                    }
                }
                Column(Modifier.fillMaxWidth()) {
                    Row(separators.background(PanelRaised), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(cellWidth).height(height), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("击杀", fontSize = 10.sp, lineHeight = 14.sp, color = Quiet)
                            Text(":", fontSize = 10.sp, lineHeight = 12.sp, color = Quiet)
                            Text("被杀", fontSize = 10.sp, lineHeight = 14.sp, color = Quiet)
                        }
                        columns.forEach { MatrixPlayer(it, Modifier.width(cellWidth).height(height), open) }
                    }
                    rows.forEach { player ->
                        HorizontalDivider(color = Stroke, thickness = .5.dp)
                        Row(separators, verticalAlignment = Alignment.CenterVertically) {
                            MatrixPlayer(player, Modifier.width(cellWidth).height(height).background(PanelRaised), open)
                            columns.forEach { opponent ->
                                val cell = cells[player.id to opponent.id]
                                val advantage = cell?.let { it.rowKills.toLong() - it.columnKills } ?: 0L
                                val tint = when { advantage > 0 -> Green; advantage < 0 -> Red; else -> Quiet }
                                val alpha = if (advantage == 0L) 0f else .05f + .12f * (abs(advantage).toFloat() / (cell!!.rowKills.toLong() + cell.columnKills).coerceAtLeast(1))
                                Box(Modifier.width(cellWidth).height(height).background(tint.copy(alpha = alpha))
                                    .testTag("duel-cell-${player.id}-${opponent.id}")
                                    .clickable(enabled = cell != null) { selectedCell = cell }
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = "${player.name} 对 ${opponent.name}，${cell?.rowKills ?: "—"} 次击杀，${cell?.columnKills ?: "—"} 次被杀"
                                    }.padding(horizontal = 3.dp), contentAlignment = Alignment.Center) {
                                    Text(cell?.let { "${it.rowKills} : ${it.columnKills}" } ?: "—", Modifier.fillMaxWidth(), color = tint,
                                        style = numberStyle.copy(fontSize = numberSize), maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }
        }
        Text("纵向选手的击杀 : 被杀", color = Quiet, fontSize = 11.sp, lineHeight = 15.sp)
    }
    selectedCell?.let { cell ->
        val row = rows.first { it.id == cell.rowPlayerId }
        val column = columns.first { it.id == cell.columnPlayerId }
        AlertDialog(onDismissRequest = { selectedCell = null }, title = { Text(duelMetricLabel(matrix.metric)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MatrixPlayer(row, Modifier.weight(1f).height(72.dp), open)
                    FittedMatchScore("${cell.rowKills} : ${cell.columnKills}", 24.sp, Modifier.weight(1f))
                    MatrixPlayer(column, Modifier.weight(1f).height(72.dp), open)
                }
                Text("${row.name} 击杀 ${column.name}：${cell.rowKills}", fontSize = 13.sp)
                Text("${column.name} 击杀 ${row.name}：${cell.columnKills}", fontSize = 13.sp)
            }
        }, confirmButton = { TextButton({ selectedCell = null }) { Text("关闭") } })
    }
}

@Composable
private fun DuelAxisTeam(team: Team?, axis: String, modifier: Modifier, reverse: Boolean = false) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp, if (reverse) Alignment.End else Alignment.Start), verticalAlignment = Alignment.CenterVertically) {
        if (!reverse && team != null) TeamLogo(team, 22.dp)
        Column(Modifier.weight(1f), horizontalAlignment = if (reverse) Alignment.End else Alignment.Start) {
            if (team != null) FittedPlayerName(team.name, Modifier.fillMaxWidth(), 12.sp, if (reverse) TextAlign.End else TextAlign.Start)
            Text(axis, color = Quiet, fontSize = 10.sp, lineHeight = 14.sp)
        }
        if (reverse && team != null) TeamLogo(team, 22.dp)
    }
}

@Composable
private fun MatrixPlayer(player: PlayerRef, modifier: Modifier, open: (String) -> Unit) {
    Column(modifier.clickable { open("player|${player.id}") }.padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        IdentityImage(player.photoUrl, "${player.name} 照片", Modifier.size(30.dp)) { Glyph("profile", Quiet, 22.dp) }
        FittedPlayerName(player.name, Modifier.fillMaxWidth(), 9.sp, TextAlign.Center)
    }
}
