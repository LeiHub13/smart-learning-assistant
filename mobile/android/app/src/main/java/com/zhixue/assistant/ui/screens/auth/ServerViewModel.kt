package com.zhixue.assistant.ui.screens.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.ProbeResult
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.core.userMessage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 服务器地址设置：地址校验/生效、历史地址、`GET /api/health` 探活。
 * 登录页与服务器的说明文案都在这里，供 [ServerSettingsScreen] 与登录页折叠面板共用。
 */
class ServerViewModel(private val container: AppContainer) : ViewModel() {

    /** 正在编辑的地址。 */
    var input by mutableStateOf(container.server.baseUrl)
        private set

    /** 已生效的地址（切换后需要同步刷新，ServerConfig 本身不是可观察对象）。 */
    var currentUrl by mutableStateOf(container.server.baseUrl)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var notice by mutableStateOf<String?>(null)
        private set

    /** null = 还没测试过。 */
    var probe by mutableStateOf<UiState<ProbeResult>?>(null)
        private set

    val recentServers: StateFlow<List<String>> = container.settings.recentServers

    fun updateInput(value: String) {
        input = value
        error = null
    }

    fun clearMessages() {
        error = null
        notice = null
    }

    /** 校验并生效；失败时把 [com.zhixue.assistant.core.ServerConfig.normalize] 的拒绝原因原样显示。 */
    fun applyAddress(raw: String = input): Boolean {
        val normalized = runCatching { container.server.previewBaseUrl(raw) }
        if (normalized.isFailure) {
            error = normalized.exceptionOrNull()?.userMessage().orEmpty()
            return false
        }
        val target = normalized.getOrThrow()
        val changed = target != container.server.baseUrl
        val applied = runCatching { container.server.applyBaseUrl(target).getOrThrow() }
        if (applied.isFailure) {
            error = applied.exceptionOrNull()?.userMessage().orEmpty()
            return false
        }
        val effective = applied.getOrThrow()
        currentUrl = effective
        input = effective
        container.settings.rememberServer(effective)
        probe = null
        notice = if (changed) "已切换到 $effective，登录态按服务器分别保存" else "已保存：$effective"
        return true
    }

    /** 从历史地址一键切换。 */
    fun useRecent(url: String) {
        updateInput(url)
        applyAddress(url)
    }

    /**
     * 探活：`/api/health` 是公开接口，无需登录。
     * 输入框里的地址与已生效地址不同时，先让它生效再探活，否则测的就不是用户想测的服务器。
     */
    fun probeConnection() {
        viewModelScope.launch {
            error = null
            val target = runCatching {
                if (input.isBlank()) container.server.baseUrl else container.server.previewBaseUrl(input)
            }
            if (target.isFailure) {
                error = target.exceptionOrNull()?.userMessage().orEmpty()
                return@launch
            }
            val url = target.getOrThrow()
            if (url != container.server.baseUrl && !applyAddress(url)) return@launch
            probe = UiState.Loading
            val result = runCatching { container.auth.probe() }
            probe = result.fold(
                { UiState.Ready(it) },
                { UiState.Error(it.userMessage()) },
            )
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ServerViewModel(container) }
        }
    }
}
