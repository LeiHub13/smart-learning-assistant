package com.zhixue.assistant.ui.screens.hall

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.data.model.FriendPending
import com.zhixue.assistant.data.model.FriendRow
import com.zhixue.assistant.data.model.SpaceNote
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.ConfirmDialog
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.LabeledField
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavigator
import com.zhixue.assistant.ui.components.LocalToast
import com.zhixue.assistant.ui.components.MarkdownText
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.components.cardShape
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.util.friendlyTime
import kotlinx.coroutines.launch

/**
 * 对话厅：顶部两页（频道 / 好友）+ 主区消息列表 + 输入栏。
 * peerId > 0 表示从通知的 /hall?peer= 跳转进来，直接打开与该好友的私聊。
 */
@Composable
fun HallScreen(peerId: Long) {
    val container = LocalAppContainer.current
    val vm: HallViewModel = viewModel(factory = HallViewModel.factory(container))
    val state by vm.state.collectAsState()
    val toast = LocalToast.current
    val navigate = LocalNavigator.current
    val palette = LocalZhixuePalette.current
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    var removeTarget by remember { mutableStateOf<FriendRow?>(null) }

    // 连接只在本页存活期间持有，避免和别的页面共用一条 socket 导致串线
    DisposableEffect(container) {
        vm.openDocs = { courseId -> navigate("/docs?courseId=$courseId") }
        container.hallSocket.start()
        onDispose { container.hallSocket.stop() }
    }
    LaunchedEffect(peerId) { vm.usePeer(peerId) }
    // 好友在线状态与未读徽标 30 秒轮询一次，只在页面处于前台时跑
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refreshMe()
        vm.startFriendsPolling()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.stopFriendsPolling() }
    LaunchedEffect(state.hint) {
        state.hint?.let {
            toast(it)
            vm.consumeHint()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .background(palette.canvas),
    ) {
        HallTopTabs(
            selected = pagerState.currentPage,
            friendUnread = state.totalUnread,
            onSelect = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
        )
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { page ->
            if (page == 0) {
                HallChatArea(
                    vm = vm,
                    state = state,
                    onRemoveFriendRequest = { row -> removeTarget = row },
                )
            } else {
                HallFriendsArea(
                    vm = vm,
                    state = state,
                    onOpenPeer = { row ->
                        vm.openDm(row.userId)
                        scope.launch { pagerState.animateScrollToPage(0) }
                    },
                    onRemoveFriendRequest = { row -> removeTarget = row },
                )
            }
        }
    }

    removeTarget?.let { row ->
        ConfirmDialog(
            title = "删除好友",
            body = "确认删除好友「${row.nickname}」？聊天记录将保留但无法继续私聊。",
            confirmText = "确认删除",
            danger = true,
            onConfirm = {
                removeTarget = null
                vm.removeFriend(row)
            },
            onDismiss = { removeTarget = null },
        )
    }
}

@Composable
private fun HallTopTabs(selected: Int, friendUnread: Int, onSelect: (Int) -> Unit) {
    val palette = LocalZhixuePalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(palette.card)
            .border(width = 1.dp, color = palette.border),
    ) {
        listOf("频道", if (friendUnread > 0) "好友 · $friendUnread" else "好友").forEachIndexed { index, label ->
            Box(
                Modifier
                    .weight(1f)
                    .clickable { onSelect(index) }
                    .background(if (selected == index) palette.accentSubtle else palette.card)
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected == index) palette.primaryDeep else palette.muted,
                )
            }
        }
    }
}

@Composable
private fun HallChatArea(vm: HallViewModel, state: HallUiState, onRemoveFriendRequest: (FriendRow) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        HallChannelBar(vm, state)
        HallChatHead(vm, state, onRemoveFriendRequest)
        if (state.isCourseChannel) {
            HallChatTabs(state.chatTab, vm::setChatTab)
        }
        state.notice?.let { message ->
            HintBanner(
                text = message,
                tone = BannerTone.Error,
                onDismiss = vm::dismissNotice,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        if (state.showChat) {
            HallMessageArea(vm, state, Modifier.weight(1f))
            HallInputBar(vm, state)
        } else {
            HallSpacePanel(vm, state, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HallChannelBar(vm: HallViewModel, state: HallUiState) {
    val palette = LocalZhixuePalette.current
    val activeCourseId = (state.target as? HallTarget.Channel)?.courseId
    Column(Modifier.fillMaxWidth().background(palette.card)) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.channelsLoading && state.channels.isEmpty()) {
                InlineLoading("频道加载中…")
            }
            state.channels.forEach { channel ->
                val selected = !state.isDm && activeCourseId == channel.courseId
                Surface(
                    modifier = Modifier.clickable { vm.openChannelById(channel.courseId) },
                    shape = RoundedCornerShape(16.dp),
                    color = if (selected) palette.accentSubtle else palette.soft,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (selected) palette.primary else palette.border,
                    ),
                ) {
                    Text(
                        "# ${channel.name}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) palette.primaryDeep else palette.muted,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        state.channelsError?.let { error ->
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("频道加载失败：$error", color = palette.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::reloadChannels) { Text("重试") }
            }
        }
    }
}

@Composable
private fun HallChatHead(vm: HallViewModel, state: HallUiState, onRemoveFriendRequest: (FriendRow) -> Unit) {
    val palette = LocalZhixuePalette.current
    val peerId = (state.target as? HallTarget.Peer)?.userId
    val peerRow = peerId?.let { id -> state.friends.firstOrNull { it.userId == id } }
    Row(
        Modifier
            .fillMaxWidth()
            .background(palette.card)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                state.target?.title ?: "# 公共大厅",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 点状态行按当前频道重新拉在线名单
            Text(
                state.statusText,
                modifier = Modifier
                    .clickable(enabled = !state.isDm) { vm.refreshOnline() }
                    .padding(top = 1.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.isDm || state.connected) palette.muted else palette.warn,
            )
        }
        if (peerRow != null) {
            TextButton(onClick = { onRemoveFriendRequest(peerRow) }) {
                Text("删除好友", color = palette.danger, fontSize = 12.sp)
            }
        }
        if (state.showReconnect) {
            SecondaryButton("重新连接", onClick = vm::reconnect)
        }
    }
}

@Composable
private fun HallChatTabs(tab: String, onSelect: (String) -> Unit) {
    val palette = LocalZhixuePalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(palette.card)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(HallViewModel.TAB_CHAT to "聊天", HallViewModel.TAB_SPACE to "学习空间").forEach { (value, label) ->
            val selected = tab == value
            Surface(
                modifier = Modifier.clickable { onSelect(value) },
                shape = RoundedCornerShape(16.dp),
                color = if (selected) palette.accentSubtle else MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (selected) palette.primary else palette.border,
                ),
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) palette.primaryDeep else palette.muted,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun HallMessageArea(vm: HallViewModel, state: HallUiState, modifier: Modifier = Modifier) {
    val palette = LocalZhixuePalette.current
    val listState = rememberLazyListState()
    val bubble = state.ai
    val showAiBubble = state.aiVisible && bubble != null

    // 新消息、切会话、助教流式增量都把列表拉回底部（与 Web 的 scrollDown 一致）
    LaunchedEffect(state.target?.key, state.entries.size, bubble?.text?.length, showAiBubble) {
        if (state.entries.isNotEmpty() || showAiBubble) listState.scrollToItem(0)
    }

    Box(modifier.fillMaxSize().background(palette.canvas)) {
        val error = state.historyError
        when {
            state.historyLoading && state.entries.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { InlineLoading("消息加载中…") }
            }

            error != null && state.entries.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    ErrorState(message = error, onRetry = vm::retryHistory)
                }
            }

            state.entries.isEmpty() && !showAiBubble -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyView(title = state.emptyTitle(), hint = state.emptyHint)
                }
            }

            else -> {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    if (showAiBubble && bubble != null) {
                        item(key = "ai-pending") {
                            HallAiPendingRow(
                                bubble = bubble,
                                onOpenDocs = vm::openDocsForActiveCourse,
                            )
                        }
                    }
                    items(state.entries.reversed(), key = { it.key }) { entry ->
                        when (entry) {
                            is HallEntry.Channel -> HallChannelEntryRow(
                                entry = entry,
                                meId = state.meId,
                                onOpenDocs = { vm.openDocsForActiveCourse() },
                            )

                            is HallEntry.Direct -> HallDirectEntryRow(entry)
                            is HallEntry.Notice -> HallNoticeLine(entry.text)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HallAiPendingRow(bubble: AiBubble, onOpenDocs: () -> Unit) {
    val openLink = rememberLinkOpener()
    when {
        bubble.error != null -> HallMessageRow(
            nickname = "AI 助教",
            timeRaw = null,
            mine = false,
            aiAvatar = true,
            errorText = bubble.error,
        )

        bubble.text.isEmpty() -> HallMessageRow(
            nickname = "AI 助教",
            timeRaw = null,
            mine = false,
            aiAvatar = true,
            timeText = "正在回答…",
            plainText = "思考中…",
        )

        else -> HallMessageRow(
            nickname = "AI 助教",
            timeRaw = null,
            mine = false,
            aiAvatar = true,
            timeText = if (bubble.settled) null else "正在回答…",
            markdownContent = bubble.text,
            streaming = !bubble.settled,
            onCitation = { onOpenDocs() },
            onLinkClick = openLink,
        )
    }
}

@Composable
private fun HallInputBar(vm: HallViewModel, state: HallUiState) {
    val palette = LocalZhixuePalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.card)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (state.input.length > 400) {
            Text(
                "${state.input.length}/${HallViewModel.MAX_CONTENT} 字",
                style = MaterialTheme.typography.labelSmall,
                color = if (state.input.length >= HallViewModel.MAX_CONTENT) palette.danger else palette.muted,
                modifier = Modifier.padding(bottom = 2.dp, start = 4.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = { next -> vm.onInputChange(next.take(HallViewModel.MAX_CONTENT)) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp, max = 110.dp),
                placeholder = {
                    Text(state.inputHint, color = palette.muted.copy(alpha = 0.7f), maxLines = 2)
                },
                enabled = state.connected,
                maxLines = 4,
                shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { if (state.canSend) vm.send() },
                ),
            )
            if (state.isCourseChannel) {
                SecondaryButton("问助教", onClick = vm::askAssistant, enabled = state.canSend)
            }
            PrimaryButton(
                text = if (state.connected) "发送" else "未连接",
                onClick = vm::send,
                enabled = state.canSend,
            )
        }
    }
}

@Composable
private fun HallSpacePanel(vm: HallViewModel, state: HallUiState, modifier: Modifier = Modifier) {
    val palette = LocalZhixuePalette.current
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZhixueCard(title = "AI 课程助教") {
            Text(
                "基于本课程知识库回答问题；回复会出现在频道聊天里，全部成员可见、可回看。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton("去问助教", onClick = { vm.setChatTab(HallViewModel.TAB_CHAT) })
        }
        ZhixueCard(
            title = "共享笔记",
            actions = { TextButton(onClick = vm::refreshSpace) { Text("刷新") } },
        ) {
            Text(
                "同学在「学习笔记」页勾选「共享到课程」的笔记会出现在这里（只读）。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
            Spacer(Modifier.height(8.dp))
            val error = state.spaceError
            when {
                state.spaceLoading && state.spaceNotes.isEmpty() -> InlineLoading("加载中…")
                error != null && state.spaceNotes.isEmpty() -> ErrorState(message = error, onRetry = vm::refreshSpace)
                state.spaceNotes.isEmpty() -> Text("还没有共享笔记", style = MaterialTheme.typography.bodySmall, color = palette.muted)
                else -> state.spaceNotes.forEach { note -> HallSharedNoteCard(note) }
            }
            if (error != null && state.spaceNotes.isNotEmpty()) {
                Text(error, color = palette.danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun HallSharedNoteCard(note: SpaceNote) {
    val palette = LocalZhixuePalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(palette.card, cardShape)
            .border(1.dp, palette.border, cardShape)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                note.title.ifBlank { "未命名笔记" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            note.kpName?.takeIf { it.isNotBlank() }?.let {
                TagBadge(it, TagTone.Info, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        MarkdownText(note.content)
        Spacer(Modifier.height(4.dp))
        Text(
            "${note.author ?: "未知作者"} · 更新于 ${friendlyTime(note.updatedAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
        )
    }
}

@Composable
private fun HallFriendsArea(
    vm: HallViewModel,
    state: HallUiState,
    onOpenPeer: (FriendRow) -> Unit,
    onRemoveFriendRequest: (FriendRow) -> Unit,
) {
    val palette = LocalZhixuePalette.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "好友",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = if (state.addOpen) "收起" else "+ 加好友",
                onClick = vm::toggleAddFriend,
            )
        }

        if (state.addOpen) {
            LabeledField(
                label = "对方用户名",
                value = state.addName,
                onValueChange = vm::onAddNameChange,
                placeholder = "输入对方的登录用户名",
                singleLine = true,
            )
            Spacer(Modifier.height(6.dp))
            PrimaryButton(
                text = "请求",
                onClick = vm::requestFriend,
                enabled = state.addName.isNotBlank(),
                loading = state.adding,
            )
            state.addMsg?.let { message ->
                HintBanner(
                    text = message,
                    tone = if (state.addMsgFailed) BannerTone.Error else BannerTone.Success,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        if (state.incoming.isNotEmpty()) {
            HallListSection("收到的请求")
            state.incoming.forEach { request ->
                HallRequestRow(
                    request = request,
                    onAccept = { vm.acceptFriend(request) },
                    onReject = { vm.rejectFriend(request) },
                )
            }
        }

        if (state.outgoing.isNotEmpty()) {
            HallListSection("已发出的请求")
            state.outgoing.forEach { request ->
                Text(
                    "待同意：${request.nickname.ifBlank { request.username }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.muted,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }

        HallListSection("我的好友")
        val friendsError = state.friendsError
        when {
            friendsError != null && state.friends.isEmpty() -> ErrorState(message = friendsError, onRetry = vm::loadFriends)
            state.friendsLoading && state.friends.isEmpty() && state.incoming.isEmpty() ->
                InlineLoading("好友加载中…")

            state.friends.isEmpty() && state.incoming.isEmpty() -> Text(
                "还没有好友，点上方「+ 加好友」",
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )

            else -> state.friends.forEach { friend ->
                HallFriendRow(
                    friend = friend,
                    unread = state.unreadOf(friend.userId),
                    active = (state.target as? HallTarget.Peer)?.userId == friend.userId,
                    onOpen = { onOpenPeer(friend) },
                    onRemove = { onRemoveFriendRequest(friend) },
                )
            }
        }
        friendsError?.takeIf { state.friends.isNotEmpty() }?.let {
            Text(it, color = palette.danger, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HallListSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = LocalZhixuePalette.current.muted,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun HallRequestRow(request: FriendPending, onAccept: () -> Unit, onReject: () -> Unit) {
    val palette = LocalZhixuePalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                request.nickname.ifBlank { request.username },
                style = MaterialTheme.typography.bodyMedium,
                color = palette.text,
            )
            Text(request.username, style = MaterialTheme.typography.labelSmall, color = palette.muted)
        }
        TextButton(onClick = onAccept) { Text("同意", color = palette.primary) }
        TextButton(onClick = onReject) { Text("拒绝", color = palette.muted) }
    }
}

@Composable
private fun HallFriendRow(
    friend: FriendRow,
    unread: Int,
    active: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val palette = LocalZhixuePalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (active) palette.soft else palette.card,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HallOnlineDot(online = friend.online == true)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                friend.nickname.ifBlank { friend.username },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (friend.online == true) "在线" else "离线",
                style = MaterialTheme.typography.labelSmall,
                color = palette.muted,
            )
        }
        if (unread > 0) {
            HallUnreadBadge(unread)
            Spacer(Modifier.width(6.dp))
        }
        TextButton(onClick = onRemove) {
            Text("删除", color = palette.danger, fontSize = 12.sp)
        }
    }
}
