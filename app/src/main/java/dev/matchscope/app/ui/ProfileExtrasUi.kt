package dev.matchscope.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.matchscope.app.data.*

internal fun LazyListScope.profileHonors(honors: List<ProfileHonor>, selected: String, select: (String) -> Unit, limit: Int, more: () -> Unit, open: (String) -> Unit) {
    item { Chips(listOf("全部") + honors.map { it.category }.distinct(), selected, select) }
    val visible = honors.filter { selected == "全部" || it.category == selected }
    if (visible.isEmpty()) item { EmptyCard("暂无荣誉") }
    items(visible.take(limit), key = { it.key }) { honor ->
        CardBlock(Modifier.clickable(enabled = honor.event != null) { honor.event?.let { open("event|${it.id}") } }) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IdentityImage(honor.imageUrl, "${honor.title} 奖杯", Modifier.size(44.dp)) { Glyph("matches",Quiet,24.dp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(honor.title,fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                    Text(listOfNotNull(honor.category,honor.year?.toString(),honor.placement?.let { "#$it" }).joinToString(" · "),fontSize=11.sp,color=Quiet)
                    honor.newsUrl?.let { SourceLink(it,"查看报道 ↗") }
                }
                if (honor.event != null) Glyph("next",Quiet,16.dp)
            }
        }
    }
    if (visible.size > limit) item { TextButton(more,Modifier.fillMaxWidth()) { Text("显示更多") } }
}

internal fun LazyListScope.profilePeers(title: String, players: List<ProfilePeer>, open: (String) -> Unit) {
    if (players.isEmpty()) return
    item { SectionLabel(title) }
    if (title == "队友") {
        item { RosterStrip(players.map { it.player }, players.associate { it.player.id to listOfNotNull(it.rating?.let { rating -> "Rating $rating" }, it.roles.takeIf { roles -> roles.isNotEmpty() }?.joinToString(" · ")).joinToString("\n") }, open) }
        return
    }
    item { ContentGrid(players, 2) { peer -> CardBlock(Modifier.fillMaxWidth().clickable { open("player|${peer.player.id}") }) {
            PlayerPhoto(peer.player,Modifier.fillMaxWidth().height(135.dp))
            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(peer.player.name,fontSize=13.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                peer.teamName?.let { Text(it,fontSize=10.sp,color=Quiet,maxLines=1,overflow=TextOverflow.Ellipsis) }
                peer.rating?.let { Text("Rating ${peer.ratingVersion.orEmpty()} · $it",fontSize=10.sp,color=Quiet) }
                if (peer.roles.isNotEmpty()) Text(peer.roles.joinToString(" · "),fontSize=10.sp,color=Green)
            }
        }
    } }
}
internal fun LazyListScope.profileEarnings(earnings: List<TeamEarning>, open: (String) -> Unit) {
    if (earnings.isEmpty()) return
    item { SectionLabel("奖金分布") }
    items(earnings) { item -> CardBlock(Modifier.clickable(enabled = item.team.id.toLongOrNull() != null) { open("team|${item.team.id}") }) {
        Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            TeamLogo(item.team,36.dp)
            Text(item.team.name,Modifier.weight(1f),fontSize=13.sp,fontWeight=FontWeight.SemiBold)
            Text(item.prize,fontSize=13.sp,color=Green)
        }
    } }
}

@Composable
internal fun EventStatisticsPanel(groups: List<EventStatistics>, grid: Boolean = false, open: (String) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(groups.firstOrNull()?.key) }
    var order by rememberSaveable { mutableStateOf("Rating 降序") }
    val group = groups.firstOrNull { it.key == selected } ?: groups.firstOrNull() ?: return
    val players = when (order) {
        "地图数降序" -> group.players.sortedByDescending { it.maps ?: -1 }
        "选手名称" -> group.players.sortedBy { it.player.name.lowercase() }
        else -> group.players.sortedByDescending { it.rating?.toDoubleOrNull() ?: -1.0 }
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f)) {
                if (groups.size > 1) Chips(groups.map { it.label },group.label) { name -> selected=groups.first { it.label==name }.key }
            }
            ChoiceMenu(order, listOf("Rating 降序", "地图数降序", "选手名称"), { it }) { order = it }
        }
        if (grid) ContentGrid(players, 2) { row -> CardBlock(Modifier.clickable { open("player|${row.player.id}") }) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${players.indexOf(row) + 1}", Modifier.weight(1f), fontSize = 12.sp, color = Quiet)
                PlayerPhoto(row.player, Modifier.size(48.dp))
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(row.player.name, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                row.teamName?.let { Text(it, fontSize = 11.sp, color = Quiet) }
                Text("Rating ${row.ratingVersion.orEmpty()} · ${row.rating ?: "—"}", fontSize = 12.sp, color = Ink)
                row.maps?.let { Text("$it 图", fontSize = 11.sp, color = Quiet) }
            }
        } } else CardBlock {
            Row(Modifier.padding(14.dp)) {
                Text("选手",Modifier.weight(1f),fontSize=11.sp,color=Quiet)
                Text("地图",Modifier.width(45.dp),fontSize=11.sp,color=Quiet)
                Text("Rating ${group.players.mapNotNull { it.ratingVersion }.distinct().singleOrNull().orEmpty()}",fontSize=11.sp,color=Quiet)
            }
            players.forEachIndexed { index, row ->
                HorizontalDivider(color=Subtle)
                Row(Modifier.fillMaxWidth().clickable { open("player|${row.player.id}") }.padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("${index + 1}", Modifier.width(18.dp), fontSize = 10.sp, color = Quiet)
                    PlayerPhoto(row.player,Modifier.size(36.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text(row.player.name,fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                        row.teamName?.let { Text(it,fontSize=11.sp,color=Quiet) }
                    }
                    Text(row.maps?.toString() ?: "—",Modifier.width(34.dp),fontSize=12.sp,color=Quiet)
                    Text(row.rating ?: "—",fontSize=16.sp,fontWeight=FontWeight.Bold,color=Ink)
                }
            }
        }
    }
}
