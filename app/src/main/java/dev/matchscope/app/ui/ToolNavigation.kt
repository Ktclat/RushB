package dev.matchscope.app.ui

import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.matchscope.app.data.NavigationStyle
import dev.matchscope.app.data.RootTab

/** Floating mode gives the entire viewport to content. Only the pill consumes hits;
 * list padding lets the last row scroll above it without an opaque bottom strip. */
@Composable
internal fun RootPageFrame(selected: RootTab, style: NavigationStyle, select: (RootTab) -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val initialInset = 78.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var floatingHeight by remember(density, initialInset) { mutableStateOf(initialInset) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CompositionLocalProvider(LocalRootBottomInset provides if (style == NavigationStyle.FLOATING) floatingHeight else 0.dp) { content() }
            if (style == NavigationStyle.FLOATING) Box(Modifier.align(Alignment.BottomCenter)
                .onSizeChanged { floatingHeight = with(density) { it.height.toDp() } }) { ToolNavigation(selected, style, select) }
        }
        if (style == NavigationStyle.FIXED) ToolNavigation(selected, style, select)
    }
}

@Composable
internal fun ToolNavigation(selected: RootTab, style: NavigationStyle = NavigationStyle.FIXED, select: (RootTab) -> Unit) {
    val floating = style == NavigationStyle.FLOATING
    val latestSelect by rememberUpdatedState(select)
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    Box(Modifier.fillMaxWidth().background(if (floating) Color.Transparent else Panel).navigationBarsPadding()
        .padding(horizontal = if (floating) 16.dp else 0.dp, vertical = if (floating) 8.dp else 0.dp)
        .testTag(if (floating) "navigation-floating" else "navigation-fixed")) {
        Surface(Modifier.fillMaxWidth().testTag("navigation-pill"), color = Panel,
            shape = RoundedCornerShape(if (floating) 22.dp else 0.dp),
            border = androidx.compose.foundation.BorderStroke(.5.dp, Stroke),
            shadowElevation = if (!floating) 0.dp else if (LocalRushColors.current.dark) 12.dp else 3.dp) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp)) {
            val cell = maxWidth / RootTab.entries.size
            val cellPixels = with(LocalDensity.current) { cell.toPx() }
            val target = dragPosition ?: selected.ordinal.toFloat()
            val position by animateFloatAsState(target, if (dragPosition != null) snap() else spring(dampingRatio = 1f, stiffness = 700f), label = "navigationIndicator")
            val preview = RootTab.entries[target.roundToInt().coerceIn(0, RootTab.entries.lastIndex)]
            Box(Modifier.offset { IntOffset((position * cellPixels).roundToInt(), 0) }.width(cell).height(52.dp)
                .clip(RoundedCornerShape(16.dp)).background(Accent.copy(alpha = .10f)))
            Row(Modifier.fillMaxWidth().selectableGroup().testTag("navigation-drag-track")
                .pointerInput(cellPixels) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragPosition = (it.x / cellPixels - .5f).coerceIn(0f, RootTab.entries.lastIndex.toFloat()) },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragPosition = (change.position.x / cellPixels - .5f).coerceIn(0f, RootTab.entries.lastIndex.toFloat())
                        },
                        onDragEnd = {
                            dragPosition?.let { latestSelect(RootTab.entries[it.roundToInt().coerceIn(0, RootTab.entries.lastIndex)]) }
                            dragPosition = null
                        },
                        onDragCancel = { dragPosition = null },
                    )
                }) {
                RootTab.entries.forEach { tab ->
                    val active = tab == preview
                    val color by animateColorAsState(if (active) Accent else Quiet, tween(180), label = "tabColor")
                    Column(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(16.dp))
                        .testTag("nav-${tab.icon}").selectable(tab == selected, role = Role.Tab, onClick = { select(tab) }),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Glyph(tab.icon, color, 22.dp, filled = active)
                        Spacer(Modifier.height(4.dp))
                        Text(tab.label, fontSize = 10.sp, color = color, lineHeight = 13.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            }
        }
    }
}
