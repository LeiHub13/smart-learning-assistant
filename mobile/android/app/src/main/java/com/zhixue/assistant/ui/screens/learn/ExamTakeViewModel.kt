package com.zhixue.assistant.ui.screens.learn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.AnswerInput
import com.zhixue.assistant.data.model.ExamDto
import com.zhixue.assistant.data.model.ExamRecordDto
import com.zhixue.assistant.data.model.PaperItem
import com.zhixue.assistant.ui.nav.Routes
import com.zhixue.assistant.util.parseDateTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * 进入考试：start 复用未交卷记录（续考），题目带分值。
 * 倒计时基于 deadlineAt，1 秒一跳，剩余 <120 秒标红；不自动交卷，超时仍允许提交。
 */
class ExamTakeViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
        val content: UiState<Pair<ExamDto, List<PaperItem>>> = UiState.Loading,
        val record: ExamRecordDto? = null,
        val answers: Map<Long, String> = emptyMap(),
        val submitError: String? = null,
        val submitting: Boolean = false,
        val remainSec: Int? = null,
        val unlimited: Boolean = false,
    ) {
        val timedOut: Boolean get() = remainSec != null && remainSec <= 0
        val urgent: Boolean get() = remainSec != null && remainSec in 1..119
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _navigate = Channel<String>(Channel.UNLIMITED)
    val navigate = _navigate.receiveAsFlow()

    private var examId: Long? = null
    private var timerJob: Job? = null

    fun init(id: Long) {
        if (examId == id) return
        examId = id
        load()
    }

    fun load() {
        val id = examId ?: return
        _state.update { it.copy(content = UiState.Loading, submitError = null) }
        viewModelScope.launch {
            runCatching {
                val detail = container.exam.detail(id)
                val record = container.exam.start(id)
                detail to record
            }.onSuccess { (detail, record) ->
                _state.update {
                    it.copy(
                        content = UiState.Ready(detail.exam to detail.questions),
                        record = record,
                        unlimited = record.deadlineAt.isNullOrBlank(),
                        remainSec = computeRemain(record.deadlineAt),
                    )
                }
                startTimer(record)
            }.onFailure { e ->
                _state.update { it.copy(content = UiState.Error(e.userMessage())) }
            }
        }
    }

    private fun computeRemain(deadlineAt: String?): Int? {
        val deadline = parseDateTime(deadlineAt) ?: return null
        val now = java.time.LocalDateTime.now(ZoneId.systemDefault())
        val sec = java.time.Duration.between(now, deadline).seconds.toInt()
        return sec.coerceAtLeast(0)
    }

    private fun startTimer(record: ExamRecordDto) {
        timerJob?.cancel()
        if (record.deadlineAt.isNullOrBlank()) return
        timerJob = viewModelScope.launch {
            while (isActive) {
                _state.update { it.copy(remainSec = computeRemain(record.deadlineAt)) }
                delay(1000)
            }
        }
    }

    fun setAnswer(questionId: Long, value: String) {
        _state.update { it.copy(answers = it.answers + (questionId to value)) }
    }

    fun dismissSubmitError() = _state.update { it.copy(submitError = null) }

    fun submit() {
        val s = _state.value
        val record = s.record ?: return
        val questions = s.content.readyData()?.second ?: return
        if (s.submitting) return
        _state.update { it.copy(submitting = true, submitError = null) }
        viewModelScope.launch {
            runCatching {
                container.exam.submit(record.id, questions.map { AnswerInput(it.id, s.answers[it.id].orEmpty()) })
            }.onSuccess { result ->
                timerJob?.cancel()
                _state.update { it.copy(submitting = false) }
                _navigate.trySend(Routes.examReport(result.recordId))
            }.onFailure { e ->
                _state.update { it.copy(submitting = false, submitError = e.userMessage()) }
            }
        }
    }

    fun remainText(): String {
        val s = _state.value
        if (s.unlimited || s.record?.deadlineAt.isNullOrBlank() == true) return "不限时"
        val sec = s.remainSec ?: return "不限时"
        return "%02d:%02d".format(sec / 60, sec % 60)
    }

    fun answeredCount(): Int = _state.value.answers.values.count { it.isNotBlank() }

    override fun onCleared() {
        timerJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ExamTakeViewModel(container) }
        }
    }
}
