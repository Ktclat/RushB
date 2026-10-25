package dev.matchscope.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.matchscope.app.data.*
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

internal fun LazyListScope.profileMatches(content: ProfileContent, state: ScopeState, vm: ScopeViewModel, open: (String) -> Unit,
    filter: String, select: (String) -> Unit, limit: Int, more: () -> Unit, loadMore: (() -> Unit)? = null) {
    item { Chips(listOf("全部", "即将开始", "已结束"), filter, select) }
    val rows = content.matches.map { resolveProfileMatch(it, state.allMatches) }.filter { when (filter) { "即将开始" -> it.status != MatchStatus.FINISHED; "已结束" -> it.status == MatchStatus.FINISHED; else -> true } }
    if (rows.isEmpty()) item { EmptyCard("暂无比赛") }
    items(rows.take(limit), key = { "profile-match:${it.key}" }) { match ->
        val decided = match.status == MatchStatus.FINISHED && match.leftScore != null &&
            match.rightScore != null && match.leftScore != match.rightScore
        val leftWins = decided && match.leftScore!! > match.rightScore!!
        val leftColor = if (!decided) Ink else if (leftWins) Green else Quiet
        val rightColor = if (!decided) Ink else if (leftWins) Quiet else Green
        CardBlock(Modifier.clickable(enabled = !state.busy) { vm.openProfileMatch(match, open) }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(match.event.name, Modifier.weight(1f).clickable(enabled = match.event.hasIdentity()) {
                    open("event|${match.event.id}")
                }, fontSize = 11.sp, color = if (match.event.hasIdentity()) Ink else Quiet, maxLines = 2)
                val dateFormat = if (match.status == MatchStatus.FINISHED) "yyyy.MM.dd" else "yyyy.MM.dd HH:mm"
                Text(match.time?.atZone(state.zone)?.format(DateTimeFormatter.ofPattern(dateFormat)) ?: "时间待定", fontSize = 10.sp, color = Quiet)
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TeamLogo(state.displayTeam(match.left), 32.dp)
                Text(match.left.name, Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(color = leftColor)) { append(match.leftScore?.toString() ?: "—") }
                        withStyle(SpanStyle(color = Quiet)) { append(" : ") }
                        withStyle(SpanStyle(color = rightColor)) { append(match.rightScore?.toString() ?: "—") }
                    }, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(match.status.label, color = Quiet, fontSize = 10.sp)
                }
                Text(match.right.name, Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                TeamLogo(state.displayTeam(match.right), 32.dp)
            }
        }
    }
    if (rows.size > limit) item { TextButton(more, Modifier.fillMaxWidth()) { Text("显示更多") } }
    else if (filter != "即将开始" && loadMore != null && content.moreMatchesUrl != null) item {
        TextButton(loadMore, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("更多比赛") }
    }
}

internal fun LazyListScope.profileEvents(content: ProfileContent, open: (String) -> Unit, filter: String, select: (String) -> Unit, limit: Int, more: () -> Unit) {
    item { Chips(listOf("全部", "进行中与未来", "已结束"), filter, select) }
    val combined = content.events.groupBy { it.event.id }.values.map { entries ->
        val best = entries.maxBy { (if (it.event.startDate != null) 4 else 0) + it.statistics.size }
        best.copy(placement = entries.firstNotNullOfOrNull { it.placement }, team = entries.firstNotNullOfOrNull { it.team },
            upcoming = entries.any { it.upcoming }, category = entries.mapNotNull { it.category }.distinct().joinToString(" · "))
    }
    val rows = combined.filter { filter == "全部" || it.upcoming == (filter == "进行中与未来") }
    if (rows.isEmpty()) item { EmptyCard("暂无赛事") }
    items(rows.take(limit)) { entry -> CardBlock(Modifier.clickable { open("event|${entry.event.id}") }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EventLogo(entry.event, 46.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(entry.event.name, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                val dates = listOfNotNull(entry.event.startDate, entry.event.endDate).distinct().joinToString(" — ")
                if (dates.isNotBlank()) Text(dates, fontSize = 10.sp, color = Quiet)
                val detail = listOfNotNull(entry.team?.name, entry.category).filter(String::isNotBlank).joinToString(" · ")
                if (detail.isNotBlank()) Text(detail, fontSize = 11.sp, color = Quiet)
                if (entry.statistics.isNotEmpty()) Text(entry.statistics.joinToString(" · ") { "${profileLabel(it.first)} ${it.second}" }, fontSize = 10.sp, color = Quiet)
            }
            entry.placement?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Green) }
        }
    } }
    if (rows.size > limit) item { TextButton(more, Modifier.fillMaxWidth()) { Text("显示更多") } }
    else if (filter != "进行中与未来") content.moreEventsUrl?.let { url -> item { SourceLink(url, "全部赛事 ↗") } }
}

internal fun LazyListScope.profileNews(news: List<NewsItem>, limit: Int, zone: java.time.ZoneId, open: (String) -> Unit, more: () -> Unit) {
    if (news.isEmpty()) item { EmptyCard("暂无新闻") }
    items(news.take(limit), key = { "profile-news:${it.url}" }) { article -> NewsRow(article, zone, open) }
    if (news.size > limit) item { TextButton(more, Modifier.fillMaxWidth()) { Text("更多新闻") } }
}

internal fun LazyListScope.playerCareer(career: List<CareerTeam>, open: (String) -> Unit) {
    if (career.isNotEmpty()) item { SectionLabel("战队履历") }
    items(career) { stint -> CardBlock(Modifier.clickable { open("team|${stint.team.id}") }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TeamLogo(stint.team)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stint.team.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(stint.period, fontSize = 11.sp, color = Quiet)
            }
            if (stint.trophies.isNotEmpty()) Text("${stint.trophies.size} 冠", fontSize = 12.sp, color = Green)
        }
    } }
}

private val radarAxes = listOf("Firepower" to "火力", "Entrying" to "突破", "Trading" to "补枪", "Opening" to "首杀", "Clutching" to "残局", "Utility" to "道具")

@Composable
internal fun ProfileFacts(facts: List<Pair<String, String>>, columns: Int = 2) {
    val count = columns.coerceAtLeast(1)
    CardBlock { facts.chunked(count).forEach { pair ->
        Row(Modifier.fillMaxWidth()) {
            pair.forEach { (label, value) -> Column(Modifier.weight(1f).padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                val top20Count = label == "年度 Top 20" && value.toIntOrNull() != null
                Text(if (top20Count) "Top 20 入选" else if (label == "近期胜率") "胜率" else profileLabel(label), fontSize = 11.sp, color = Quiet)
                Text(if (top20Count) "$value 次" else value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            } }
            repeat(count - pair.size) { Spacer(Modifier.weight(1f)) }
        }
    } }
}

@Composable
internal fun PlayerRadar(roles: List<RoleStat>) {
    val axes = radarAxes.map { (key, label) -> label to roles.firstOrNull { it.label.equals(key, true) }?.score }
    val complete = axes.all { it.second != null }
    val grid = Subtle
    val plot = Green
    val labelColor = Ink
    CardBlock {
        if (complete) Canvas(Modifier.fillMaxWidth().height(290.dp).semantics {
            contentDescription = axes.joinToString("，") { "${it.first} ${it.second}" }
        }) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = minOf(size.width * .33f, size.height * .33f)
            fun point(i: Int, fraction: Float): Offset {
                val a = Math.PI * 2 * i / 6 - Math.PI / 2
                return center + Offset((cos(a) * radius * fraction).toFloat(), (sin(a) * radius * fraction).toFloat())
            }
            fun polygon(fractions: List<Float>) = Path().apply {
                fractions.forEachIndexed { i, value -> val p = point(i, value); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }; close()
            }
            (1..4).forEach { ring -> drawPath(polygon(List(6) { ring / 4f }), grid, style = Stroke(1.dp.toPx())) }
            (0..5).forEach { drawLine(grid, center, point(it, 1f), 1.dp.toPx()) }
            val path = polygon(axes.map { it.second!!.coerceIn(0, 100) / 100f })
            drawPath(path, plot.copy(alpha = .20f)); drawPath(path, plot, style = Stroke(2.dp.toPx()))
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = android.graphics.Paint.Align.CENTER; textSize = 12.sp.toPx(); color = labelColor.toArgb()
            }
            axes.forEachIndexed { i, axis ->
                val p = point(i, 1.30f)
                drawContext.canvas.nativeCanvas.drawText("${axis.first} ${axis.second}", p.x, p.y + 4.sp.toPx(), paint)
            }
        }
        if (complete) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("HLTV 属性", fontSize = 11.sp, color = Quiet)
                Text("0–100", fontSize = 11.sp, color = Quiet)
            }
            ContentGrid(axes, 3) { axis -> Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(axis.first, fontSize = 11.sp, color = Quiet)
                Text(axis.second.toString(), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            } }
        }
        else axes.filter { it.second != null }.forEach { InfoRow(it.first, it.second.toString()) }
        roles.firstOrNull { it.label.equals("Sniping", true) }?.let { InfoRow("狙击", it.score.toString()) }
    }
}
