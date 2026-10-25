@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.matchscope.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import dev.matchscope.app.data.*
import dev.matchscope.app.R
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal val pagePadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp)
internal val LocalRootActive = staticCompositionLocalOf { true }
internal val LocalRootBottomInset = staticCompositionLocalOf { 0.dp }
internal val rootPagePadding: PaddingValues
    @Composable get() = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp + LocalRootBottomInset.current)

@Composable
fun MatchScopeApp(vm: ScopeViewModel) {
    SourceBrowser(vm.browser)
    DeviceTimezoneEffect(vm)
    val state by vm.state.collectAsStateWithLifecycle()
    val artworkRevision by MediaLoader.retries.collectAsStateWithLifecycle()
    val lightTeamLogos = remember(state.rankings) { state.rankings.items.mapNotNull { entry ->
        entry.team.logoUrl?.let { entry.team.id to it }
    }.toMap() }
    var tabName by rememberSaveable { mutableStateOf(state.uiPreferences.startTab.name) }
    val tab = RootTab.valueOf(tabName)
    var dateString by rememberSaveable { mutableStateOf(LocalDate.now(state.zone).toString()) }
    var routes by rememberSaveable { mutableStateOf(listOf<String>()) }
    val tabStates = rememberSaveableStateHolder()
    val detailStates = rememberSaveableStateHolder()
    val open: (String) -> Unit = { routes = routes + it }
    val back: () -> Unit = { routes = routes.dropLast(1) }
    val route = routes.lastOrNull()
    LaunchedEffect(route, tab) { vm.dismissMessage() }
    val matchScrolls = rememberSaveable(saver = listSaver<MutableMap<String, LazyListState>, Any>(
        save = { map -> map.entries.flatMap { listOf(it.key, it.value.firstVisibleItemIndex, it.value.firstVisibleItemScrollOffset) } },
        restore = { values -> values.chunked(3).associate { it[0] as String to LazyListState(it[1] as Int, it[2] as Int) }.toMutableMap() })) { mutableMapOf() }
    val matchScroll = matchScrolls.getOrPut("${routes.size}:$route") { LazyListState() }
    val activeMatch = route?.takeIf { it.startsWith("match|") }?.substringAfter('|')?.let { id -> state.allMatches.find { it.id == id } }
    val compact by remember(matchScroll) { derivedStateOf { matchScroll.firstVisibleItemIndex > 0 || matchScroll.firstVisibleItemScrollOffset > 200 } }
    BackHandler(routes.isNotEmpty(), back)
    val title = when (route?.substringBefore('|')) {
        "match" -> "比赛详情"; "team" -> state.resolveTeam(route.substringAfter('|').substringBefore('|'))?.name ?: "战队详情"; "event" -> state.resolveEvent(route.substringAfter('|'))?.name ?: "赛事详情"
        "player" -> state.playerRefs.firstOrNull { it.id == route.substringAfter('|') }?.name ?: "选手档案"; "connection" -> "数据连接"
        "history" -> if (route.endsWith("|h2h")) "历史交战" else "比赛战绩"
        "search" -> "搜索"; "news" -> "资讯"; "article" -> "新闻"; "saved" -> "收藏的比赛"; "about" -> stringResource(R.string.about_app_title, stringResource(R.string.app_name))
        "following" -> if (route.endsWith("|players")) "关注选手" else "关注战队"
        "appearance" -> "外观"
        else -> tab.label
    }
    val retry: () -> Unit = {
        val id = route?.substringAfter('|').orEmpty()
        when (route?.substringBefore('|')) {
            "match" -> vm.refreshMatch(id)
            "team" -> vm.loadTeam(id, true)
            "player" -> vm.loadPlayer(id, true)
            "event" -> vm.loadEvent(id, true)
            "article" -> vm.loadArticle(id, true)
            "news" -> vm.refreshNews()
            "history" -> vm.retryMatchHistory(id.substringBefore('|'), id.substringAfter('|').takeUnless { it == "h2h" })
            "connection" -> vm.retryLastRequest()
            else -> when (tab) { RootTab.CALENDAR -> vm.refreshEvents(); RootTab.RANKINGS -> vm.retryLastRequest(); RootTab.PROFILE -> vm.retryLastRequest(); else -> vm.refreshMatches(LocalDate.parse(dateString)) }
        }
    }
    val website = when (route?.substringBefore('|')) {
        "match" -> "https://www.hltv.org/matches/${route.substringAfter('|')}/-"
        "team" -> "https://www.hltv.org/team/${route.substringAfter('|')}/-"
        "player" -> "https://www.hltv.org/player/${route.substringAfter('|')}/-"
        "event" -> "https://www.hltv.org/events/${route.substringAfter('|')}/-"
        "article" -> route.substringAfter('|')
        "news" -> NewsParser.archiveUrl(state.newsMonth)
        else -> if (route == null && tab == RootTab.RANKINGS) "https://www.hltv.org/ranking/teams" else "https://www.hltv.org/"
    }
    val mobileRecovery = route?.substringBefore('|') in setOf("match", "team", "player", "event", "connection") || route == null && tab in setOf(RootTab.MATCHES, RootTab.CALENDAR)
    CompositionLocalProvider(LocalArtworkRevision provides artworkRevision, LocalLightTeamLogos provides lightTeamLogos,
        LocalRecovery provides RecoveryActions(!state.busy, retry) { vm.loginAndRetry(website, mobileRecovery, retry) }) {
    Box(Modifier.fillMaxSize().background(Background), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 620.dp).fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = if(route == null) 12.dp else 0.dp, end = 2.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                if (route != null) ActionIcon("back", "返回", onClick = back)
                if (activeMatch != null && compact) CompactMatchTitle(activeMatch, state.zone, Modifier.weight(1f))
                else Text(title, fontWeight = FontWeight.Bold, fontSize = if(route == null) 20.sp else 18.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (route == null) {
                    ActionIcon("news", "新闻资讯") { open("news") }
                    ActionIcon("search", "全局搜索") { open("search") }
                }
                val detailId = route?.split('|')?.getOrNull(1)
                if (detailId?.toLongOrNull() != null) when (route?.substringBefore('|')) {
                    "match" -> ActionIcon("refresh", "刷新", !state.busy) { vm.refreshMatch(detailId) }
                    "player" -> ActionIcon("refresh", "刷新", !state.busy) { vm.loadPlayer(detailId, true) }
                    "team" -> ActionIcon("refresh", "刷新", !state.busy) { vm.loadTeam(detailId, true) }
                    "event" -> ActionIcon("refresh", "刷新", !state.busy) { vm.loadEvent(detailId, true) }
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = Subtle)
            state.message?.takeUnless { state.requestFailed && route?.substringBefore('|') in setOf("news", "article", "history") }?.let { message ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(WarningSurface).padding(start = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(message, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
                        ActionIcon("close", "关闭提示", onClick = vm::dismissMessage)
                    }
                    if (state.requestFailed) RecoveryButtons()
                }
            }
            Box(Modifier.weight(1f)) {
                if (route == null) {
                    RootPageFrame(tab, state.uiPreferences.navigationStyle, { tabName = it.name }) {
                    val pager = rememberPagerState(initialPage = tab.ordinal) { RootTab.entries.size }
                    LaunchedEffect(tab) { if (pager.currentPage != tab.ordinal) pager.animateScrollToPage(tab.ordinal, animationSpec = tween(180)) }
                    HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = false, verticalAlignment = Alignment.Top,
                        beyondViewportPageCount = RootTab.entries.lastIndex, key = { RootTab.entries[it].name }) { page ->
                        val pageTab = RootTab.entries[page]
                        val active = pageTab == tab
                        var lastVisibleState by remember { mutableStateOf(state) }
                        SideEffect { if (active) lastVisibleState = state }
                        val visibleState = if (active) state else lastVisibleState
                        CompositionLocalProvider(LocalRootActive provides active) {
                            tabStates.SaveableStateProvider("tab:$pageTab") { when(pageTab) {
                                RootTab.MATCHES -> MatchesScreen(visibleState, LocalDate.parse(dateString), { dateString = it.toString() }, { vm.refreshMatches(LocalDate.parse(dateString)) }, vm::saveMatch, open)
                                RootTab.CALENDAR -> CalendarScreen(visibleState, vm, open) { dateString = it.toString(); tabName = RootTab.MATCHES.name }
                                RootTab.RANKINGS -> RankingsScreen(visibleState, vm, open)
                                RootTab.PROFILE -> ProfileScreen(visibleState, vm, open)
                            } }
                        }
                    }
                    }
                } else detailStates.SaveableStateProvider("detail:${routes.size}:$route") { DetailScreen(route, state, vm, open, matchScroll) }
            }
            if (route != null) Spacer(Modifier.navigationBarsPadding())
        }
    }
}
}

@Composable
internal fun Chips(labels: List<String>, selected: String, onSelected: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        labels.forEach { label ->
            FilterChip(selected == label, { onSelected(label) }, label = { Text(label, fontSize = 12.sp,
                fontWeight = if (selected == label) FontWeight.SemiBold else FontWeight.Normal) },
                leadingIcon = if (selected == label) { { Glyph("check", Background, 15.dp) } } else null,
                shape = RoundedCornerShape(6.dp),
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected == label, borderColor = Stroke, selectedBorderColor = Accent),
                colors = FilterChipDefaults.filterChipColors(containerColor = Panel, labelColor = Ink, selectedContainerColor = Accent, selectedLabelColor = Background))
        }
    }
}

@Composable
internal fun SearchField(value: String, placeholder: String, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, Modifier.fillMaxWidth(), placeholder = { Text(placeholder, fontSize = 13.sp, color = Quiet) }, leadingIcon = { Glyph("search", Quiet, 20.dp) }, singleLine = true,
        trailingIcon = if (value.isNotEmpty()) { { ActionIcon("close", "清空搜索") { onValue("") } } } else null,
        shape = RoundedCornerShape(8.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = PanelRaised, focusedContainerColor = PanelRaised, unfocusedBorderColor = Stroke, focusedBorderColor = Accent))
}

@Composable
internal fun TeamRow(team: Team, state: ScopeState, vm: ScopeViewModel, onOpen: () -> Unit, rank: Int? = null, points: Int? = null, matchHint: String? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 13.dp, top = 13.dp, bottom = 13.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if(rank != null) Text(rank.toString(), Modifier.width(23.dp), fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center)
        TeamLogo(state.displayTeam(team))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(team.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if(points != null || team.region != "未分类") Text(if(points == null) team.region else "$points 积分", fontSize = 11.sp, color = Quiet)
            if (matchHint != null) Text(matchHint, fontSize = 11.sp, color = Quiet)
        }
        ActionIcon(if(team.id in state.followedTeams) "check" else "star", if(team.id in state.followedTeams) "取消关注 ${team.name}" else "关注 ${team.name}", color = if(team.id in state.followedTeams) Accent else Quiet) { vm.followTeam(team.id) }
    }
}

@Composable
private fun DetailScreen(route: String, state: ScopeState, vm: ScopeViewModel, open: (String) -> Unit, matchScroll: LazyListState) {
    val parts = route.split('|')
    when(parts[0]) {
        "match" -> {
            val id = parts.getOrNull(1).orEmpty()
            LaunchedEffect(id) { vm.state.first { !it.busy }; vm.loadMatch(id) }
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(id, lifecycle) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (isActive) { vm.updateVisibleMatch(id); delay(20_000) }
                }
            }
            state.allMatches.find { it.id == id }?.let { MatchDetailScreen(it, state, vm, open, matchScroll) }
                ?: EmptyCard(if (state.busy) "加载中…" else "比赛加载失败", action = if (state.busy) null else "重新加载") { vm.refreshMatch(id) }
        }
        "history" -> state.allMatches.find { it.id == parts.getOrNull(1) }?.let {
            MatchHistoryScreen(it, parts.getOrNull(2)?.takeUnless { it == "h2h" }, state, vm, open)
        }
        "player" -> {
            val id = parts.getOrNull(1).orEmpty()
            LaunchedEffect(id) {
                val ready = vm.state.first { !it.busy }
                if(id !in ready.players) vm.loadPlayer(id)
            }
            PlayerScreen(id, state, vm, open)
        }
        "connection" -> ConnectionScreen(state, vm)
        "team" -> {
            val vrsFeed = parts.getOrNull(2)?.let { state.vrs[VrsRegion.valueOf(it)] }
            val vrsEntry = vrsFeed?.items?.find { it.team.id == parts.getOrNull(1) && (parts.getOrNull(3)?.toIntOrNull()?.let { rank -> it.rank == rank } ?: true) }
            val team = vrsEntry?.team ?: state.resolveTeam(parts.getOrNull(1).orEmpty()) ?: parts.getOrNull(1)?.takeIf { it.toLongOrNull() != null }?.let { Team(it, "载入中") }
            val id = team?.id.orEmpty()
            LaunchedEffect(id) {
                val ready = vm.state.first { !it.busy }
                if(id.toLongOrNull() != null && id !in ready.teamProfiles) vm.loadTeam(id)
            }
            if(team != null) TeamProfileScreen(team, vrsFeed?.stamp ?: state.rankings.stamp, state, vm, open)
            else EmptyCard("战队加载失败", "请返回重试")
        }
        "event" -> {
            val event = state.resolveEvent(parts.getOrNull(1).orEmpty()) ?: parts.getOrNull(1)?.takeIf { it.toLongOrNull() != null }?.let { Tournament(it, "载入中") }
            val id = event?.id.orEmpty()
            LaunchedEffect(id) {
                val ready = vm.state.first { !it.busy }
                if(id.toLongOrNull() != null && id !in ready.eventProfiles) vm.loadEvent(id)
            }
            if(event != null) EventDetailScreen(event, state, vm, open)
        }
        "saved" -> LazyColumn(contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val matches = state.allMatches.filter { it.id in state.savedMatches }
            if(matches.isEmpty()) item { EmptyCard("暂无收藏比赛") }
            items(matches) { match -> ContextMatchCard(match, state, { open("match|${match.id}") }, { vm.saveMatch(match.id) }) }
        }
        "following" -> FollowingScreen(parts.getOrNull(1) == "players", state, vm, open)
        "search" -> SearchScreen(state, vm, open)
        "news" -> NewsScreen(state, vm, open)
        "article" -> NewsDetailScreen(route.substringAfter('|'), state, vm, open)
        "about" -> AboutScreen()
        "appearance" -> AppearanceScreen(state.uiPreferences, vm::configureUi)
    }
}

@Composable
private fun SearchScreen(state: ScopeState, vm: ScopeViewModel, open: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val teams = if(q.isEmpty()) emptyList() else state.teams.filter { it.name.contains(q, true) || it.roster.any { player -> player.contains(q, true) } }
    val matches = if(q.isEmpty()) emptyList() else state.allMatches.filter { "${it.left.name} ${it.right.name} ${it.event.name}".contains(q, true) }
    val players = if(q.isEmpty()) emptyList() else state.playerRefs.filter { it.name.contains(q, true) }
    LazyColumn(contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SearchField(query, "搜索战队、选手或比赛") { query = it } }
        if (state.networkAvailable == false) item { Text("离线 · 仅显示本机记录", fontSize = 11.sp, color = Quiet) }
        if(q.isNotEmpty() && !state.libraryReady) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if(q.isNotEmpty() && state.libraryReady && teams.isEmpty() && matches.isEmpty() && players.isEmpty()) item { EmptyCard("没有找到结果") }
        if(players.isNotEmpty()) item { SectionLabel("选手") }
        items(players, key = { "player:${it.id}" }) { player -> CardBlock { PlayerRow(player, { open("player|${player.id}") }) } }
        if(teams.isNotEmpty()) item { SectionLabel("战队") }
        items(teams, key = { "team:${it.id}" }) { team ->
            val rosterMatch = team.roster.filter { it.contains(q, true) }.takeIf { !team.name.contains(q, true) && it.isNotEmpty() }
            CardBlock { TeamRow(team, state, vm, { open("team|${team.id}") }, matchHint = rosterMatch?.joinToString(" · ")?.let { "阵容 · $it" }) }
        }
        searchMatchGroups(matches, Instant.now(), state.matches.stamp).forEach { group ->
            item(key = "section:${group.title}") { SectionLabel(group.title) }
            items(group.matches, key = { "match:${it.id}" }) { match -> ContextMatchCard(match, state, { open("match|${match.id}") }, { vm.saveMatch(match.id) }) }
        }
    }
}

@Composable
private fun AboutScreen() {
    LazyColumn(contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { CardBlock { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.app_name), fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text(dev.matchscope.app.BuildConfig.VERSION_NAME, color = Quiet)
            Text("个人开源项目，非 HLTV 官方应用。", lineHeight = 23.sp)
            Text("数据与图片归各自权利人所有。", lineHeight = 23.sp)
        } } }
        item { SectionLabel("数据来源") }
        item { CardBlock { Column(Modifier.padding(18.dp)) {
            SourceLink("https://www.hltv.org/matches", "HLTV 比赛与赛果 ↗")
            SourceLink("https://www.hltv.org/ranking/teams", "HLTV 世界排名 ↗")
            SourceLink("https://github.com/ValveSoftware/counter-strike_regional_standings", "Valve 官方 VRS 仓库 ↗")
            SourceLink("https://www.hltv.org/news/42485/introducing-rating-30", "Rating 3.0 指标说明 ↗")
        } } }
        item { SectionLabel("开源许可") }
        item { CardBlock { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("GeckoView 158 · Mozilla Public License 2.0", fontSize = 13.sp)
            SourceLink("https://www.mozilla.org/MPL/2.0/", "浏览器引擎许可证 ↗")
            SourceLink("https://hg.mozilla.org/releases/mozilla-release/rev/eb9a6e3a736802c8e080a76c44dc81f409d99d54", "此版本引擎源码 ↗")
        } } }
    }
}
