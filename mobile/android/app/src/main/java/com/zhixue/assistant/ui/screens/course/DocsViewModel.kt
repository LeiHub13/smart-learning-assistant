package com.zhixue.assistant.ui.screens.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.DocListItemDto
import com.zhixue.assistant.data.model.DocPreviewDto
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.util.DocOverview
import com.zhixue.assistant.util.parseOverviewJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 文档管理页（跨课程全量文档）：课程筛选 + 文件名关键词 + 重命名/删除。
 * courseId 由路由带入，-1 表示不限课程。
 */
class DocsViewModel(private val container: AppContainer) : ViewModel(), MessageHost {

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { DocsViewModel(container) }
        }
    }

    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val docs: List<DocListItemDto> = emptyList(),
        val keyword: String = "",
        val courseFilter: String? = null,
        val courseScopeId: Long = -1L,
        val working: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    override val message: StateFlow<UiMessage?> = _message.asStateFlow()
    private var seq = 0L

    init {
        reload()
    }

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

    fun bindCourseScope(courseId: Long) {
        if (_state.value.courseScopeId == courseId) return
        update { copy(courseScopeId = courseId, courseFilter = null) }
    }

    fun reload() {
        viewModelScope.launch {
            update { copy(loading = true, error = null) }
            runCatching { container.course.allDocuments() }
                .onSuccess { update { copy(loading = false, error = null, docs = it) } }
                .onFailure { update { copy(loading = false, error = it.userMessage()) } }
        }
    }

    fun onKeywordChange(value: String) {
        update { copy(keyword = value) }
    }

    fun onCourseFilterChange(name: String?) {
        // 选「全部课程」时同时解除路由带进来的课程限定
        update { copy(courseFilter = name, courseScopeId = if (name == null) -1L else courseScopeId) }
    }

    fun courseOptions(): List<Pair<String, String>> {
        val names = _state.value.docs.mapNotNull { it.courseName }.filter { it.isNotBlank() && it != "—" }.distinct()
        return buildList {
            add(ALL_COURSES_LABEL to ALL_COURSES_LABEL)
            names.forEach { add(it to it) }
        }
    }

    fun visibleDocs(): List<DocListItemDto> {
        val snapshot = _state.value
        val kw = snapshot.keyword.trim().lowercase()
        return snapshot.docs.filter { doc ->
            val byFilter = snapshot.courseFilter == null || doc.courseName == snapshot.courseFilter
            val byScope = snapshot.courseScopeId <= 0L || doc.courseId == snapshot.courseScopeId
            val byKeyword = kw.isEmpty() || doc.fileName.lowercase().contains(kw)
            byFilter && byScope && byKeyword
        }
    }

    fun selectedCourseKey(): String = _state.value.courseFilter ?: ALL_COURSES_LABEL

    /** 后端按知识库归属返回文档；万一只缺 kbId，宁可明确提示也不猜参数。 */
    fun warnMissingKb() {
        emit("该文档缺少知识库归属，无法打开预览或删除，可在课程详情页重试", BannerTone.Error)
    }

    fun renameDoc(kbId: Long, docId: Long, fileName: String) {
        viewModelScope.launch {
            update { copy(working = true) }
            val result = runCatching { container.course.renameDocument(kbId, docId, fileName) }
            update { copy(working = false) }
            result.onSuccess { updated ->
                update { copy(docs = docs.map { if (it.docId == docId) it.copy(fileName = updated.fileName) else it }) }
                emit("已重命名为「${updated.fileName}」", BannerTone.Success)
            }.onFailure { emit(it.userMessage(), BannerTone.Error) }
        }
    }

    fun deleteDoc(kbId: Long, docId: Long, fileName: String) {
        viewModelScope.launch {
            update { copy(working = true) }
            val result = runCatching { container.course.deleteDocument(kbId, docId) }
            update { copy(working = false) }
            result.onSuccess {
                update { copy(docs = docs.filterNot { it.docId == docId }) }
                emit("文档「$fileName」已删除", BannerTone.Success)
            }.onFailure { emit(it.userMessage(), BannerTone.Error) }
        }
    }
}

/** 文档内搜索的一次命中（半开区间，避免边界 +/- 反复出错）。 */
data class DocHit(val start: Int, val end: Int)

/**
 * 文档预览页数据层：正文（可能被后端截断）、AI 速览、文档内搜索命中位置。
 * 文件落地（下载文本副本、打开原文件）由页面持 Context 完成，这里只管数据。
 */
class DocPreviewViewModel(private val container: AppContainer) : ViewModel(), MessageHost {

    companion object {
        /** 文档内搜索最多记录这么多命中，避免超长正文占内存。 */
        const val MAX_HITS: Int = 2000

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { DocPreviewViewModel(container) }
        }
    }

    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val preview: DocPreviewDto? = null,
        val overview: DocOverview? = null,
        val overviewLoading: Boolean = false,
        val query: String = "",
        val hits: List<DocHit> = emptyList(),
        val matchIndex: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    override val message: StateFlow<UiMessage?> = _message.asStateFlow()
    private var seq = 0L

    private var kbId = 0L
    private var docId = 0L
    private var bound = false

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

    fun bind(kbId: Long, docId: Long) {
        if (bound && this.kbId == kbId && this.docId == docId) return
        this.kbId = kbId
        this.docId = docId
        bound = true
        _state.value = State()
        reload()
    }

    fun reload() {
        if (kbId <= 0L || docId <= 0L) return
        viewModelScope.launch {
            update { copy(loading = true, error = null) }
            val result = runCatching {
                val preview = container.course.preview(kbId, docId)
                val overviewRaw = container.course.documents(kbId)
                    .firstOrNull { it.id == docId }?.overview
                preview to parseOverviewJson(overviewRaw)
            }
            result.onSuccess { (preview, overview) ->
                update {
                    copy(
                        loading = false,
                        error = null,
                        preview = preview,
                        overview = overview,
                        query = "",
                        hits = emptyList(),
                        matchIndex = 0,
                    )
                }
            }.onFailure {
                update { copy(loading = false, error = it.userMessage()) }
            }
        }
    }

    fun generateOverview() {
        if (_state.value.overviewLoading) return
        viewModelScope.launch {
            update { copy(overviewLoading = true) }
            val result = runCatching { container.course.overview(kbId, docId) }
            result.onSuccess { remote ->
                update {
                    copy(
                        overviewLoading = false,
                        overview = DocOverview(remote.summary, remote.points, remote.examPoints),
                    )
                }
                emit("AI 速览已生成", BannerTone.Success)
            }.onFailure {
                update { copy(overviewLoading = false) }
                emit(it.userMessage(), BannerTone.Error)
            }
        }
    }

    fun onQueryChange(value: String) {
        val text = _state.value.preview?.text.orEmpty()
        update { copy(query = value, hits = computeHits(text, value), matchIndex = 0) }
    }

    fun jumpMatch(delta: Int) {
        val hits = _state.value.hits
        if (hits.isEmpty()) return
        val next = (_state.value.matchIndex + delta + hits.size) % hits.size
        update { copy(matchIndex = next) }
    }

    fun matchSummary(): String {
        val snapshot = _state.value
        if (snapshot.query.isBlank()) return ""
        if (snapshot.hits.isEmpty()) return "无匹配"
        return "${snapshot.matchIndex + 1} / ${snapshot.hits.size}"
    }

    private fun computeHits(text: String, query: String): List<DocHit> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty() || text.isEmpty()) return emptyList()
        val haystack = text.lowercase()
        val out = mutableListOf<DocHit>()
        var index = haystack.indexOf(needle)
        while (index >= 0 && out.size < MAX_HITS) {
            out += DocHit(index, index + needle.length)
            index = haystack.indexOf(needle, index + needle.length)
        }
        return out
    }


}
