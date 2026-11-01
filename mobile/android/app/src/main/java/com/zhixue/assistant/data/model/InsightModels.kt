package com.zhixue.assistant.data.model

import com.zhixue.assistant.util.TolerantBooleanNonNull
import kotlinx.serialization.Serializable

// ---------- 学习时长 / 推荐 ----------

@Serializable
data class StudyCell(val date: String = "", val minutes: Int = 0)

@Serializable
data class CourseMinutes(val courseId: Long? = null, val courseName: String? = null, val minutes: Int = 0)

@Serializable
data class StudySummary(
    val totalMinutes: Int = 0,
    val activeDays: Int = 0,
    val calendar: List<StudyCell> = emptyList(),
    val byCourse: List<CourseMinutes> = emptyList(),
)

/** type: startup | review_kp | practice_kp | related_doc(document) */
@Serializable
data class RecommendItem(
    val type: String = "",
    val title: String = "",
    val reason: String? = null,
    val kpName: String? = null,
    val action: String? = null,
    val snippet: String? = null,
)

// ---------- 学情 ----------

@Serializable
data class MasteryDto(
    val id: Long = 0,
    val userId: Long? = null,
    val courseId: Long? = null,
    val kpName: String = "",
    val mastery: Double? = null,
    val attempts: Int = 0,
    val correctCount: Int = 0,
    val lastPracticeAt: String? = null,
) {
    val percent: Int get() = kotlin.math.round(mastery ?: 0.0).toInt()
}

@Serializable
data class WrongRow(
    val stem: String = "",
    val kpName: String? = null,
    val userAnswer: String? = null,
    val answer: String? = null,
    val wrongAt: String? = null,
)

@Serializable
data class ProgressSummary(
    val masteries: List<MasteryDto> = emptyList(),
    val averageMastery: Int = 0,
    val totalAttempts: Int = 0,
    val wrongBook: List<WrongRow> = emptyList(),
    val advice: String? = null,
    @Serializable(with = TolerantBooleanNonNull::class) val adviceCached: Boolean? = null,
)

@Serializable
data class AdviceResult(val advice: String = "", @Serializable(with = TolerantBooleanNonNull::class) val adviceCached: Boolean? = null)

@Serializable
data class KpStat(val kpName: String = "", val attempts: Int = 0, val avgMastery: Double = 0.0, val accuracy: Double = 0.0)

@Serializable
data class PeerPracticeRow(
    val nickname: String = "",
    val practiceCount: Int = 0,
    val avgScore: Double = 0.0,
    val maxScore: Int = 0,
)

// ---------- 计划 / 报告 / 笔记 ----------

@Serializable
data class PlanDto(
    val id: Long = 0,
    val userId: Long? = null,
    val courseId: Long? = null,
    val goal: String = "",
    val days: Int = 7,
    /** active | done */
    val status: String = "active",
    val createdAt: String? = null,
)

@Serializable
data class PlanTaskDto(
    val id: Long = 0,
    val planId: Long? = null,
    val dayNo: Int? = null,
    /** yyyy-MM-dd */
    val taskDate: String? = null,
    val title: String = "",
    /** 多行任务，\n 分隔 */
    val tasks: String? = null,
    val focusKp: String? = null,
    @Serializable(with = TolerantBooleanNonNull::class) val done: Boolean? = null,
    val doneAt: String? = null,
) {
    val taskLines: List<String>
        get() = tasks?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}

@Serializable
data class PlanDetail(
    val plan: PlanDto,
    val tasks: List<PlanTaskDto> = emptyList(),
    val doneCount: Int = 0,
)

@Serializable
data class CalendarCell(val date: String = "", val total: Int = 0, val done: Int = 0)

@Serializable
data class ReportDto(
    val id: Long = 0,
    val userId: Long? = null,
    val courseId: Long? = null,
    val period: String = "",
    val title: String = "",
    val content: String = "",
    val createdAt: String? = null,
)

@Serializable
data class NoteDto(
    val id: Long = 0,
    val userId: Long? = null,
    val courseId: Long? = null,
    val kpName: String? = null,
    val title: String = "",
    val content: String = "",
    /** 0/1 */
    val shared: Int? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
) {
    val isShared: Boolean get() = shared == 1
}

// ---------- 搜索 ----------

@Serializable
data class SearchQuestionHit(val id: Long = 0, val courseId: Long? = null, val type: String = "", val stem: String = "", val kpName: String? = null)

@Serializable
data class SearchNoteHit(val id: Long = 0, val courseId: Long? = null, val title: String = "", val content: String = "", val kpName: String? = null)

@Serializable
data class SearchExamHit(val id: Long = 0, val courseId: Long? = null, val title: String = "", val totalScore: Int = 0)

@Serializable
data class SearchDocHit(val chunkId: Long = 0, val kbName: String = "", val docName: String = "", val content: String = "")

@Serializable
data class SearchResults(
    val keyword: String = "",
    val questions: List<SearchQuestionHit> = emptyList(),
    val notes: List<SearchNoteHit> = emptyList(),
    val exams: List<SearchExamHit> = emptyList(),
    val docs: List<SearchDocHit> = emptyList(),
)

// ---------- 通知 ----------

@Serializable
data class NotificationDto(
    val id: Long = 0,
    val userId: Long? = null,
    /** review | plan | system | exam | practice | dm | friend | announcement */
    val type: String = "",
    val title: String = "",
    val content: String = "",
    val link: String? = null,
    val announcementId: Long? = null,
    @Serializable(with = TolerantBooleanNonNull::class) val readFlag: Boolean? = null,
    val scheduledAt: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class UnreadCount(val count: Int = 0)

@Serializable
data class MailLogDto(
    val id: Long = 0,
    val userId: Long? = null,
    val email: String = "",
    val subject: String = "",
    val content: String = "",
    /** SENT | FAILED */
    val status: String = "",
    val error: String? = null,
    val createdAt: String? = null,
)

// ---------- AI 次数与套餐（人工核对模式）----------

@Serializable
data class BillingPackage(
    val code: String = "",
    val name: String = "",
    val credits: Int = 0,
    /** 价格单位为「分」，一律来自后端配置，客户端绝不写死示例价格 */
    val priceFen: Int = 0,
)

@Serializable
data class BillingOverview(
    val enabled: Boolean = false,
    val balance: Int = 0,
    val initialCredits: Int = 0,
    val orderingAvailable: Boolean = false,
    /** 收款/联系说明，后端自由文本，原样展示 */
    val paymentInstructions: String = "",
    /** 仅 https 时后端才会给出 */
    val paymentUrl: String = "",
    val packages: List<BillingPackage> = emptyList(),
)

/** WAITING 待付款 → REVIEW 待核对 → PAID 已到账；CANCELLED 已取消 */
@Serializable
data class BillingOrder(
    val orderNo: String = "",
    val userId: Long? = null,
    val packageName: String = "",
    val credits: Int = 0,
    val priceFen: Int = 0,
    val status: String = "WAITING",
    val submittedChannel: String? = null,
    val submittedReference: String? = null,
    val createdAt: String? = null,
    val paidAt: String? = null,
) {
    val statusLabel: String
        get() = when (status) {
            "WAITING" -> "待付款"
            "REVIEW" -> "待核对"
            "PAID" -> "已到账"
            "CANCELLED" -> "已取消"
            else -> status
        }
}

// ---------- 管理看板 ----------

@Serializable
data class AdminUserStats(val total: Int = 0, val newToday: Int = 0, val new7d: Int = 0)

@Serializable
data class SceneStat(val scene: String = "", val calls: Int = 0, val failures: Int = 0, val avgMs: Long = 0, val tokens: Long = 0)

@Serializable
data class DashboardPoint(
    val date: String = "",
    val registrations: Int = 0,
    val dau: Int = 0,
    val minutes: Int = 0,
    val llmCalls: Int = 0,
    val llmTokens: Long = 0,
)

@Serializable
data class AdminDashboard(
    val users: AdminUserStats = AdminUserStats(),
    val dauToday: Int = 0,
    val llmTodayCalls: Int = 0,
    val llmTodayTokens: Long = 0,
    val llmAvailable: Boolean = false,
    val byScene: List<SceneStat> = emptyList(),
    val series: List<DashboardPoint> = emptyList(),
)

@Serializable
data class AdminUserRow(
    val id: Long = 0,
    val username: String = "",
    val nickname: String = "",
    val email: String? = null,
    val avatar: String? = null,
    val createdAt: String? = null,
    val practiceCount: Int = 0,
    val lastStudy: String? = null,
    @Serializable(with = TolerantBooleanNonNull::class) val admin: Boolean? = null,
)

@Serializable
data class AdminUserPage(
    val records: List<AdminUserRow> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val size: Int = 10,
)

@Serializable
data class RecallResult(val recalled: Int = 0)
