package com.zhixue.assistant.ui.screens.notify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.MailLogDto
import com.zhixue.assistant.data.model.NotificationDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 通知类型中文标签，与 Web 铃铛的 TYPE_LABELS 一致。 */
val NOTIFICATION_TYPE_LABELS = mapOf(
    "review" to "复习提醒",
    "plan" to "学习计划",
    "system" to "系统通知",
    "exam" to "考试",
    "practice" to "练习",
    "dm" to "私信",
    "friend" to "好友",
    "announcement" to "公告",
)

fun notificationTypeLabel(type: String): String = NOTIFICATION_TYPE_LABELS[type] ?: "通知"

class NotificationsViewModel(private val container: AppContainer) : ViewModel() {

    private val _inbox = MutableStateFlow<UiState<List<NotificationDto>>>(UiState.Loading)
    val inbox: StateFlow<UiState<List<NotificationDto>>> = _inbox.asStateFlow()

    private val _mails = MutableStateFlow<UiState<List<MailLogDto>>>(UiState.Loading)
    val mails: StateFlow<UiState<List<MailLogDto>>> = _mails.asStateFlow()

    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 已读后带 link 的通知由页面完成跳转并关闭自己。 */
    private val _pendingLink = MutableStateFlow<String?>(null)
    val pendingLink: StateFlow<String?> = _pendingLink.asStateFlow()

    private var busy = false

    init {
        load()
        // container.notifications 是唯一数据源：SSE refresh、其它页面顺带清未读都会回流到这里
        viewModelScope.launch {
            container.notifications.items.collect { list ->
                val current = _inbox.value
                if (current is UiState.Ready && current.data != list) {
                    _inbox.value = UiState.Ready(list)
                }
            }
        }
        viewModelScope.launch {
            container.notifications.unread.collect { _unread.value = it }
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun consumePendingLink(): String? {
        val link = _pendingLink.value
        _pendingLink.value = null
        return link
    }

    fun load() {
        _inbox.value = UiState.Loading
        _mails.value = UiState.Loading
        viewModelScope.launch {
            runCatching { container.notifications.loadInbox() }
                .fold(
                    onSuccess = { _inbox.value = UiState.Ready(it) },
                    onFailure = { _inbox.value = UiState.Error(it.userMessage()) },
                )
        }
        viewModelScope.launch {
            runCatching { container.notification.mailLogs() }
                .fold(
                    onSuccess = { _mails.value = UiState.Ready(it) },
                    onFailure = { _mails.value = UiState.Error(it.userMessage()) },
                )
        }
    }

    fun open(notification: NotificationDto) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            val result = runCatching {
                if (notification.readFlag != true) {
                    container.notifications.markRead(notification.id)
                }
                container.notifications.refresh()
            }
            result.exceptionOrNull()?.let { _message.value = "标记已读失败：${it.userMessage()}" }
            notification.link?.takeIf { it.isNotBlank() }?.let { _pendingLink.value = it }
            busy = false
        }
    }

    fun markAllRead() {
        viewModelScope.launch {
            runCatching { container.notifications.markAllRead() }
                .onSuccess { container.notifications.refresh() }
                .onFailure { _message.value = "全部已读失败：${it.userMessage()}" }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { NotificationsViewModel(container) }
        }
    }
}
