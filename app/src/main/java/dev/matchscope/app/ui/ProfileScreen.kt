package dev.matchscope.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.matchscope.app.BuildConfig
import dev.matchscope.app.data.*

@Composable
internal fun ProfileScreen(state: ScopeState, vm: ScopeViewModel, open: (String) -> Unit) {
    val active = LocalRootActive.current
    LaunchedEffect(active) { if (active) vm.measureImageCache() }
    ProfileContent(state, vm::configureUi, vm::clearImageCache, open)
}

@Composable
internal fun ProfileContent(state: ScopeState, configure: (UiPreferences) -> Unit, clearCache: () -> Unit, open: (String) -> Unit) {
    LazyColumn(contentPadding = rootPagePadding, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("profile-list")) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Ink), contentAlignment = Alignment.Center) {
                    Text("B", color = Background, fontSize = 29.sp, fontWeight = FontWeight.Black)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("RushB", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("v${BuildConfig.VERSION_NAME}", fontSize = 11.sp, color = Quiet)
                }
            }
        }
        item {
            CardBlock {
                Row(Modifier.fillMaxWidth()) {
                    ProfileCounter(state.savedMatches.size, "收藏比赛", Modifier.weight(1f)) { open("saved") }
                    ProfileCounter(state.followedTeams.size, "关注战队", Modifier.weight(1f)) { open("following|teams") }
                    ProfileCounter(state.followedPlayers.size, "关注选手", Modifier.weight(1f)) { open("following|players") }
                }
            }
        }
        item { CardBlock { SettingRow("外观", state.uiPreferences.themeMode.label, "appearance") { open("appearance") } } }
        item { SectionLabel("数据与存储") }
        item {
            CardBlock {
                SettingRow("数据连接", if (state.relayEnabled) "电脑中继" else "手机直连", "connection") { open("connection") }
                HorizontalDivider(color = Stroke, thickness = .5.dp)
                SettingRow("清理图片缓存", if (state.cleaningImages) "清理中…" else state.imageCacheMessage
                    ?: state.imageCacheBytes?.let { if (it < 1_048_576) "${it / 1024} KB" else String.format(java.util.Locale.ROOT, "%.1f MB", it / 1_048_576.0) }.orEmpty(),
                    "cache", enabled = !state.cleaningImages, onClick = clearCache)
            }
        }
        item { CardBlock { SettingRow("关于 RushB", BuildConfig.VERSION_NAME, "info") { open("about") } } }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AppearanceScreen(preferences: UiPreferences, configure: (UiPreferences) -> Unit) {
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("appearance-list")) {
        item { SettingsGroupLabel("显示") }
        item {
            CardBlock {
                SettingRow("主题", preferences.themeMode.label, "appearance", modifier = Modifier.testTag("appearance-theme")) {
                    editor = "theme"
                }
            }
        }
        item { SettingsGroupLabel("导航") }
        item {
            CardBlock {
                SettingRow("底部导航", preferences.navigationStyle.label, "grid", modifier = Modifier.testTag("appearance-navigation")) {
                    editor = "navigation"
                }
                HorizontalDivider(Modifier.padding(start = 43.dp), color = Stroke, thickness = .5.dp)
                SettingRow("启动页面", preferences.startTab.label, "matches", modifier = Modifier.testTag("appearance-start")) {
                    editor = "start"
                }
            }
        }
    }
    editor?.let { active ->
        val title = when (active) { "theme" -> "主题"; "navigation" -> "底部导航"; else -> "启动页面" }
        val options = when (active) {
            "theme" -> ThemeMode.entries.map { it.label }
            "navigation" -> NavigationStyle.entries.map { it.label }
            else -> RootTab.entries.map { it.label }
        }
        val current = when (active) { "theme" -> preferences.themeMode.label; "navigation" -> preferences.navigationStyle.label; else -> preferences.startTab.label }
        ModalBottomSheet(onDismissRequest = { editor = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Panel, contentColor = Ink) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    ActionIcon("close", "关闭$title") { editor = null }
                }
                Column(Modifier.selectableGroup()) {
                    options.forEach { label ->
                        val selected = current == label
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(8.dp))
                            .testTag("appearance-option-$active-$label")
                            .background(if (selected) AccentSurface else Panel)
                            .selectable(selected, role = Role.RadioButton) {
                                val next = when (active) {
                                    "theme" -> preferences.copy(themeMode = ThemeMode.entries.first { it.label == label })
                                    "navigation" -> preferences.copy(navigationStyle = NavigationStyle.entries.first { it.label == label })
                                    else -> preferences.copy(startTab = RootTab.entries.first { it.label == label })
                                }
                                configure(next)
                                editor = null
                            }.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f), fontSize = 15.sp, color = if (selected) Accent else Ink,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                            RadioButton(selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Accent, unselectedColor = Quiet))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupLabel(title: String) {
    Text(title, Modifier.padding(start = 14.dp, top = 12.dp, bottom = 2.dp), color = Quiet, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun ProfileCounter(count: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(count.toString(), fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = Quiet, fontSize = 11.sp)
    }
}

@Composable
private fun SettingRow(label: String, value: String, icon: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Glyph(icon, Quiet, 19.dp)
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Text(value, color = Quiet, fontSize = 12.sp)
        Glyph("next", Quiet, 14.dp)
    }
}

@Composable
internal fun FollowingScreen(players: Boolean, state: ScopeState, vm: ScopeViewModel, open: (String) -> Unit) {
    LazyColumn(contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${if (players) state.followedPlayers.size else state.followedTeams.size} ${if (players) "位" else "支"}", Modifier.weight(1f), fontSize = 12.sp, color = Quiet)
                TextButton({ open("search") }) { Text(if (players) "查找选手" else "查找战队") }
            }
        }
        if (players) {
            if (state.followedPlayers.isEmpty()) item { EmptyCard("暂无关注选手") }
            items(state.followedPlayers, key = { it.id }) { player ->
                CardBlock { PlayerRow(state.playerRefs.find { it.id == player.id } ?: player, { open("player|${player.id}") }) {
                    ActionIcon("check", "取消关注 ${player.name}", color = Accent) { vm.followPlayer(player) }
                } }
            }
        } else {
            if (state.followedTeams.isEmpty()) item { EmptyCard("暂无关注战队") }
            items(state.followedTeams.toList(), key = { it }) { id ->
                val team = state.resolveTeam(id) ?: Team(id, "战队 #$id")
                CardBlock { TeamRow(team, state, vm, { open("team|$id") }) }
            }
        }
    }
}
