package com.zhixue.assistant.ui.screens.course

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.CourseCard
import com.zhixue.assistant.data.model.DocumentDto
import com.zhixue.assistant.data.model.KnowledgeBaseDto
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.util.humanizeSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 我的课程：列表 + 创建 + Hub 入驻 + 删除/退出。课程数据走全局缓存，改完必须强刷。 */
class CourseViewModel(private val container: AppContainer) : ViewModel(), MessageHost {

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { CourseViewModel(container) }
        }
    }

    data class Form(
        val name: String = "",
        val description: String = "",
        val inHub: Boolean = false,
    )

    private val _state = MutableStateFlow<UiState<List<CourseCard>>>(UiState.Loading)
    val state: StateFlow<UiState<List<CourseCard>>> = _state.asStateFlow()

    private val _form = MutableStateFlow(Form())
    val form: StateFlow<Form> = _form.asStateFlow()

    private val _creating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = _creating.asStateFlow()

    private val _busyId = MutableStateFlow<Long?>(null)
    val busyId: StateFlow<Long?> = _busyId.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    override val message: StateFlow<UiMessage?> = _message.asStateFlow()
    private var seq = 0L

    init {
        // 课程缓存是全局的：Hub 页加入/退出后也会刷新它，这里跟着缓存走，避免「我的课程」停留在旧列表。
        viewModelScope.launch {
            container.courses.courses.collect { cached ->
                if (cached.isNotEmpty()) _state.value = UiState.Ready(cached)
            }
        }
        reload()
    }

    override fun clearMessage() {
        _message.value = null
    }

    private fun emit(text: String, tone: BannerTone = BannerTone.Info) {
        seq += 1
        _message.value = UiMessage(seq, text, tone)
    }

    fun reload() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            runCatching { container.courses.ensure(force = true) }
                .onSuccess { _state.value = UiState.Ready(it) }
                .onFailure { _state.value = UiState.Error(it.userMessage()) }
        }
    }

    private suspend fun refreshList() {
        runCatching { container.courses.ensure(force = true) }
            .onSuccess { _state.value = UiState.Ready(it) }
            .onFailure { _state.value = UiState.Error(it.userMessage()) }
    }

    fun onNameChange(value: String) {
        _form.value = _form.value.copy(name = value)
    }

    fun onDescriptionChange(value: String) {
        _form.value = _form.value.copy(description = value)
    }

    fun onInHubChange(value: Boolean) {
        _form.value = _form.value.copy(inHub = value)
    }

    fun create() {
        val current = _form.value
        if (current.name.isBlank()) {
            emit("请输入课程名称", BannerTone.Error)
            return
        }
        viewModelScope.launch {
            _creating.value = true
            val result = runCatching {
                container.course.create(current.name, current.description, current.inHub)
            }
            _creating.value = false
            result.onSuccess {
                _form.value = Form()
                emit("课程「${it.name}」已创建", BannerTone.Success)
                refreshList()
            }.onFailure { emit(it.userMessage(), BannerTone.Error) }
        }
    }

    fun toggleHub(course: CourseCard) {
        val next = course.inHub != true
        viewModelScope.launch {
            _busyId.value = course.id
            val result = runCatching { container.course.setHub(course.id, next) }
            result.onSuccess {
                emit(if (next) "课程「${course.name}」已入驻 Hub" else "课程「${course.name}」已移出 Hub", BannerTone.Success)
            }.onFailure { emit(it.userMessage(), BannerTone.Error) }
            _busyId.value = null
            refreshList()
        }
    }

    fun deleteCourse(course: CourseCard) {
        viewModelScope.launch {
            _busyId.value = course.id
            val result = runCatching { container.course.deleteCourse(course.id) }
            result.onSuccess {
                emit("课程「${course.name}」已删除", BannerTone.Success)
            }.onFailure { emit(it.userMessage(), BannerTone.Error) }
            _busyId.value = null
            refreshList()
        }
    }

    fun leaveCourse(course: CourseCard) {
        viewModelScope.launch {
            _busyId.value = course.id
            val result = runCatching { container.course.unenroll(course.id) }
            result.onSuccess {
                emit("已退出课程「${course.name}」", BannerTone.Success)
            }.onFailure { emit(it.userMessage(), BannerTone.Error) }
            _busyId.value = null
            refreshList()
        }
    }
}

/** 一个知识库连同它的文档（课程详情页的展示单元）。 */
data class KbWithDocs(val kb: KnowledgeBaseDto, val docs: List<DocumentDto>)

/**
 * 课程详情：知识库增删 + 每个知识库的文档列表与上传（文件选择器 / 粘贴文本）+ 向量索引重建。
 * courseId 由页面 bind 进来，配置变更或参数变化时重新加载。
 */
class CourseDetailViewModel(private val container: AppContainer) : ViewModel(), MessageHost {

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { CourseDetailViewModel(container) }
        }
    }

    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val courseName: String? = null,
        val kbs: List<KbWithDocs> = emptyList(),
        val newKbName: String = "",
        val uploadKbId: Long? = null,
        val docName: String = "",
        val pastedText: String = "",
        val picked: PickedDocument.Ready? = null,
        val working: Boolean = false,
        val reindexing: Boolean = false,
        val reindexed: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    override val message: StateFlow<UiMessage?> = _message.asStateFlow()
    private var seq = 0L

    private var boundCourseId: Long? = null

    override fun clearMessage() {
        _message.value = null
    }

    private fun emit(text: String, tone: BannerTone = BannerTone.Info) {
        seq += 1
        _message.value = UiMessage(seq, text, tone)
    }

    private inline fun update(block: State.() -> State) {
        _state.value = _state.value.block()
    }

    fun bind(courseId: Long) {
        if (boundCourseId == courseId) return
        boundCourseId = courseId
        _state.value = State()
        reload()
    }

    fun reload() {
        val courseId = boundCourseId ?: return
        viewModelScope.launch {
            update { copy(loading = true, error = null) }
            runCatching {
                val courses = container.courses.ensure()
                val kbs = container.course.knowledgeBases(courseId)
                val withDocs = kbs.map { kb -> KbWithDocs(kb, container.course.documents(kb.id)) }
                (courses.firstOrNull { it.id == courseId }?.name) to withDocs
            }.onSuccess { (name, list) ->
                update {
                    copy(
                        loading = false,
                        error = null,
                        courseName = name,
                        kbs = list,
                        uploadKbId = if (list.any { it.kb.id == uploadKbId }) uploadKbId else null,
                    )
                }
            }.onFailure {
                update { copy(loading = false, error = it.userMessage()) }
            }
        }
    }

    fun onNewKbNameChange(value: String) {
        update { copy(newKbName = value) }
    }

    fun createKb() {
        val courseId = boundCourseId ?: return
        val name = _state.value.newKbName
        viewModelScope.launch {
            update { copy(working = true) }
            val result = runCatching { container.course.createKb(courseId, name) }
            update { copy(working = false) }
            result.onSuccess {
                update { copy(newKbName = "") }
                emit("知识库「${it.name}」已创建", BannerTone.Success)
                reload()
            }.onFailure { emit(it.userMessage(), BannerTone.Error) }
        }
    }

    fun deleteKb(kbId: Long, kbName: String) {
        viewModelScope.launch {
            update { copy(working = true) }
            val result = runCatching { container.course.deleteKb(kbId) }
            update { copy(working = false) }
            result.onSuccess {
                emit("知识库「$kbName」已删除", BannerTone.Success)
                reload()
            }.onFailure { emit(it.userMessage(), BannerTone.Error) }
        }
    }

    fun toggleUploadPanel(kbId: Long) {
        update {
            if (uploadKbId == kbId) {
                copy(uploadKbId = null, docName = "", pastedText = "", picked = null)
            } else {
                copy(uploadKbId = kbId, docName = "", pastedText = "", picked = null)
            }
        }
    }

    fun onDocNameChange(value: String) {
        update { copy(docName = value) }
    }

    fun onPastedTextChange(value: String) {
        update { copy(pastedText = value) }
    }

    fun clearPicked() {
        update { copy(picked = null) }
    }

    /** 系统文件选择器返回的 Uri：读字节 + 扩展名/体积校验，不合格直接给后端一致的拒绝文案。 */
    fun onFilePicked(context: Context, uri: Uri) {
        if (_state.value.uploadKbId == null) return
        viewModelScope.launch {
            update { copy(working = true) }
            val picked = readPickedDocument(context, uri)
            update { copy(working = false) }
            when (picked) {
                is PickedDocument.Rejected -> emit(picked.reason, BannerTone.Error)
                is PickedDocument.Ready -> {
                    update {
                        copy(
                            picked = picked,
                            docName = if (docName.isBlank()) picked.fileName else docName,
                        )
                    }
                    emit("已选择 ${picked.fileName}（${humanizeSize(picked.bytes.size.toLong())}），点「解析入库」提交", BannerTone.Info)
                }
            }
        }
    }

    /** 只取文件名与体积用于界面回显，字节留在状态里等提交。 */
    fun describePicked(): String? = _state.value.picked
        ?.let { "${it.fileName} · ${humanizeSize(it.bytes.size.toLong())}" }

    fun submitUpload() {
        val snapshot = _state.value
        val kbId = snapshot.uploadKbId ?: return
        if (snapshot.working) return
        val picked = snapshot.picked
        val text = snapshot.pastedText
        if (picked == null && text.isBlank()) {
            emit("请选择文件或粘贴文档内容", BannerTone.Error)
            return
        }
        val rawName = snapshot.docName.trim()
        if (picked != null && rawName.isNotBlank()) {
            val ext = extensionOf(rawName)
            if (ext == "doc") {
                emit(LEGACY_DOC_HINT, BannerTone.Error)
                return
            }
            if (ext.isNotEmpty() && !isSupportedExtension(ext)) {
                emit("不支持 .$ext 文件，请在「选择文件」里挑选合规格式", BannerTone.Error)
                return
            }
        }
        val uploadName = if (picked != null) (rawName.ifBlank { picked.fileName }) else normalizedTextName(rawName)
        if (picked == null && extensionOf(uploadName) == "doc") {
            emit(LEGACY_DOC_HINT, BannerTone.Error)
            return
        }
        viewModelScope.launch {
            update { copy(working = true) }
            val result = runCatching {
                if (picked != null) {
                    container.course.uploadDocument(kbId, uploadName, picked.bytes, picked.mime)
                } else {
                    container.course.uploadText(kbId, uploadName, text)
                }
            }
            update { copy(working = false) }
            result.onSuccess {
                update { copy(uploadKbId = null, docName = "", pastedText = "", picked = null) }
                emit("文档已入库，AI 速览正在后台生成，稍后打开预览即可查看", BannerTone.Success)
                reload()
            }.onFailure { emit(it.userMessage(), BannerTone.Error) }
        }
    }

    fun renameDoc(kbId: Long, docId: Long, fileName: String) {
        if (fileName.isBlank()) return
        viewModelScope.launch {
            update { copy(working = true) }
            val result = runCatching { container.course.renameDocument(kbId, docId, fileName) }
            update { copy(working = false) }
            result.onSuccess {
                emit("已重命名为「${it.fileName}」", BannerTone.Success)
                reload()
            }.onFailure { emit(it.userMessage(), BannerTone.Error) }
        }
    }

    fun deleteDoc(kbId: Long, docId: Long, fileName: String) {
        viewModelScope.launch {
            update { copy(working = true) }
            val result = runCatching { container.course.deleteDocument(kbId, docId) }
            update { copy(working = false) }
            result.onSuccess {
                emit("文档「$fileName」已删除", BannerTone.Success)
                reload()
            }.onFailure { emit(it.userMessage(), BannerTone.Error) }
        }
    }

    fun reindex() {
        viewModelScope.launch {
            update { copy(reindexing = true) }
            val result = runCatching { container.course.reindex() }
            result.onSuccess {
                update {
                    copy(
                        reindexing = false,
                        reindexed = "已重建向量索引：${it.indexed} 个片段入库，当前向量 ${it.vectorCount} 条",
                    )
                }
            }.onFailure {
                update { copy(reindexing = false) }
                emit(it.userMessage(), BannerTone.Error)
            }
        }
    }

    fun dismissReindexNotice() {
        update { copy(reindexed = null) }
    }

    fun docsOf(kbId: Long): List<DocumentDto> =
        _state.value.kbs.firstOrNull { it.kb.id == kbId }?.docs.orEmpty()
}
