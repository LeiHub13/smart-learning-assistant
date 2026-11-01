package com.zhixue.assistant.ui.screens.learn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.KeyValueStore
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.QuestionDto
import com.zhixue.assistant.data.repo.QuestionPayload
import com.zhixue.assistant.util.parseOptions
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 题目录入/编辑：新建 POST /api/questions；编辑 PUT /api/questions/{id}（课程不可改）。 */
class QuestionEditorViewModel(
    private val container: AppContainer,
    private val prefs: KeyValueStore,
) : ViewModel() {

    data class OptionRow(val key: String, val text: String)

    data class State(
        val loading: Boolean = false,
        val error: String? = null,
        val notFound: Boolean = false,
        val isNew: Boolean = true,
        val questionId: Long = -1L,
        val coursesLoaded: Boolean = false,
        val courses: List<com.zhixue.assistant.data.model.CourseCard> = emptyList(),
        val courseId: Long? = null,
        val courseLocked: Boolean = false,
        val type: String = "单选",
        val difficulty: String = "进阶",
        val kpName: String = "",
        val stem: String = "",
        val options: List<OptionRow> = DEFAULT_OPTIONS,
        val answer: String = "",
        val analysis: String = "",
        val saving: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _saved = Channel<Unit>(Channel.UNLIMITED)
    val saved = _saved.receiveAsFlow()

    private val _toast = Channel<String>(Channel.UNLIMITED)
    val toast = _toast.receiveAsFlow()

    private var started = false

    fun init(questionId: Long) {
        if (started) return
        started = true
        viewModelScope.launch {
            runCatching { container.courses.ensure() }
                .onSuccess { list ->
                    val saved = prefs.getString(BankViewModel.KEY_BANK_COURSE)?.toLongOrNull()
                    val default = saved?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id
                    _state.update { it.copy(coursesLoaded = true, courses = list, courseId = it.courseId ?: default) }
                    if (questionId >= 0) {
                        runCatching { loadForEdit(questionId) }
                            .onFailure { e -> _state.update { it.copy(loading = false, error = e.userMessage()) } }
                    }
                }
                .onFailure { e -> _state.update { it.copy(coursesLoaded = true, error = e.userMessage()) } }
        }
    }

    /** 后端没有「按 id 取题」接口：从各课程题库分页中定位。 */
    private suspend fun loadForEdit(questionId: Long) {
        _state.update { it.copy(loading = true, error = null) }
        var found: QuestionDto? = null
        var scannedCourse: Long? = null
        outer@ for (course in _state.value.courses) {
            var page = 1
            while (page <= MAX_SCAN_PAGES) {
                val data = container.questionBank.page(course.id, page = page, size = SCAN_SIZE)
                val hit = data.records.firstOrNull { it.id == questionId }
                if (hit != null) {
                    found = hit
                    scannedCourse = course.id
                    break@outer
                }
                if (data.records.size < SCAN_SIZE) break
                page++
            }
        }
        val q = found
        if (q == null) {
            _state.update { it.copy(loading = false, notFound = true) }
            return
        }
        val opts = parseOptions(q.options)
        _state.update {
            it.copy(
                loading = false,
                isNew = false,
                questionId = q.id,
                courseId = scannedCourse ?: q.courseId,
                courseLocked = true,
                type = q.type,
                difficulty = q.difficulty ?: "进阶",
                kpName = q.kpName.orEmpty(),
                stem = q.stem,
                options = if (opts.isNotEmpty()) opts.map { o -> OptionRow(o.key, o.text) } else DEFAULT_OPTIONS,
                // 判断题归一到 对/错（兼容历史行的 正确/错误）
                answer = if (q.type == "判断") normalizeJudge(q.answer) else q.answer.orEmpty(),
                analysis = q.analysis.orEmpty(),
            )
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun retryLoad() {
        val id = _state.value.questionId
        started = false
        _state.value = State(questionId = id, isNew = id < 0)
        init(id)
    }

    fun setCourse(id: Long?) = _state.update { it.copy(courseId = id) }
    fun setType(value: String) {
        _state.update { s ->
            s.copy(
                type = value,
                answer = when (value) {
                    "判断" -> normalizeJudge(s.answer).takeIf { it == "对" || it == "错" } ?: "对"
                    else -> s.answer
                },
            )
        }
    }

    fun setDifficulty(value: String) = _state.update { it.copy(difficulty = value) }
    fun setKpName(value: String) = _state.update { it.copy(kpName = value) }
    fun setStem(value: String) = _state.update { it.copy(stem = value) }
    fun setAnswer(value: String) = _state.update { it.copy(answer = value) }
    fun setAnalysis(value: String) = _state.update { it.copy(analysis = value) }

    fun setOptionKey(index: Int, value: String) = updateOption(index) { it.copy(key = value.take(2)) }
    fun setOptionText(index: Int, value: String) = updateOption(index) { it.copy(text = value) }

    fun removeOption(index: Int) {
        _state.update { s ->
            if (s.options.size <= 2) s else s.copy(options = s.options.toMutableList().also { it.removeAt(index) })
        }
    }

    fun addOption() {
        _state.update { s ->
            val used = s.options.map { it.key }
            val key = "ABCDEFGH".split("").filter { it.isNotBlank() && it !in used }.firstOrNull()
                ?: (s.options.size + 1).toString()
            s.copy(options = s.options + OptionRow(key, ""))
        }
    }

    private fun updateOption(index: Int, transform: (OptionRow) -> OptionRow) {
        _state.update { s ->
            s.copy(options = s.options.toMutableList().also { it[index] = transform(it[index]) })
        }
    }

    fun save() {
        val s = _state.value
        if (s.saving) return
        if (s.stem.isBlank()) {
            _state.update { it.copy(error = "题干不能为空") }
            return
        }
        if (s.courseId == null) {
            _state.update { it.copy(error = "请先选择课程") }
            return
        }
        var optionsJson: String? = null
        if (s.type == "单选" || s.type == "多选") {
            val rows = s.options.filter { it.key.isNotBlank() && it.text.isNotBlank() }
            if (rows.size < 2) {
                _state.update { it.copy(error = "请至少填写两个选项") }
                return
            }
            optionsJson = buildJsonArray {
                rows.forEach { r ->
                    add(
                        buildJsonObject {
                            put("k", r.key)
                            put("v", r.text)
                        },
                    )
                }
            }.toString()
            val keys = rows.map { it.key }
            if (s.type == "单选" && s.answer !in keys) {
                _state.update { it.copy(error = "答案必须是已有选项之一") }
                return
            }
            if (s.type == "多选" && s.answer.isNotBlank() && !s.answer.all { c -> keys.any { it.startsWith(c.toString()) } }) {
                _state.update { it.copy(error = "答案必须由已有选项字母组成") }
                return
            }
            if (s.type == "多选" && s.answer.isBlank()) {
                _state.update { it.copy(error = "答案必须由已有选项字母组成") }
                return
            }
        }
        if (s.type == "判断" && s.answer !in listOf("对", "错")) {
            _state.update { it.copy(error = "答案必须是已有选项之一") }
            return
        }
        if (s.type == "问答" && s.answer.isBlank()) {
            _state.update { it.copy(error = "请填写参考答案") }
            return
        }
        val payload = QuestionPayload(
            courseId = s.courseId,
            type = s.type,
            stem = s.stem,
            options = optionsJson,
            answer = s.answer,
            analysis = s.analysis,
            kpName = s.kpName,
            difficulty = s.difficulty,
        )
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            runCatching {
                if (s.isNew) container.questionBank.create(payload) else container.questionBank.update(s.questionId, payload)
            }.onSuccess {
                _state.update { it.copy(saving = false) }
                _toast.trySend(if (s.isNew) "题目已录入" else "题目已更新")
                _saved.trySend(Unit)
            }.onFailure { e ->
                _state.update { it.copy(saving = false, error = e.userMessage()) }
            }
        }
    }

    companion object {
        val DEFAULT_OPTIONS = listOf(
            OptionRow("A", ""),
            OptionRow("B", ""),
            OptionRow("C", ""),
            OptionRow("D", ""),
        )
        const val SCAN_SIZE = 50
        const val MAX_SCAN_PAGES = 20

        fun normalizeJudge(raw: String?): String = when (raw) {
            "正确" -> "对"
            "错误" -> "错"
            else -> raw.orEmpty()
        }

        fun factory(container: AppContainer, prefs: KeyValueStore) = viewModelFactory {
            initializer { QuestionEditorViewModel(container, prefs) }
        }
    }
}
