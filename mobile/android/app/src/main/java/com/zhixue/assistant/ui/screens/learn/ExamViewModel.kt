package com.zhixue.assistant.ui.screens.learn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.CourseCard
import com.zhixue.assistant.data.model.ExamDto
import com.zhixue.assistant.data.model.PaperItem
import com.zhixue.assistant.ui.nav.Routes
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 在线考试列表 + 创建（手动/自动组卷）。 */
class ExamViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
        val courses: List<CourseCard> = emptyList(),
        val courseId: Long? = null,
        val exams: UiState<List<ExamDto>> = UiState.Loading,
        val error: String? = null,
        // 创建表单
        val createOpen: Boolean = false,
        val title: String = "",
        val durationInput: String = "",
        val manual: Boolean = false,
        val countInput: String = "5",
        val bank: List<PaperItem> = emptyList(),
        val bankLoading: Boolean = false,
        val bankError: String? = null,
        val pickedIds: List<Long> = emptyList(),
        val creating: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _navigate = Channel<String>(Channel.UNLIMITED)
    val navigate = _navigate.receiveAsFlow()

    private var started = false

    fun init() {
        if (started) return
        started = true
        viewModelScope.launch {
            runCatching { container.courses.ensure() }.onSuccess { list ->
                _state.update { it.copy(courses = list, courseId = it.courseId ?: list.firstOrNull()?.id) }
            }.onFailure { e ->
                _state.update { it.copy(exams = UiState.Error(e.userMessage())) }
            }
        }
        refresh()
    }

    fun setCourse(id: Long?) {
        _state.update { it.copy(courseId = id, bank = emptyList(), pickedIds = emptyList()) }
        if (_state.value.createOpen) reloadBank()
        refresh()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun refresh() {
        _state.update { it.copy(exams = UiState.Loading) }
        val courseId = _state.value.courseId
        viewModelScope.launch {
            runCatching { container.exam.list(courseId) }
                .onSuccess { list -> _state.update { it.copy(exams = UiState.Ready(list)) } }
                .onFailure { e -> _state.update { it.copy(exams = UiState.Error(e.userMessage())) } }
        }
    }

    fun toggleCreate() {
        val open = !_state.value.createOpen
        _state.update { it.copy(createOpen = open) }
        if (open) reloadBank()
    }

    fun setTitle(value: String) = _state.update { it.copy(title = value) }
    fun setDuration(value: String) = _state.update { it.copy(durationInput = value.filter { c -> c.isDigit() }.take(3)) }
    fun setCount(value: String) = _state.update { it.copy(countInput = value.filter { c -> c.isDigit() }.take(2)) }

    fun setManual(manual: Boolean) {
        _state.update { it.copy(manual = manual) }
        if (manual) reloadBank()
    }

    private fun reloadBank() {
        val cid = _state.value.courseId ?: return
        _state.update { it.copy(bankLoading = true, bankError = null) }
        viewModelScope.launch {
            runCatching { container.exam.bank(cid) }
                .onSuccess { list -> _state.update { it.copy(bankLoading = false, bank = list) } }
                .onFailure { e -> _state.update { it.copy(bankLoading = false, bankError = e.userMessage()) } }
        }
    }

    fun togglePick(id: Long) {
        _state.update { s ->
            val picked = s.pickedIds.toMutableList()
            if (!picked.remove(id)) picked.add(id)
            s.copy(pickedIds = picked)
        }
    }

    fun create() {
        val s = _state.value
        if (s.creating) return
        if (s.courseId == null) {
            _state.update { it.copy(error = "请先选择课程") }
            return
        }
        if (s.manual && s.pickedIds.isEmpty()) {
            _state.update { it.copy(error = "请至少勾选一道题目") }
            return
        }
        val duration = s.durationInput.trim().toIntOrNull()
        val count = s.countInput.trim().toIntOrNull() ?: 5
        _state.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            val cid = s.courseId ?: return@launch
            runCatching {
                if (s.manual) {
                    container.exam.create(cid, s.title, duration, s.pickedIds)
                } else {
                    container.exam.auto(cid, s.title, duration, count)
                }
            }.onSuccess {
                _state.update { st ->
                    st.copy(
                        creating = false,
                        createOpen = false,
                        title = "",
                        durationInput = "",
                        countInput = "5",
                        pickedIds = emptyList(),
                    )
                }
                refresh()
            }.onFailure { e ->
                _state.update { it.copy(creating = false, error = e.userMessage()) }
            }
        }
    }

    fun enterExam(examId: Long) {
        _navigate.trySend(Routes.examTake(examId))
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ExamViewModel(container) }
        }
    }
}
