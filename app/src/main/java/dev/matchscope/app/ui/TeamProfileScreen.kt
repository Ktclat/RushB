package dev.matchscope.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.matchscope.app.data.*

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun TeamProfileScreen(team: Team, stamp: SourceStamp, state: ScopeState, vm: ScopeViewModel, open: (String) -> Unit) {
    val data = state.teamProfiles[team.id]
    val profile = data?.value
    val current = profile?.team ?: team
    var tab by rememberSaveable(team.id) { mutableStateOf("概括") }
    var matchFilter by rememberSaveable(team.id) { mutableStateOf("全部") }
    var honorFilter by rememberSaveable(team.id) { mutableStateOf("全部") }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var eventFilter by rememberSaveable(team.id) { mutableStateOf("全部") }
    var limit by rememberSaveable(team.id, tab, matchFilter, eventFilter, honorFilter) { mutableIntStateOf(30) }
    LaunchedEffect(team.id, tab, data?.stamp?.capturedAt, data?.warning) {
        if (tab == "比赛" && profile != null) vm.completeProfileSchedule(profile.content.matches)
    }
    LazyColumn(state = list, modifier = Modifier.testTag("team-detail-list"), contentPadding = pagePadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "identity") { if (tab != "概括") CardBlock {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TeamLogo(state.displayTeam(current), 36.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(current.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    state.hltvRank(team.id)?.let { Text("HLTV #$it", fontSize = 11.sp, color = Quiet) }
                }
                if (!team.id.startsWith("vrs:")) TextButton({ vm.followTeam(team.id) }) { Text(if (team.id in state.followedTeams) "已关注" else "关注", fontSize = 12.sp) }
            }
        } else CardBlock {
            Row(Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TeamLogo(state.displayTeam(current), 44.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(current.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (current.region != "未分类") Text(current.region, fontSize = 12.sp, color = Quiet)
                }
                if (!team.id.startsWith("vrs:")) OutlinedButton({ vm.followTeam(team.id) }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text(if (team.id in state.followedTeams) "已关注" else "关注", fontSize = 12.sp)
                }
            }
            val facts = listOfNotNull(state.hltvRank(team.id)?.let { "HLTV 世界排名" to "#$it" }, profile?.coach?.let { "教练" to it })
            if (facts.isNotEmpty()) {
                HorizontalDivider(color = Stroke, thickness = .5.dp)
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    facts.forEach { (label, value) -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, fontSize = 10.sp, color = Quiet)
                        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    } }
                }
            }
        } }
        if (profile == null) {
            item { SectionLabel("阵容", stamp.publishedOn.orEmpty()) }
            if (current.roster.isEmpty()) item { EmptyCard(if (state.busy) "加载中…" else "暂无阵容") }
            else item { CardBlock { current.roster.forEach { InfoRow(it, "") } } }
        } else {
            if (data.warning != null) item { UpdateWarning(if (data.warning == "新闻更新失败") profile.content.newsStamp ?: data.stamp else data.stamp, state.zone, state.zoneLabel) }
            stickyHeader(key = "tabs") { Box(Modifier.fillMaxWidth().background(Background).padding(vertical = 4.dp)) { PageTabs(listOf("概括", "比赛", "赛事") + (if (profile.content.honors.isNotEmpty()) listOf("荣誉") else emptyList()) + "新闻", tab) { tab = it; scope.launch { list.scrollToItem(0) } } } }
            when (tab) {
                "概括" -> {
                    val summary = profile.content.summary.filterNot { it.first in setOf("World ranking", "Coach") }
                    item { SectionLabel("当前阵容") }
                    if (profile.players.isNotEmpty()) item { RosterStrip(profile.players, open = open) }
                    if (summary.isNotEmpty()) item { ProfileFacts(summary, columns = 3) }
                    items(profile.content.roster, key = { "roster:${it.player.id}" }) { member -> CardBlock(Modifier.clickable { open("player|${member.player.id}") }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PlayerPhoto(member.player, Modifier.size(54.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(member.player.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(listOfNotNull(member.country, member.status?.let(::profileLabel)).joinToString(" · "), fontSize = 11.sp, color = Quiet)
                                member.tenure?.let { Text(it, fontSize = 11.sp, color = Quiet) }
                                if (member.roles.isNotEmpty()) Text(member.roles.joinToString(" · "), fontSize = 11.sp, color = Green)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                member.rating?.let { Text(it, fontWeight = FontWeight.Bold, color = Ink); Text("Rating", fontSize = 9.sp, color = Quiet) }
                                member.maps?.let { Text("$it 图", fontSize = 11.sp, color = Quiet) }
                            }
                        }
                    } }
                    if (profile.content.maps.isNotEmpty()) item { SectionLabel("地图表现") }
                    items(profile.content.maps) { map -> CardBlock {
                        InfoRow(map.name, map.winRate)
                        if (map.wins != null) InfoRow("胜 / 平 / 负", "${map.wins} / ${map.draws ?: "—"} / ${map.losses ?: "—"}")
                        map.statistics.forEach { (name, value) -> InfoRow(profileLabel(name), value) }
                    } }
                }
                "比赛" -> profileMatches(profile.content, state, vm, open, matchFilter, { matchFilter = it }, limit, { limit += 30 }, { vm.moreProfileMatches("team", team.id) })
                "赛事" -> profileEvents(profile.content, open, eventFilter, { eventFilter = it }, limit, { limit += 30 })
                "荣誉" -> profileHonors(profile.content.honors, honorFilter, { honorFilter = it }, limit, { limit += 30 }, open)
                "新闻" -> profileNews(profile.content.news, limit, state.zone, open) { limit += 30 }
            }
        }
        item { SourceLink(current.url) }
    }
}

internal fun profileLabel(name: String): String {
    if (" · " in name) return name.split(" · ").joinToString(" · ") { profileLabel(it) }
    return mapOf(
    "Born" to "出生日期", "World ranking" to "世界排名", "Valve ranking" to "Valve 排名", "Coach" to "教练", "Average player age" to "平均年龄",
    "Win rate" to "胜率", "Wins" to "胜场", "Losses" to "负场", "Matches" to "比赛", "Maps" to "地图", "Rating" to "Rating",
    "Teams" to "效力战队", "Prize money" to "赛事奖金", "Total prize money" to "赛事奖金", "Total winnings" to "赛事奖金", "Earnings" to "赛事奖金",
    "Days in current team" to "当前战队天数", "Days in teams" to "职业战队天数", "Major won" to "Major 冠军", "Major trophies" to "Major 奖杯", "Notable trophies" to "重要赛事奖杯",
    "Major EVPs" to "Major EVP", "Total EVPs" to "EVP 总数", "Total MVPs" to "MVP 总数", "Weeks in top30 for core" to "核心阵容前 30 周数", "Current win streak" to "当前连胜", "Last 3 months" to "近 3 个月", "Top 20" to "年度 Top 20", "Award" to "年度奖项",
    "Majors played" to "Major 参赛", "Majors won" to "Major 冠军", "LANs played" to "线下赛参赛", "LANs won" to "线下赛冠军",
    "Trophies" to "奖杯", "MVPs" to "MVP", "EVPs" to "EVP", "Active" to "在役", "Benched" to "替补", "Substitute" to "替补",
    "MVP medals" to "MVP 奖章",
    "Coach tenure" to "执教时长", "Maps coached" to "执教地图", "Coach trophies" to "教练奖杯", "Coach win rate" to "执教胜率",
    "Firepower" to "火力", "Entrying" to "突破", "Trading" to "补枪", "Opening" to "首杀", "Clutching" to "残局", "Sniping" to "狙击", "Utility" to "道具",
    "First pick" to "首选率", "First ban" to "首禁率", "Times played" to "出场次数", "Total rounds" to "总回合", "Rounds won" to "获胜回合", "Win percent" to "胜率"
    )[name] ?: name
}
