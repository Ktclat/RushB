package dev.matchscope.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.matchscope.app.data.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun EventTeams(teams: List<Team>, open: (String) -> Unit) {
    var grid by rememberSaveable { mutableStateOf(true) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LayoutHeading("参赛队伍", grid) { grid = it }
        ContentGrid(teams, if (grid) 4 else 1) { team -> CardBlock(Modifier.clickable { open("team|${team.id}") }) {
            if (grid) Column(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                TeamLogo(team, 36.dp)
                Text(team.name, fontSize = 11.sp, lineHeight = 14.sp, minLines = 2, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            } else Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TeamLogo(team, 34.dp); Text(team.name, fontSize = 14.sp)
            }
        } }
    }
}

@Composable
internal fun EventPrizes(prizes: List<EventPrize>, open: (String) -> Unit) {
    var grid by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LayoutHeading("奖金分配", grid) { grid = it }
        ContentGrid(prizes, if (grid) 2 else 1) { prize -> CardBlock(Modifier.clickable(enabled = prize.team != null) { prize.team?.let { open("team|${it.id}") } }) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    prize.team?.let { TeamLogo(it, 28.dp) }
                    Column(Modifier.weight(1f)) {
                        Text(prize.place.replace(Regex("(?<=\\d)(st|nd|rd|th)"), "").let { if (it.matches(Regex("[0-9]+(?:-[0-9]+)?"))) "第 $it 名" else it }, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(prize.team?.name ?: "待定", fontSize = 11.sp, color = Quiet)
                    }
                }
                prize.playerPrize?.let { Text("选手奖金  $it", fontSize = 12.sp, color = Green) }
                prize.clubPrize?.let { Text("俱乐部分成  $it", fontSize = 11.sp, color = Quiet) }
            }
        } }
    }
}

@Composable
internal fun EventMaps(maps: List<String>) {
    var grid by rememberSaveable { mutableStateOf(true) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LayoutHeading("地图池", grid) { grid = it }
        ContentGrid(maps, if (grid) 3 else 1) { name -> MapBackdrop(name, Modifier.fillMaxWidth().height(64.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(mapLabel(name), color = Color.White, fontSize = 12.sp)
                Text(name, color = Color.White.copy(alpha = .75f), fontSize = 10.sp)
            }
        } }
    }
}

@Composable
internal fun EventFacts(title: String, facts: List<Pair<String, String>>) {
    var grid by rememberSaveable(title) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LayoutHeading(title, grid) { grid = it }
        if (grid) ProfileFacts(facts) else CardBlock { facts.forEach { (name, value) -> InfoRow(name, value) } }
    }
}

@Composable
internal fun MiniMatchCard(match: Match, zone: ZoneId, open: () -> Unit) {
    CardBlock(Modifier.clickable(onClick = open)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text((match.startsAt ?: match.resultAt)?.atZone(zone)?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "时间待定", fontSize = 11.sp, color = Quiet)
            listOf(match.left to match.leftScore, match.right to match.rightScore).forEach { (team, score) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    TeamLogo(team, 22.dp)
                    Text(team.name, Modifier.weight(1f), fontSize = 11.sp)
                    Text(score?.toString() ?: "—", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(match.format, fontSize = 10.sp, color = Quiet)
        }
    }
}
