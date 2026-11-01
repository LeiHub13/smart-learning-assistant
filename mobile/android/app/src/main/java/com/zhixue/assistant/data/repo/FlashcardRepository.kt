package com.zhixue.assistant.data.repo

import com.zhixue.assistant.core.ApiClient
import com.zhixue.assistant.core.jsonBody
import com.zhixue.assistant.core.putOpt
import com.zhixue.assistant.data.model.DueCount
import com.zhixue.assistant.data.model.FlashcardDto
import com.zhixue.assistant.data.model.GeneratedFlashcards
import kotlinx.serialization.json.put

class FlashcardRepository(private val api: ApiClient) {

    /** 到期优先，再按盒子等级与上次复习时间排序；count 后端裁剪到 30。 */
    suspend fun study(courseId: Long?, count: Int = 10): List<FlashcardDto> =
        api.get("/api/flashcards/study" + api.query("courseId" to courseId, "count" to count))

    suspend fun due(courseId: Long? = null): DueCount = api.get("/api/flashcards/due" + api.query("courseId" to courseId))

    suspend fun count(): Long = api.get("/api/flashcards/count")

    /** result 只有 ok / miss 两种（对应 UI 的「记住了」「没记住」）。 */
    suspend fun review(cardId: Long, result: String): FlashcardDto =
        api.post("/api/flashcards/$cardId/review", jsonBody { put("result", result) })

    /** mode: chat(会话 id) | doc(文档 id) | mistake(无 sourceId) */
    suspend fun generate(mode: String, sourceId: Long? = null): GeneratedFlashcards =
        api.post(
            "/api/flashcards/generate",
            jsonBody {
                put("mode", mode)
                putOpt("sourceId", sourceId)
            }
        )

    suspend fun delete(cardId: Long) = api.deleteVoid("/api/flashcards/$cardId")

    /** Anki 文本：#separator:tab 头 + front<TAB>back，前端已把换行转成 <br>。 */
    suspend fun exportAnki(): ByteArray = api.download("/api/flashcards/export")
}
