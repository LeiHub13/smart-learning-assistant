package com.zhixue.assistant.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.ApiException
import com.zhixue.assistant.core.NetworkException
import com.zhixue.assistant.core.isAuthExpired
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.AgentActionDto
import com.zhixue.assistant.data.model.AgentActionUi
import com.zhixue.assistant.data.model.ChatMessageDto
import com.zhixue.assistant.data.model.ChatSessionDto
import com.zhixue.assistant.data.model.ChatStreamFinal
import com.zhixue.assistant.data.model.ChatUiMessage
import com.zhixue.assistant.data.model.KbChunkRef
import com.zhixue.assistant.data.model.KnowledgeBaseDto
import com.zhixue.assistant.data.repo.ChatStreamEvent
import com.zhixue.assistant.util.autoTitle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 答疑页状态。核心约束：**每条流只写入它所属会话的消息桶**。
 * 生成中切到别的会话，流继续跑并写进原会话的桶；切回来时接回同一个气泡，
 * 既不串会话，也不丢已显示的内容（和 Web 的 activeStream.target 行为一致，但按会话分桶更安全）。
 */
data class ChatUiState(
    /** 全量会话（切换答疑模式时不能丢数据，只重新过滤显示）。 */
    val sessions: List<ChatSessionDto> = emptyList(),
    val currentSessionId: Long? = null,
    val buckets: Map<Long, List<ChatUiMessage>> = emptyMap(),
    val loadingMessages: Boolean = false,
    val streamingSessionId: Long? = null,
    val mode: String = MODE_KB,
    val courseId: Long? = null,
    val kbId: Long? = null,
    val kbScope: String = "single",
    val knowledgeBases: List<KnowledgeBaseDto> = emptyList(),
    val listLoading: Boolean = true,
    val error: String? = null,
    val hint: String? = null,
) {
    val messages: List<ChatUiMessage>
        get() = currentSessionId?.let { buckets[it] }.orEmpty()

    /** 会话列表按答疑模式过滤：知识库会话与自由对话会话分属两个池子。 */
    val visibleSessions: List<ChatSessionDto>
        get() = sessions.filter { if (mode == MODE_KB) it.kbMode else !it.kbMode }

    val streaming: Boolean get() = streamingSessionId != null
    val currentSession: ChatSessionDto? get() = sessions.firstOrNull { it.id == currentSessionId }
    val socratic: Boolean get() = currentSession?.socratic == true
    val feynman: Boolean get() = currentSession?.feynman == true

    companion object {
        const val MODE_KB = "kb"
        const val MODE_FREE = "free"
    }
}

class ChatViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** 引用原文按消息 id 缓存，展开过的不重复请求。 */
    private val _chunks = MutableStateFlow<Map<Long, List<KbChunkRef>>>(emptyMap())
    val chunks: StateFlow<Map<Long, List<KbChunkRef>>> = _chunks.asStateFlow()

    private var streamJob: Job? = null
    private var streamSessionId: Long? = null

    /** 本地消息（未落库的提问与进行中的气泡）用负数自增 id，避免与库里的正数 id 或彼此撞 key。 */
    private var localIdCounter = 0L
    private fun nextLocalId(): Long {
        localIdCounter -= 1
        return localIdCounter
    }

    /** 图谱跳转带过来的待自动提问，消费一次即清空，避免旋转/返回后重发。 */
    private var pendingAsk: String? = null

    init {
        viewModelScope.launch {
            container.courses.courses.collect { courses ->
                if (courses.isNotEmpty() && _state.value.courseId == null) {
                    _state.update { it.copy(courseId = courses.first().id) }
                    loadKnowledgeBases(courses.first().id)
                }
            }
        }
        refreshSessions(selectFirst = true)
    }

    fun useSeed(courseId: Long?, kbId: Long?, scope: String, ask: String, rel: String) {
        if (courseId != null && courseId > 0) {
            _state.update { it.copy(courseId = courseId, mode = ChatUiState.MODE_KB) }
            loadKnowledgeBases(courseId)
        }
        if (kbId != null && kbId > 0) {
            _state.update { it.copy(kbId = kbId, kbScope = scope) }
        }
        if (ask.isNotBlank()) {
            val relation = rel.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(4)
            pendingAsk = if (relation.isEmpty()) {
                "请结合知识库资料讲解「${ask}」这个概念：它的定义与要点。"
            } else {
                "请结合知识库资料讲解「${ask}」这个概念：它的定义与要点，以及它与" +
                    relation.joinToString("") { "「$it」" } + "等概念的关系。"
            }
        }
    }

    fun setMode(mode: String) {
        val kbMode = mode == ChatUiState.MODE_KB
        val opened = _state.value.currentSessionId?.let { id -> _state.value.sessions.firstOrNull { it.id == id } }
        _state.update { state ->
            val candidates = state.sessions.filter { it.kbMode == kbMode }
            val keepCurrent = opened?.takeIf { it.kbMode == kbMode }?.id
            state.copy(
                mode = mode,
                courseId = if (kbMode) state.courseId else null,
                kbId = if (kbMode) state.kbId else null,
                currentSessionId = keepCurrent ?: candidates.firstOrNull()?.id,
                error = null,
            )
        }
        // 模式切换后可能换了一个会话：没有本地缓存就去拉历史
        _state.value.currentSessionId?.let { id ->
            if (_state.value.buckets[id] == null) loadMessages(id) else attachPendingActions(id)
        }
    }

    fun selectCourse(courseId: Long) {
        _state.update { it.copy(courseId = courseId, kbId = null) }
        loadKnowledgeBases(courseId)
    }

    fun selectKnowledgeBase(kbId: Long) {
        _state.update { it.copy(kbId = kbId) }
    }

    fun setKbScope(scope: String) {
        _state.update { it.copy(kbScope = scope) }
        val sid = _state.value.currentSessionId ?: return
        viewModelScope.launch {
            runCatching { container.chat.setKbScope(sid, _state.value.courseId ?: 0L, _state.value.kbId ?: 0L, scope) }
                .onFailure { toast(it.userMessage()) }
        }
    }

    private fun loadKnowledgeBases(courseId: Long) {
        viewModelScope.launch {
            runCatching { container.course.knowledgeBases(courseId) }
                .onSuccess { kbs ->
                    _state.update { it.copy(knowledgeBases = kbs, kbId = it.kbId?.takeIf { sel -> kbs.any { k -> k.id == sel } }) }
                }
                .onFailure { toast(it.userMessage()) }
        }
    }

    fun refreshSessions(selectFirst: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(listLoading = true) }
            runCatching { container.chat.sessions() }
                .onSuccess { list ->
                    _state.update { state ->
                        val keepCurrent = state.currentSessionId?.takeIf { id -> list.any { it.id == id } }
                        val target = keepCurrent
                            ?: if (selectFirst) state.visibleSessions.firstOrNull()?.id.also { } else null
                        state.copy(sessions = list, listLoading = false)
                    }
                    val pending = _state.value.currentSessionId
                    if (pending == null && selectFirst) {
                        _state.value.visibleSessions.firstOrNull()?.let { openSession(it.id) }
                    } else if (pending != null && _state.value.buckets[pending] == null) {
                        loadMessages(pending)
                    }
                }
                .onFailure { err ->
                    _state.update { state -> state.copy(listLoading = false, error = err.userMessage()) }
                }
        }
    }

    fun openSession(sessionId: Long) {
        if (sessionId == _state.value.currentSessionId) return
        _state.update { it.copy(currentSessionId = sessionId, error = null) }
        container.session.saveLastChatSession(sessionId)
        val cached = _state.value.buckets[sessionId]
        // 该会话正在生成时不再重拉历史：服务端还没写入这条回答，拉回来会覆盖进行中的气泡
        if (cached == null && streamSessionId != sessionId) loadMessages(sessionId) else attachPendingActions(sessionId)
        // 回到正在生成的会话时，气泡已经带着部分内容在桶里，无需任何补拉
        consumePendingAsk()
    }

    private fun consumePendingAsk() {
        val ask = pendingAsk ?: return
        if (_state.value.currentSessionId != null) {
            pendingAsk = null
            send(ask)
            return
        }
        viewModelScope.launch {
            val created = createSessionLocked() ?: return@launch
            pendingAsk = null
            if (_state.value.currentSessionId == created) send(ask)
        }
    }

    private fun loadMessages(sessionId: Long) {
        viewModelScope.launch {
            _state.update { it.copy(loadingMessages = true) }
            runCatching { container.chat.messages(sessionId) }
                .onSuccess { list ->
                    _state.update { state ->
                        val remote = list.map { m -> m.toUi() }.toMutableList()
                        // 本地还没落库的消息（刚发出的提问、正在生成的气泡）必须保留在尾部，
                        // 否则「新建会话 → 立刻提问」时这次历史加载会把它们冲掉，回答就永远不上屏。
                        val local = state.buckets[sessionId].orEmpty().filter { it.id < 0 }
                        remote += local
                        state.copy(loadingMessages = false, buckets = state.buckets + (sessionId to remote))
                    }
                    attachPendingActions(sessionId)
                }
                .onFailure { err ->
                    _state.update { state -> state.copy(loadingMessages = false, error = err.userMessage()) }
                }
        }
    }

    /** 待确认动作必须挂到最后一条助手消息上，否则刷新后会「卡片消失但动作还在」。 */
    private fun attachPendingActions(sessionId: Long) {
        viewModelScope.launch {
            runCatching { container.chat.pendingActions(sessionId) }
                .onSuccess { actions ->
                    if (actions.isEmpty()) return@onSuccess
                    mutate(sessionId) { list ->
                        val lastAssistantIndex = list.indexOfLast { it.role == "assistant" }
                        if (lastAssistantIndex < 0) {
                            list + ChatUiMessage(id = -1, role = "assistant", content = "", actions = actions.map { it.toUi() })
                        } else {
                            list.mapIndexed { index, message ->
                                if (index == lastAssistantIndex) message.copy(actions = actions.map { it.toUi() }) else message
                            }
                        }
                    }
                }
        }
    }

    fun newSession() {
        viewModelScope.launch { createSessionLocked() }
    }

    private suspend fun createSessionLocked(): Long? {
        val state = _state.value
        if (state.mode == ChatUiState.MODE_KB && state.kbId == null) {
            toast("请先选择课程和知识库，再新建会话")
            return null
        }
        return try {
            val session = container.chat.createSession(
                courseId = if (state.mode == ChatUiState.MODE_KB) state.courseId else null,
                kbId = if (state.mode == ChatUiState.MODE_KB) state.kbId else null,
                kbScope = if (state.mode == ChatUiState.MODE_KB) state.kbScope else null,
            )
            _state.update { it.copy(sessions = listOf(session) + it.sessions) }
            openSession(session.id)
            session.id
        } catch (e: Throwable) {
            if (!e.isAuthExpired()) toast(e.userMessage())
            null
        }
    }

    fun rename(sessionId: Long, title: String) {
        if (title.isBlank()) {
            toast("标题不能为空")
            return
        }
        viewModelScope.launch {
            runCatching { container.chat.rename(sessionId, title) }
                .onSuccess { updated ->
                    _state.update { it.copy(sessions = it.sessions.map { if (it.id == sessionId) updated else it }) }
                }
                .onFailure { toast(it.userMessage()) }
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            runCatching { container.chat.delete(sessionId) }
                .onSuccess {
                    _state.update { it.copy(buckets = it.buckets - sessionId, sessions = it.sessions.filter { s -> s.id != sessionId }) }
                    if (_state.value.currentSessionId == sessionId) {
                        val next = _state.value.sessions.firstOrNull()?.id
                        _state.update { it.copy(currentSessionId = next) }
                        if (next != null) openSession(next)
                    }
                    toast("已删除会话")
                }
                .onFailure { toast(it.userMessage()) }
        }
    }

    fun toggleSocratic(enabled: Boolean) = setModeFlags(socratic = enabled)

    fun toggleFeynman(enabled: Boolean) = setModeFlags(feynman = enabled)

    private fun setModeFlags(socratic: Boolean? = null, feynman: Boolean? = null) {
        val sessionId = _state.value.currentSessionId
        if (sessionId == null) {
            toast("先选择或新建会话，再开启引导模式")
            return
        }
        viewModelScope.launch {
            runCatching {
                if (feynman != null) container.chat.setFeynman(sessionId, feynman)
                else if (socratic != null) container.chat.setSocratic(sessionId, socratic)
                else container.chat.rename(sessionId, _state.value.currentSession?.title.orEmpty())
            }.onSuccess { updated ->
                _state.update { it.copy(sessions = it.sessions.map { if (it.id == sessionId) updated else it }) }
            }.onFailure { toast(it.userMessage()) }
        }
    }

    fun send(text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        val state = _state.value
        if (state.streaming) return
        if (state.mode == ChatUiState.MODE_KB && state.kbId == null) {
            toast("请先选择知识库再提问")
            return
        }
        viewModelScope.launch {
            var sessionId = state.currentSessionId ?: createSessionLocked()
            if (sessionId == null) return@launch
            // 会话可能刚创建，state 里的模式标记还没刷新，这里以服务端会话为准
            val userMessage = ChatUiMessage(id = nextLocalId(), role = "user", content = content)
            val placeholder = ChatUiMessage(
                id = nextLocalId(),
                role = "assistant",
                content = "",
                streaming = true,
            )
            mutate(sessionId) { it + userMessage + placeholder }
            startStream(sessionId, content, placeholder.id)
        }
    }

    private fun startStream(sessionId: Long, message: String, targetKey: Long) {
        streamJob?.cancel()
        streamSessionId = sessionId
        _state.update { it.copy(streamingSessionId = sessionId, error = null) }
        streamJob = viewModelScope.launch {
            val builder = StringBuilder()
            var failure: String? = null
            var completed = false
            var finalEvent: ChatStreamFinal? = null
            try {
                container.chat.stream(sessionId, message).collect { event ->
                    when (event) {
                        is ChatStreamEvent.Delta -> {
                            builder.append(event.text)
                            mutate(sessionId) { list ->
                                list.map { if (it.id == targetKey) it.copy(content = builder.toString()) else it }
                            }
                        }

                        is ChatStreamEvent.Completed -> {
                            completed = true
                            finalEvent = event.final
                        }

                        is ChatStreamEvent.Failed -> {
                            completed = true
                            failure = event.message
                        }

                        ChatStreamEvent.Interrupted -> failure = "连接中断，请稍后查看生成结果"
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 额度不足 / 未登录等预检错误在流开始前以信封抛出，不能当成「已生成」
                if (!e.isAuthExpired()) failure = e.userMessage() else failure = null
            } finally {
                val actions = finalEvent?.actions.orEmpty().map { it.toUi() }
                mutate(sessionId) { list ->
                    list.map { message ->
                        if (message.id != targetKey) return@map message
                        message.copy(
                            streaming = false,
                            content = when {
                                failure != null && builder.isEmpty() -> "（请求失败：$failure）"
                                builder.isNotEmpty() -> builder.toString()
                                else -> message.content
                            },
                            sources = finalEvent?.sources?.takeIf { it.isNotBlank() },
                            sourceChunks = finalEvent?.sourceChunks?.takeIf { it.isNotBlank() },
                            followups = finalEvent?.followups,
                            actions = actions,
                            error = failure,
                        )
                    }
                }
                streamSessionId = null
                _state.update { it.copy(streamingSessionId = null) }
                container.refreshQuota()
                // 首问会自动改标题，重新拉一次会话列表
                refreshSessions()
                failure?.let { f -> if (!completed) toast(f) }
            }
        }
    }

    fun loadChunks(messageId: Long, ids: List<String>) {
        if (ids.isEmpty() || _chunks.value.containsKey(messageId)) return
        viewModelScope.launch {
            runCatching { container.chat.chunks(ids) }
                .onSuccess { list -> _chunks.update { map -> map + (messageId to list) } }
                .onFailure { toast("引用原文加载失败：" + it.userMessage()) }
        }
    }

    fun confirmAction(messageKey: Long, actionId: Long) {
        val sessionId = _state.value.currentSessionId ?: return
        mutate(sessionId) { list ->
            list.map { m ->
                if (m.id != messageKey) return@map m
                m.copy(actions = m.actions.map { if (it.id == actionId) it.copy(busy = true, error = null) else it })
            }
        }
        viewModelScope.launch {
            try {
                val result = container.chat.confirmAction(actionId)
                mutate(sessionId) { list ->
                    list.map { m ->
                        if (m.id != messageKey) return@map m
                        m.copy(actions = m.actions.map {
                            if (it.id == actionId) {
                                it.copy(
                                    busy = false,
                                    result = result.result?.takeIf { r -> r.isNotBlank() } ?: "已执行",
                                )
                            } else it
                        })
                    }
                }
                if (!result.navigate.isNullOrBlank()) {
                    com.zhixue.assistant.ui.nav.WebRoute.toAndroid(result.navigate)?.let {
                        navigateToWebRoute(it)
                    }
                }
            } catch (e: Throwable) {
                if (e.isAuthExpired()) return@launch
                mutate(sessionId) { list ->
                    list.map { m ->
                        if (m.id != messageKey) return@map m
                        m.copy(actions = m.actions.map {
                            if (it.id == actionId) it.copy(busy = false, error = e.userMessage()) else it
                        })
                    }
                }
                toast(e.userMessage())
            }
        }
    }

    fun cancelAction(messageKey: Long, actionId: Long) {
        val sessionId = _state.value.currentSessionId ?: return
        viewModelScope.launch {
            runCatching { container.chat.cancelAction(actionId) }
                .onSuccess {
                    mutate(sessionId) { list ->
                        list.map { m ->
                            if (m.id != messageKey) return@map m
                            m.copy(actions = m.actions.filter { a -> a.id != actionId })
                        }
                    }
                }
                .onFailure { toast(it.userMessage()) }
        }
    }

    fun generateFlashcards() {
        val sessionId = _state.value.currentSessionId
        if (sessionId == null) {
            toast("请先选择会话")
            return
        }
        viewModelScope.launch {
            runCatching { container.chat.generateFlashcards(sessionId) }
                .onSuccess { toast("已生成 ${it.created} 张闪卡") }
                .onFailure { toast(it.userMessage()) }
        }
    }

    /** 供导航层注入：ViewModel 不直接依赖 NavController。 */
    var navigateToWebRoute: (String) -> Unit = {}

    /** 会话页被彻底销毁时才取消网络请求；仅切页面不取消，服务端会继续生成并落库。 */
    override fun onCleared() {
        super.onCleared()
        streamJob?.cancel()
    }

    private fun mutate(sessionId: Long, block: (List<ChatUiMessage>) -> List<ChatUiMessage>) {
        _state.update { state ->
            val current = state.buckets[sessionId].orEmpty()
            state.copy(buckets = state.buckets + (sessionId to block(current)))
        }
    }

    private fun toast(message: String) {
        _state.update { it.copy(hint = message) }
    }

    fun consumeHint() {
        _state.update { it.copy(hint = null) }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ChatViewModel(container) }
        }

        private fun ChatMessageDto.toUi(): ChatUiMessage = ChatUiMessage(
            id = id,
            role = role,
            content = content,
            sources = sources?.takeIf { it.isNotBlank() },
            sourceChunks = sourceChunks?.takeIf { it.isNotBlank() },
            followups = followups,
            actions = emptyList(),
            createdAt = createdAt,
        )

        private fun AgentActionDto.toUi(): AgentActionUi = AgentActionUi(
            id = id,
            kind = kind,
            summary = summary,
            expiresAt = expiresAt,
        )

        fun draftTitle(text: String): String = autoTitle(text)
    }

    /** 网络错误单独暴露，用于「重试」而不是静默。 */
    val lastErrorIsNetwork: Boolean
        get() = _state.value.error?.let { it.contains("网络") || it.contains("连接") } == true

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun retryCurrentMessages() {
        _state.value.currentSessionId?.let { id ->
            _state.update { it.copy(buckets = it.buckets - id) }
            loadMessages(id)
        }
    }

    fun isStreamingFor(sessionId: Long?): Boolean = streamSessionId == sessionId && sessionId != null

    fun describeError(t: Throwable): String = when (t) {
        is ApiException -> t.message
        is NetworkException -> t.message
        else -> t.userMessage()
    }
}
