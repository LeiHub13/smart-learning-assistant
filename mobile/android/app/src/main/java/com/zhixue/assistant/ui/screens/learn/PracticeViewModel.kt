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
import com.zhixue.assistant.data.model.PaperItem
import com.zhixue.assistant.data.model.PracticeHistoryItem
import com.zhixue.assistant.ui.nav.Routes
import com.zhixue.assistant.util.parseIdCsv
import com.zhixue.assistant.util.parseOptions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 题库练习：设置区（课程/题量/知识点/专项选题）→ 逐题作答（答题卡）→ 交卷跳报告页。
 * 作答进度全部保存在 ViewModel，旋转/返回后状态不丢。
 */
class PracticeViewModel(private val container: AppContainer) : ViewModel() {

    data class Paper(
        val items: List<PaperItem>,
        val courseId: Long,
        val mistake: Boolean,
        val favorite: Boolean,
        val kp: String,
        val answers: Map<Long, String> = emptyMap(),
        val index: Int = 0,
    ) {
        val answeredCount: Int get() = answers.values.count { it.isNotBlank() }
    }

    data class State(
        val courses: List<CourseCard> = emptyList(),
        val loadingCourses: Boolean = true,
        val courseId: Long? = null,
        val count: Int = 5,
        val kp: String = "",
        val error: String? = null,
        val fetching: Boolean = false,
        val submitting: Boolean = false,
        val paper: Paper? = null,
        val confirmExit: Boolean = false,
        // 专项练习（course-questions + kp 过滤 + 手动选题）
        val browseOpen: Boolean = false,
        val browseLoading: Boolean = false,
        val browseError: String? = null,
        val browseKp: String? = null,
        val courseQuestions: List<PaperItem> = emptyList(),
        val selectedIds: List<Long> = emptyList(),
        // 最近练习（分页加载更多）
        val history: UiState<List<PracticeHistoryItem>> = UiState.Loading,
        val historyTotal: Int = 0,
        val historyPage: Int = 1,
        val historyLoadingMore: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _navigate = Channel<String>(Channel.UNLIMITED)
    val navigate = _navigate.receiveAsFlow()

    private var started = false
    private var lastMode = "normal"

    fun init(mode: String, courseId: Long, kp: String, auto: Boolean, ids: String) {
        if (started) return
        started = true
        lastMode = mode
        _state.update { it.copy(courseId = courseId.takeIf { c -> c > 0 }, kp = kp) }
        viewModelScope.launch {
            runCatching { container.courses.ensure() }.onSuccess { list ->
                _state.update { s ->
                    s.copy(
                        courses = list,
                        loadingCourses = false,
                        courseId = s.courseId ?: list.firstOrNull()?.id,
                    )
                }
                val cid = _state.value.courseId ?: return@onSuccess
                val idList = parseIdCsv(ids).mapNotNull { it.toLongOrNull() }
                when {
                    mode == "manual" && idList.isNotEmpty() -> fetchPaper(cid, ids = idList)
                    mode == "mistake" || mode == "favorite" ->
                        fetchPaper(cid, mistake = mode == "mistake", favorite = mode == "favorite", kp = kp)
                    auto || kp.isNotBlank() -> fetchPaper(cid, kp = kp)
                }
            }.onFailure { e ->
                _state.update { it.copy(loadingCourses = false, error = e.userMessage()) }
            }
        }
        loadHistory(1)
    }

    private fun loadHistory(page: Int, append: Boolean = false) {
        if (!append) _state.update { it.copy(history = UiState.Loading) } else _state.update { it.copy(historyLoadingMore = true) }
        viewModelScope.launch {
            runCatching { container.practice.history(page = page, size = HISTORY_SIZE) }
                .onSuccess { data ->
                    _state.update { s ->
                        val old = s.history.readyData().orEmpty()
                        val merged = if (append) old + data.records else data.records
                        s.copy(
                            history = UiState.Ready(merged),
                            historyTotal = data.total,
                            historyPage = page,
                            historyLoadingMore = false,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            history = if (append) it.history else UiState.Error(e.userMessage()),
                            historyLoadingMore = false,
                        )
                    }
                }
        }
    }

    fun loadMoreHistory() {
        val s = _state.value
        if (s.historyLoadingMore || s.history is UiState.Loading) return
        if (s.historyCount() >= s.historyTotal) return
        loadHistory(s.historyPage + 1, append = true)
    }

    fun retryHistory() = loadHistory(1)


    fun setCourse(id: Long?) {
        _state.update { it.copy(courseId = id, courseQuestions = emptyList(), selectedIds = emptyList()) }
    }

    fun setCount(count: Int) {
        _state.update { it.copy(count = count) }
    }

    fun setKp(kp: String) {
        _state.update { it.copy(kp = kp) }
    }

    fun startPractice() {
        val cid = _state.value.courseId
        if (cid == null) {
            _state.update { it.copy(error = "请先选择课程") }
            return
        }
        fetchPaper(cid, kp = _state.value.kp)
    }

    fun startMistakeRetrain() {
        val cid = _state.value.courseId ?: run {
            _state.update { it.copy(error = "请先选择课程") }
            return
        }
        fetchPaper(cid, mistake = true)
    }

    fun startFavoriteRetrain() {
        val cid = _state.value.courseId ?: run {
            _state.update { it.copy(error = "请先选择课程") }
            return
        }
        fetchPaper(cid, favorite = true)
    }

    private fun fetchPaper(
        courseId: Long,
        count: Int = _state.value.count,
        mistake: Boolean = false,
        favorite: Boolean = false,
        kp: String = _state.value.kp,
        ids: List<Long>? = null,
    ) {
        _state.update { it.copy(fetching = true, error = null) }
        viewModelScope.launch {
            runCatching {
                container.practice.paper(
                    courseId = courseId,
                    count = count,
                    favorite = favorite,
                    mistake = mistake,
                    kp = kp.takeIf { it.isNotBlank() },
                    ids = ids?.takeIf { it.isNotEmpty() },
                )
            }.onSuccess { items ->
                _state.update { s ->
                    s.copy(
                        fetching = false,
                        error = null,
                        paper = Paper(items = items, courseId = courseId, mistake = mistake, favorite = favorite, kp = kp),
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(fetching = false, error = e.userMessage()) }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    // ---------- 作答 ----------

    fun selectQuestion(index: Int) {
        _state.update { s -> s.paper?.let { p -> s.copy(paper = p.copy(index = index.coerceIn(0, p.items.lastIndex))) } ?: s }
    }

    fun setAnswer(questionId: Long, value: String) {
        _state.update { s -> s.paper?.let { p -> s.copy(paper = p.copy(answers = p.answers + (questionId to value))) } ?: s }
    }

    fun toggleOption(question: PaperItem, key: String) {
        val current = _state.value.paper?.answers?.get(question.id).orEmpty()
        val options = parseOptions(question.options)
        setAnswer(question.id, toggleMultiKey(current, key, options, !current.contains(key.take(1))))
    }

    fun submit() {
        val paper = _state.value.paper ?: return
        if (_state.value.submitting) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            runCatching {
                container.practice.submit(
                    courseId = paper.courseId,
                    items = paper.items.map { AnswerInput(it.id, paper.answers[it.id].orEmpty()) },
                )
            }.onSuccess { result ->
                _state.update { it.copy(submitting = false, paper = null) }
                loadHistory(1)
                _navigate.trySend(Routes.practiceReport(result.id))
            }.onFailure { e ->
                _state.update { it.copy(submitting = false, error = e.userMessage()) }
            }
        }
    }

    fun requestExit() = _state.update { it.copy(confirmExit = true) }
    fun cancelExit() = _state.update { it.copy(confirmExit = false) }

    fun confirmExit() {
        _state.update { it.copy(confirmExit = false, paper = null, error = null) }
        lastMode = "normal"
    }

    // ---------- 专项练习（课程题库选题） ----------

    fun toggleBrowse() {
        val open = !_state.value.browseOpen
        _state.update { it.copy(browseOpen = open) }
        if (open && _state.value.courseQuestions.isEmpty()) loadCourseQuestions()
    }

    fun loadCourseQuestions() {
        val cid = _state.value.courseId ?: run {
            _state.update { it.copy(browseError = "请先选择课程") }
            return
        }
        _state.update { it.copy(browseLoading = true, browseError = null) }
        viewModelScope.launch {
            runCatching { container.practice.courseQuestions(cid) }
                .onSuccess { list ->
                    _state.update { it.copy(browseLoading = false, courseQuestions = list, selectedIds = emptyList(), browseError = null) }
                }
                .onFailure { e ->
                    _state.update { it.copy(browseLoading = false, browseError = e.userMessage()) }
                }
        }
    }

    fun setBrowseKp(kp: String?) = _state.update { it.copy(browseKp = kp) }

    fun toggleSelect(id: Long) {
        _state.update { s ->
            val sel = s.selectedIds.toMutableList()
            if (!sel.remove(id) && sel.size < MAX_SELECT) sel.add(id)
            s.copy(selectedIds = sel)
        }
    }

    fun selectAll() {
        _state.update { s ->
            s.copy(selectedIds = s.visibleQuestions().take(MAX_SELECT).map { it.id })
        }
    }

    fun startSelected() {
        val cid = _state.value.courseId ?: return
        fetchPaper(cid, ids = _state.value.selectedIds)
    }

    fun paperCourseName(): String? = _state.value.let { s -> s.courses.firstOrNull { it.id == s.paper?.courseId }?.name }

    companion object {
        const val HISTORY_SIZE = 5
        const val MAX_SELECT = 20

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { PracticeViewModel(container) }
        }
    }
}

fun PracticeViewModel.State.historyCount(): Int = when (val h = history) {
    is UiState.Ready -> h.data.size
    else -> 0
}

fun PracticeViewModel.State.visibleQuestions(): List<PaperItem> =
    if (browseKp.isNullOrBlank()) courseQuestions else courseQuestions.filter { it.kpName == browseKp }

fun PracticeViewModel.State.kpOptions(): List<String> =
    courseQuestions.mapNotNull { it.kpName }.filter { it.isNotBlank() }.distinct()
