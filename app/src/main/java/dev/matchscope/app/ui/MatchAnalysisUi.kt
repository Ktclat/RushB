package dev.matchscope.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.matchscope.app.data.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun LazyListScope.matchAnalysis(match: Match, state: ScopeState, vm: ScopeViewModel, open: (String) -> Unit) {
    match.insights.vrs?.let { value ->
        item { VrsImpactCard(match, value) }
    }
    val pools = match.insights.mapPools.ifEmpty {
        listOfNotNull(MatchAnalysisCalculations.profileMapPool(match, state.teamProfiles[match.left.id]?.value, state.teamProfiles[match.right.id]?.value))
    }
    item { SectionLabel("地图使用对比") }
    if (pools.isNotEmpty()) item { MapPoolComparison(match, pools) }
    else item { CardBlock {
        TextButton({ vm.loadMatchTeams(match.id) }, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("载入战队地图统计") }
    } }
    val comparisons = MatchAnalysisCalculations.comparisonRows(match)
    if (comparisons.isNotEmpty()) {
        item { SectionLabel("战队数据对比") }
        item { CardBlock {
            ComparisonTeams(match)
            comparisons.forEach { CompareRow(it) }
        } }
    }
    if (match.stats.isNotEmpty()) {
        item { SectionLabel("选手数据对比", "本场") }
        item { PlayerMatchComparison(match, open) }
    }
    item { SectionLabel("历史交战", if (match.status == MatchStatus.FINISHED) "地图 · 含本场" else "地图") }
    val allHeadToHead = MatchAnalysisCalculations.headToHeadMaps(match)
    if (allHeadToHead.isEmpty()) item { EmptyCard(if (state.networkAvailable == false && match.insights.headToHead == null) "交战数据未缓存" else "暂无交战数据") }
    else item { CardBlock {
        allHeadToHead.take(5).forEach { HistoryResultRow(it, state.zone) { vm.openHistoryMatch(it, open) } }
        TextButton({ open("history|${match.id}|h2h") }, Modifier.fillMaxWidth()) { Text("查看全部交战") }
    } }
    val form = match.insights.recentForm.filter { it.scope == MatchHistoryScope.TEAM }
    if (form.isNotEmpty()) item { SectionLabel("近期表现", "最近 5 场") }
    form.forEach { recent ->
        val current = if (match.status == MatchStatus.FINISHED && match.leftScore != null && match.rightScore != null)
            MatchHistoryResult(match.id, match.url, match.left, match.right, MatchResultUnit.SERIES, match.format,
                leftScore = match.leftScore, rightScore = match.rightScore, playedAt = match.startsAt ?: match.resultAt, event = match.event) else null
        val results = (listOfNotNull(current) + recent.results).distinctBy { it.matchId }.take(5)
        item { CardBlock {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                TeamLogo(state.displayTeam(recent.team), 28.dp)
                Text(recent.team.name, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                val wins = results.count { row -> if (row.left.id == recent.team.id) (row.leftScore ?: -1) > (row.rightScore ?: Int.MAX_VALUE) else (row.rightScore ?: -1) > (row.leftScore ?: Int.MAX_VALUE) }
                Text("$wins 胜 ${results.size - wins} 负", fontSize = 11.sp, color = Quiet)
            }
            results.forEach { HistoryResultRow(it, state.zone) { vm.openHistoryMatch(it, open) } }
            TextButton({ open("history|${match.id}|${recent.team.id}") }, Modifier.fillMaxWidth()) { Text("更多战绩") }
        } }
    }
}

@Composable
internal fun ComparisonTeams(match: Match) {
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(match.left, 28.dp)
        Text(match.left.name, Modifier.weight(1f).padding(start = 7.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(match.right.name, Modifier.weight(1f).padding(end = 7.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
        TeamLogo(match.right, 28.dp)
    }
}

@Composable
private fun VrsImpactCard(match: Match, value: MatchVrs) {
    var explanation by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (value.mode == MatchVrsMode.FORECAST) "VRS 赛前预测" else "VRS 本场影响", Modifier.weight(1f).padding(start = 5.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            ActionIcon("info", "积分计算范围") { explanation = true }
        }
        CardBlock {
            val forecast = value.mode == MatchVrsMode.FORECAST
            Row(Modifier.padding(14.dp)) {
                Text("战队", Modifier.weight(1.5f), fontSize = 10.sp, color = Quiet)
                listOf("排名", "积分", if (forecast) "获胜" else "变动", if (forecast) "失利" else "赛后排名").forEach {
                    Text(it, Modifier.weight(1f), textAlign = TextAlign.End, fontSize = 10.sp, color = Quiet)
                }
            }
            listOf(match.left, match.right).forEach { team ->
                val data = value.teams.firstOrNull { it.teamId == team.id }
                Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1.5f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        TeamLogo(team, 22.dp); Text(team.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
                    }
                    Text(data?.rank?.let { "#$it" } ?: "—", Modifier.weight(1f), textAlign = TextAlign.End, fontSize = 12.sp)
                    Text(data?.points?.toString() ?: "—", Modifier.weight(1f), textAlign = TextAlign.End, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    val impact = if (forecast) data?.ifWin else data?.result
                    Text(impact?.pointsChange?.let(::signed) ?: "—", Modifier.weight(1f), textAlign = TextAlign.End, fontSize = 12.sp, color = if ((impact?.pointsChange ?: 0) >= 0) Green else Red)
                    Text(if (forecast) data?.ifLoss?.pointsChange?.let(::signed) ?: "—" else data?.result?.rank?.let { "#$it" } ?: "—", Modifier.weight(1f), textAlign = TextAlign.End, fontSize = 12.sp)
                }
            }
        }
    }
    if (explanation) AlertDialog(onDismissRequest = { explanation = false }, title = { Text("VRS 本场影响") },
        text = { Text("HLTV 根据本场胜负估算的积分和排名变化，不包含后续奖金、赛事名次等因素。") },
        confirmButton = { TextButton({ explanation = false }) { Text("知道了") } })
}

private fun signed(value: Int) = if (value > 0) "+$value" else value.toString()

@Composable
private fun MapPoolComparison(match: Match, pools: List<MatchMapPool>) {
    var metric by remember(match.id) { mutableStateOf(MatchMapMetric.WIN) }
    val pool = pools.firstOrNull { it.metric == metric } ?: pools.first()
    fun label(value: MatchMapMetric) = when (value) { MatchMapMetric.WIN -> "胜率"; MatchMapMetric.PICK -> "选图率"; MatchMapMetric.BAN -> "禁图率" }
    CardBlock {
        ComparisonTeams(match)
        if (pools.size > 1) Box(Modifier.padding(horizontal = 14.dp)) { Chips(pools.map { label(it.metric) }, label(pool.metric)) { name -> metric = pools.first { label(it.metric) == name }.metric } }
        Text(if (pool.description == "战队近3个月地图胜率") "战队近 3 个月 · 当前统计" else "阵容核心近 3 个月", Modifier.padding(horizontal = 14.dp, vertical = 9.dp), color = Quiet, fontSize = 10.sp)
        pool.maps.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                MapRate(row.left, pool.metric, Modifier.width(64.dp))
                MapBackdrop(row.name, Modifier.weight(1f).height(57.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(row.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        val picked = match.veto.firstOrNull { it.contains("picked ${row.name}", true) }
                        picked?.let { Text(it.substringBefore(" picked").substringAfter(". ") + " PICK", fontSize = 9.sp, color = Color(0xFFBCF0CB)) }
                    }
                }
                MapRate(row.right, pool.metric, Modifier.width(64.dp))
            }
        }
        Spacer(Modifier.height(9.dp))
    }
}

@Composable
private fun MapRate(value: MatchMapRate, metric: MatchMapMetric, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.percentage?.let { String.format(Locale.ROOT, "%.1f%%", it) } ?: "—", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(value.sampleSize?.let { "$it ${if (metric == MatchMapMetric.WIN) "图" else "次"}" } ?: "—", fontSize = 10.sp, color = Quiet)
    }
}

@Composable
private fun CompareRow(row: MatchComparison) {
    Column(Modifier.padding(horizontal = 15.dp, vertical = 11.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(row.left ?: "—", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(row.label, Modifier.weight(2.2f), textAlign = TextAlign.Center, fontSize = 10.sp, color = Quiet)
            Text(row.right ?: "—", Modifier.weight(1f), fontSize = 13.sp, textAlign = TextAlign.End, fontWeight = FontWeight.Bold, color = Ink)
        }
        HorizontalDivider(Modifier.padding(top = 10.dp), color = Subtle)
    }
}

@Composable
internal fun HistoryResultRow(row: MatchHistoryResult, zone: ZoneId, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp)) {
        row.event?.name?.let { Text(it, fontSize = 9.sp, color = Quiet, maxLines = 1, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(7.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(row.playedAt?.atZone(zone)?.format(DateTimeFormatter.ofPattern("MM.dd")) ?: row.dateLabel.orEmpty(), Modifier.width(44.dp), fontSize = 10.sp, color = Quiet, maxLines = 1)
            Text(row.left.name, Modifier.weight(1f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${row.leftScore ?: "—"} : ${row.rightScore ?: "—"}", Modifier.width(56.dp), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, fontSize = 12.sp)
            Text(row.right.name, Modifier.weight(1f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
            Text(if (row.unit == MatchResultUnit.MAP) row.mapName.orEmpty() else row.format.orEmpty(), Modifier.width(45.dp), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, color = Quiet)
        }
    }
}

@Composable
private fun PlayerMatchComparison(match: Match, open: (String) -> Unit) {
    val a = match.stats.filter { it.teamId == match.left.id }
    val b = match.stats.filter { it.teamId == match.right.id }
    if (a.isEmpty() || b.isEmpty()) return
    var leftIndex by remember(match.id) { mutableIntStateOf(0) }
    var rightIndex by remember(match.id) { mutableIntStateOf(0) }
    val left = a[leftIndex.coerceAtMost(a.lastIndex)]
    val right = b[rightIndex.coerceAtMost(b.lastIndex)]
    CardBlock {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(left to a, right to b).forEachIndexed { side, (selected, players) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    var expanded by remember { mutableStateOf(false) }
                    selected.player?.let { player -> PlayerPhoto(player, Modifier.fillMaxWidth().height(105.dp).clickable { open("player|${player.id}") }) }
                    Box {
                        TextButton({ expanded = true }) { Text(selected.name + " ▾", fontSize = 12.sp) }
                        DropdownMenu(expanded, { expanded = false }) { players.forEachIndexed { index, p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = { if (side == 0) leftIndex = index else rightIndex = index; expanded = false })
                        } }
                    }
                }
            }
        }
        listOf(MatchComparison("K-D", left.kd, right.kd), MatchComparison("ADR", left.adr, right.adr), MatchComparison("KAST", left.kast, right.kast),
            MatchComparison("Swing", left.swing, right.swing), MatchComparison("Rating ${left.ratingVersion.orEmpty()}", left.rating, right.rating)).forEach { CompareRow(it) }
    }
}
