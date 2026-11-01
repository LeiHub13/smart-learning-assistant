package com.zhixue.assistant.ui.screens.insight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.CourseCard
import com.zhixue.assistant.data.model.RecommendItem
import com.zhixue.assistant.data.model.StudySummary
import com.zhixue.assistant.data.model.TrendPoint
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 一门课的成绩曲线（后端 trend 已按日期升序返回）。 */
data class CourseTrend(
    val courseId: Long,
    val courseName: String,
    val points: List<TrendPoint>,
)

data class DashboardData(
    val greetingName: String,
    val study: StudySummary,
    val courses: List<CourseCard>,
    val trends: List<CourseTrend>,
    val recommend: List<RecommendItem>,
    val practiceCount: Int,
    val favoriteCount: Int,
    val mistakeCount: Int,
    val dueCardCount: Int,
    val unreadCount: Int,
    /** 非关键接口失败时给出提示，不阻塞整页。 */
    val partialFailure: String?,
)

/**
 * 首页看板：与 Web 的 DashboardView 一致，进入页面时并行拉多路数据，
 * 任何一路（除学习总览）失败都只降级成「该指标为 0 + 顶部提示」，不做假数据。
 */
class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<UiState<DashboardData>>(UiState.Loading)
    val state: StateFlow<UiState<DashboardData>> = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            val courses = runCatching { container.courses.ensure() }.getOrElse {
                _state.value = UiState.Error(it.userMessage())
                return@launch
            }
            val firstCourseId = courses.firstOrNull()?.id

            val summaryR = async { runCatching { container.study.summary() } }
            val unreadR = async { runCatching { container.notification.unreadCount().count } }
            val practiceR = async { runCatching { container.practice.history(page = 1, size = 1).total } }
            val favoriteR = async { runCatching { container.practice.favoriteCount(null).total } }
            val mistakeR = async { runCatching { container.practice.mistakes(courseId = null, page = 1, size = 1).total } }
            val dueR = async { runCatching { container.flashcard.due().due } }
            val recommendR = async {
                if (firstCourseId == null) Result.success(emptyList<RecommendItem>())
                else runCatching { container.study.recommend(firstCourseId) }
            }
            // 每门课程一条曲线，无数据/失败的课程不画（与 Web 一致）
            val trendRs = courses.map { course ->
                async {
                    runCatching { container.practice.trend(course.id) }
                        .map { points -> points.takeIf { it.isNotEmpty() }?.let { CourseTrend(course.id, course.name, it) } }
                }
            }

            val study = summaryR.await().getOrElse {
                _state.value = UiState.Error(it.userMessage())
                return@launch
            }

            val bestEffort = listOf(
                "未读通知" to unreadR.await(),
                "练习次数" to practiceR.await(),
                "收藏数" to favoriteR.await(),
                "错题数" to mistakeR.await(),
                "到期闪卡" to dueR.await(),
                "今日推荐" to recommendR.await(),
            )
            val failed = bestEffort.firstOrNull { !it.second.isSuccess }
            val trends = trendRs.map { it.await() }.mapNotNull { it.getOrNull() }

            _state.value = UiState.Ready(
                DashboardData(
                    greetingName = container.session.me
                        ?.let { it.nickname.ifBlank { it.username } }
                        .orEmpty()
                        .ifBlank { "同学" },
                    study = study,
                    courses = courses,
                    trends = trends,
                    recommend = recommendR.await().getOrNull().orEmpty(),
                    practiceCount = practiceR.await().getOrDefault(0),
                    favoriteCount = favoriteR.await().getOrDefault(0),
                    mistakeCount = mistakeR.await().getOrDefault(0),
                    dueCardCount = dueR.await().getOrDefault(0),
                    unreadCount = unreadR.await().getOrDefault(0),
                    partialFailure = failed?.let { "${it.first}加载失败：${it.second.exceptionOrNull()?.userMessage()}" },
                )
            )
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { DashboardViewModel(container) }
        }
    }
}
