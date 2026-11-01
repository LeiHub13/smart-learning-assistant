package com.zhixue.assistant.ui.screens.learn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.KeyValueStore
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.CourseCard
import com.zhixue.assistant.data.model.QuestionDto
import com.zhixue.assistant.ui.nav.Routes
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 题库管理：筛选分页（记住上次课程，与 Web localStorage key 一致）+ 编辑/删除/收录。 */
class BankViewModel(
    private val container: AppContainer,
    private val prefs: KeyValueStore,
) : ViewModel() {

    data class State(
        val courses: List<CourseCard> = emptyList(),
        val courseId: Long? = null,
        val type: String? = null,
        val difficulty: String? = null,
        val keyword: String = "",
        val records: UiState<List<QuestionDto>> = UiState.Loading,
        val page: Int = 1,
        val total: Int = 0,
        val loadingMore: Boolean = false,
        val collected: Set<Long> = emptySet(),
        val confirmDelete: QuestionDto? = null,
        val deleting: Boolean = false,
        val busyCollect: Set<Long> = emptySet(),
        val hint: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _navigate = Channel<String>(Channel.UNLIMITED)
    val navigate = _navigate.receiveAsFlow()

    private val _toast = Channel<String>(Channel.UNLIMITED)
    val toast = _toast.receiveAsFlow()

    private var started = false

    fun init() {
        if (started) return
        started = true
        viewModelScope.launch {
            runCatching { container.courses.ensure() }.onSuccess { list ->
                val saved = prefs.getString(KEY_BANK_COURSE)?.toLongOrNull()
                val restored = saved?.takeIf { id -> list.any { it.id == id } }
                _state.update { it.copy(courses = list, courseId = restored ?: list.firstOrNull()?.id) }
                load(1)
            }.onFailure { e ->
                _state.update { it.copy(records = UiState.Error(e.userMessage())) }
            }
        }
    }

    fun setCourse(id: Long?) {
        id?.let { prefs.put(KEY_BANK_COURSE, it.toString()) }
        _state.update { it.copy(courseId = id, type = null, difficulty = null, keyword = "") }
        load(1)
    }

    fun setType(type: String?) {
        _state.update { it.copy(type = type) }
        load(1)
    }

    fun setDifficulty(difficulty: String?) {
        _state.update { it.copy(difficulty = difficulty) }
        load(1)
    }

    fun setKeyword(value: String) = _state.update { it.copy(keyword = value) }

    fun search() = load(1)

    fun retry() = load(_state.value.page)

    private fun load(page: Int, append: Boolean = false) {
        val courseId = _state.value.courseId
        if (courseId == null) {
            _state.update { it.copy(records = UiState.Ready(emptyList()), total = 0) }
            return
        }
        if (!append) _state.update { it.copy(records = UiState.Loading) } else _state.update { it.copy(loadingMore = true) }
        val s = _state.value
        viewModelScope.launch {
            runCatching {
                container.questionBank.page(
                    courseId = courseId,
                    page = page,
                    size = PAGE_SIZE,
                    type = s.type,
                    difficulty = s.difficulty,
                    keyword = s.keyword.trim(),
                )
            }.onSuccess { data ->
                _state.update { st ->
                    val old = if (append) st.records.readyData().orEmpty() else emptyList()
                    st.copy(
                        records = UiState.Ready(old + data.records),
                        total = data.total,
                        page = page,
                        loadingMore = false,
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        records = if (append) it.records else UiState.Error(e.userMessage()),
                        loadingMore = false,
                    )
                }
            }
        }
        viewModelScope.launch {
            runCatching { container.practice.collectedIds(courseId) }
                .onSuccess { ids -> _state.update { it.copy(collected = ids.toSet()) } }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loadingMore || s.records is UiState.Loading) return
        if (recordCount() >= s.total) return
        load(s.page + 1, append = true)
    }

    fun recordCount(): Int = when (val r = _state.value.records) {
        is UiState.Ready -> r.data.size
        else -> 0
    }

    fun openEditor(questionId: Long) {
        _navigate.trySend(Routes.questionEditor(questionId))
    }

    fun createQuestion() {
        _navigate.trySend(Routes.questionEditor(-1L))
    }

    fun requestDelete(question: QuestionDto) = _state.update { it.copy(confirmDelete = question) }
    fun cancelDelete() = _state.update { it.copy(confirmDelete = null) }

    fun confirmDelete() {
        val question = _state.value.confirmDelete ?: return
        if (_state.value.deleting) return
        _state.update { it.copy(deleting = true) }
        viewModelScope.launch {
            runCatching { container.questionBank.delete(question.id) }
                .onSuccess {
                    _state.update { it.copy(confirmDelete = null, deleting = false) }
                    _toast.trySend("已删除题目")
                    load(if (recordCount() <= 1 && _state.value.page > 1) _state.value.page - 1 else _state.value.page)
                }
                .onFailure { e ->
                    // 被练习/考试/收藏引用时后端 message 原样展示
                    _state.update { it.copy(confirmDelete = null, deleting = false) }
                    _toast.trySend(e.userMessage())
                }
        }
    }

    fun collect(question: QuestionDto) {
        if (question.id in _state.value.busyCollect || question.id in _state.value.collected) return
        _state.update { it.copy(busyCollect = it.busyCollect + question.id) }
        viewModelScope.launch {
            runCatching { container.practice.collect(question.id) }
                .onSuccess {
                    _state.update { s -> s.copy(collected = s.collected + question.id) }
                    _toast.trySend("已收录错题本：「${question.stem.take(30)}…」，重练答对后自动出本")
                }
                .onFailure { e -> _toast.trySend(e.userMessage()) }
            _state.update { it.copy(busyCollect = it.busyCollect - question.id) }
        }
    }

    companion object {
        const val PAGE_SIZE = 10
        const val KEY_BANK_COURSE = "bank-courseId"

        fun factory(container: AppContainer, prefs: KeyValueStore) = viewModelFactory {
            initializer { BankViewModel(container, prefs) }
        }
    }
}
