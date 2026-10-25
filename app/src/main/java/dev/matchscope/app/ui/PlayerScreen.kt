package dev.matchscope.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.saveable.rememberSaveable
import dev.matchscope.app.data.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ConnectionScreen(state: ScopeState, vm: ScopeViewModel) {
    var enabled by remember { mutableStateOf(state.relayEnabled) }
    var token by remember { mutableStateOf(vm.relayToken()) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { CardBlock { InfoRow("连接方式", if(enabled) "电脑中继" else "手机直连") } }
        item { CardBlock {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("使用电脑中继", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Switch(enabled, { enabled = it })
            }
        } }
        if(enabled) {
            item { OutlinedTextField(token, { token = it }, label = { Text("中继令牌") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()) }
            item { Text("请先启动电脑中继并连接 USB。", fontSize = 12.sp, color = Quiet) }
        }
        item { Button({ vm.configureRelay(enabled, token) }, Modifier.fillMaxWidth()) { Text("保存") } }
        if (!enabled) item { CardBlock {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("手机连接", fontWeight = FontWeight.SemiBold)
                Chips(listOf("直接连接", "内置浏览器"), if (state.mobileTransport == MobileTransport.HTTPS) "直接连接" else "内置浏览器") {
                    vm.configureMobileMode(if (it == "直接连接") MobileTransport.HTTPS else MobileTransport.BROWSER)
                }
            }
            InfoRow("连接状态", state.mobileIssue?.takeIf { it.retryAtMillis > System.currentTimeMillis() }?.reason?.label ?: state.mobileConnection ?: "未检测")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ vm.checkMobileConnection(state.mobileTransport) }, Modifier.weight(1f), enabled = !state.busy) { Text("检测连接") }
            }
        } }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(id: String, state: ScopeState, vm: ScopeViewModel, open: (String) -> Unit) {
    var tab by rememberSaveable(id) { mutableStateOf("概括") }
    var matchFilter by rememberSaveable(id) { mutableStateOf("全部") }
    var honorFilter by rememberSaveable(id) { mutableStateOf("全部") }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var eventFilter by rememberSaveable(id) { mutableStateOf("全部") }
    var limit by rememberSaveable(id, tab, matchFilter, eventFilter, honorFilter) { mutableIntStateOf(30) }
    val data = state.players[id]
    LaunchedEffect(id, tab, data?.stamp?.capturedAt, data?.warning) {
        if (tab == "比赛" && data != null) vm.completeProfileSchedule(data.value.content.matches)
    }
    val ref = data?.value?.player ?: state.playerRefs.find { it.id == id }
    val followed = state.followedPlayers.any { it.id == id }
    LazyColumn(state = list, modifier = Modifier.testTag("player-detail-list"), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if(ref != null) item(key = "identity") { if (tab != "概括") CardBlock {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlayerPhoto(ref, Modifier.size(40.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(ref.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    data?.value?.team?.let { team -> Text(team.name, Modifier.clickable { open("team|${team.id}") }, color = Quiet, fontSize = 11.sp) }
                }
                TextButton({ vm.followPlayer(ref) }) { Text(if (followed) "已关注" else "关注", fontSize = 12.sp) }
            }
        } else CardBlock {
            Row(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(PanelRaised, Panel))).padding(start = 16.dp, top = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(ref.name, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    data?.value?.realName?.let { Text(it, fontSize = 13.sp, color = Quiet) }
                    data?.value?.let { p -> Text(listOfNotNull(p.country, p.age?.let { "$it 岁" }).joinToString(" · "), fontSize = 11.sp, color = Quiet) }
                    FilledTonalButton({ vm.followPlayer(ref) }) { Text(if(followed) "已关注" else "关注", fontSize = 12.sp) }
                }
                PlayerPhoto(ref, Modifier.width(84.dp).height(120.dp))
            }
            data?.value?.team?.let { team -> Row(Modifier.fillMaxWidth().clickable { open("team|${team.id}") }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TeamLogo(state.displayTeam(team), 30.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(team.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Glyph("next", Quiet, 18.dp)
            } }
        } }
        if(data == null) item { EmptyCard(if(state.busy) "加载中…" else "暂无选手统计") }
        else {
            val player = data.value
            if(data.warning != null) item { UpdateWarning(if (data.warning == "新闻更新失败") player.content.newsStamp ?: data.stamp else data.stamp, state.zone, state.zoneLabel) }
            stickyHeader(key = "tabs") { Box(Modifier.fillMaxWidth().background(Background).padding(vertical = 4.dp)) { PageTabs(listOf("概括", "比赛", "赛事", "状态") + (if (player.content.honors.isNotEmpty()) listOf("荣誉") else emptyList()) + "新闻", tab) { tab = it; scope.launch { list.scrollToItem(0) } } } }
            when (tab) {
                "概括" -> {
                    if (player.content.summary.isNotEmpty()) item { ProfileFacts(player.content.summary) }
                    val rating = player.statistics.filter { it.first.contains("Rating", true) }
                    if (rating.isNotEmpty()) item { CardBlock { player.statisticsPeriod?.let { Text(it, Modifier.padding(12.dp), fontSize = 11.sp, color = Quiet) }; rating.forEach { (name, value) -> InfoRow(name, value) } } }
                    profilePeers("队友", player.content.teammates, open)
                    playerCareer(player.content.career, open)
                    profileEarnings(player.content.earnings, open)
                    profilePeers("相关选手", player.content.similarPlayers, open)
                    val trophies = player.content.events.filter { it.category in setOf("冠军", "MVP", "EVP", "奖杯") || it.placement == "1st" }.take(8)
                    if (trophies.isNotEmpty() && player.content.honors.isEmpty()) {
                        item { SectionLabel("荣誉") }
                        items(trophies) { trophy -> CardBlock(Modifier.clickable { open("event|${trophy.event.id}") }) { InfoRow(trophy.event.name, trophy.category ?: trophy.placement.orEmpty()) } }
                    }
                }
                "比赛" -> profileMatches(player.content, state, vm, open, matchFilter, { matchFilter = it }, limit, { limit += 30 }, { vm.moreProfileMatches("player", id) })
                "赛事" -> profileEvents(player.content, open, eventFilter, { eventFilter = it }, limit, { limit += 30 })
                "状态" -> {
                    player.statisticsPeriod?.let { period -> item { Text(period, fontSize = 12.sp, color = Quiet) } }
                    if (player.content.roles.isNotEmpty()) item { PlayerRadar(player.content.roles) }
                    if (player.statistics.isEmpty()) item { EmptyCard("暂无选手统计") }
                    else item { CardBlock { player.statistics.filter { (name, _) -> player.content.roles.none { it.label.equals(name, true) } }.forEach { (name, value) -> InfoRow(profileLabel(name), value) } } }
                }
                "荣誉" -> profileHonors(player.content.honors, honorFilter, { honorFilter = it }, limit, { limit += 30 }, open)
                "新闻" -> profileNews(player.content.news, limit, state.zone, open) { limit += 30 }
            }
            item { SourceLink(data.value.player.url) }
        }
    }
}
