package com.zhixue.assistant.data.repo

import com.zhixue.assistant.core.ZhixueJson
import com.zhixue.assistant.core.jsonBody
import com.zhixue.assistant.core.putOpt
import com.zhixue.assistant.data.model.HallMessageDto
import com.zhixue.assistant.data.model.OnlineUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class SocketStatus { IDLE, CONNECTING, OPEN, RETRYING, CLOSED }

sealed interface HallFrame {
    data class Message(val message: HallMessageDto) : HallFrame
    data class AiStart(val courseId: Long?) : HallFrame
    data class AiDelta(val courseId: Long?, val delta: String) : HallFrame
    data class AiError(val courseId: Long?, val message: String) : HallFrame
    data class Presence(val courseId: Long?, val users: List<OnlineUser>, val onlineCount: Int) : HallFrame
    data class Direct(val message: DmIncomingDto) : HallFrame
    /** 公共大厅的进出场提示（课程频道后端不推送）。 */
    data class RoomEvent(val event: String, val nickname: String) : HallFrame
    data class Rejected(val message: String) : HallFrame
}

/**
 * 对话厅 WebSocket。协议要点（对齐后端 HallWebSocketHandler）：
 * - 浏览器 API 带不了 header，token 走握手 URL 参数；401 直接握手失败
 * - 心跳是 JSON {"type":"ping"} → {"type":"pong"}，服务端空闲 90s 断开
 * - 断线按 3s/6s/… 递增重试，最多 5 次，之后只能手动「重新连接」
 * - 重连成功后要重新上报 view 与 online，否则在线名单和私聊已读会失联
 */
class HallSocket(
    private val websocketUrl: () -> String,
    private val token: () -> String?,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val maxAttempts: Int = 5,
    private val heartbeatMillis: Long = 25_000L,
    private val retryBaseMillis: Long = 3_000L,
) {
    private val _frames = MutableSharedFlow<HallFrame>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frames: SharedFlow<HallFrame> = _frames.asSharedFlow()

    private val _status = MutableStateFlow(SocketStatus.IDLE)
    val status: StateFlow<SocketStatus> = _status.asStateFlow()

    /** 重连第几次，UI 用来显示「连接断开，正在重连…」。 */
    private val _attempt = MutableStateFlow(0)
    val attempt: StateFlow<Int> = _attempt.asStateFlow()

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var desired = false

    /** 重连后需要恢复的上下文。 */
    private var viewKind: String? = null
    private var viewPeer: Long? = null
    private var onlineCourseId: Long? = null

    val isOpen: Boolean get() = _status.value == SocketStatus.OPEN

    fun start() {
        if (token() == null) return
        if (desired && (_status.value == SocketStatus.OPEN || _status.value == SocketStatus.CONNECTING)) return
        desired = true
        _attempt.value = 0
        reconnectJob?.cancel()
        open()
    }

    fun stop() {
        desired = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, null)
        webSocket = null
        _status.value = SocketStatus.IDLE
    }

    /** 手动重连（自动重试耗尽后由按钮触发）。 */
    fun reconnect() {
        _attempt.value = 0
        desired = true
        open()
    }

    private fun open() {
        val jwt = token() ?: run { _status.value = SocketStatus.CLOSED; return }
        _status.value = SocketStatus.CONNECTING
        val request = Request.Builder()
            .url("${websocketUrl()}?token=${java.net.URLEncoder.encode(jwt, "UTF-8")}")
            .build()
        webSocket = client.newWebSocket(request, listener())
    }

    private fun listener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            _status.value = SocketStatus.OPEN
            _attempt.value = 0
            // 恢复上下文：在线名单 + 当前正在看的是频道还是私聊
            viewKind?.let { kind -> sendRaw(frame("view", "kind" to kind, "peerUserId" to viewPeer)) }
            sendRaw(frame("online", "courseId" to onlineCourseId))
            heartbeatJob?.cancel()
            heartbeatJob = scope.launch {
                while (isActive) {
                    delay(heartbeatMillis)
                    sendRaw("""{"type":"ping"}""")
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handle(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            heartbeatJob?.cancel()
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            heartbeatJob?.cancel()
            if (response?.code == 401) {
                // 登录态失效：不无限重连，等用户重新登录
                desired = false
                _status.value = SocketStatus.CLOSED
                _frames.tryEmit(HallFrame.Rejected("登录已过期，请重新登录"))
                return
            }
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!desired) {
            _status.value = SocketStatus.IDLE
            return
        }
        val next = _attempt.value + 1
        if (next > maxAttempts) {
            _status.value = SocketStatus.CLOSED
            return
        }
        _attempt.value = next
        _status.value = SocketStatus.RETRYING
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(retryBaseMillis * next)
            if (desired) open()
        }
    }

    private fun handle(text: String) {
        val obj = runCatching {
            ZhixueJson.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return
        val type = obj["type"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: return
        when (type) {
            "chat" -> decode<HallMessageDto>(obj)?.let { _frames.tryEmit(HallFrame.Message(it)) }
            "ai_start" -> decode<AiCourseDto>(obj)?.let { _frames.tryEmit(HallFrame.AiStart(it.courseId)) }
            "ai_delta" -> decode<AiDeltaDto>(obj)?.let { _frames.tryEmit(HallFrame.AiDelta(it.courseId, it.delta)) }
            "ai_error" -> decode<AiCourseDto>(obj)?.let { _frames.tryEmit(HallFrame.AiError(it.courseId, it.message)) }
            "online" -> decode<OnlinePayload>(obj)?.let {
                _frames.tryEmit(HallFrame.Presence(it.courseId, it.online, it.onlineCount))
            }

            "dm" -> decode<DmIncomingDto>(obj)?.let { _frames.tryEmit(HallFrame.Direct(it)) }
            "system" -> decode<SystemFrameDto>(obj)?.let {
                _frames.tryEmit(HallFrame.RoomEvent(it.event, it.nickname))
                // 服务端 system 帧的 online 是全厅口径，Web 忽略它并重新按频道拉取
                sendRaw(frame("online", "courseId" to onlineCourseId))
            }

            "error" -> decode<ErrorFrameDto>(obj)?.let { _frames.tryEmit(HallFrame.Rejected(it.message)) }
            "pong" -> Unit
            else -> Unit
        }
    }

    private inline fun <reified T> decode(obj: kotlinx.serialization.json.JsonObject): T? =
        runCatching { ZhixueJson.decodeFromString<T>(obj.toString()) }.getOrNull()

    private fun frame(type: String, vararg pairs: Pair<String, Any?>): String = jsonBody {
        put("type", type)
        pairs.forEach { (key, value) -> putOpt(key, value) }
    }

    /** 消息内容上限 500 字（后端同样校验并会回 error 帧）。 */
    private fun messageFrame(type: String, content: String, courseId: Long?, toUserId: Long?): String = jsonBody {
        put("type", type)
        put("content", content.take(500))
        putOpt("courseId", courseId)
        putOpt("toUserId", toUserId)
    }

    fun sendRaw(payload: String): Boolean {
        val socket = webSocket ?: return false
        if (_status.value != SocketStatus.OPEN) return false
        return socket.send(payload)
    }

    fun enterChannel(courseId: Long?) {
        viewKind = "channel"
        viewPeer = null
        onlineCourseId = courseId
        sendRaw(frame("view", "kind" to "channel"))
        sendRaw(frame("online", "courseId" to courseId))
    }

    fun enterDm(peerUserId: Long) {
        viewKind = "dm"
        viewPeer = peerUserId
        onlineCourseId = null
        sendRaw(frame("view", "kind" to "dm", "peerUserId" to peerUserId))
        sendRaw(frame("online"))
    }

    fun sendChat(courseId: Long?, content: String) =
        sendRaw(messageFrame("chat", content, courseId, null))

    fun askAssistant(courseId: Long?, content: String) =
        sendRaw(messageFrame("ai", content, courseId, null))

    fun sendDm(toUserId: Long, content: String) =
        sendRaw(messageFrame("dm", content, null, toUserId))

    fun requestOnline(courseId: Long?) {
        onlineCourseId = courseId
        sendRaw(frame("online", "courseId" to courseId))
    }
}
