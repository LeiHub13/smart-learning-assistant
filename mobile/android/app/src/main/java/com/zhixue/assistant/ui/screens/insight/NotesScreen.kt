package com.zhixue.assistant.ui.screens.insight

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.data.model.NoteDto
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.ConfirmDialog
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.LoadingView
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavController
import com.zhixue.assistant.ui.components.MarkdownText
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.SelectField
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.nav.Routes
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.util.friendlyTime

@Composable
fun NotesScreen(courseId: Long) {
    val container = LocalAppContainer.current
    val vm: NotesViewModel = viewModel(factory = NotesViewModel.factory(container))
    val state by vm.state.collectAsState()
    val message by vm.message.collectAsState()
    var pendingDelete by remember { mutableStateOf<NoteDto?>(null) }

    LaunchedEffect(courseId) { vm.applyCourseFilter(courseId) }

    Box(Modifier.fillMaxSize()) {
        when (val current = state) {
            is UiState.Loading -> LoadingView("正在加载学习笔记…")
            is UiState.Error -> ErrorState(message = current.message, onRetry = { vm.load() })
            is UiState.Ready -> NotesContent(current.data, vm, message, askDelete = { pendingDelete = it })
        }
    }

    pendingDelete?.let { note ->
        ConfirmDialog(
            title = "删除笔记",
            body = "删除笔记「${note.title}」？删除后无法恢复。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                pendingDelete = null
                vm.delete(note)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun NotesContent(
    data: NotesData,
    vm: NotesViewModel,
    message: UiMessage?,
    askDelete: (NoteDto) -> Unit,
) {
    val navController = LocalNavController.current
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }
    val options = remember(data.courses) { courseOptions(data.courses, includeAll = "全部课程") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                PageHeader(
                    title = "学习笔记",
                    subtitle = "按课程记录学习与练习心得，可关联知识点并共享到课程空间",
                ) {
                    SecondaryButton("新建笔记", onClick = { navController.navigate(Routes.noteEditor(-1L)) })
                }
                val banner = message
                if (banner != null) {
                    HintBanner(
                        text = banner.text,
                        tone = if (banner.error) BannerTone.Error else BannerTone.Success,
                        onDismiss = { vm.dismissMessage() },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        item {
            ZhixueCard(title = "筛选") {
                SelectField(
                    label = "课程",
                    options = options,
                    selected = data.courseId.toString(),
                    onSelect = { vm.selectCourse(it) },
                )
            }
        }

        if (data.notes.isEmpty()) {
            item {
                ZhixueCard {
                    EmptyView(
                        title = "暂无笔记",
                        hint = if (data.courseId > 0) "该课程下还没有笔记，点击右上角「新建笔记」" else "还没有笔记，点击右上角「新建笔记」记录第一条",
                    )
                }
            }
        } else {
            items(data.notes, key = { it.id }) { note ->
                NoteCard(
                    note = note,
                    courseName = remember(note.courseId, data.courses) {
                        note.courseId?.let { id -> data.courses.firstOrNull { it.id == id }?.name }
                    },
                    expanded = expanded[note.id] == true,
                    onToggle = { expanded[note.id] = expanded[note.id] != true },
                    onShare = { shared -> vm.setShared(note, shared) },
                    onEdit = { navController.navigate(Routes.noteEditor(note.id)) },
                    onDelete = { askDelete(note) },
                )
            }
        }
    }
}

@Composable
private fun NoteCard(
    note: NoteDto,
    courseName: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onShare: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalZhixuePalette.current
    val canShare = note.courseId != null
    ZhixueCard {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (expanded) "▾" else "▸", color = palette.muted)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        note.title.ifBlank { "未命名笔记" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!note.kpName.isNullOrBlank()) {
                        Spacer(Modifier.width(6.dp))
                        TagBadge(note.kpName.orEmpty(), TagTone.Info)
                    }
                    if (note.isShared) {
                        Spacer(Modifier.width(6.dp))
                        TagBadge("已共享到课程", TagTone.Success)
                    }
                }
                Text(
                    buildString {
                        if (courseName != null) {
                            append(courseName)
                            append(" · ")
                        }
                        append("更新于 ")
                        append(friendlyTime(note.updatedAt).ifBlank { "—" })
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            MarkdownText(note.content)
        } else {
            val preview = firstLinePreview(note.content)
            if (preview.isNotBlank()) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "共享到课程空间",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (canShare) palette.text else palette.muted,
                )
                if (!canShare) {
                    Text(
                        "未绑定课程的笔记不能共享",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                    )
                }
            }
            Switch(
                checked = note.isShared,
                onCheckedChange = { onShare(it) },
                enabled = canShare,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SecondaryButton("编辑", onClick = onEdit)
            Spacer(Modifier.width(8.dp))
            SecondaryButton("删除", onClick = onDelete, danger = true)
        }
    }
}
