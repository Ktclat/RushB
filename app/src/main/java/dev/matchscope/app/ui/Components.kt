package dev.matchscope.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.matchscope.app.data.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun Glyph(name: String, color: Color = Ink, size: Dp = 22.dp, filled: Boolean = false) {
    val fill by animateFloatAsState(if (filled) 1f else 0f, tween(160), label = "iconFill")
    val cutout = Background
    Canvas(Modifier.size(size)) {
        scale(this.size.width / 24f, this.size.height / 24f, pivot = Offset.Zero) {
            fun line(vararg points: Pair<Float, Float>) {
                val p = Path().apply { points.forEachIndexed { i, v -> if (i == 0) moveTo(v.first, v.second) else lineTo(v.first, v.second) } }
                drawPath(p, color.copy(alpha = color.alpha * (1f - fill)), style = Stroke(1.7f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            fun circle(x: Float, y: Float, r: Float) { drawCircle(color.copy(alpha = color.alpha * (1f - fill)), r, Offset(x, y), style = Stroke(1.7f)) }
            when(name) {
                "matches" -> { line(7f to 3f,17f to 3f,17f to 10f,15f to 14f,9f to 14f,7f to 10f,7f to 3f); line(7f to 5f,3f to 5f,3f to 10f,7f to 11f); line(17f to 5f,21f to 5f,21f to 10f,17f to 11f); line(12f to 14f,12f to 20f); line(8f to 21f,16f to 21f) }
                "calendar" -> { line(4f to 5f,20f to 5f,20f to 21f,4f to 21f,4f to 5f); line(4f to 10f,20f to 10f); line(8f to 3f,8f to 7f); line(16f to 3f,16f to 7f); line(8f to 14f,10f to 14f); line(14f to 17f,16f to 17f) }
                "teams" -> { circle(9f,7f,3f); line(3f to 21f,3f to 17f,6f to 13f,12f to 13f,15f to 17f,15f to 21f); line(16f to 4f,19f to 6f,19f to 9f,17f to 11f); line(18f to 14f,21f to 17f,21f to 21f) }
                "rankings" -> { line(3f to 21f,3f to 13f,8f to 13f,8f to 21f); line(9f to 21f,9f to 4f,15f to 4f,15f to 21f); line(16f to 21f,16f to 9f,21f to 9f,21f to 21f) }
                "profile" -> { circle(12f,7f,4f); line(4f to 21f,4f to 18f,7f to 14f,17f to 14f,20f to 18f,20f to 21f) }
                "search" -> { circle(10f,10f,7f); line(15f to 15f,21f to 21f) }
                "back" -> line(15f to 4f,7f to 12f,15f to 20f)
                "next" -> line(9f to 6f,15f to 12f,9f to 18f)
                "down" -> line(6f to 9f,12f to 15f,18f to 9f)
                "up" -> line(6f to 15f,12f to 9f,18f to 15f)
                "close" -> { line(6f to 6f,18f to 18f); line(18f to 6f,6f to 18f) }
                "check" -> line(4f to 12f,9f to 17f,20f to 6f)
                "refresh" -> { drawArc(color, 35f, 292f, false, Offset(4f,4f), Size(16f,16f), style = Stroke(1.9f, cap = StrokeCap.Round)); line(20f to 3.5f,20f to 9f,14.5f to 9f) }
                "filter" -> { line(3f to 5f,21f to 5f,14f to 13f,14f to 20f,10f to 18f,10f to 13f,3f to 5f) }
                "sort" -> { line(6f to 3f,6f to 21f); line(3f to 17f,6f to 21f,9f to 17f); line(12f to 5f,21f to 5f); line(12f to 11f,18f to 11f); line(12f to 17f,15f to 17f) }
                "connection" -> {
                    drawArc(color, 225f, 90f, false, Offset(1f,4f), Size(22f,22f), style = Stroke(1.7f, cap = StrokeCap.Round))
                    drawArc(color, 225f, 90f, false, Offset(5f,9f), Size(14f,14f), style = Stroke(1.7f, cap = StrokeCap.Round))
                    drawCircle(color, 1.5f, Offset(12f,19f))
                }
                "appearance" -> { circle(12f,12f,8f); drawArc(color, 90f, 180f, true, Offset(4f,4f), Size(16f,16f)) }
                "maps" -> { line(3f to 5f,9f to 3f,15f to 5f,21f to 3f,21f to 19f,15f to 21f,9f to 19f,3f to 21f,3f to 5f); line(9f to 3f,9f to 19f); line(15f to 5f,15f to 21f) }
                "cache" -> { line(4f to 6f,20f to 6f); line(9f to 6f,9f to 3f,15f to 3f,15f to 6f); line(6f to 6f,7f to 21f,17f to 21f,18f to 6f); line(10f to 10f,10f to 17f); line(14f to 10f,14f to 17f) }
                "grid" -> { listOf(4f to 4f,14f to 4f,4f to 14f,14f to 14f).forEach { (x,y) -> drawRoundRect(color,Offset(x,y),Size(6f,6f),CornerRadius(1.5f)) } }
                "list" -> { listOf(6f,12f,18f).forEach { y -> line(4f to y,5f to y); line(9f to y,20f to y) } }
                "news" -> { line(4f to 3f,20f to 3f,20f to 21f,4f to 21f,4f to 3f); line(8f to 8f,16f to 8f); line(8f to 12f,16f to 12f); line(8f to 16f,13f to 16f) }
                "star" -> line(12f to 2f,15f to 8f,22f to 9f,17f to 14f,18f to 21f,12f to 18f,6f to 21f,7f to 14f,2f to 9f,9f to 8f,12f to 2f)
                else -> { circle(12f,12f,9f); line(12f to 11f,12f to 17f); circle(12f,7f,.5f) }
            }
            if (fill > 0f) {
                val face = color.copy(alpha = color.alpha * fill)
                fun facePath(vararg points: Pair<Float, Float>) = Path().apply {
                    points.forEachIndexed { i, v -> if (i == 0) moveTo(v.first, v.second) else lineTo(v.first, v.second) }
                    close()
                }
                when (name) {
                    "matches" -> {
                        drawPath(facePath(6.5f to 2.5f,17.5f to 2.5f,17.5f to 10f,15f to 14.5f,9f to 14.5f,6.5f to 10f), face)
                        drawPath(facePath(7f to 5f,3f to 5f,3f to 10f,7f to 11f), face, style = Stroke(2f))
                        drawPath(facePath(17f to 5f,21f to 5f,21f to 10f,17f to 11f), face, style = Stroke(2f))
                        drawRoundRect(face, Offset(10.7f,13f), Size(2.6f,7f), CornerRadius(1f))
                        drawRoundRect(face, Offset(7f,19.5f), Size(10f,2.5f), CornerRadius(1f))
                    }
                    "calendar" -> {
                        drawRoundRect(face, Offset(3f,4f), Size(18f,18f), CornerRadius(2.5f))
                        drawLine(cutout.copy(alpha = fill), Offset(5f,9f), Offset(19f,9f), 1.4f)
                        listOf(8f,16f).forEach { x -> drawLine(face, Offset(x,2f), Offset(x,6f), 2.2f, StrokeCap.Round) }
                        listOf(Offset(7f,12f),Offset(12f,12f),Offset(7f,17f)).forEach { at ->
                            drawRoundRect(cutout.copy(alpha = fill), at, Size(3f,2f), CornerRadius(.5f))
                        }
                    }
                    "teams" -> {
                        drawCircle(face, 3.5f, Offset(9f,7f))
                        drawCircle(face, 2.8f, Offset(18f,7.5f))
                        drawPath(facePath(2.5f to 22f,2.5f to 17f,6f to 12.5f,12f to 12.5f,15.5f to 17f,15.5f to 22f), face)
                        drawPath(facePath(17.3f to 22f,17.3f to 16.5f,15f to 13.5f,19f to 13.5f,22f to 17f,22f to 22f), face)
                    }
                    "rankings" -> {
                        drawRoundRect(face, Offset(2.5f,12f), Size(5.5f,10f), CornerRadius(.8f))
                        drawRoundRect(face, Offset(9f,3f), Size(6f,19f), CornerRadius(.8f))
                        drawRoundRect(face, Offset(16f,8f), Size(5.5f,14f), CornerRadius(.8f))
                    }
                    "star" -> drawPath(facePath(12f to 2f,15f to 8f,22f to 9f,17f to 14f,18f to 21f,12f to 18f,6f to 21f,7f to 14f,2f to 9f,9f to 8f), face)
                    "profile" -> {
                        drawCircle(face, 4.5f, Offset(12f,7f))
                        drawPath(Path().apply { moveTo(3.5f,22f); lineTo(3.5f,19f); cubicTo(3.5f,12f,20.5f,12f,20.5f,19f); lineTo(20.5f,22f); close() }, face)
                    }
                }
            }
        }
    }
}

@Composable
fun ActionIcon(name: String, label: String, enabled: Boolean = true, color: Color = Ink, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(48.dp).semantics { contentDescription = label }, enabled = enabled) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(if (name == "refresh") PanelRaised else Color.Transparent), contentAlignment = Alignment.Center) {
            Glyph(name, if(enabled) color else Quiet, 20.dp)
        }
    }
}

@Composable
fun CardBlock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = Panel,
        border = androidx.compose.foundation.BorderStroke(if (LocalRushColors.current.dark) 1.dp else .5.dp, dev.matchscope.app.ui.Stroke)) { Column(content = content) }
}

@Composable
fun SectionLabel(title: String, trailing: String = "") {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(trailing, fontSize = 11.sp, color = Quiet)
    }
}

@Composable
fun EmptyCard(title: String, description: String? = null, action: String? = null, onClick: () -> Unit = {}) {
    CardBlock { Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Glyph("info", Quiet, 32.dp)
        Text(title, fontWeight = FontWeight.SemiBold)
        if (!description.isNullOrBlank()) Text(description, color = Quiet, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
        if (action != null) TextButton(onClick) { Text(action) }
    } }
}

@Composable
fun UpdateWarning(stamp: SourceStamp, zone: ZoneId, zoneLabel: String) {
    val time = stamp.capturedAt.atZone(zone).format(DateTimeFormatter.ofPattern("MM.dd HH:mm"))
    val updating = LocalRecovery.current?.enabled == false
    val status = if (updating) "正在更新" else "更新失败"
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$status\n缓存于 $time", Modifier.weight(1f).padding(horizontal = 5.dp), fontSize = 10.sp, color = Quiet, lineHeight = 15.sp)
        if (updating) CircularProgressIndicator(Modifier.padding(horizontal = 12.dp).size(16.dp), strokeWidth = 2.dp)
        else RecoveryButtons(compact = true, loginLabel = "HLTV 验证")
    }
}

@Composable
fun SourceLink(url: String, label: String = "查看 HLTV 网页 ↗") {
    val context = LocalContext.current
    if (url.startsWith("https://")) TextButton(onClick = {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: ActivityNotFoundException) { Toast.makeText(context, "没有可打开链接的浏览器", Toast.LENGTH_SHORT).show() }
    }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(label, color = Accent, fontSize = 12.sp) }
}

@Composable
fun MatchTeam(team: Team, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        TeamLogo(team, 35.dp)
        Text(team.name, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, fontSize = 12.sp, color = Quiet, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1.6f), textAlign = TextAlign.End)
    }
}
