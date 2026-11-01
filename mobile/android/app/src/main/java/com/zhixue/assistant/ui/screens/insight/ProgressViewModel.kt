package com.zhixue.assistant.ui.screens.insight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.CourseCard
import com.zhixue.assistant.data.model.ProgressSummary
import com.zhixue.assistant.data.model.StudySummary
import com.zhixue.assistant.ui.nav.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProgressData(
    val courses: List<CourseCard>,
    /** -1 = 不指定课程，后端按默认课程 1 计算（与 Web 行为一致）。 */
    val courseId: Long,
    val summary: ProgressSummary,
    val study: StudySummary?,
    val studyError: String?,
)

class ProgressViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<UiState<ProgressData>>(UiState.Loading)
    val state: StateFlow<UiState<ProgressData>> = _state.asStateFlow()

    private val _adviceLoading = MutableStateFlow(false)
    val adviceLoading: StateFlow<Boolean> = _adviceLoading.asStateFlow()

    private val _adviceError = MutableStateFlow<String?>(null)
    val adviceError: StateFlow<String?> = _adviceError.asStateFlow()

    /** 记住用户上次选择的课程，刷新/重新进入页面时不跳回默认值。 */
    private var selectedCourseId: Long? = null

    fun selectCourse(key: String?) {
        selectedCourseId = key?.toLongOrNull() ?: Routes.NO_COURSE
        load()
    }

    fun dismissAdviceError() {
        _adviceError.value = null
    }

    fun load() {
        _state.value = UiState.Loading
        _adviceError.value = null
        viewModelScope.launch {
            val courses = runCatching { container.courses.ensure() }.getOrElse {
                _state.value = UiState.Error(it.userMessage())
                return@launch
            }
            val courseId = selectedCourseId ?: courses.firstOrNull()?.id ?: Routes.NO_COURSE
            selectedCourseId = courseId

            val summary = runCatching { container.study.progress(courseId.takeIf { it > 0 }) }.getOrElse {
                _state.value = UiState.Error(it.userMessage())
                return@launch
            }
            // 学习时长是附加信息，拉取失败不影响掌握度与错题本
            val studyResult = runCatching { container.study.summary() }
            _state.value = UiState.Ready(
                ProgressData(
                    courses = courses,
                    courseId = courseId,
                    summary = summary,
                    study = studyResult.getOrNull(),
                    studyError = studyResult.exceptionOrNull()?.userMessage(),
                )
            )
        }
    }

    /** 强制重新生成：后端绕过 7 天缓存并回写，失败时保留旧建议（与 Web 一致）。 */
    fun refreshAdvice() {
        val courseId = selectedCourseId ?: return
        if (_adviceLoading.value) return
        _adviceLoading.value = true
        _adviceError.value = null
        viewModelScope.launch {
            runCatching { container.study.refreshAdvice(courseId.takeIf { it > 0 }) }
                .onSuccess { result ->
                    val ready = _state.value as? UiState.Ready<ProgressData>
                    val data = ready?.data
                    if (data != null) {
                        val updated = data.summary.copy(advice = result.advice, adviceCached = result.adviceCached ?: false)
                        _state.value = UiState.Ready(data.copy(summary = updated))
                    }
                }
                .onFailure { _adviceError.value = "复习建议生成失败：${it.userMessage()}" }
            _adviceLoading.value = false
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ProgressViewModel(container) }
        }
    }
}
