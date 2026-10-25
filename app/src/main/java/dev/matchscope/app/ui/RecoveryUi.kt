package dev.matchscope.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class RecoveryActions(val enabled: Boolean, val retry: () -> Unit, val login: () -> Unit)
internal val LocalRecovery = staticCompositionLocalOf<RecoveryActions?> { null }

@Composable
internal fun RecoveryButtons(compact: Boolean = false, loginLabel: String = "打开 HLTV 验证", verifyFirst: Boolean = false) {
    val actions = LocalRecovery.current ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 8.dp)) {
        val padding = PaddingValues(horizontal = if (compact) 4.dp else 12.dp, vertical = 8.dp)
        val primaryAction = if (verifyFirst) actions.login else actions.retry
        val primaryLabel = if (verifyFirst) loginLabel else "重试"
        if (compact) TextButton(primaryAction, enabled = actions.enabled, contentPadding = padding) { Text(primaryLabel, fontSize = 11.sp) }
        else FilledTonalButton(primaryAction, enabled = actions.enabled, contentPadding = padding) { Text(primaryLabel, fontSize = 14.sp) }
        TextButton(if (verifyFirst) actions.retry else actions.login, enabled = actions.enabled, contentPadding = padding) {
            Text(if (verifyFirst) "重试" else loginLabel, fontSize = if (compact) 11.sp else 14.sp)
        }
    }
}
