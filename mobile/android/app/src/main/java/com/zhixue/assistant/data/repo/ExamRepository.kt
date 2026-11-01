package com.zhixue.assistant.data.repo

import com.zhixue.assistant.core.ApiClient
import com.zhixue.assistant.core.jsonBody
import com.zhixue.assistant.core.putOpt
import com.zhixue.assistant.data.model.AnswerInput
import com.zhixue.assistant.data.model.ExamDto
import com.zhixue.assistant.data.model.ExamRecordDto
import com.zhixue.assistant.data.model.ExamReport
import com.zhixue.assistant.data.model.ExamSubmitResult
import com.zhixue.assistant.data.model.ExamWithQuestions
import com.zhixue.assistant.data.model.PaperItem
import com.zhixue.assistant.data.model.QuestionDto
import com.zhixue.assistant.data.model.QuestionPage
import kotlinx.serialization.json.put

class ExamRepository(private val api: ApiClient) {

    /** 手动组卷：questionIds 必须都属于该课程，时长 5~180 分钟。 */
    suspend fun create(courseId: Long, title: String, durationMin: Int?, questionIds: List<Long>): ExamDto =
        api.post(
            "/api/exams",
            jsonBody {
                put("courseId", courseId)
                put("title", title.trim())
                putOpt("durationMin", durationMin)
                put("questionIds", longArray(questionIds))
            }
        )

    suspend fun auto(courseId: Long, title: String, durationMin: Int?, count: Int): ExamDto =
        api.post(
            "/api/exams/auto",
            jsonBody {
                put("courseId", courseId)
                put("title", title.trim())
                putOpt("durationMin", durationMin)
                put("count", count.coerceIn(1, 20))
            }
        )

    /** 考试对同课程所有用户可见（后端只做 owner 标记），我的成绩另算。 */
    suspend fun list(courseId: Long?): List<ExamDto> = api.get("/api/exams" + api.query("courseId" to courseId))

    suspend fun bank(courseId: Long): List<PaperItem> = api.get("/api/exams/bank?courseId=$courseId")

    suspend fun detail(examId: Long): ExamWithQuestions = api.get("/api/exams/$examId")

    /** 开始考试：未交卷的记录会复用（续考），deadlineAt 只做提示，超时后端仍受理。 */
    suspend fun start(examId: Long): ExamRecordDto = api.post("/api/exams/$examId/start", "{}")

    suspend fun submit(recordId: Long, items: List<AnswerInput>): ExamSubmitResult =
        api.post("/api/exams/records/$recordId/submit", jsonBody { put("items", answersJson(items)) })

    suspend fun report(recordId: Long): ExamReport = api.get("/api/exams/records/$recordId/report")

    suspend fun myRecords(examId: Long): List<ExamRecordDto> = api.get("/api/exams/$examId/my-records")

    suspend fun exportXlsx(): ByteArray = api.download("/api/export/exams.xlsx")
}

class QuestionBankRepository(private val api: ApiClient) {

    suspend fun page(
        courseId: Long?,
        page: Int = 1,
        size: Int = 10,
        type: String? = null,
        difficulty: String? = null,
        keyword: String? = null,
    ): QuestionPage = api.get(
        "/api/questions" + api.query(
            "courseId" to courseId,
            "page" to page,
            "size" to size,
            "type" to type?.takeIf { it.isNotBlank() },
            "difficulty" to difficulty?.takeIf { it.isNotBlank() },
            "keyword" to keyword?.takeIf { it.isNotBlank() },
        )
    )

    suspend fun create(payload: QuestionPayload): QuestionDto =
        api.post("/api/questions", payload.toJson())

    suspend fun update(id: Long, payload: QuestionPayload): QuestionDto =
        api.put("/api/questions/$id", payload.toJson(update = true))

    /** 已被练习/考试/收藏引用的题目后端会拒绝删除。 */
    suspend fun delete(id: Long) = api.deleteVoid("/api/questions/$id")
}

/** 题目录入/编辑表单；options 是 [{k,v}] 的 JSON 字符串，与后端存库格式一致。 */
data class QuestionPayload(
    val courseId: Long?,
    val type: String,
    val stem: String,
    val options: String?,
    val answer: String,
    val analysis: String?,
    val kpName: String?,
    val difficulty: String,
) {
    fun toJson(update: Boolean = false): String = jsonBody {
        if (!update) putOpt("courseId", courseId)
        put("type", type)
        put("stem", stem.trim())
        putOpt("options", options)
        put("answer", answer.trim())
        putOpt("analysis", analysis?.trim())
        putOpt("kpName", kpName?.trim())
        put("difficulty", difficulty)
    }
}
