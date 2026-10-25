package dev.matchscope.app.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.matchscope.app.data.PlayerRef
import java.time.LocalDate
import java.time.ZoneId

internal fun mapLabel(name: String) = when (name.lowercase().replace(" ", "")) {
    "mirage" -> "荒漠迷城"; "dust2" -> "炙热沙城Ⅱ"; "inferno" -> "炼狱小镇"
    "nuke" -> "核子危机"; "ancient" -> "远古遗迹"; "anubis" -> "阿努比斯"
    "train" -> "列车停放站"; "cache" -> "死城之谜"; "overpass" -> "死亡游乐园"
    else -> name
}

@Composable
internal fun <T> ContentGrid(items: List<T>, columns: Int, content: @Composable (T) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns.coerceAtLeast(1)).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item -> Box(Modifier.weight(1f)) { content(item) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun LayoutHeading(title: String, grid: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).padding(start = 5.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.selectableGroup()) {
            listOf(false, true).forEach { value ->
                Box(Modifier.size(44.dp).selectable(grid == value, role = Role.RadioButton, onClick = { onChange(value) })
                    .semantics { contentDescription = "$title${if (value) "图标显示" else "列表显示"}" }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(5.dp)).background(if (grid == value) AccentSurface else androidx.compose.ui.graphics.Color.Transparent), contentAlignment = Alignment.Center) {
                        Glyph(if (value) "grid" else "list", if (grid == value) Accent else Quiet, 16.dp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun RosterStrip(players: List<PlayerRef>, captions: Map<String, String> = emptyMap(), open: (String) -> Unit) {
    // A starting five shares the available width. Extra substitutes occupy another full row.
    ContentGrid(players, minOf(5, players.size).coerceAtLeast(1)) { player ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(PanelRaised)
            .clickable { open("player|${player.id}") }.padding(vertical = 7.dp, horizontal = 2.dp)
            .testTag("roster-${player.id}"), horizontalAlignment = Alignment.CenterHorizontally) {
            IdentityImage(player.photoUrl, "${player.name} 照片", Modifier.fillMaxWidth().aspectRatio(.8f)) { Glyph("profile", Quiet, 28.dp) }
            Text(player.name, Modifier.fillMaxWidth().padding(top = 5.dp), fontSize = 10.sp, lineHeight = 13.sp,
                textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
            if (captions.isNotEmpty()) Text(captions[player.id].orEmpty(), Modifier.fillMaxWidth(), fontSize = 9.sp, lineHeight = 12.sp,
                minLines = 2, maxLines = 2, color = Quiet, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun DateStrip(date: LocalDate, zone: ZoneId, select: (LocalDate) -> Unit) {
    AnimatedContent(date, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).testTag("date-strip"),
        transitionSpec = {
            val direction = if (targetState > initialState) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right
            (slideIntoContainer(direction, tween(240)) + fadeIn(tween(180))) togetherWith
                (slideOutOfContainer(direction, tween(240)) + fadeOut(tween(180)))
        }, label = "match-date") { active ->
        Row(Modifier.fillMaxWidth()) {
            (-3L..3L).forEach { offset ->
                val day = active.plusDays(offset)
                Column(Modifier.weight(1f).clip(RoundedCornerShape(4.dp))
                    .background(if (day == active) AccentSurface else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { select(day) }.semantics { contentDescription = day.toString() }
                    .padding(top = 6.dp, bottom = 3.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (day == LocalDate.now(zone)) "今天" else listOf("一", "二", "三", "四", "五", "六", "日")[day.dayOfWeek.value - 1],
                        color = if (day == active) Accent else Quiet, fontSize = 10.sp)
                    Text(day.dayOfMonth.toString(), color = if (day == active) Accent else Ink,
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Box(Modifier.width(20.dp).height(2.dp).background(if (day == active) Accent else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
    }
}

@Composable
internal fun DeviceTimezoneEffect(vm: ScopeViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(context, owner, vm) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { vm.syncDeviceTimezone() }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.syncDeviceTimezone() }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED))
        owner.lifecycle.addObserver(observer)
        vm.syncDeviceTimezone()
        onDispose { context.unregisterReceiver(receiver); owner.lifecycle.removeObserver(observer) }
    }
}
@Composable
internal fun PageTabs(labels: List<String>, selected: String, onSelected: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Panel).selectableGroup()) {
        labels.forEach { label ->
            val active = label == selected
            Box(Modifier.weight(1f).height(48.dp).selectable(active, role = Role.Tab, onClick = { onSelected(label) }),
                contentAlignment = Alignment.Center) {
                Text(label, fontSize = 12.sp, lineHeight = 16.sp, color = if (active) Accent else Quiet,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                if (active) Box(Modifier.align(Alignment.BottomCenter).width(24.dp).height(2.dp).background(Accent))
            }
        }
    }
}
