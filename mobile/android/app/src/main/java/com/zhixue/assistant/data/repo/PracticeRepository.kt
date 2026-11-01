package com.zhixue.assistant.data.repo

import com.zhixue.assistant.core.ApiClient
import com.zhixue.assistant.core.jsonBody
import com.zhixue.assistant.data.model.AnswerInput
import com.zhixue.assistant.data.model.CollectResult
import com.zhixue.assistant.data.model.CountWithTotal
import com.zhixue.assistant.data.model.FavoriteItem
import com.zhixue.assistant.data.model.MistakePage
import com.zhixue.assistant.data.model.PagedHistory
import com.zhixue.assistant.data.model.PaperItem
import com.zhixue.assistant.data.model.PracticeDetail
import com.zhixue.assistant.data.model.PracticeResult
import com.zhixue.assistant.data.model.ToggleResult
import com.zhixue.assistant.data.model.TrendPoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PracticeRepository(private val api: ApiClient) {

    /**
     * 出题：ids 优先（手动选题，最多 20 且保持顺序），否则按 courseId + 筛选抽题。
     * favorite / mistake 用于「收藏重练」「错题重练」入口。
     */
    suspend fun paper(
        courseId: Long,
        count: Int = 5,
        favorite: Boolean = false,
        mistake: Boolean = false,
        kp: String? = null,
        ids: List<Long>? = null,
    ): List<PaperItem> = api.get(
        "/api/practice/paper" + api.query(
            "courseId" to courseId,
            "count" to count,
            "favorite" to favorite.takeIf { it },
            "mistake" to mistake.takeIf { it },
            "kp" to kp?.takeIf { it.isNotBlank() },
            "ids" to ids?.takeIf { it.isNotEmpty() }?.joinToString(","),
        )
    )

    suspend fun courseQuestions(courseId: Long): List<PaperItem> =
        api.get("/api/practice/course-questions?courseId=$courseId")

    suspend fun submit(courseId: Long, items: List<AnswerInput>): PracticeResult =
        api.post("/api/practice/submit", jsonBody {
            put("courseId", courseId)
            put("items", answersJson(items))
        })

    /** 判分报告需要单独拉：submit 只回分数概要，逐题解析在 /{id}。 */
    suspend fun detail(practiceId: Long): PracticeDetail = api.get("/api/practice/$practiceId")

    suspend fun history(page: Int = 1, size: Int = 5): PagedHistory =
        api.get("/api/practice/history" + api.query("page" to page, "size" to size))

    suspend fun trend(courseId: Long?): List<TrendPoint> =
        api.get("/api/practice/trend" + api.query("courseId" to courseId))

    suspend fun exportXlsx(): ByteArray = api.download("/api/export/practice.xlsx")

    // ---------- 错题本 ----------

    suspend fun mistakes(courseId: Long?, page: Int = 1, size: Int = 10): MistakePage =
        api.get("/api/mistakes" + api.query("courseId" to courseId, "page" to page, "size" to size))

    /** 当前已收录进错题本的题目 id，用于列表上的「已收录」标记。 */
    suspend fun collectedIds(courseId: Long?): List<Long> =
        api.get("/api/mistakes/collected" + api.query("courseId" to courseId))

    suspend fun collect(questionId: Long): CollectResult =
        api.post("/api/mistakes/$questionId/collect", "{}")

    /** 举一反三：变式题会直接以 source=AI 入库，返回题卷后本地组织作答。 */
    suspend fun variants(questionId: Long, count: Int = 2): List<PaperItem> =
        api.post("/api/mistakes/$questionId/variants", jsonBody { put("count", count.coerceIn(1, 4)) })

    // ---------- 收藏夹 ----------

    suspend fun favorites(courseId: Long?): List<FavoriteItem> =
        api.get("/api/favorites" + api.query("courseId" to courseId))

    suspend fun favoriteCount(courseId: Long?): CountWithTotal =
        api.get("/api/favorites/count" + api.query("courseId" to courseId))

    suspend fun toggleFavorite(questionId: Long): ToggleResult =
        api.post("/api/favorites/toggle", jsonBody { put("questionId", questionId) })
}

internal fun answersJson(items: List<AnswerInput>): JsonArray = buildJsonArray {
    items.forEach { item ->
        add(
            buildJsonObject {
                put("questionId", item.questionId)
                put("answer", item.answer)
            }
        )
    }
}

internal fun longArray(ids: List<Long>): JsonArray = buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }

internal fun emptyObject(): JsonObject = buildJsonObject { }
