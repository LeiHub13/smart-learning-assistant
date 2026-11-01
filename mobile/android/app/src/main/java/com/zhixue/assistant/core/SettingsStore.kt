package com.zhixue.assistant.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { System, Light, Dark }

/** 主题、服务器历史等非敏感偏好；token 走 [SecureStore]。 */
class SettingsStore(store: KeyValueStore) {
    private var raw = store

    private val _theme = MutableStateFlow(
        when (store.getString(KEY_THEME)) {
            "light" -> ThemeMode.Light
            "dark" -> ThemeMode.Dark
            else -> ThemeMode.System
        }
    )
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _recentServers = MutableStateFlow(
        store.getString(KEY_RECENT)?.split("||")?.filter { it.isNotBlank() }.orEmpty()
    )
    val recentServers: StateFlow<List<String>> = _recentServers.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        raw.put(KEY_THEME, mode.name.lowercase())
    }

    fun rememberServer(url: String) {
        val next = (listOf(url) + _recentServers.value.filter { it != url }).take(5)
        _recentServers.value = next
        raw.put(KEY_RECENT, next.joinToString("||"))
    }

    companion object {
        const val KEY_THEME = "ui.theme"
        const val KEY_RECENT = "server.recent"
    }
}
