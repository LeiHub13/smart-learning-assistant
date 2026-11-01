package com.zhixue.assistant.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.ui.components.ConfirmDialog
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.InputDialog
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavigator
import com.zhixue.assistant.ui.components.LocalToast
import com.zhixue.assistant.ui.components.MarkdownText
import com.zhixue.assistant.ui.components.SelectField
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.nav.Routes
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.util.parseFollowups
import com.zhixue.assistant.util.parseIdCsv
import com.zhixue.assistant.util.friendlyTime
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: Long,
    courseId: Long,
    kbId: Long,
    scope: String,
    ask: String,
    rel: String,
) {
    val container = LocalAppContainer.current
    val toast = LocalToast.current
    val navigator = LocalNavigator.current
    val palette = LocalZhixuePalette.current
    val vm: ChatViewModel = viewModel(factory = ChatViewModel.factory(container))
    val state by vm.state.collectAsState()
    val chunks by vm.chunks.collectAsState()

    var input by remember { mutableStateOf("") }
    var showSessions by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Long?>(null) }
    var deleting by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { vm.navigateToWebRoute = navigator }

    LaunchedEffect(sessionId, kbId, ask) {
        vm.useSeed(
            courseId = courseId.takeIf { it > 0 },
            kbId = kbId.takeIf { it > 0 },
            scope = scope,
            ask = ask,
            rel = rel,
        )
        val target = sessionId.takeIf { it > 0 }
        if (target != null) vm.openSession(target)
    }

    LaunchedEffect(state.hint) {
        state.hint?.let { toast(it) }
        vm.consumeHint()
    }

    // 生成过程中内容持续增长，只有在用户本来就在底部时才自动跟随，避免打断阅读
    val lastLength = state.messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(state.messages.size, lastLength) {
        val index = state.messages.size - 1
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .imePadding(),
    ) {
        ModeRow(state = state, onMode = { vm.setMode(it) }, onOpenSessions = { showSessions = true }, onNew = { vm.newSession() })

        if (state.mode == ChatUiState.MODE_KB) {
            KbSelectors(
                state = state,
                onCourse = vm::selectCourse,
                onKb = vm::selectKnowledgeBase,
                onScope = vm::setKbScope,
            )
        }

        ModeSwitches(state = state, onSocratic = vm::toggleSocratic, onFeynman = vm::toggleFeynman, onFlashcards = vm::generateFlashcards)

        if (state.error != null && state.messages.isEmpty()) {
            ErrorState(message = state.error!!, onRetry = { vm.clearError(); vm.refreshSessions(selectFirst = true) })
        } else if (state.messages.isEmpty() && !state.loadingMessages) {
            EmptyView(
                title = if (state.currentSessionId == null) "还没有会话" else "开始提问",
                hint = if (state.mode == ChatUiState.MODE_KB) "选择课程与知识库后，AI 会基于资料回答并给出引用" else "自由对话适合通用问题；课程相关问题请用知识库答疑",
                actionText = if (state.currentSessionId == null) "新建会话" else null,
                onAction = if (state.currentSessionId == null) ({ vm.newSession() }) else null,
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
            ) {
                items(state.messages, key = { "${it.id}-${it.role}" }) { message ->
                    MessageRow(
                        message = message,
                        streaming = state.streamingSessionId == state.currentSessionId && message.streaming,
                        chunks = chunks[message.id],
                        onCitation = { index -> vm.loadChunks(message.id, parseIdCsv(message.sourceChunks)) },
                        onFollowup = { question ->
                            if (state.streaming) toast("正在生成中，请稍候") else vm.send(question)
                        },
                        onConfirm = { actionId -> vm.confirmAction(message.id, actionId) },
                        onCancel = { actionId -> vm.cancelAction(message.id, actionId) },
                        onOpenDocs = { navigator("/docs?courseId=${state.currentSession?.courseId ?: ""}") },
                    )
                }
                if (state.loadingMessages) {
                    item { InlineLoading("正在加载历史消息…") }
                }
            }
        }

        InputBar(
            value = input,
            onValueChange = { input = it },
            enabled = !state.streaming,
            streaming = state.streaming,
            onSend = {
                val text = input.trim()
                if (text.isEmpty()) return@InputBar
                input = ""
                vm.send(text)
            },
        )
    }

    if (showSessions) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showSessions = false }, sheetState = sheetState) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("会话列表", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("刷新", color = palette.primary, fontSize = 13.sp, modifier = Modifier.clickable { vm.refreshSessions() })
                }
                Spacer(Modifier.height(8.dp))
                if (state.listLoading) {
                    InlineLoading("加载中…")
                } else if (state.visibleSessions.isEmpty()) {
                    Text("还没有会话，点右上角「新对话」开始", color = palette.muted, fontSize = 13.sp)
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(state.visibleSessions, key = { it.id }) { session ->
                            SessionRow(
                                title = session.title.orEmpty().ifBlank { "未命名会话" },
                                time = friendlyTime(session.createdAt),
                                selected = session.id == state.currentSessionId,
                                onOpen = {
                                    showSessions = false
                                    vm.openSession(session.id)
                                },
                                onRename = { renaming = session.id },
                                onDelete = { deleting = session.id },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    renaming?.let { id ->
        InputDialog(
            title = "重命名会话",
            label = "会话标题",
            initial = state.sessions.firstOrNull { it.id == id }?.title.orEmpty(),
            maxLength = 40,
            onDismiss = { renaming = null },
            onSubmit = { value ->
                vm.rename(id, value)
                renaming = null
            },
        )
    }

    deleting?.let { id ->
        ConfirmDialog(
            title = "删除会话",
            body = "会话里的消息、AI 摘要与滚动记忆会一并清除，且不可恢复。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                vm.deleteSession(id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun ModeRow(state: ChatUiState, onMode: (String) -> Unit, onOpenSessions: () -> Unit, onNew: () -> Unit) {
    val palette = LocalZhixuePalette.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = state.mode == ChatUiState.MODE_KB,
            onClick = { onMode(ChatUiState.MODE_KB) },
            label = { Text("知识库答疑") },
        )
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = state.mode == ChatUiState.MODE_FREE,
            onClick = { onMode(ChatUiState.MODE_FREE) },
            label = { Text("自由对话") },
        )
        Spacer(Modifier.weight(1f))
        Text(
            state.currentSession?.title?.ifBlank { "新对话" } ?: "未选择会话",
            fontSize = 12.sp,
            color = palette.muted,
            maxLines = 1,
            modifier = Modifier.widthIn(max = 110.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("会话", color = palette.primary, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onOpenSessions))
        Spacer(Modifier.width(10.dp))
        Text("新对话", color = palette.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable(onClick = onNew))
    }
}

@Composable
private fun KbSelectors(state: ChatUiState, onCourse: (Long) -> Unit, onKb: (Long) -> Unit, onScope: (String) -> Unit) {
    val container = LocalAppContainer.current
    val courses by container.courses.courses.collectAsState()
    Column(Modifier.padding(horizontal = 12.dp)) {
        SelectField(
            label = "课程",
            options = courses.map { it.id.toString() to it.name },
            selected = state.courseId?.toString(),
            onSelect = { onCourse(it.toLong()) },
        )
        Spacer(Modifier.height(6.dp))
        SelectField(
            label = "知识库",
            options = state.knowledgeBases.map { it.id.toString() to it.name },
            selected = state.kbId?.toString(),
            onSelect = { onKb(it.toLong()) },
            enabled = state.knowledgeBases.isNotEmpty(),
            emptyLabel = if (state.courseId == null) "请先选课程" else "该课程还没有知识库",
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = state.kbScope == "single",
                onClick = { onScope("single") },
                label = { Text("仅本知识库") },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = state.kbScope == "course",
                onClick = { onScope("course") },
                label = { Text("全课程知识库") },
            )
        }
    }
}

@Composable
private fun ModeSwitches(
    state: ChatUiState,
    onSocratic: (Boolean) -> Unit,
    onFeynman: (Boolean) -> Unit,
    onFlashcards: () -> Unit,
) {
    val palette = LocalZhixuePalette.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SwitchLabel("苏格拉底引导", state.socratic) { onSocratic(it) }
        SwitchLabel("费曼讲题", state.feynman) { onFeynman(it) }
        Spacer(Modifier.weight(1f))
        Text(
            "生成闪卡",
            fontSize = 13.sp,
            color = if (state.currentSessionId == null) palette.muted else palette.primary,
            modifier = Modifier
                .clickable(enabled = state.currentSessionId != null, onClick = onFlashcards)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SwitchLabel(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun SessionRow(
    title: String,
    time: String,
    selected: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalZhixuePalette.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) palette.accentSubtle else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (time.isNotBlank()) Text(time, fontSize = 11.sp, color = palette.muted)
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "会话操作", modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun MessageRow(
    message: com.zhixue.assistant.data.model.ChatUiMessage,
    streaming: Boolean,
    chunks: List<com.zhixue.assistant.data.model.KbChunkRef>?,
    onCitation: (Int) -> Unit,
    onFollowup: (String) -> Unit,
    onConfirm: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onOpenDocs: () -> Unit,
) {
    val palette = LocalZhixuePalette.current
    val isUser = message.fromUser
    var showSources by remember(message.id) { mutableStateOf(false) }
    var flashIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(flashIndex) {
        if (flashIndex != null) {
            delay(1800)
            flashIndex = null
        }
    }

    val citationIds = parseIdCsv(message.sources)
    val chunkIds = parseIdCsv(message.sourceChunks)
    val followups = parseFollowups(message.followups)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Box(
            Modifier
                .widthIn(max = 560.dp)
                .background(
                    if (isUser) palette.primary else palette.card,
                    RoundedCornerShape(12.dp),
                )
                .border(
                    1.dp,
                    if (isUser) palette.primary else palette.border,
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            if (isUser) {
                Text(message.content, color = palette.onPrimary, style = MaterialTheme.typography.bodyMedium)
            } else {
                Column {
                    MarkdownText(
                        source = message.content + if (streaming) " ▍" else "",
                        streaming = streaming,
                        onCitation = { index ->
                            showSources = true
                            flashIndex = index
                            onCitation(index)
                        },
                    )
                    if (streaming && message.content.isEmpty()) {
                        InlineLoading("AI 正在思考…")
                    }
                    if (message.error != null && !message.content.startsWith("（请求失败")) {
                        Spacer(Modifier.height(6.dp))
                        HintBanner(message.error!!, tone = BannerTone.Warn)
                    }
                }
            }
        }

        if (!isUser && (citationIds.isNotEmpty() || message.actions.isNotEmpty())) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                citationIds.forEachIndexed { index, label ->
                    Text(
                        "[$label]",
                        color = palette.primary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable {
                                showSources = !showSources
                                flashIndex = index + 1
                                onCitation(index + 1)
                            }
                            .padding(horizontal = 3.dp, vertical = 1.dp),
                    )
                }
                if (citationIds.isNotEmpty()) {
                    Text(
                        if (showSources) "收起原文" else "查看引用原文",
                        fontSize = 11.sp,
                        color = palette.muted,
                        modifier = Modifier
                            .clickable {
                                showSources = !showSources
                                onCitation(1)
                            }
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
        }

        if (!isUser && showSources) {
            SourcePanel(
                citationIds = citationIds,
                chunkIds = chunkIds,
                chunks = chunks,
                flashIndex = flashIndex,
                onOpenDocs = onOpenDocs,
            )
        }

        if (!isUser && message.actions.isNotEmpty()) {
            message.actions.forEach { action ->
                ActionCard(action = action, onConfirm = { onConfirm(action.id) }, onCancel = { onCancel(action.id) })
            }
        }

        if (!isUser && followups.isNotEmpty() && !streaming) {
            Column(Modifier.padding(top = 6.dp)) {
                Text("继续问", fontSize = 11.sp, color = palette.muted)
                Spacer(Modifier.height(4.dp))
                followups.forEach { question ->
                    Text(
                        question,
                        fontSize = 13.sp,
                        color = palette.primary,
                        modifier = Modifier
                            .padding(vertical = 3.dp)
                            .background(palette.accentSubtle, RoundedCornerShape(8.dp))
                            .clickable { onFollowup(question) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SourcePanel(
    citationIds: List<String>,
    chunkIds: List<String>,
    chunks: List<com.zhixue.assistant.data.model.KbChunkRef>?,
    flashIndex: Int?,
    onOpenDocs: () -> Unit,
) {
    val palette = LocalZhixuePalette.current
    val byId = chunks?.associateBy { it.chunkId.toString() }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(palette.soft, RoundedCornerShape(10.dp))
            .border(1.dp, palette.border, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("引用原文", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("去文档管理", fontSize = 11.sp, color = palette.primary, modifier = Modifier.clickable(onClick = onOpenDocs))
        }
        Spacer(Modifier.height(6.dp))
        if (chunks == null) {
            InlineLoading("正在加载原文片段…")
        } else if (chunks.isEmpty()) {
            Text("没有可展示的片段（可能已被删除或未索引）", fontSize = 12.sp, color = palette.muted)
        } else {
            citationIds.forEachIndexed { index, label ->
                val chunkId = chunkIds.getOrNull(index) ?: label
                val chunk = byId?.get(chunkId)
                val highlighted = flashIndex == index + 1
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(
                            if (highlighted) palette.accentSubtle else androidx.compose.ui.graphics.Color.Transparent,
                            RoundedCornerShape(6.dp),
                        )
                        .padding(6.dp),
                ) {
                    Text(
                        "[$label] ${chunk?.docName ?: "片段 $chunkId"}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.text,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        chunk?.content ?: "加载中…",
                        fontSize = 12.sp,
                        color = palette.muted,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionCard(
    action: com.zhixue.assistant.data.model.AgentActionUi,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = LocalZhixuePalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(palette.card, RoundedCornerShape(10.dp))
            .border(1.dp, if (action.result != null) palette.success else palette.primary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TagBadge("Agent 提议", TagTone.Info)
            Spacer(Modifier.width(8.dp))
            Text(kindLabel(action.kind), fontSize = 11.sp, color = palette.muted)
        }
        Spacer(Modifier.height(6.dp))
        Text(action.summary, style = MaterialTheme.typography.bodyMedium)
        if (action.result != null) {
            Spacer(Modifier.height(6.dp))
            HintBanner(action.result!!, tone = BannerTone.Success)
        } else if (action.error != null) {
            Spacer(Modifier.height(6.dp))
            HintBanner(action.error!!, tone = BannerTone.Error)
        } else {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                com.zhixue.assistant.ui.components.PrimaryButton(
                    text = if (action.busy) "执行中…" else "确认执行",
                    onClick = onConfirm,
                    enabled = !action.busy,
                    loading = action.busy,
                )
                com.zhixue.assistant.ui.components.SecondaryButton(text = "取消", onClick = onCancel, enabled = !action.busy)
            }
            Spacer(Modifier.height(4.dp))
            Text("确认后才写入数据；过期或未确认的动作不会生效", fontSize = 11.sp, color = palette.muted)
        }
    }
}

private fun kindLabel(kind: String): String = when (kind) {
    "add_material" -> "收藏资料进知识库"
    "schedule_review" -> "安排复习提醒"
    "finish_plan_task" -> "完成计划任务"
    "add_note" -> "新建学习笔记"
    "favorite_question" -> "收藏题目"
    "generate_questions" -> "生成练习题"
    "open_page" -> "打开页面"
    "add_plan_task" -> "追加计划任务"
    "add_question" -> "录入题目"
    "add_flashcard" -> "新增闪卡"
    "add_mistake" -> "收录错题本"
    else -> kind
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    streaming: Boolean,
    onSend: () -> Unit,
) {
    val palette = LocalZhixuePalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.card)
            .border(width = 1.dp, color = palette.border)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (streaming) {
            Text("正在生成回答，可切换到其它会话，生成不会中断", fontSize = 11.sp, color = palette.muted)
            Spacer(Modifier.height(4.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 140.dp),
                placeholder = { Text("输入问题…", fontSize = 14.sp) },
                shape = RoundedCornerShape(10.dp),
                enabled = true,
            )
            Spacer(Modifier.width(8.dp))
            com.zhixue.assistant.ui.components.PrimaryButton(
                text = "发送",
                onClick = onSend,
                enabled = enabled && value.isNotBlank(),
            )
        }
    }
}
