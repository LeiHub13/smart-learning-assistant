package com.zhixue.assistant.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatSessionDto(
    val id: Long = 0,
    val userId: Long? = null,
    val courseId: Long? = null,
    val kbId: Long? = null,
    val kbScope: String? = null,
    val socratic: Boolean? = null,
    val feynman: Boolean? = null,
    val title: String? = null,
    val createdAt: String? = null,
) {
    val kbMode: Boolean get() = kbId != null
    val scopeSingle: Boolean get() = kbScope != "course"
}

@Serializable
data class ChatMessageDto(
    val id: Long = 0,
    val sessionId: Long = 0,
    val role: String = "",
    val content: String = "",
    val sources: String? = null,
    val sourceChunks: String? = null,
    val followups: String? = null,
    val createdAt: String? = null,
)

/** 待确认动作（后端 toView 只有这 4 个字段）。 */
@Serializable
data class AgentActionDto(
    val id: Long = 0,
    val kind: String = "",
    val summary: String = "",
    val expiresAt: String? = null,
)

@Serializable
data class AgentConfirmResult(
    val actionId: Long = 0,
    val kind: String = "",
    val status: String = "",
    val result: String? = null,
    val kbId: Long? = null,
    val docId: Long? = null,
    /** 后端指定跳转的 Web 路由（如 /practice?courseId=1&auto=1），Android 转成对应导航。 */
    val navigate: String? = null,
)

@Serializable
data class KbChunkRef(
    val chunkId: Long = 0,
    val docId: Long = 0,
    val docName: String = "",
    val content: String = "",
)

@Serializable
data class GeneratedFlashcards(val created: Int = 0, val mode: String = "")

/** 答疑 UI 侧消息：历史消息与流式气泡共用一个结构，streaming 标记正在生成的那一条。 */
data class ChatUiMessage(
    val id: Long,
    val role: String,
    val content: String,
    val sources: String? = null,
    val sourceChunks: String? = null,
    val followups: String? = null,
    val actions: List<AgentActionUi> = emptyList(),
    val streaming: Boolean = false,
    val error: String? = null,
    val createdAt: String? = null,
) {
    val fromUser: Boolean get() = role == "user"
}

/** 动作卡片的本地状态：pending → busy → done(有 result) / cancelled(移出列表)。 */
data class AgentActionUi(
    val id: Long,
    val kind: String,
    val summary: String,
    val expiresAt: String? = null,
    val busy: Boolean = false,
    val result: String? = null,
    val error: String? = null,
)

/** SSE 结束事件里的引用与追问，服务端全部以字符串下发。 */
@Serializable
data class ChatStreamFinal(
    val sources: String = "",
    val sourceChunks: String = "",
    val followups: String = "[]",
    val actions: List<AgentActionDto> = emptyList(),
)
