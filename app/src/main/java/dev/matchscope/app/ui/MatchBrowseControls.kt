@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.matchscope.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.matchscope.app.data.*

@Composable
internal fun MatchToolbarButton(icon: String, label: String, description: String, active: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (active) AccentSurface else Color.Transparent)
        .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description; stateDescription = label },
        contentAlignment = Alignment.Center) {
        Glyph(icon, if (active) Accent else Ink, 21.dp)
        if (active) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(4.dp).clip(RoundedCornerShape(2.dp)).background(Accent))
    }
}

@Composable
internal fun MatchFilterSheet(region: MatchRegion, level: MatchLevel, matches: List<Match>,
    dismiss: () -> Unit, apply: (MatchRegion, MatchLevel) -> Unit) {
    var regionName by rememberSaveable { mutableStateOf(region.name) }
    var levelName by rememberSaveable { mutableStateOf(level.name) }
    val draftRegion = MatchRegion.valueOf(regionName)
    val draftLevel = MatchLevel.valueOf(levelName)
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Panel, contentColor = Ink) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("筛选比赛", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                ActionIcon("close", "关闭筛选", onClick = dismiss)
            }
            Text("举办地／线上赛区", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Chips(MatchRegion.entries.map { it.label }, draftRegion.label) { label -> regionName = MatchRegion.entries.first { it.label == label }.name }
            Text("赛事级别", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Chips(MatchLevel.entries.map { it.label }, draftLevel.label) { label -> levelName = MatchLevel.entries.first { it.label == label }.name }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ regionName = MatchRegion.ALL.name; levelName = MatchLevel.ALL.name }, Modifier.weight(1f)) { Text("重置") }
                Button({ apply(draftRegion, draftLevel) }, Modifier.weight(2f)) {
                    Text("显示 ${filterMatchEvents(matches, draftRegion, draftLevel).size} 场")
                }
            }
        }
    }
}

@Composable
internal fun MatchSortSheet(selected: MatchOrder, dismiss: () -> Unit, select: (MatchOrder) -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Panel, contentColor = Ink) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("比赛排序", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                ActionIcon("close", "关闭排序", onClick = dismiss)
            }
            Column(Modifier.selectableGroup()) {
                MatchOrder.entries.forEach { order ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected == order, role = Role.RadioButton) { select(order) }
                        .padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(order.label, Modifier.weight(1f), fontSize = 14.sp, color = if (selected == order) Accent else Ink)
                        if (selected == order) Glyph("check", Accent, 20.dp)
                    }
                }
            }
        }
    }
}
