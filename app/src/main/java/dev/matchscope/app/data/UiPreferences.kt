package dev.matchscope.app.data

import android.content.SharedPreferences

enum class RootTab(val label: String, val icon: String) {
    MATCHES("赛事", "matches"), CALENDAR("赛程", "calendar"), RANKINGS("排名", "rankings"), PROFILE("我的", "profile"),
}

enum class NavigationStyle(val label: String) { FIXED("固定"), FLOATING("悬浮") }

enum class ThemeMode(val label: String) {
    DARK("深色"), LIGHT("浅色"), SYSTEM("跟随系统");

    fun isDark(systemDark: Boolean) = when (this) { DARK -> true; LIGHT -> false; SYSTEM -> systemDark }
}

data class UiPreferences(
    val navigationStyle: NavigationStyle = NavigationStyle.FIXED,
    val startTab: RootTab = RootTab.MATCHES,
    val themeMode: ThemeMode = ThemeMode.DARK,
)

class UiPreferencesStore(private val preferences: SharedPreferences) {
    fun read() = UiPreferences(
        runCatching { NavigationStyle.valueOf(preferences.getString("navigationStyle", "")!!) }.getOrDefault(NavigationStyle.FIXED),
        runCatching { RootTab.valueOf(preferences.getString("startTab", "")!!) }.getOrDefault(RootTab.MATCHES),
        runCatching { ThemeMode.valueOf(preferences.getString("themeMode", "")!!) }.getOrDefault(ThemeMode.DARK),
    )
    fun write(value: UiPreferences) {
        preferences.edit().putString("navigationStyle", value.navigationStyle.name).putString("startTab", value.startTab.name)
            .putString("themeMode", value.themeMode.name).apply()
    }
}
