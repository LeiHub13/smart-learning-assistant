package com.zhixue.assistant.data.repo

import com.zhixue.assistant.core.ApiClient
import com.zhixue.assistant.core.SseClient
import com.zhixue.assistant.core.SseMessage
import com.zhixue.assistant.core.jsonBody
import com.zhixue.assistant.core.longArrayJson
import com.zhixue.assistant.core.putOpt
import com.zhixue.assistant.core.textOf
import com.zhixue.assistant.data.model.QuestionDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put

/** 讲义是「服务端边生成边落库」的：客户端断流不代表失败，可用 history 补拉已保存结果。 */
sealed interface LectureEvent {
    data class Delta(val text: String) : LectureEvent
    data class Saved(val id: Long) : LectureEvent
    data class Failed(val message: String) : LectureEvent
    data object Interrupted : LectureEvent
}

@Serializable
data class GeneratedContentDto(
    val id: Long = 0,
    val userId: Long? = null,
    val courseId: Long? = null,
    /** lecture | questions */
    val type: String = "",
    val title: String = "",
    val content: String = "",
    val createdAt: String? = null,
)

@Serializable
data class ToKbResult(val kbId: Long = 0, val kbName: String = "", val docId: Long = 0)

class GenerateRepository(
    private val api: ApiClient,
    private val sse: SseClient,
) {

    fun lectureStream(courseId: Long, topic: String, kp: String, kbIds: List<Long>): Flow<LectureEvent> =
        sse.stream("/api/generate/lecture/stream", jsonBody {
                put("courseId", courseId)
                put("topic", topic.trim())
                putOpt("kp", kp.trim().ifBlank { "核心概念" })
                putOpt("kbIds", longArrayJson(kbIds))
            })
            .map { msg ->
                when (msg) {
                    is SseMessage.Delta -> LectureEvent.Delta(msg.text)
                    is SseMessage.Final -> LectureEvent.Saved(msg.json.textOf("saved")?.toLongOrNull() ?: 0L)

                    is SseMessage.ServerError -> LectureEvent.Failed(msg.message)
                    else -> LectureEvent.Interrupted
                }
            }

    /** 断流后补拉：后端可能已经把讲义存好，返回最近生成记录。 */
    suspend fun history(): List<GeneratedContentDto> = api.get("/api/generate/history")

    /** 同步版讲义生成（Web 未用，保留给「重试一次」这类场景）。 */
    suspend fun lectureSync(courseId: Long, topic: String, kp: String, kbIds: List<Long>): GeneratedContentDto =
        api.post(
            "/api/generate/lecture",
            jsonBody {
                put("courseId", courseId)
                put("topic", topic.trim())
                putOpt("kp", kp.trim().ifBlank { "核心概念" })
                putOpt("kbIds", longArrayJson(kbIds))
            }
        )

    /** 题目直接落库（source=AI），所以返回后只需展示，不需要再「保存」。 */
    suspend fun questions(courseId: Long, kp: String, count: Int, kbIds: List<Long>): List<QuestionDto> =
        api.post(
            "/api/generate/questions",
            jsonBody {
                put("courseId", courseId)
                putOpt("kp", kp.trim().ifBlank { "核心概念" })
                put("count", count.coerceIn(1, 10))
                putOpt("kbIds", longArrayJson(kbIds))
            }
        )

    suspend fun downloadLecture(id: Long): ByteArray = api.download("/api/generate/content/$id/download")

    /** 课程没有知识库时后端会自动建「课程 · AI 讲义」。 */
    suspend fun toKnowledgeBase(id: Long): ToKbResult = api.post("/api/generate/content/$id/to-kb", "{}")
}
