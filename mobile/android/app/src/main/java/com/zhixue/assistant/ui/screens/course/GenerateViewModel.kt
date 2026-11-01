package com.zhixue.assistant.ui.screens.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.CourseCard
import com.zhixue.assistant.data.model.KnowledgeBaseDto
import com.zhixue.assistant.data.model.QuestionDto
import com.zhixue.assistant.data.repo.GeneratedContentDto
import com.zhixue.assistant.data.repo.LectureEvent
import com.zhixue.assistant.ui.components.BannerTone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 知识库选择的三个取值：不使用 / 全部 / 具体库 id。 */
private const val KB_NONE = ""
private const val KB_ALL = "all"

/**
 * AI 生成页：讲义走 SSE 流式（服务端边生成边落库，断流不等于失败），练习题同步生成并自动入库。
 * 两个生成动作共用 busy 状态互斥；每次结束都重拉额度。
 */
class GenerateViewModel(private val container: AppContainer) : ViewModel(), MessageHost {

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { GenerateViewModel(container) }
        }
    }

    data class State(
        val loading: Boolean = true,
        val courses: List<CourseCard> = emptyList(),
        val courseId: Long? = null,
        val kbs: List<KnowledgeBaseDto> = emptyList(),
        val kbChoice: String = KB_NONE,
        val kp: String = "",
        val topic: String = "",
        val questionCount: Int = 5,
        val busy: Boolean = false,
        val streaming: Boolean = false,
        val lecture: String = "",
        val lectureId: Long? = null,
        val interrupted: Boolean = false,
        val history: List<GeneratedContentDto> = emptyList(),
        val historyLoading: Boolean = false,
        val questions: List<QuestionDto> = emptyList(),
        val kbSaving: Boolean = false,
        val kbSaved: Boolean = false,
        val kbName: String = "",
        val downloading: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    override val message: StateFlow<UiMessage?> = _message.asStateFlow()
    private var seq = 0L

    private var streamJob: Job? = null

    init {
        loadCourses()
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

    fun loadCourses() {
        viewModelScope.launch {
            update { copy(loading = true) }
            val result = runCatching { container.courses.ensure() }
            result.onSuccess { list ->
                val keepCurrent = list.any { it.id == _state.value.courseId }
                val selected = if (keepCurrent) _state.value.courseId else list.firstOrNull()?.id
                update { copy(loading = false, courses = list, courseId = selected) }
                loadKnowledgeBases(selected)
            }.onFailure {
                update { copy(loading = false) }
                emit(it.userMessage(), BannerTone.Error)
            }
        }
    }

    private fun loadKnowledgeBases(courseId: Long?) {
        if (courseId == null) {
            update { copy(kbs = emptyList(), kbChoice = KB_NONE) }
            return
        }
        viewModelScope.launch {
            // 知识库列表拉取失败不阻塞生成：退化成「不使用知识库」
            val list = runCatching { container.course.knowledgeBases(courseId) }.getOrNull().orEmpty()
            update { copy(kbs = list, kbChoice = KB_NONE) }
        }
    }

    fun onCourseSelected(id: String) {
        val courseId = id.toLongOrNull() ?: return
        if (courseId == _state.value.courseId) return
        // 课程切换：清掉知识库选择并重新拉取该课程的知识库
        update { copy(courseId = courseId, kbs = emptyList(), kbChoice = KB_NONE) }
        loadKnowledgeBases(courseId)
    }

    fun onKbChoice(value: String) {
        update { copy(kbChoice = value) }
    }

    fun onKpChange(value: String) {
        update { copy(kp = value) }
    }

    fun onTopicChange(value: String) {
        update { copy(topic = value) }
    }

    fun onCountChange(value: String) {
        update { copy(questionCount = value.toIntOrNull()?.coerceIn(1, 10) ?: 5) }
    }

    fun courseOptions(): List<Pair<String, String>> =
        _state.value.courses.map { it.id.toString() to it.name }

    fun kbOptions(): List<Pair<String, String>> = buildList {
        add(KB_NONE to "不使用知识库")
        if (_state.value.kbs.size > 1) add(KB_ALL to "全部知识库（${_state.value.kbs.size} 个）")
        _state.value.kbs.forEach { add(it.id.toString() to it.name) }
    }

    fun countOptions(): List<Pair<String, String>> = (1..10).map { it.toString() to "$it 道" }

    fun kbLabel(): String = when (_state.value.kbChoice) {
        KB_ALL -> "全部知识库"
        KB_NONE -> ""
        else -> _state.value.kbs.firstOrNull { it.id.toString() == _state.value.kbChoice }?.name.orEmpty()
    }

    private fun kbIds(): List<Long> {
        val snapshot = _state.value
        return when (snapshot.kbChoice) {
            KB_ALL -> snapshot.kbs.map { it.id }
            KB_NONE -> emptyList()
            else -> snapshot.kbs.firstOrNull { it.id.toString() == snapshot.kbChoice }?.let { listOf(it.id) }.orEmpty()
        }
    }

    fun generateLecture() {
        val snapshot = _state.value
        val courseId = snapshot.courseId
        if (courseId == null || snapshot.topic.isBlank()) {
            emit("请填写课程与讲义主题", BannerTone.Error)
            return
        }
        if (snapshot.busy) return
        streamJob?.cancel()
        update {
            copy(
                busy = true,
                streaming = true,
                lecture = "",
                lectureId = null,
                interrupted = false,
                questions = emptyList(),
                history = emptyList(),
                kbSaved = false,
                kbName = "",
            )
        }
        val topic = snapshot.topic.trim()
        val kp = snapshot.kp.trim()
        val selectedKbIds = kbIds()
        streamJob = viewModelScope.launch {
            try {
                container.generate.lectureStream(courseId, topic, kp, selectedKbIds).collect { event ->
                    when (event) {
                        is LectureEvent.Delta -> update { copy(lecture = lecture + event.text) }

                        is LectureEvent.Saved -> {
                            val savedId = event.id.takeIf { it > 0L }
                            update { copy(busy = false, streaming = false, lectureId = savedId) }
                            if (savedId != null) {
                                emit("讲义已生成并保存", BannerTone.Success)
                            } else {
                                emit("讲义已生成，但后端没回传内容 id，可从生成历史里找回", BannerTone.Warn)
                                loadHistory()
                            }
                        }

                        is LectureEvent.Failed -> {
                            update { copy(busy = false, streaming = false) }
                            emit(event.message.ifBlank { "生成失败，请重试" }, BannerTone.Error)
                        }

                        LectureEvent.Interrupted -> {
                            update { copy(busy = false, streaming = false, interrupted = true) }
                            emit("连接中断，请稍后查看生成结果", BannerTone.Warn)
                        }
                    }
                }
            } catch (error: CancellationException) {
                // ViewModel 被回收：不弹错误，也不把断流当失败
                throw error
            } catch (error: Throwable) {
                update { copy(busy = false, streaming = false) }
                emit(error.userMessage(), BannerTone.Error)
            } finally {
                container.refreshQuota()
            }
        }
    }

    fun generateQuestions() {
        val snapshot = _state.value
        val courseId = snapshot.courseId
        if (courseId == null) {
            emit("请选择课程", BannerTone.Error)
            return
        }
        if (snapshot.busy) return
        update { copy(busy = true, questions = emptyList(), lecture = "", lectureId = null, streaming = false, interrupted = false, kbSaved = false, kbName = "") }
        val kp = snapshot.kp.trim()
        val count = snapshot.questionCount
        val selectedKbIds = kbIds()
        viewModelScope.launch {
            val result = runCatching { container.generate.questions(courseId, kp, count, selectedKbIds) }
            update { copy(busy = false) }
            result.onSuccess {
                update { copy(questions = it) }
                emit("已生成 ${it.size} 道题目并入库，可直接去练习", BannerTone.Success)
            }.onFailure { emit(it.userMessage(), BannerTone.Error) }
            container.refreshQuota()
        }
    }

    fun loadHistory() {
        viewModelScope.launch {
            update { copy(historyLoading = true) }
            val result = runCatching { container.generate.history() }
            update { copy(historyLoading = false) }
            result.onSuccess { list ->
                val lectures = list.filter { it.type == "lecture" }
                update { copy(history = lectures) }
                if (lectures.isEmpty()) emit("生成历史里还没有讲义", BannerTone.Warn)
            }.onFailure { emit(it.userMessage(), BannerTone.Error) }
        }
    }

    /** 从历史里取回一篇讲义，后续「存入知识库 / 下载」都按这篇走。 */
    fun useFromHistory(item: GeneratedContentDto) {
        update {
            copy(
                lecture = item.content,
                lectureId = item.id.takeIf { it > 0L },
                interrupted = false,
                kbSaved = false,
                kbName = "",
                questions = emptyList(),
            )
        }
        emit("已载入讲义「${item.title.ifBlank { "未命名" }}」", BannerTone.Info)
    }

    fun saveToKnowledgeBase() {
        val snapshot = _state.value
        val id = snapshot.lectureId
        if (id == null || snapshot.kbSaved || snapshot.kbSaving) return
        update { copy(kbSaving = true) }
        viewModelScope.launch {
            val result = runCatching { container.generate.toKnowledgeBase(id) }
            update { copy(kbSaving = false) }
            result.onSuccess {
                val name = it.kbName.ifBlank { "课程知识库" }
                update { copy(kbSaved = true, kbName = name) }
                emit("已存入「$name」，智能答疑可直接引用该讲义内容", BannerTone.Success)
            }.onFailure { emit(it.userMessage(), BannerTone.Error) }
        }
    }

    /** 下载讲义：字节在这里取，落盘/分享由页面持 Context 完成。 */
    suspend fun lectureBytes(): ByteArray {
        val id = _state.value.lectureId ?: throw IllegalStateException("还没有可下载的讲义")
        return container.generate.downloadLecture(id)
    }

    fun onDownloadStateChanged(downloading: Boolean) {
        update { copy(downloading = downloading) }
    }
}
