package com.zhixue.assistant.ui.screens.insight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.CourseCard
import com.zhixue.assistant.data.model.NoteDto
import com.zhixue.assistant.ui.nav.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val NOTE_TITLE_MAX = 100
const val NOTE_CONTENT_MAX = 5000

data class NotesData(
    val courses: List<CourseCard>,
    val notes: List<NoteDto>,
    /** -1 = 全部课程（后端不带 courseId 参数）。 */
    val courseId: Long,
)

class NotesViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<UiState<NotesData>>(UiState.Loading)
    val state: StateFlow<UiState<NotesData>> = _state.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private var selectedCourseId: Long? = null

    init {
        load()
    }

    /** 路由带来的课程过滤条件（-1 表示不限），只接受一次，避免用户手动切换后被覆盖。 */
    fun applyCourseFilter(courseId: Long) {
        if (selectedCourseId == courseId) return
        selectedCourseId = courseId
        load()
    }

    fun selectCourse(key: String?) {
        selectedCourseId = key?.toLongOrNull() ?: Routes.NO_COURSE
        load()
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun load() {
        _state.value = UiState.Loading
        _message.value = null
        viewModelScope.launch {
            val courses = runCatching { container.courses.ensure() }.getOrElse {
                _state.value = UiState.Error(it.userMessage())
                return@launch
            }
            val courseId = selectedCourseId ?: Routes.NO_COURSE
            selectedCourseId = courseId
            val notes = runCatching { container.study.notes(courseId.takeIf { it > 0 }) }.getOrElse {
                _state.value = UiState.Error(it.userMessage())
                return@launch
            }
            _state.value = UiState.Ready(NotesData(courses, notes, courseId))
        }
    }

    /** 共享到课程空间要求笔记已绑定课程，后端会拒绝未绑定笔记，这里先拦住。 */
    fun setShared(note: NoteDto, shared: Boolean) {
        if (note.courseId == null) {
            _message.value = UiMessage("未绑定课程的笔记不能共享", error = true)
            return
        }
        _message.value = null
        viewModelScope.launch {
            runCatching { container.study.setNoteShared(note.id, shared) }
                .onSuccess {
                    _message.value = UiMessage(if (shared) "已共享到课程" else "已取消共享", error = false)
                    reloadSilently()
                }
                .onFailure { _message.value = UiMessage("共享设置失败：${it.userMessage()}", error = true) }
        }
    }

    fun delete(note: NoteDto) {
        viewModelScope.launch {
            runCatching { container.study.deleteNote(note.id) }
                .onSuccess {
                    _message.value = UiMessage("已删除「${note.title}」", error = false)
                    reloadSilently()
                }
                .onFailure { _message.value = UiMessage("删除失败：${it.userMessage()}", error = true) }
        }
    }

    /** 操作后重拉列表，但不把整页打回 loading（保留滚动位置与展开状态）。 */
    private suspend fun reloadSilently() {
        val data = (_state.value as? UiState.Ready)?.data ?: return
        runCatching { container.study.notes(data.courseId.takeIf { it > 0 }) }
            .onSuccess { _state.value = UiState.Ready(data.copy(notes = it)) }
            .onFailure { _message.value = UiMessage("列表刷新失败：${it.userMessage()}", error = true) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { NotesViewModel(container) }
        }
    }
}

class NoteEditorViewModel(private val container: AppContainer) : ViewModel() {

    private val _courses = MutableStateFlow<List<CourseCard>>(emptyList())
    val courses: StateFlow<List<CourseCard>> = _courses.asStateFlow()

    private val _courseKey = MutableStateFlow<String?>(null)
    val courseKey: StateFlow<String?> = _courseKey.asStateFlow()

    private val _kpName = MutableStateFlow("")
    val kpName: StateFlow<String> = _kpName.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    /** 编辑态不允许改课程（后端 PUT 忽略 courseId），下拉框只读展示。 */
    private var editingId: Long? = null

    val isEditing: Boolean get() = editingId != null

    init {
        viewModelScope.launch {
            _courses.value = runCatching { container.courses.ensure() }.getOrDefault(emptyList())
            // 新建态默认落在第一门课；编辑态由 load() 用笔记自己的课程覆盖，不能被这里抢先把下拉改掉
            if (editingId == null && _courseKey.value == null) {
                _courseKey.value = _courses.value.firstOrNull()?.id?.toString()
            }
        }
    }

    /** noteId = -1 表示新建；编辑时用列表接口取回这条笔记（后端没有单条查询）。 */
    fun load(noteId: Long) {
        if (noteId <= 0) {
            editingId = null
            return
        }
        if (editingId == noteId) return
        editingId = noteId
        _loading.value = true
        _message.value = null
        viewModelScope.launch {
            val notes = runCatching { container.study.notes(null) }
            val note = notes.getOrNull()?.firstOrNull { it.id == noteId }
            if (note == null) {
                _message.value = UiMessage(
                    "笔记加载失败：${notes.exceptionOrNull()?.userMessage() ?: "笔记不存在或无权访问"}",
                    error = true,
                )
            } else {
                _courseKey.value = note.courseId?.toString()
                _kpName.value = note.kpName.orEmpty()
                _title.value = note.title
                _content.value = note.content
            }
            _loading.value = false
        }
    }

    fun setCourse(key: String?) {
        if (isEditing) return
        _courseKey.value = key
    }

    fun setKpName(value: String) {
        _kpName.value = value
    }

    fun setTitle(value: String) {
        _title.value = value.take(NOTE_TITLE_MAX)
    }

    fun setContent(value: String) {
        _content.value = value.take(NOTE_CONTENT_MAX)
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun save() {
        if (_saving.value) return
        val title = _title.value.trim()
        val contentError = when {
            title.isEmpty() -> "标题不能为空"
            title.length > NOTE_TITLE_MAX -> "标题过长（≤$NOTE_TITLE_MAX 字）"
            _content.value.isBlank() -> "内容不能为空"
            _content.value.length > NOTE_CONTENT_MAX -> "内容过长（≤$NOTE_CONTENT_MAX 字）"
            else -> null
        }
        if (contentError != null) {
            _message.value = UiMessage(contentError, error = true)
            return
        }
        val kp = _kpName.value.trim().takeIf { it.isNotEmpty() }
        val id = editingId
        _saving.value = true
        _message.value = null
        viewModelScope.launch {
            if (id != null) {
                runCatching { container.study.updateNote(id, kp, title, _content.value) }
                    .onFailure {
                        _message.value = UiMessage("保存失败：${it.userMessage()}", error = true)
                    }
            } else {
                val courseId = _courseKey.value?.toLongOrNull()?.takeIf { it > 0 }
                if (courseId == null) {
                    // 后端创建笔记必须带课程，这里先拦住，避免拿到无意义的 500
                    _message.value = UiMessage("请选择课程后再保存", error = true)
                    _saving.value = false
                    return@launch
                }
                runCatching { container.study.createNote(courseId, kp, title, _content.value) }
                    .onFailure {
                        _message.value = UiMessage("保存失败：${it.userMessage()}", error = true)
                    }
            }
            _saving.value = false
            _saved.value = (_message.value?.error != true)
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { NoteEditorViewModel(container) }
        }
    }
}
