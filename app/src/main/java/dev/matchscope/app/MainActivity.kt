package dev.matchscope.app

import android.os.Bundle
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import dev.matchscope.app.data.UiPreferencesStore
import dev.matchscope.app.ui.ScopeViewModel
import dev.matchscope.app.ui.MatchScopeApp
import dev.matchscope.app.ui.MatchScopeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val initial = UiPreferencesStore(getSharedPreferences("matchscope", 0)).read()
        val dark = initial.themeMode.isDark(resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        setTheme(if (dark) R.style.Theme_MatchScope else R.style.Theme_MatchScope_Light)
        super.onCreate(savedInstanceState)
        updateSystemBars(dark)
        setContent {
            val vm: ScopeViewModel = viewModel()
            val preferences by remember(vm) { vm.state.map { it.uiPreferences }.distinctUntilChanged() }
                .collectAsStateWithLifecycle(initialValue = initial)
            val darkTheme = preferences.themeMode.isDark(isSystemInDarkTheme())
            SideEffect { updateSystemBars(darkTheme) }
            MatchScopeTheme(darkTheme) { MatchScopeApp(vm) }
        }
    }

    private fun updateSystemBars(dark: Boolean) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, android.graphics.Color.rgb(14, 19, 26))
        enableEdgeToEdge(
            statusBarStyle = style, navigationBarStyle = style,
        )
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.parseColor(if (dark) "#0E131A" else "#F5F6F8")))
    }
}
