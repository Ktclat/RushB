package dev.matchscope.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.matchscope.app.data.*

internal data class StatisticsSelection(
    val maps: List<MatchStatGroup>, val selected: MatchStatGroup?, val sides: List<MatchSide>, val eco: Boolean,
    val selectMap: (String) -> Unit, val selectSide: (MatchSide) -> Unit, val selectEco: (Boolean) -> Unit,
    val mapKey: String?, val side: MatchSide,
)

@Composable
internal fun rememberStatisticsSelection(match: Match, mapKey: String? = null, onMapSelected: ((String) -> Unit)? = null): StatisticsSelection {
    val groups = match.statGroups.ifEmpty { if (match.stats.isEmpty()) emptyList() else listOf(MatchStatGroup("all", null, MatchSide.BOTH, match.stats)) }
    var localMap by rememberSaveable(match.id) { mutableStateOf("all") }
    var side by rememberSaveable(match.id) { mutableStateOf(MatchSide.BOTH) }
    var eco by rememberSaveable(match.id) { mutableStateOf(false) }
    val maps = groups.distinctBy { it.mapKey }
    val actualMap = (mapKey ?: localMap).takeIf { key -> maps.any { it.mapKey == key } } ?: maps.firstOrNull()?.mapKey
    val sides = groups.filter { it.mapKey == actualMap }.map { it.side }.distinct()
    val selected = groups.firstOrNull { it.mapKey == actualMap && it.side == side }
    // A side without economy-adjusted values must continue to show the available base statistics.
    val hasEco = selected?.rows?.any { it.ecoKd != null || it.ecoAdr != null || it.ecoKast != null } == true
    return StatisticsSelection(maps, selected, sides, eco && hasEco, { localMap = it; onMapSelected?.invoke(it) }, { side = it }, { eco = it }, actualMap, side)
}

@Composable
internal fun StatisticsControls(selection: StatisticsSelection, showMapSelector: Boolean = true) {
    var explainEconomy by remember { mutableStateOf(false) }
    if (showMapSelector && selection.maps.isNotEmpty()) Chips(selection.maps.map { it.mapName ?: "全部地图" }, selection.maps.firstOrNull { it.mapKey == selection.mapKey }?.mapName ?: "全部地图") { name ->
        selection.selectMap(selection.maps.first { (it.mapName ?: "全部地图") == name }.mapKey)
    }
    if (selection.maps.isNotEmpty()) Chips(MatchSide.entries.map { if (it == MatchSide.BOTH) "Both" else it.name },
        if (selection.side == MatchSide.BOTH) "Both" else selection.side.name) {
        selection.selectSide(if (it == "Both") MatchSide.BOTH else MatchSide.valueOf(it))
    }
    if (selection.selected?.rows?.any { it.ecoKd != null || it.ecoAdr != null || it.ecoKast != null } == true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("经济修正", Modifier.weight(1f), fontSize = 12.sp, color = Quiet)
            ActionIcon("info", "经济修正说明") { explainEconomy = true }
            Switch(selection.eco, selection.selectEco, colors = SwitchDefaults.colors(uncheckedThumbColor = Quiet,
                uncheckedTrackColor = PanelRaised, uncheckedBorderColor = Quiet))
        }
    }
    if (explainEconomy) AlertDialog(onDismissRequest = { explainEconomy = false }, title = { Text("经济修正") },
        text = { Text("开启后显示 HLTV 提供的经济修正 K-D、ADR 和 KAST。Rating 与 Swing 不变。") },
        confirmButton = { TextButton({ explainEconomy = false }) { Text("关闭") } })
}

@Composable
internal fun MatchStatistics(match: Match, mapKey: String? = null, onMapSelected: ((String) -> Unit)? = null, showMapSelector: Boolean = true, open: (String) -> Unit) {
    val selection = rememberStatisticsSelection(match, mapKey, onMapSelected)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatisticsControls(selection, showMapSelector)
        val group = selection.selected
        if (group == null || group.rows.isEmpty()) EmptyCard("暂无选手统计")
        else group.rows.groupBy { it.teamId }.forEach { (teamId, rows) ->
            CardBlock {
                StatisticsTeamHeader(match, group, teamId, rows, selection.eco)
                StatisticsTeamRows(rows, selection.eco, open)
            }
        }
        group?.statsUrl?.let { SourceLink(it, "详细统计 ↗") }
    }
}

@Composable
internal fun StatisticsTeamHeader(match: Match, group: MatchStatGroup, teamId: String, rows: List<PlayerStat>, eco: Boolean) {
    val team = listOf(match.left, match.right).firstOrNull { it.id == teamId }
    Column(Modifier.fillMaxWidth().background(Panel).testTag("stats-header-$teamId")) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (team != null) TeamLogo(team, 22.dp)
            Text(team?.name ?: "选手", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(listOfNotNull(group.mapName?.let(::mapLabel) ?: "全部地图", if (group.side == MatchSide.BOTH) "全阵营" else "${group.side.name} 阵营", "经济修正".takeIf { eco }).joinToString(" · "),
                Modifier.weight(1.4f), fontSize = 10.sp, lineHeight = 14.sp, color = Quiet, textAlign = TextAlign.End)
        }
        StatisticsColumnHeader(rows)
    }
}

@Composable
internal fun StatisticsColumnHeader(rows: List<PlayerStat>) {
    BoxWithConstraints(Modifier.fillMaxWidth().background(PanelRaised).padding(horizontal = 10.dp)) {
        StatLine(listOf("选手", "K-D", "ADR", "KAST", "Swing", "Rating ${rows.firstOrNull()?.ratingVersion.orEmpty()}"), true, maxWidth)
    }
    HorizontalDivider(color = Stroke)
}

@Composable
internal fun StatisticsTeamRows(rows: List<PlayerStat>, eco: Boolean, open: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 10.dp)) {
        val width = maxWidth
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val numericStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.sp, lineHeight = 16.sp, textAlign = TextAlign.End)
        val values = rows.map { player -> listOf(
            if (eco) player.ecoKd else player.kd, if (eco) player.ecoAdr else player.adr,
            if (eco) player.ecoKast else player.kast, player.swing, player.rating,
        ).map { it ?: "—" } }
        // Keep a comparable table at every phone width. Fit numeric columns as one
        // group, including signs/percentages, instead of changing to player cards.
        val numberSize = remember(values, width, density, measurer, numericStyle) {
            var low = .1f
            var high = 11f
            repeat(12) {
                val candidate = (low + high) / 2
                val fits = values.all { row -> row.withIndex().all { (index, value) ->
                    val available = with(density) { (width * statWeights[index + 1] - if (index == 4) statColumnGap else statColumnGap * 2).toPx() } - 3f
                    measurer.measure(value, numericStyle.copy(fontSize = candidate.sp,
                        fontWeight = if (index == 4) FontWeight.Bold else FontWeight.Normal), softWrap = false, maxLines = 1).size.width <= available
                } }
                if (fits) low = candidate else high = candidate
            }
            low.sp
        }
        Column(Modifier.fillMaxWidth()) {
            rows.forEachIndexed { index, player ->
                Box(Modifier.fillMaxWidth().testTag("stats-player-${player.player?.id ?: player.name}")
                    .clickable(enabled = player.player != null) { player.player?.let { open("player|${it.id}") } }) {
                    StatLine(listOf(player.name) + values[index], false, width, numberSize, player.player ?: PlayerRef("", player.name))
                }
                HorizontalDivider(color = Background)
            }
        }
    }
}

private val statWeights = listOf(.28f, .14f, .13f, .14f, .17f, .14f)
private val statColumnGap = 4.dp

@Composable
private fun StatLine(values: List<String>, heading: Boolean, width: Dp, numberSize: TextUnit = 11.sp, player: PlayerRef? = null) {
    val separator = Stroke.copy(alpha = .65f)
    Row(Modifier.width(width).heightIn(min = if (heading) 32.dp else 48.dp).drawBehind {
        var edge = 0f
        statWeights.dropLast(1).forEach { weight ->
            edge += size.width * weight
            drawLine(separator, Offset(edge, 0f), Offset(edge, size.height), strokeWidth = .5.dp.toPx())
        }
    }.padding(vertical = if (heading) 6.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
        values.forEachIndexed { index, value ->
        if (index == 0 && player != null) Row(Modifier.weight(statWeights[0]).padding(end = statColumnGap), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IdentityImage(player.photoUrl, "${player.name} 照片", Modifier.size(20.dp, 28.dp).clip(RoundedCornerShape(4.dp)).background(PanelRaised)) { Glyph("profile", Quiet, 16.dp) }
            FittedPlayerName(value, Modifier.weight(1f))
        } else Text(value, Modifier.weight(statWeights[index]).padding(start = if (index == 0) 0.dp else statColumnGap, end = if (index == 5) 0.dp else statColumnGap),
            fontSize = if (heading) 9.sp else if (index == 0) 11.sp else numberSize,
            lineHeight = if (heading) 12.sp else if (index == 0) 14.sp else 16.sp,
            fontFamily = if (!heading && index > 0) FontFamily.Monospace else FontFamily.SansSerif,
            letterSpacing = 0.sp,
            fontWeight = if (!heading && (index == 0 || index == 5)) FontWeight.Bold else FontWeight.Normal,
            color = if (heading) Quiet else Ink, maxLines = if (heading || index == 0) 2 else 1,
            overflow = if (index == 0) TextOverflow.Ellipsis else TextOverflow.Clip,
            textAlign = if (index == 0) TextAlign.Start else TextAlign.End) }
    }
}

@Composable
internal fun FittedPlayerName(name: String, modifier: Modifier = Modifier, size: TextUnit = 11.sp, align: TextAlign = TextAlign.Start) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val base = LocalTextStyle.current.copy(fontSize = size, lineHeight = 14.sp, fontWeight = FontWeight.Bold, textAlign = align)
    BoxWithConstraints(modifier) {
        val available = with(density) { maxWidth.toPx() } - 2f
        val fitted = remember(name, available, density, base) {
            var low = .1f
            var high = size.value
            repeat(12) {
                val candidate = (low + high) / 2
                val measured = measurer.measure(name, base.copy(fontSize = candidate.sp), softWrap = false, maxLines = 1)
                if (measured.size.width <= available) low = candidate else high = candidate
            }
            base.copy(fontSize = low.sp)
        }
        Text(name, Modifier.fillMaxWidth(), style = fitted, maxLines = 1, softWrap = false)
    }
}
