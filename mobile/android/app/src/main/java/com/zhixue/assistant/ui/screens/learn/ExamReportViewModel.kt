package com.zhixue.assistant.ui.screens.learn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.ExamRecordDto
import com.zhixue.assistant.data.model.ExamReport
import com.zhixue.assistant.ui.nav.Routes
import com.zhixue.assistant.util.parseDateTime
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration

/** 成绩单：分数 + 逐题（答案/正确/解析/AI 批改/收藏）+ 我的历次考试记录。 */
class ExamReportViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
        val report: UiState<ExamReport> = UiState.Loading,
        val items: List<GradedItem> = emptyList(),
        val myRecords: List<ExamRecordDto> = emptyList(),
        val busyFavorite: Set<Long> = emptySet(),
        val busyCollect: Set<Long> = emptySet(),
        val collected: Set<Long> = emptySet(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _navigate = Channel<String>(Channel.UNLIMITED)
    val navigate = _navigate.receiveAsFlow()

    private val _toast = Channel<String>(Channel.UNLIMITED)
    val toast = _toast.receiveAsFlow()

    private var recordId: Long? = null

    fun init(id: Long) {
        if (recordId == id) return
        recordId = id
        load()
    }

    fun load() {
        val id = recordId ?: return
        _state.update { it.copy(report = UiState.Loading) }
        viewModelScope.launch {
            runCatching { container.exam.report(id) }
                .onSuccess { data ->
                    _state.update {
                        it.copy(
                            report = UiState.Ready(data),
                            items = data.items.map { item -> gradedOf(item) },
                        )
                    }
                    val examId = data.exam.id
                    viewModelScope.launch {
                        runCatching { container.exam.myRecords(examId) }
                            .onSuccess { records -> _state.update { it.copy(myRecords = records) } }
                    }
                }
                .onFailure { e -> _state.update { it.copy(report = UiState.Error(e.userMessage())) } }
        }
    }

    fun useTimeText(): String {
        val record = _state.value.report.readyData()?.record ?: return ""
        val start = parseDateTime(record.startedAt) ?: return ""
        val end = parseDateTime(record.submittedAt) ?: return ""
        val sec = Duration.between(start, end).seconds
        return "${sec / 60} 分 ${sec % 60} 秒"
    }

    fun toggleFavorite(questionId: Long) {
        if (questionId in _state.value.busyFavorite) return
        _state.update { it.copy(busyFavorite = it.busyFavorite + questionId) }
        viewModelScope.launch {
            runCatching { container.practice.toggleFavorite(questionId) }
                .onSuccess { r ->
                    _state.update { s ->
                        s.copy(items = s.items.map { if (it.questionId == questionId) it.copy(favorited = r.favorited) else it })
                    }
                    _toast.trySend(if (r.favorited) "已收藏" else "已取消收藏")
                }
                .onFailure { e -> _toast.trySend(e.userMessage()) }
            _state.update { it.copy(busyFavorite = it.busyFavorite - questionId) }
        }
    }

    fun collect(questionId: Long, stem: String) {
        if (questionId in _state.value.busyCollect || questionId in _state.value.collected) return
        _state.update { it.copy(busyCollect = it.busyCollect + questionId) }
        viewModelScope.launch {
            runCatching { container.practice.collect(questionId) }
                .onSuccess {
                    _state.update { s -> s.copy(collected = s.collected + questionId) }
                    _toast.trySend("已收录错题本：「${stem.take(30)}…」，重练答对后自动出本")
                }
                .onFailure { e -> _toast.trySend(e.userMessage()) }
            _state.update { it.copy(busyCollect = it.busyCollect - questionId) }
        }
    }

    fun viewRecord(id: Long) {
        _navigate.trySend(Routes.examReport(id))
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ExamReportViewModel(container) }
        }
    }
}
