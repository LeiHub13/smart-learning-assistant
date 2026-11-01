package com.zhixue.assistant.core

import com.zhixue.assistant.data.model.NotificationDto
import com.zhixue.assistant.data.repo.NotificationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 全局通知中心：未读数供顶栏铃铛显示，SSE event:refresh 到达即重拉。
 * 与 Web 一致：连接断开 5 秒后重连，并保留 120 秒轮询兜底（断网期间不丢通知）。
 */
class NotificationCenter(
    private val repo: NotificationRepository,
    private val session: SessionStore,
    private val scope: CoroutineScope,
    private val reconnectDelayMillis: Long = 5_000L,
    private val pollFallbackMillis: Long = 120_000L,
) {
    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread.asStateFlow()

    private val _items = MutableStateFlow<List<NotificationDto>>(emptyList())
    val items: StateFlow<List<NotificationDto>> = _items.asStateFlow()

    private var streamJob: Job? = null
    private var pollJob: Job? = null

    fun start() {
        if (streamJob != null) return
        refresh()
        streamJob = scope.launch {
            while (isActive && session.isLoggedIn) {
                runCatching { repo.refreshSignals().collect { refresh() } }
                if (!isActive || !session.isLoggedIn) break
                delay(reconnectDelayMillis)
            }
        }
        pollJob = scope.launch {
            while (isActive && session.isLoggedIn) {
                delay(pollFallbackMillis)
                refresh()
            }
        }
    }

    fun stop() {
        streamJob?.cancel(); streamJob = null
        pollJob?.cancel(); pollJob = null
        _unread.value = 0
        _items.value = emptyList()
    }

    fun refresh() {
        if (!session.isLoggedIn) return
        scope.launch {
            runCatching { repo.unreadCount().count }.onSuccess { _unread.value = it }
        }
    }

    suspend fun loadInbox(): List<NotificationDto> {
        val list = repo.list()
        _items.value = list
        return list
    }

    suspend fun markRead(id: Long) {
        repo.markRead(id)
        _items.value = _items.value.map { if (it.id == id) it.copy(readFlag = true) else it }
        _unread.value = (_unread.value - 1).coerceAtLeast(0)
    }

    suspend fun markAllRead() {
        repo.markAllRead()
        _items.value = _items.value.map { it.copy(readFlag = true) }
        _unread.value = 0
    }

    /** 其它页面（如打开私聊）会顺带清掉某条通知，这里只重拉计数。 */
    fun onOpenLink(link: String?) {
        if (link != null) refresh()
    }
}
