package com.zhixue.assistant.ui.screens.insight

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.CourseCard
import com.zhixue.assistant.data.model.ReportDto
import com.zhixue.assistant.ui.nav.Routes
import com.zhixue.assistant.util.FileActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReportData(
    val courses: List<CourseCard>,
    val reports: List<ReportDto>,
    /** -1 = 未选择课程，后端按默认课程 1 生成。 */
    val courseId: Long,
    val expandedId: Long?,
)

class ReportViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<UiState<ReportData>>(UiState.Loading)
    val state: StateFlow<UiState<ReportData>> = _state.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private var selectedCourseId: Long? = null

    init {
        load()
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun selectCourse(key: String?) {
        selectedCourseId = key?.toLongOrNull() ?: Routes.NO_COURSE
        val data = (_state.value as? UiState.Ready)?.data ?: return
        _state.value = UiState.Ready(data.copy(courseId = selectedCourseId ?: Routes.NO_COURSE))
    }

    fun toggleExpanded(reportId: Long) {
        val data = (_state.value as? UiState.Ready)?.data ?: return
        _state.value = UiState.Ready(
            data.copy(expandedId = if (data.expandedId == reportId) null else reportId)
        )
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            val courses = runCatching { container.courses.ensure() }.getOrElse {
                _state.value = UiState.Error(it.userMessage())
                return@launch
            }
            val reports = runCatching { container.study.reports() }.getOrElse {
                _state.value = UiState.Error(it.userMessage())
                return@launch
            }
            val courseId = selectedCourseId ?: courses.firstOrNull()?.id ?: Routes.NO_COURSE
            selectedCourseId = courseId
            _state.value = UiState.Ready(
                ReportData(courses = courses, reports = reports, courseId = courseId, expandedId = reports.firstOrNull()?.id)
            )
        }
    }

    /** 周报是 LLM 生成，耗时较长，全程保持按钮 loading。 */
    fun generate() {
        if (_generating.value) return
        val courseId = selectedCourseId?.takeIf { it > 0 } ?: 1L
        _generating.value = true
        _message.value = null
        viewModelScope.launch {
            runCatching { container.study.generateWeekly(courseId) }
                .onSuccess { report ->
                    val data = (_state.value as? UiState.Ready)?.data
                    if (data != null) {
                        _state.value = UiState.Ready(
                            data.copy(
                                reports = listOf(report) + data.reports,
                                expandedId = report.id,
                            )
                        )
                    }
                    _message.value = UiMessage("周报已生成", error = false)
                }
                .onFailure { _message.value = UiMessage("周报生成失败：${it.userMessage()}", error = true) }
            _generating.value = false
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ReportViewModel(container) }
        }
    }
}

class ReportDetailViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<UiState<ReportDto>>(UiState.Loading)
    val state: StateFlow<UiState<ReportDto>> = _state.asStateFlow()

    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading.asStateFlow()

    /** 保存/分享结果回给页面做轻提示，避免 ViewModel 持有 Context。 */
    private val _downloadMessage = MutableStateFlow<String?>(null)
    val downloadMessage: StateFlow<String?> = _downloadMessage.asStateFlow()

    fun consumeDownloadMessage() {
        _downloadMessage.value = null
    }

    fun load(reportId: Long) {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = runCatching { container.study.report(reportId) }
                .fold(onSuccess = { UiState.Ready(it) }, onFailure = { UiState.Error(it.userMessage()) })
        }
    }

    /** PDF 由后端渲染，先落「下载」目录，写不进去时回落系统分享面板。 */
    fun downloadPdf(context: Context) {
        val report = (_state.value as? UiState.Ready)?.data ?: return
        if (_downloading.value) return
        _downloading.value = true
        viewModelScope.launch {
            val fileName = "学习报告-${report.title.ifBlank { report.period }}.pdf"
            runCatching { container.study.reportPdf(report.id) }
                .onSuccess { bytes ->
                    val saved = FileActions.saveToDownloads(context, fileName, bytes, "application/pdf")
                    if (saved.uri == null) {
                        runCatching { FileActions.share(context, fileName, bytes, "application/pdf") }
                            .onSuccess { _downloadMessage.value = "无法写入下载目录，已改用分享面板保存" }
                            .onFailure { _downloadMessage.value = "导出失败：${it.userMessage()}" }
                    } else {
                        _downloadMessage.value = saved.message
                    }
                }
                .onFailure { _downloadMessage.value = "PDF 下载失败：${it.userMessage()}" }
            _downloading.value = false
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ReportDetailViewModel(container) }
        }
    }
}
