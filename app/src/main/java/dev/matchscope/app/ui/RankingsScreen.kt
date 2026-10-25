package dev.matchscope.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import dev.matchscope.app.data.*
import kotlinx.coroutines.flow.first

@Composable
internal fun RankingsScreen(state: ScopeState, vm: ScopeViewModel, open: (String) -> Unit) {
    val active = LocalRootActive.current
    LaunchedEffect(active) { if (active) { vm.state.first { !it.busy }; vm.ensureRankings() } }
    CompositionLocalProvider(LocalRecovery provides RecoveryActions(!state.busy, { vm.refreshRankings() }) {
        vm.loginAndRetry("https://www.hltv.org/ranking/teams", false) { vm.refreshRankings() }
    }) { RankingContent(state, { vm.refreshRankings() }, { vm.refreshVrs(it) }, open, { vm.ensureVrs() }) }
}

@Composable
internal fun RankingContent(state: ScopeState, refreshHltv: () -> Unit, refreshVrs: (VrsRegion) -> Unit, open: (String) -> Unit, ensureVrs: () -> Unit = {}) {
    var kind by rememberSaveable { mutableStateOf("HLTV") }
    var regionName by rememberSaveable { mutableStateOf(VrsRegion.GLOBAL.name) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val region = VrsRegion.valueOf(regionName)
    val vrs = kind == "Valve VRS"
    val active = LocalRootActive.current
    LaunchedEffect(vrs, active) { if (vrs && active) ensureVrs() }
    val listStates = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        PageTabs(listOf("HLTV", "Valve VRS"), kind) { kind = it }
        AnimatedVisibility(vrs) {
            RegionTabs(region, Modifier.padding(top = 10.dp)) { regionName = it.name }
        }
        AnimatedVisibility(searching) {
            Box(Modifier.padding(top = 8.dp, bottom = 2.dp)) { SearchField(query, "搜索战队") { query = it } }
        }
        AnimatedContent(targetState = if (vrs) region.name else "HLTV", modifier = Modifier.weight(1f),
            transitionSpec = {
                val forwards = targetState == "HLTV" || initialState != "HLTV" &&
                    VrsRegion.valueOf(targetState).ordinal > VrsRegion.valueOf(initialState).ordinal
                (fadeIn(tween(190)) + slideInHorizontally(tween(220)) { if (forwards) it / 12 else -it / 12 })
                    .togetherWith(fadeOut(tween(110)) + slideOutHorizontally(tween(220)) { if (forwards) -it / 12 else it / 12 })
            }, label = "rankingRegion") { key ->
            val targetRegion = if (key == "HLTV") null else VrsRegion.valueOf(key)
            val feed = if (targetRegion == null) state.rankings else state.vrs[targetRegion]
            val failure = (if (targetRegion == null) state.rankingFailure else state.vrsFailure) ?: feed?.warning
            val entries = feed?.items.orEmpty().filter { it.team.name.contains(query.trim(), true) }
            listStates.SaveableStateProvider(key) {
                LazyColumn(Modifier.fillMaxSize().testTag("ranking-list-$key"), contentPadding = PaddingValues(bottom = 16.dp + LocalRootBottomInset.current),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(if (targetRegion == null) "世界排名" else "${targetRegion.label}邀请排名", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(listOfNotNull(feed?.stamp?.publishedOn, "${entries.size} 支").joinToString(" · "), fontSize = 11.sp, color = Quiet)
                            }
                            TextButton(onClick = {
                                searching = !searching
                                if (!searching) query = ""
                            }, modifier = Modifier.semantics { contentDescription = "搜索排名" }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Glyph("search", if (searching) Accent else Quiet, 16.dp)
                                Spacer(Modifier.width(4.dp))
                                Text(if (searching) "收起" else "榜内搜索", fontSize = 11.sp)
                            }
                            ActionIcon("refresh", "刷新排名", !state.busy) { if (targetRegion == null) refreshHltv() else refreshVrs(targetRegion) }
                        }
                    }
                    if (failure != null && feed != null) item {
                        if (targetRegion == null) {
                            if (feed.items.isNotEmpty()) UpdateWarning(feed.stamp, state.zone, state.zoneLabel) else RecoveryButtons()
                        }
                        else Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("更新失败 · 保留 ${feed.stamp.publishedOn}", Modifier.weight(1f), fontSize = 11.sp, color = Quiet)
                            TextButton({ refreshVrs(targetRegion) }, enabled = !state.busy) { Text("重试") }
                        }
                    }
                    if (entries.isEmpty()) item {
                        EmptyCard(if (query.isNotBlank()) "没有找到战队" else if (state.busy) "加载中…" else "暂无排名",
                            action = if (query.isNotBlank()) "清空搜索" else if (state.busy) null else "刷新") {
                            if (query.isNotBlank()) query = "" else if (targetRegion == null) refreshHltv() else refreshVrs(targetRegion)
                        }
                    }
                    items(entries, key = { it.rank }) { entry ->
                        RankingRow(entry, state.displayTeam(entry.team),
                            targetRegion?.let { rankingRegion(entry, it, state.vrs) } ?: hltvRankingRegion(entry, state.vrs), showChange = targetRegion == null) {
                            open("team|${entry.team.id}${targetRegion?.let { "|${it.name}|${entry.rank}" }.orEmpty()}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegionTabs(selected: VrsRegion, modifier: Modifier = Modifier, select: (VrsRegion) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(10.dp)).background(Panel).padding(3.dp)) {
        val cell = maxWidth / VrsRegion.entries.size
        val offset by animateDpAsState(cell * selected.ordinal, tween(220), label = "regionIndicator")
        Box(Modifier.offset { IntOffset(offset.roundToPx(), 0) }.width(cell).fillMaxHeight().clip(RoundedCornerShape(7.dp)).background(Subtle))
        Row(Modifier.fillMaxSize().selectableGroup()) {
            VrsRegion.entries.forEach { region ->
                Box(Modifier.weight(1f).fillMaxHeight().testTag("region-${region.key}")
                    .selectable(selected == region, role = Role.Tab, onClick = { select(region) }), contentAlignment = Alignment.Center) {
                    Text(region.label, fontSize = 12.sp, color = if (selected == region) Accent else Quiet,
                        fontWeight = if (selected == region) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun RankingRow(entry: RankingEntry, team: Team, region: String?, showChange: Boolean = false, onOpen: () -> Unit) {
    val color = region?.let { regionColor(it) } ?: Stroke
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Panel)
        .border(.5.dp, Stroke, RoundedCornerShape(10.dp))
        .drawBehind { if (region != null) drawRect(color, size = Size(3.dp.toPx(), size.height)) }
        .clickable(onClick = onOpen).testTag("ranking-${entry.rank}").padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.width(29.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(entry.rank.toString(), fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (showChange) {
                val delta = entry.change
                Text(when { delta == null -> "—"; delta > 0 -> "↑ $delta"; delta < 0 -> "↓ ${-delta}"; else -> "0" },
                    Modifier.semantics { contentDescription = when {
                        delta == null -> "排名变化未知"; delta > 0 -> "上升 $delta 名"; delta < 0 -> "下降 ${-delta} 名"; else -> "排名无变化 0"
                    } }, fontSize = 10.sp, lineHeight = 12.sp,
                    color = when { delta == null || delta == 0 -> Quiet; delta > 0 -> Green; else -> Red })
            }
        }
        TeamLogo(team, 34.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(team.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (region != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(5.dp).clip(RoundedCornerShape(2.dp)).background(color))
                Text(region, color = color, fontSize = 10.sp, lineHeight = 12.sp)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(entry.points.toString(), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("积分", color = Quiet, fontSize = 9.sp, lineHeight = 12.sp)
        }
    }
}
