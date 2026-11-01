package com.zhixue.assistant.data.model

import com.zhixue.assistant.util.TolerantBooleanNonNull
import kotlinx.serialization.Serializable

@Serializable
data class QuestionDto(
    val id: Long = 0,
    val courseId: Long? = null,
    val type: String = "单选",
    val stem: String = "",
    /** JSON 字符串 [{"k":"A","v":"..."}] */
    val options: String? = null,
    val answer: String? = null,
    val analysis: String? = null,
    val kpName: String? = null,
    val difficulty: String? = null,
    val source: String? = null,
    val createdAt: String? = null,
    /** 练习报告 / 收藏列表附带 */
    val favorited: Boolean? = null,
)

/** 出题卷（刻意不含 answer/analysis）。 */
@Serializable
data class PaperItem(
    val id: Long = 0,
    val type: String = "",
    val stem: String = "",
    val options: String? = null,
    val kpName: String? = null,
    val difficulty: String? = null,
    val score: Int? = null,
)

@Serializable
data class AnswerInput(val questionId: Long, val answer: String)

@Serializable
data class PracticeResult(
    val id: Long = 0,
    val userId: Long? = null,
    val courseId: Long? = null,
    val title: String = "",
    val totalScore: Int = 0,
    val score: Int = 0,
    val createdAt: String? = null,
)

@Serializable
data class PracticeHistoryItem(
    val id: Long = 0,
    val title: String = "",
    val score: Int = 0,
    val totalScore: Int = 0,
    val createdAt: String? = null,
    val courseId: Long? = null,
    val courseName: String? = null,
)

@Serializable
data class PagedHistory(val records: List<PracticeHistoryItem> = emptyList(), val total: Int = 0)

@Serializable
data class PracticeQuestionRow(
    val id: Long = 0,
    val practiceId: Long? = null,
    val questionId: Long? = null,
    val userAnswer: String? = null,
    val score: Int? = null,
    @Serializable(with = TolerantBooleanNonNull::class) val correct: Boolean? = null,
    val review: String? = null,
    val kpName: String? = null,
)

@Serializable
data class PracticeDetailItem(val pq: PracticeQuestionRow, val q: QuestionDto)

@Serializable
data class PracticeDetail(
    val id: Long = 0,
    val courseId: Long? = null,
    val title: String = "",
    val totalScore: Int = 0,
    val score: Int = 0,
    val createdAt: String? = null,
    val items: List<PracticeDetailItem> = emptyList(),
)

@Serializable
data class TrendPoint(
    val date: String = "",
    val title: String = "",
    val score: Int = 0,
    val totalScore: Int = 0,
    val rate: Double = 0.0,
)

@Serializable
data class MistakeRecord(
    val question: QuestionDto,
    val lastWrongAt: String? = null,
    val lastWrongAnswer: String? = null,
    val wrongCount: Int = 0,
)

@Serializable
data class MistakePage(val records: List<MistakeRecord> = emptyList(), val total: Int = 0)

@Serializable
data class CollectResult(@Serializable(with = TolerantBooleanNonNull::class) val collected: Boolean = false)

@Serializable
data class FavoriteItem(
    val questionId: Long = 0,
    val courseId: Long? = null,
    val courseName: String? = null,
    val type: String = "",
    val stem: String = "",
    val kpName: String? = null,
    val difficulty: String? = null,
    val options: String? = null,
    val answer: String? = null,
    val analysis: String? = null,
    val lastAnswer: String? = null,
    @Serializable(with = TolerantBooleanNonNull::class) val lastCorrect: Boolean? = null,
)

@Serializable
data class ToggleResult(@Serializable(with = TolerantBooleanNonNull::class) val favorited: Boolean = false)

@Serializable
data class CountWithTotal(val count: Int = 0, val total: Int = 0)

@Serializable
data class QuestionPage(
    val records: List<QuestionDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val size: Int = 10,
)

@Serializable
data class FlashcardDto(
    val id: Long = 0,
    val userId: Long? = null,
    val courseId: Long? = null,
    /** mistake | doc | chat */
    val source: String? = null,
    val sourceId: Long? = null,
    val front: String = "",
    val back: String = "",
    val box: Int = 1,
    /** ok | miss */
    val lastResult: String? = null,
    val lastReviewedAt: String? = null,
    val dueAt: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class DueCount(val due: Int = 0, val total: Int = 0)

@Serializable
data class ExamDto(
    val id: Long = 0,
    val userId: Long? = null,
    val courseId: Long? = null,
    val title: String = "",
    val durationMin: Int = 0,
    val totalScore: Int = 0,
    val createdAt: String? = null,
    /** 列表接口附带 */
    val courseName: String? = null,
    @Serializable(with = TolerantBooleanNonNull::class) val owner: Boolean? = null,
    val attemptCount: Int? = null,
    val myBestScore: Int? = null,
)

@Serializable
data class ExamRecordDto(
    val id: Long = 0,
    val examId: Long = 0,
    val userId: Long? = null,
    val startedAt: String? = null,
    val deadlineAt: String? = null,
    val submittedAt: String? = null,
    val score: Int? = null,
    val totalScore: Int? = null,
)

@Serializable
data class ExamWithQuestions(
    val exam: ExamDto,
    val questions: List<PaperItem> = emptyList(),
)

@Serializable
data class ExamSubmitResult(val recordId: Long = 0, val score: Int = 0, val totalScore: Int = 0)

@Serializable
data class ExamAnswerRow(
    val id: Long = 0,
    val recordId: Long? = null,
    val questionId: Long? = null,
    val userAnswer: String? = null,
    val score: Int? = null,
    @Serializable(with = TolerantBooleanNonNull::class) val correct: Boolean? = null,
    val review: String? = null,
    val kpName: String? = null,
)

@Serializable
data class ExamReportItem(val answer: ExamAnswerRow, val question: QuestionDto)

@Serializable
data class ExamReport(
    val exam: ExamDto,
    val record: ExamRecordDto,
    val items: List<ExamReportItem> = emptyList(),
)
