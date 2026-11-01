package com.zhixue.assistant.data.repo

import com.zhixue.assistant.core.ApiClient
import com.zhixue.assistant.core.SseClient
import com.zhixue.assistant.core.SseMessage
import com.zhixue.assistant.core.ZhixueJson
import com.zhixue.assistant.core.jsonBody
import com.zhixue.assistant.core.putOpt
import com.zhixue.assistant.data.model.AgentActionDto
import com.zhixue.assistant.data.model.AgentConfirmResult
import com.zhixue.assistant.data.model.ChatMessageDto
import com.zhixue.assistant.data.model.ChatSessionDto
import com.zhixue.assistant.data.model.ChatStreamFinal
import com.zhixue.assistant.data.model.GeneratedFlashcards
import com.zhixue.assistant.data.model.KbChunkRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

sealed interface ChatStreamEvent {
    data class Delta(val text: String) : ChatStreamEvent
    data class Completed(val final: ChatStreamFinal) : ChatStreamEvent
    /** 生成过程中服务端下发的 data:{"error":...} */
    data class Failed(val message: String) : ChatStreamEvent
    /** 断流但没收到结束事件：后端可能仍在生成并已落库，只能提示稍后查看，不能退款或自动重发。 */
    data object Interrupted : ChatStreamEvent
}

class ChatRepository(
    private val api: ApiClient,
    private val sse: SseClient,
) {

    suspend fun sessions(): List<ChatSessionDto> = api.get("/api/chat/sessions")

    suspend fun messages(sessionId: Long): List<ChatMessageDto> =
        api.get("/api/chat/sessions/$sessionId/messages")

    suspend fun createSession(courseId: Long?, kbId: Long?, kbScope: String?): ChatSessionDto =
        api.post(
            "/api/chat/sessions",
            jsonBody {
                putOpt("courseId", courseId)
                putOpt("kbId", kbId)
                putOpt("kbScope", kbId?.let { kbScope ?: "single" })
            }
        )

    /** PUT 是「缺省即不修改」的局部更新，patch 里没写的 key 不会覆盖服务端值。 */
    suspend fun update(sessionId: Long, patch: JsonObjectBuilder.() -> Unit): ChatSessionDto =
        api.put("/api/chat/sessions/$sessionId", jsonBody(patch))

    suspend fun rename(sessionId: Long, title: String) = update(sessionId) { put("title", title.trim()) }

    /** 费曼与苏格拉底互斥：打开费曼时服务端会强制关掉苏格拉底，客户端也同步传两个字段。 */
    suspend fun setSocratic(sessionId: Long, enabled: Boolean) =
        update(sessionId) {
            put("socratic", enabled)
            if (enabled) put("feynman", false)
        }

    suspend fun setFeynman(sessionId: Long, enabled: Boolean) =
        update(sessionId) {
            put("feynman", enabled)
            if (enabled) put("socratic", false)
        }

    suspend fun setKbScope(sessionId: Long, courseId: Long, kbId: Long, kbScope: String) =
        update(sessionId) {
            put("courseId", courseId)
            put("kbId", kbId)
            put("kbScope", kbScope)
        }

    suspend fun delete(sessionId: Long) = api.deleteVoid("/api/chat/sessions/$sessionId")

    suspend fun pendingActions(sessionId: Long?): List<AgentActionDto> =
        api.get("/api/agent/actions${api.query("sessionId" to sessionId)}")

    suspend fun confirmAction(actionId: Long): AgentConfirmResult =
        api.post("/api/agent/actions/$actionId/confirm", "{}")

    suspend fun cancelAction(actionId: Long) = api.postVoid("/api/agent/actions/$actionId/cancel", "{}")

    /** 引用原文：后端每次最多回 10 条片段。 */
    suspend fun chunks(ids: List<String>): List<KbChunkRef> =
        api.get("/api/kb/chunks?ids=${ids.joinToString(",")}")

    suspend fun generateFlashcards(sessionId: Long): GeneratedFlashcards =
        api.post("/api/flashcards/generate", jsonBody {
            put("mode", "chat")
            put("sourceId", sessionId)
        })

    fun stream(sessionId: Long, message: String): Flow<ChatStreamEvent> =
        sse.stream("/api/chat/sessions/$sessionId/stream", jsonBody { put("message", message) })
            .map { msg ->
                when (msg) {
                    is SseMessage.Delta -> ChatStreamEvent.Delta(msg.text)
                    is SseMessage.Final -> ChatStreamEvent.Completed(
                        ZhixueJson.decodeFromJsonElement(ChatStreamFinal.serializer(), msg.json)
                    )

                    is SseMessage.ServerError -> ChatStreamEvent.Failed(msg.message)
                    SseMessage.Interrupted -> ChatStreamEvent.Interrupted
                    SseMessage.Refresh -> ChatStreamEvent.Interrupted
                }
            }
}
