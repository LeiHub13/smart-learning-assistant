package com.zhixue.assistant.ui.screens.learn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.AnswerInput
import com.zhixue.assistant.data.model.CourseCard
import com.zhixue.assistant.data.model.MistakeRecord
import com.zhixue.assistant.data.model.PaperItem
import com.zhixue.assistant.data.model.PracticeDetail
import com.zhixue.assistant.ui.nav.Routes
import com.zhixue.assistant.util.parseOptions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 错题本：分页列表 + 选项红绿对比 + 解析展开 + 举一反三（生成中/作答/结果三态）。 */
class MistakeBookViewModel(private val container: AppContainer) : ViewModel() {

    enum class VariantPhase { Loading, Answer, Result }

    data class VariantState(
        val phase: VariantPhase,
        val sourceQuestionId: Long,
        val kp: String?,
        val stem: String,
        val courseId: Long,
        val paper: List<PaperItem> = emptyList(),
        val answers: Map<Long, String> = emptyMap(),
        val report: PracticeDetail? = null,
        val error: String? = null,
        val submitting: Boolean = false,
    )

    data class State(
        val courses: List<CourseCard> = emptyList(),
        val courseId: Long? = null,
        val records: UiState<List<MistakeRecord>> = UiState.Loading,
        val page: Int = 1,
        val total: Int = 0,
        val loadingMore: Boolean = false,
        val collected: Set<Long> = emptySet(),
        val expanded: Set<Long> = emptySet(),
        val variant: Map<Long, VariantState> = emptyMap(),
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
            runCatching { container.courses.ensure() }
                .onSuccess { list -> _state.update { it.copy(courses = list) } }
                .onFailure { e -> _toast.trySend(e.userMessage()) }
        }
        load(1)
    }

    fun setCourse(id: Long?) {
        _state.update { it.copy(courseId = id, variant = emptyMap()) }
        load(1)
    }

    fun retry() = load(_state.value.page.coerceAtLeast(1))

    private fun load(page: Int, append: Boolean = false) {
        if (!append) _state.update { it.copy(records = UiState.Loading) } else _state.update { it.copy(loadingMore = true) }
        val courseId = _state.value.courseId
        viewModelScope.launch {
            runCatching { container.practice.mistakes(courseId, page = page, size = PAGE_SIZE) }
                .onSuccess { data ->
                    _state.update { s ->
                        val old = if (append) s.records.readyData().orEmpty() else emptyList()
                        s.copy(
                            records = UiState.Ready(old + data.records),
                            total = data.total,
                            page = page,
                            loadingMore = false,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            records = if (append) it.records else UiState.Error(e.userMessage()),
                            loadingMore = false,
                        )
                    }
                }
        }
        loadCollected()
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

    private fun loadCollected() {
        viewModelScope.launch {
            runCatching { container.practice.collectedIds(_state.value.courseId) }
                .onSuccess { ids -> _state.update { it.copy(collected = ids.toSet()) } }
        }
    }

    fun toggleExpand(questionId: Long) {
        _state.update { s ->
            s.copy(expanded = if (questionId in s.expanded) s.expanded - questionId else s.expanded + questionId)
        }
    }

    fun goRetrain() {
        _navigate.trySend(Routes.practice(mode = "mistake", courseId = _state.value.courseId))
    }

    // ---------- 举一反三 ----------

    fun openVariants(record: MistakeRecord) {
        val q = record.question
        if (_state.value.variant.containsKey(q.id)) return
        val courseId = q.courseId
        if (courseId == null) {
            _toast.trySend("该题未关联课程，无法生成变式")
            return
        }
        _state.update { s ->
            s.copy(
                variant = s.variant + (
                    q.id to VariantState(
                        phase = VariantPhase.Loading,
                        sourceQuestionId = q.id,
                        kp = q.kpName,
                        stem = q.stem,
                        courseId = courseId,
                    )
                    ),
            )
        }
        viewModelScope.launch {
            runCatching { container.practice.variants(q.id, count = 2) }
                .onSuccess { paper ->
                    _state.update { s ->
                        s.variant[q.id]?.let { v ->
                            s.copy(variant = s.variant + (q.id to v.copy(phase = VariantPhase.Answer, paper = paper)))
                        } ?: s
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(variant = it.variant - q.id) }
                    _toast.trySend(e.userMessage())
                }
        }
    }

    fun setVariantAnswer(sourceQuestionId: Long, questionId: Long, value: String) {
        _state.update { s ->
            s.variant[sourceQuestionId]?.let { v ->
                s.copy(variant = s.variant + (sourceQuestionId to v.copy(answers = v.answers + (questionId to value))))
            } ?: s
        }
    }

    fun toggleVariantOption(sourceQuestionId: Long, question: PaperItem, key: String) {
        val current = _state.value.variant[sourceQuestionId]?.answers?.get(question.id).orEmpty()
        setVariantAnswer(sourceQuestionId, question.id, toggleMultiKey(current, key, parseOptions(question.options), !current.contains(key.take(1))))
    }

    fun submitVariants(sourceQuestionId: Long) {
        val v = _state.value.variant[sourceQuestionId] ?: return
        if (v.submitting) return
        _state.update { s -> s.copy(variant = s.variant + (sourceQuestionId to v.copy(submitting = true, error = null))) }
        viewModelScope.launch {
            runCatching {
                val result = container.practice.submit(v.courseId, v.paper.map { AnswerInput(it.id, v.answers[it.id].orEmpty()) })
                container.practice.detail(result.id)
            }.onSuccess { detail ->
                _state.update { s ->
                    s.copy(variant = s.variant + (sourceQuestionId to v.copy(phase = VariantPhase.Result, report = detail, submitting = false)))
                }
            }.onFailure { e ->
                _state.update { s ->
                    s.copy(variant = s.variant + (sourceQuestionId to v.copy(submitting = false, error = e.userMessage())))
                }
            }
        }
    }

    fun closeVariants(sourceQuestionId: Long) {
        _state.update { it.copy(variant = it.variant - sourceQuestionId) }
    }

    companion object {
        const val PAGE_SIZE = 10

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { MistakeBookViewModel(container) }
        }
    }
}
