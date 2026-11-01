package com.zhixue.assistant.ui.screens.hall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.FriendPending
import com.zhixue.assistant.data.model.FriendRow
import com.zhixue.assistant.data.model.HallChannel
import com.zhixue.assistant.data.model.HallMessageDto
import com.zhixue.assistant.data.model.OnlineUser
import com.zhixue.assistant.data.model.SpaceNote
import com.zhixue.assistant.data.repo.DmIncomingDto
import com.zhixue.assistant.data.repo.HallFrame
import com.zhixue.assistant.data.repo.SocketStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** 当前打开的会话：公共大厅 / 课程频道 / 好友私聊。 */
sealed interface HallTarget {
    val key: String
    val title: String

    data class Channel(val courseId: Long?, val name: String) : HallTarget {
        override val key: String get() = "channel-" + (courseId?.toString() ?: "pub")
        override val title: String get() = "# $name"
    }

    data class Peer(val userId: Long, val name: String, val avatar: String?) : HallTarget {
        override val key: String get() = "peer-$userId"
        override val title: String get() = name
    }
}

/** 消息流里的一行：频道消息、私聊消息、居中灰字提示（进出场等）。 */
sealed class HallEntry {
    abstract val key: String

    data class Channel(val message: HallMessageDto) : HallEntry() {
        override val key: String get() = "c${message.id}"
    }

    data class Direct(
        val id: Long,
        val mine: Boolean,
        val userId: Long,
        val nickname: String,
        val avatar: String?,
        val content: String,
        val timeRaw: String?,
    ) : HallEntry() {
        override val key: String get() = "d$id"
    }

    data class Notice(override val key: String, val text: String) : HallEntry()
}

/**
 * 助教生成中的占位气泡（挂在发起提问的频道上，切走再切回来内容还在）。
 * error 非空表示后端回了 ai_error；settled 表示流已收口，可以按 Markdown 排版。
 */
data class AiBubble(
    val courseId: Long?,
    val text: String = "",
    val error: String? = null,
    val settled: Boolean = false,
)

data class HallUiState(
    val meId: Long = 0L,
    val myNickname: String = "",
    val myAvatar: String = "",

    val channels: List<HallChannel> = emptyList(),
    val channelsLoading: Boolean = true,
    val channelsError: String? = null,

    val target: HallTarget? = null,
    val entries: List<HallEntry> = emptyList(),
    val historyLoading: Boolean = false,
    val historyError: String? = null,

    val onlineUsers: List<OnlineUser> = emptyList(),
    val onlineCount: Int = 0,
    val status: SocketStatus = SocketStatus.IDLE,
    val attempt: Int = 0,

    val chatTab: String = HallViewModel.TAB_CHAT,
    val spaceNotes: List<SpaceNote> = emptyList(),
    val spaceLoading: Boolean = false,
    val spaceError: String? = null,

    val friends: List<FriendRow> = emptyList(),
    val incoming: List<FriendPending> = emptyList(),
    val outgoing: List<FriendPending> = emptyList(),
    val friendsLoading: Boolean = true,
    val friendsError: String? = null,
    val unread: Map<Long, Int> = emptyMap(),
    val addOpen: Boolean = false,
    val addName: String = "",
    val adding: Boolean = false,
    val addMsg: String? = null,
    val addMsgFailed: Boolean = false,

    val input: String = "",
    val notice: String? = null,
    val hint: String? = null,
    val ai: AiBubble? = null,
) {
    val connected: Boolean get() = status == SocketStatus.OPEN
    val isDm: Boolean get() = target is HallTarget.Peer

    /** 学习空间只在课程频道存在（公共大厅没有知识库，私聊不适用）。 */
    val isCourseChannel: Boolean get() = target is HallTarget.Channel && (target as HallTarget.Channel).courseId != null
    val showChat: Boolean get() = !isCourseChannel || chatTab == HallViewModel.TAB_CHAT
    val activeCourseId: Long? get() = (target as? HallTarget.Channel)?.courseId

    /** 占位气泡只在「正在看它所属频道」时出现，和 Web 的 aiThinking 一致。 */
    val aiVisible: Boolean
        get() = ai != null && target is HallTarget.Channel &&
            (ai as AiBubble).courseId == (target as HallTarget.Channel).courseId

    val onlineNames: String get() = onlineUsers.joinToString("、") { it.nickname }
    val totalUnread: Int get() = unread.values.sumOf { it }

    /** 正在看私聊时只显示对端在线（口径来自 /api/friends，30 秒粒度）。 */
    val peerOnline: Boolean
        get() = (target as? HallTarget.Peer)?.userId
            ?.let { id -> friends.firstOrNull { it.userId == id }?.online == true } == true

    val statusText: String
        get() = when {
            isDm -> if (peerOnline) "对方在线" else "对方不在线（消息会通知对方）"
            connected -> "在线 $onlineCount 人" + if (onlineNames.isNotBlank()) "：$onlineNames" else ""
            status == SocketStatus.RETRYING -> "连接断开，正在重连…"
            else -> "连接中…"
        }

    /** 自动重试全部失败（CLOSED）后才给手动重连入口，避免和 3s/6s 重试抢按钮。 */
    val showReconnect: Boolean get() = status == SocketStatus.CLOSED

    val canSend: Boolean get() = connected && input.trim().isNotEmpty() && target != null

    fun unreadOf(userId: Long): Int = unread[userId] ?: 0

    fun emptyTitle(): String = when (val t = target) {
        is HallTarget.Peer -> "与 ${t.name} 的私聊"
        is HallTarget.Channel -> "欢迎来到 ${t.name}"
        null -> "欢迎来到 公共大厅"
    }

    val emptyHint: String
        get() = if (isDm) "聊点什么吧，只有你们两个人能看到" else "打个招呼吧"

    val inputHint: String
        get() = when {
            !connected -> "未连接"
            isDm -> "悄悄话"
            isCourseChannel -> "和大家聊聊，或点「问助教」让 AI 基于课程资料回答"
            else -> "和大家聊聊学习心得"
        }
}

/**
 * 对话厅状态机：REST 拉历史/好友/学习空间，WebSocket 收实时帧，两者都按「当前会话归属」过滤，
 * 绝不把某个频道或某位好友的消息渲染到别的会话里。
 */
class HallViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(HallUiState())
    val state: StateFlow<HallUiState> = _state.asStateFlow()

    private val noticeSeq = AtomicInteger(0)
    private var friendsPollJob: Job? = null
    private var aiCheckJob: Job? = null
    private var booted = false
    private var pendingPeerId: Long? = null

    init {
        // 「我」的 id / 头像：个人中心可能改过头像，这里跟随登录态变化
        viewModelScope.launch {
            container.session.session.collect { saved ->
                val me = saved?.me
                _state.update {
                    it.copy(
                        meId = me?.userId ?: it.meId,
                        myNickname = me?.nickname?.takeIf { n -> n.isNotBlank() } ?: me?.username ?: it.myNickname,
                        myAvatar = me?.avatar ?: "",
                    )
                }
            }
        }
        viewModelScope.launch { runCatching { container.auth.fetchMe() } }
        viewModelScope.launch { container.hallSocket.status.collect { s -> _state.update { it.copy(status = s) } } }
        viewModelScope.launch { container.hallSocket.attempt.collect { a -> _state.update { it.copy(attempt = a) } } }
        viewModelScope.launch { container.hallSocket.frames.collect { handleFrame(it) } }
        bootstrap()
    }

    private fun bootstrap() {
        viewModelScope.launch {
            loadChannels()
            loadFriendsNow()
            val list = _state.value.channels.ifEmpty { listOf(HallChannel(null, PUBLIC_NAME)) }
            val restoreKey = container.session.session.value?.lastHallChannel
            val preferred = list.firstOrNull { channelKey(it.courseId) == restoreKey } ?: list.first()
            openChannel(preferred.courseId, preferred.name)
            booted = true
            pendingPeerId?.let {
                pendingPeerId = null
                openDm(it)
            }
        }
    }

    /** 通知中心的 /hall?peer=3 之类跳转：boot 未完成时先记下，完成后消费一次。 */
    fun usePeer(peerId: Long) {
        if (peerId <= 0L) return
        if (!booted) {
            pendingPeerId = peerId
            return
        }
        if ((_state.value.target as? HallTarget.Peer)?.userId == peerId) return
        openDm(peerId)
    }

    // ---------------- 频道与历史 ----------------

    fun reloadChannels() {
        viewModelScope.launch { loadChannels() }
    }

    private suspend fun loadChannels() {
        _state.update { it.copy(channelsLoading = true) }
        runCatching { container.hall.channels() }
            .onSuccess { list -> _state.update { it.copy(channels = list, channelsLoading = false, channelsError = null) } }
            .onFailure { e -> _state.update { it.copy(channelsLoading = false, channelsError = e.userMessage()) } }
    }

    /** 不传 name 时按已加载的频道列表解析标题（删除好友后回落到首个频道会用到）。 */
    fun openChannel(courseId: Long?, name: String? = null) {
        val resolved = name
            ?: _state.value.channels.firstOrNull { it.courseId == courseId }?.name
            ?: PUBLIC_NAME
        val target = HallTarget.Channel(courseId, resolved)
        _state.update {
            it.copy(
                target = target,
                entries = emptyList(),
                historyLoading = true,
                historyError = null,
                // 旧频道的在线名单不再适用，清空等新回帧
                onlineUsers = emptyList(),
                onlineCount = 0,
                chatTab = TAB_CHAT,
                spaceNotes = emptyList(),
                spaceLoading = false,
                spaceError = null,
                notice = null,
            )
        }
        container.session.saveLastHallChannel(channelKey(courseId))
        container.hallSocket.enterChannel(courseId)
        loadChannelHistory(courseId)
    }

    fun openChannelById(courseId: Long?) {
        val channel = _state.value.channels.firstOrNull { it.courseId == courseId } ?: return
        openChannel(channel.courseId, channel.name)
    }

    private fun loadChannelHistory(courseId: Long?) {
        viewModelScope.launch {
            val result = runCatching { container.hall.history(courseId) }
            if (!isChannelActive(courseId)) return@launch
            result
                .onSuccess { list ->
                    _state.update {
                        it.copy(entries = list.map { m -> HallEntry.Channel(m) }, historyLoading = false, historyError = null)
                    }
                }
                .onFailure { e -> _state.update { it.copy(historyLoading = false, historyError = e.userMessage()) } }
        }
    }

    fun retryHistory() {
        when (val t = _state.value.target) {
            is HallTarget.Channel -> {
                _state.update { it.copy(historyLoading = true, historyError = null) }
                loadChannelHistory(t.courseId)
            }

            is HallTarget.Peer -> {
                _state.update { it.copy(historyLoading = true, historyError = null) }
                loadDmHistory(t.userId)
            }

            null -> Unit
        }
    }

    // ---------------- 私聊 ----------------

    fun openDm(peerUserId: Long) {
        val row = _state.value.friends.firstOrNull { it.userId == peerUserId }
        val target = HallTarget.Peer(
            userId = peerUserId,
            name = row?.nickname?.takeIf { it.isNotBlank() } ?: "用户$peerUserId",
            avatar = row?.avatar?.takeIf { it.isNotBlank() },
        )
        _state.update {
            it.copy(
                target = target,
                entries = emptyList(),
                historyLoading = true,
                historyError = null,
                unread = it.unread + (peerUserId to 0),
                chatTab = TAB_CHAT,
                notice = null,
            )
        }
        container.hallSocket.enterDm(peerUserId)
        loadDmHistory(peerUserId)
    }

    /** 拉私聊历史有副作用（后端置已读并清对应通知），这里只顺带重拉一次未读计数，不再调通知接口。 */
    private fun loadDmHistory(peerUserId: Long) {
        viewModelScope.launch {
            val result = runCatching { container.hall.dmMessages(peerUserId) }
            if ((_state.value.target as? HallTarget.Peer)?.userId != peerUserId) return@launch
            result
                .onSuccess { list ->
                    val row = _state.value.friends.firstOrNull { it.userId == peerUserId }
                    val myId = _state.value.meId
                    _state.update { state ->
                        state.copy(
                            entries = list.map { m ->
                                val mine = m.fromUserId == myId
                                HallEntry.Direct(
                                    id = m.id,
                                    mine = mine,
                                    userId = m.fromUserId,
                                    nickname = if (mine) state.myNickname.ifBlank { "我" } else row?.nickname ?: "用户$peerUserId",
                                    avatar = if (mine) state.myAvatar.ifBlank { null } else row?.avatar?.takeIf { it.isNotBlank() },
                                    content = m.content,
                                    timeRaw = m.createdAt,
                                )
                            },
                            historyLoading = false,
                            historyError = null,
                        )
                    }
                    container.notifications.refresh()
                }
                .onFailure { e -> _state.update { it.copy(historyLoading = false, historyError = e.userMessage()) } }
        }
    }

    // ---------------- 发送 ----------------

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value.take(MAX_CONTENT)) }
    }

    fun send() {
        val content = _state.value.input.trim().take(MAX_CONTENT)
        if (content.isEmpty()) return
        val socket = container.hallSocket
        val target = _state.value.target
        val ok = when (target) {
            is HallTarget.Peer -> socket.sendDm(target.userId, content)
            is HallTarget.Channel -> socket.sendChat(target.courseId, content)
            null -> false
        }
        if (!ok) {
            _state.update { it.copy(notice = "连接未就绪，消息没有发出去，请点「重新连接」") }
            return
        }
        _state.update { it.copy(input = "") }
    }

    /** 问助教：仅课程频道。问题本身由后端按普通消息落库广播，这里只发 ai 帧。 */
    fun askAssistant() {
        val content = _state.value.input.trim().take(MAX_CONTENT)
        if (content.isEmpty()) return
        val courseId = _state.value.activeCourseId
        if (courseId == null) {
            _state.update { it.copy(notice = "公共大厅没有 AI 助教，请在课程频道提问") }
            return
        }
        if (!container.hallSocket.askAssistant(courseId, content)) {
            _state.update { it.copy(notice = "连接未就绪，问题没有发出去，请点「重新连接」") }
            return
        }
        _state.update { it.copy(input = "") }
    }

    fun reconnect() = container.hallSocket.reconnect()

    /** 回到前台时重取「我」的资料（个人中心可能刚改过头像），与 Web 的 onActivated 一致。 */
    fun refreshMe() {
        viewModelScope.launch { runCatching { container.auth.fetchMe() } }
    }

    /** 顶栏「在线 N 人」点一下按当前频道重新拉名单。 */
    fun refreshOnline() {
        val courseId = (_state.value.target as? HallTarget.Channel)?.courseId ?: return
        container.hallSocket.requestOnline(courseId)
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun consumeHint() = _state.update { it.copy(hint = null) }

    // ---------------- WS 帧分发 ----------------

    private fun handleFrame(frame: HallFrame) {
        when (frame) {
            is HallFrame.Message -> onChatFrame(frame.message)
            is HallFrame.AiStart -> onAiStart(frame.courseId)
            is HallFrame.AiDelta -> onAiDelta(frame.courseId, frame.delta)
            is HallFrame.AiError -> onAiError(frame.courseId, frame.message)
            is HallFrame.Presence -> onPresence(frame)
            is HallFrame.Direct -> onDmFrame(frame.message)
            is HallFrame.RoomEvent -> onRoomEvent(frame.event, frame.nickname)
            is HallFrame.Rejected -> _state.update { it.copy(notice = frame.message.ifBlank { "发送失败" }) }
        }
    }

    private fun onChatFrame(message: HallMessageDto) {
        val target = _state.value.target
        if (target !is HallTarget.Channel || message.courseId != target.courseId) {
            // 助教最终帧落在别的会话：内容下次进频道从历史看，这里只负责收口占位气泡
            if (message.fromAi) clearAiBubble()
            return
        }
        appendChannelMessage(message)
        if (message.fromAi) clearAiBubble()
    }

    private fun appendChannelMessage(message: HallMessageDto) {
        _state.update { state ->
            if (state.entries.any { it is HallEntry.Channel && it.message.id == message.id }) state
            else state.copy(entries = state.entries + HallEntry.Channel(message))
        }
    }

    private fun onAiStart(courseId: Long?) {
        _state.update { it.copy(ai = AiBubble(courseId)) }
        // 只对「正看着的这个频道」做历史兜底核对，别的频道交给下次进入时加载的历史
        if ((_state.value.target as? HallTarget.Channel)?.courseId == courseId) watchAiResult(courseId)
    }

    private fun onAiDelta(courseId: Long?, delta: String) {
        val bubble = _state.value.ai ?: return
        if (bubble.courseId != courseId || bubble.error != null) return
        _state.update { state -> state.copy(ai = bubble.copy(text = bubble.text + delta)) }
    }

    private fun onAiError(courseId: Long?, message: String) {
        val bubble = _state.value.ai ?: return
        if (bubble.courseId != courseId) return
        aiCheckJob?.cancel()
        aiCheckJob = null
        _state.update {
            it.copy(ai = AiBubble(courseId, text = "", error = message.ifBlank { "助教回答失败" }, settled = true))
        }
    }

    /**
     * 助教最终帧是 chat 帧，但后端在该帧里把 role 下发成字符串 `"ai"`，
     * 数据层的 HallMessageDto.role 是数字口径，存在解析不到被丢弃的风险。
     * 因此占位气泡除了等最终帧，还按历史接口（REST 的 role=1 稳定）兜底核对：
     * 拿到比进入频道时更大的 AI 消息 id 即视为回答完成，随后停止轮询。
     */
    private fun watchAiResult(courseId: Long?) {
        aiCheckJob?.cancel()
        val baseline = _state.value.entries
            .filterIsInstance<HallEntry.Channel>()
            .maxOfOrNull { it.message.id } ?: 0L
        // 历史都没拉下来时无法判定「新回答」，交给最终帧与下次进频道的历史
        if (_state.value.historyError != null) return
        aiCheckJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + AI_FALLBACK_TIMEOUT_MS
            while (isActive) {
                delay(AI_POLL_INTERVAL_MS)
                val bubble = _state.value.ai ?: return@launch
                if (bubble.courseId != courseId || bubble.error != null) return@launch
                // 已经切走：本频道历史会在下次进入时重新加载，这里不再往别的会话里塞消息
                if (!isChannelActive(courseId)) return@launch
                val history = runCatching { container.hall.history(courseId) }.getOrNull() ?: continue
                val fresh = history.lastOrNull { it.fromAi && it.courseId == courseId && it.id > baseline }
                if (fresh != null) {
                    appendChannelMessage(fresh)
                    clearAiBubble()
                    return@launch
                }
                if (System.currentTimeMillis() > deadline) {
                    _state.update { state -> state.copy(ai = state.ai?.copy(settled = true)) }
                    return@launch
                }
            }
        }
    }

    private fun clearAiBubble() {
        aiCheckJob?.cancel()
        aiCheckJob = null
        _state.update { it.copy(ai = null) }
    }

    private fun onPresence(frame: HallFrame.Presence) {
        val target = _state.value.target as? HallTarget.Channel ?: return
        if (frame.courseId != target.courseId) return
        _state.update { it.copy(onlineUsers = frame.users, onlineCount = frame.onlineCount) }
    }

    private fun onRoomEvent(event: String, nickname: String) {
        val target = _state.value.target as? HallTarget.Channel ?: return
        // 进出场提示行只放公共大厅；课程频道的名单变化由 online 请求帧重新拉取（数据层已自动重发）
        if (target.courseId != null) return
        pushNotice(if (event == "join") "$nickname 进入对话厅" else "$nickname 离开了对话厅")
    }

    private fun pushNotice(text: String) {
        _state.update { it.copy(entries = it.entries + HallEntry.Notice("n${noticeSeq.incrementAndGet()}", text)) }
    }

    private fun onDmFrame(frame: DmIncomingDto) {
        val state = _state.value
        val mine = frame.fromUserId == state.meId
        val peerId = if (mine) frame.toUserId else frame.fromUserId
        val target = state.target
        if (target is HallTarget.Peer && target.userId == peerId) {
            val row = state.friends.firstOrNull { it.userId == peerId }
            val entry = HallEntry.Direct(
                id = frame.id,
                mine = mine,
                userId = frame.fromUserId,
                nickname = if (mine) state.myNickname.ifBlank { "我" } else frame.fromNickname.ifBlank { target.name },
                avatar = if (mine) state.myAvatar.ifBlank { null }
                else frame.fromAvatar?.takeIf { it.isNotBlank() } ?: row?.avatar?.takeIf { it.isNotBlank() },
                content = frame.content,
                timeRaw = frame.sentAt,
            )
            _state.update { s ->
                if (s.entries.any { it is HallEntry.Direct && it.id == entry.id }) s
                else s.copy(entries = s.entries + entry)
            }
            return
        }
        if (!mine) {
            _state.update { s -> s.copy(unread = s.unread + (peerId to (s.unreadOf(peerId) + 1))) }
        }
    }

    // ---------------- 学习空间 ----------------

    fun setChatTab(tab: String) {
        _state.update { it.copy(chatTab = tab) }
        if (tab == TAB_SPACE) refreshSpace()
    }

    fun refreshSpace() {
        val courseId = _state.value.activeCourseId ?: return
        _state.update { it.copy(spaceLoading = true, spaceError = null) }
        viewModelScope.launch {
            val result = runCatching { container.hall.spaceNotes(courseId) }
            if (_state.value.activeCourseId != courseId) return@launch
            result
                .onSuccess { list -> _state.update { it.copy(spaceNotes = list, spaceLoading = false, spaceError = null) } }
                .onFailure { e -> _state.update { it.copy(spaceLoading = false, spaceError = e.userMessage()) } }
        }
    }

    // ---------------- 好友 ----------------

    /** 页面切到前台时才轮询，避免后台耗流量与「离开还在改状态」。 */
    fun startFriendsPolling() {
        if (friendsPollJob?.isActive == true) return
        loadFriends()
        friendsPollJob = viewModelScope.launch {
            while (isActive) {
                delay(FRIENDS_POLL_MS)
                loadFriendsNow()
            }
        }
    }

    fun stopFriendsPolling() {
        friendsPollJob?.cancel()
        friendsPollJob = null
    }

    fun loadFriends() {
        viewModelScope.launch { loadFriendsNow() }
    }

    private suspend fun loadFriendsNow() {
        _state.update { it.copy(friendsLoading = true) }
        runCatching { container.hall.friends() }
            .onSuccess { data ->
                val snapshot = _state.value
                val activePeer = (snapshot.target as? HallTarget.Peer)?.userId
                val row = data.friends.firstOrNull { it.userId == activePeer }
                // 正开着的私聊：名字/头像以好友数据为准（可能刚同意、刚改昵称）
                val nextTarget = if (row != null && snapshot.target is HallTarget.Peer) {
                    val peer = snapshot.target as HallTarget.Peer
                    peer.copy(
                        name = row.nickname.takeIf { it.isNotBlank() } ?: peer.name,
                        avatar = row.avatar?.takeIf { it.isNotBlank() } ?: peer.avatar,
                    )
                } else snapshot.target
                _state.update { state ->
                    state.copy(
                        friends = data.friends,
                        incoming = data.incoming,
                        outgoing = data.outgoing,
                        friendsLoading = false,
                        friendsError = null,
                        // 未读以服务端计数为准；正开着的会话视为已读
                        unread = data.friends.associate { f ->
                            f.userId to (if (f.userId == activePeer) 0 else f.unread)
                        },
                        target = nextTarget,
                    )
                }
            }
            .onFailure { e -> _state.update { it.copy(friendsLoading = false, friendsError = e.userMessage()) } }
    }

    fun toggleAddFriend() = _state.update { it.copy(addOpen = !it.addOpen, addMsg = null, addMsgFailed = false) }

    fun onAddNameChange(value: String) = _state.update { it.copy(addName = value) }

    fun requestFriend() {
        val name = _state.value.addName.trim()
        if (name.isEmpty() || _state.value.adding) return
        _state.update { it.copy(adding = true, addMsg = null, addMsgFailed = false) }
        viewModelScope.launch {
            runCatching { container.hall.requestFriend(name) }
                .onSuccess {
                    _state.update { it.copy(adding = false, addName = "", addMsg = "已向「$name」发出好友请求") }
                    loadFriendsNow()
                }
                .onFailure { e -> _state.update { it.copy(adding = false, addMsg = e.userMessage(), addMsgFailed = true) } }
        }
    }

    fun acceptFriend(request: FriendPending) {
        viewModelScope.launch {
            runCatching { container.hall.acceptFriend(request.requestId) }
                .onSuccess {
                    _state.update { it.copy(hint = "已同意 ${request.nickname} 的好友请求") }
                    loadFriendsNow()
                }
                .onFailure { e -> _state.update { it.copy(hint = e.userMessage()) } }
        }
    }

    fun rejectFriend(request: FriendPending) {
        viewModelScope.launch {
            runCatching { container.hall.rejectFriend(request.requestId) }
                .onSuccess {
                    _state.update { it.copy(hint = "已拒绝 ${request.nickname} 的请求") }
                    loadFriendsNow()
                }
                .onFailure { e -> _state.update { it.copy(hint = e.userMessage()) } }
        }
    }

    /** requestId 是 friendship 行 id；删除后保留聊天记录但无法继续私聊，故切回频道。 */
    fun removeFriend(friend: FriendRow) {
        viewModelScope.launch {
            runCatching { container.hall.removeFriend(friend.requestId) }
                .onSuccess {
                    _state.update { it.copy(hint = "已删除好友 ${friend.nickname}") }
                    loadFriendsNow()
                    if ((_state.value.target as? HallTarget.Peer)?.userId == friend.userId) {
                        val first = _state.value.channels.firstOrNull() ?: HallChannel(null, PUBLIC_NAME)
                        openChannel(first.courseId, first.name)
                    }
                }
                .onFailure { e -> _state.update { it.copy(hint = e.userMessage()) } }
        }
    }

    /** AI 回答的引用来源跳课程文档页，由页面注入，ViewModel 不依赖导航层。 */
    var openDocs: (Long) -> Unit = {}

    fun openDocsForActiveCourse() {
        val courseId = _state.value.activeCourseId ?: return
        openDocs(courseId)
    }

    private fun isChannelActive(courseId: Long?): Boolean =
        (_state.value.target as? HallTarget.Channel)?.courseId == courseId

    companion object {
        const val TAB_CHAT = "chat"
        const val TAB_SPACE = "space"
        const val PUBLIC_NAME = "公共大厅"

        /** 与后端 HallWebSocketHandler.MAX_CONTENT_LEN 一致，客户端先裁一次。 */
        const val MAX_CONTENT = 500

        private const val FRIENDS_POLL_MS = 30_000L
        private const val AI_POLL_INTERVAL_MS = 2_500L
        private const val AI_FALLBACK_TIMEOUT_MS = 180_000L

        fun channelKey(courseId: Long?): String = courseId?.let { "course-$it" } ?: "pub"

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { HallViewModel(container) }
        }
    }
}
