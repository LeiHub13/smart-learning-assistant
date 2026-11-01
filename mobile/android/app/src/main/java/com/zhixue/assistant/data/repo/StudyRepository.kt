package com.zhixue.assistant.data.repo

import com.zhixue.assistant.core.ApiClient
import com.zhixue.assistant.core.jsonBody
import com.zhixue.assistant.core.putOpt
import com.zhixue.assistant.data.model.AdviceResult
import com.zhixue.assistant.data.model.CalendarCell
import com.zhixue.assistant.data.model.NoteDto
import com.zhixue.assistant.data.model.PlanDetail
import com.zhixue.assistant.data.model.PlanDto
import com.zhixue.assistant.data.model.PlanTaskDto
import com.zhixue.assistant.data.model.ProgressSummary
import com.zhixue.assistant.data.model.RecommendItem
import com.zhixue.assistant.data.model.ReportDto
import com.zhixue.assistant.data.model.SearchResults
import com.zhixue.assistant.data.model.StudySummary
import kotlinx.serialization.json.put

class StudyRepository(private val api: ApiClient) {

    /**
     * 学习时长心跳：Web 是「页面可见时每 60 秒一次，不带 courseId」。
     * 后端把单次分钟数裁剪到 0..5 并按 (用户,课程,日期) 累加。
     */
    suspend fun heartbeat(minutes: Int = 1) =
        api.postVoid("/api/study/heartbeat", jsonBody { put("minutes", minutes) })

    suspend fun summary(days: Int? = null): StudySummary =
        api.get("/api/study/summary" + api.query("days" to days))

    suspend fun recommend(courseId: Long?): List<RecommendItem> =
        api.get("/api/recommend" + api.query("courseId" to courseId))

    // ---------- 学情 ----------

    /** courseId 为空时后端按课程 1 计算（与 Web 行为一致，不是 bug）。 */
    suspend fun progress(courseId: Long?): ProgressSummary =
        api.get("/api/progress/summary" + api.query("courseId" to courseId))

    suspend fun refreshAdvice(courseId: Long?): AdviceResult =
        api.post("/api/progress/advice/refresh" + api.query("courseId" to courseId), "{}")

    // ---------- 学习计划 ----------

    suspend fun plans(): List<PlanDto> = api.get("/api/plans")

    suspend fun plan(planId: Long): PlanDetail = api.get("/api/plans/$planId")

    /** days 超出 1..30 时后端按 7 天处理；生成是 LLM 调用，失败消息为「学习计划生成失败，请重试」。 */
    suspend fun createPlan(courseId: Long?, goal: String, days: Int): PlanDto =
        api.post(
            "/api/plans",
            jsonBody {
                putOpt("courseId", courseId)
                put("goal", goal.trim())
                put("days", days)
            }
        )

    /** 打卡是切换语义：已完成的任务再点会取消完成。 */
    suspend fun checkIn(taskId: Long): PlanTaskDto = api.post("/api/plans/tasks/$taskId/checkin", "{}")

    suspend fun deletePlan(planId: Long) = api.deleteVoid("/api/plans/$planId")

    suspend fun calendar(month: String): List<CalendarCell> = api.get("/api/plans/calendar?month=$month")

    // ---------- 学习报告 ----------

    suspend fun reports(): List<ReportDto> = api.get("/api/reports")

    suspend fun report(id: Long): ReportDto = api.get("/api/reports/$id")

    suspend fun generateWeekly(courseId: Long): ReportDto = api.post("/api/reports/weekly?courseId=$courseId", "{}")

    suspend fun reportPdf(id: Long): ByteArray = api.download("/api/reports/$id/pdf")

    // ---------- 学习笔记 ----------

    suspend fun notes(courseId: Long?): List<NoteDto> = api.get("/api/notes" + api.query("courseId" to courseId))

    suspend fun createNote(courseId: Long?, kpName: String?, title: String, content: String): NoteDto =
        api.post(
            "/api/notes",
            jsonBody {
                putOpt("courseId", courseId)
                putOpt("kpName", kpName?.trim())
                put("title", title.trim())
                put("content", content)
            }
        )

    /** 更新接口忽略 courseId，所以编辑时不能改所属课程。 */
    suspend fun updateNote(noteId: Long, kpName: String?, title: String, content: String): NoteDto =
        api.put(
            "/api/notes/$noteId",
            jsonBody {
                putOpt("kpName", kpName?.trim())
                put("title", title.trim())
                put("content", content)
            }
        )

    /** 共享到课程空间的前提是笔记已绑定课程，后端会拒绝未绑定笔记。 */
    suspend fun setNoteShared(noteId: Long, shared: Boolean) =
        api.putVoid("/api/notes/$noteId/shared", jsonBody { put("shared", shared) })

    suspend fun deleteNote(noteId: Long) = api.deleteVoid("/api/notes/$noteId")

    // ---------- 全局搜索 ----------

    suspend fun search(keyword: String, courseId: Long? = null): SearchResults =
        api.get("/api/search" + api.query("q" to keyword.trim(), "courseId" to courseId))
}
