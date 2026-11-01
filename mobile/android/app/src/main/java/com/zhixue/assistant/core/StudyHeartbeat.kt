package com.zhixue.assistant.core

import com.zhixue.assistant.data.repo.StudyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 学习时长心跳，行为对齐 Web：
 *  - 每 60 秒一次，只在应用处于前台（等价于 document.visibilityState==='visible'）时发送
 *  - 不带 courseId，后端归入「未归属课程」
 *  - 失败静默，不打扰用户
 */
class StudyHeartbeat(
    private val repo: StudyRepository,
    private val session: SessionStore,
    private val scope: CoroutineScope,
    private val intervalMillis: Long = 60_000L,
) {
    private var job: Job? = null
    private var foreground = false

    fun setForeground(active: Boolean) {
        foreground = active
    }

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                delay(intervalMillis)
                if (foreground && session.isLoggedIn) {
                    runCatching { repo.heartbeat(1) }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
