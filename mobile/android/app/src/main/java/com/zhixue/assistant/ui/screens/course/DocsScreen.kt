package com.zhixue.assistant.ui.screens.course

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.data.model.DocListItemDto
import com.zhixue.assistant.ui.components.ConfirmDialog
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.InputDialog
import com.zhixue.assistant.ui.components.LabeledField
import com.zhixue.assistant.ui.components.LoadingView
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavController
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.SelectField
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.nav.Routes
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.util.fmtDateTime

/**
 * 文档管理页：跨课程汇总所有知识库文档，支持课程筛选与文件名关键词过滤。
 * courseId 为 -1 时不限课程（从抽屉进入）；从其它页面带 id 进来时先限定到该课程。
 */
@Composable
fun DocsScreen(courseId: Long) {
    val container = LocalAppContainer.current
    val navController = LocalNavController.current
    val vm: DocsViewModel = viewModel(factory = DocsViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(courseId) { vm.bindCourseScope(courseId) }

    var renameTarget by remember { mutableStateOf<DocListItemDto?>(null) }
    var deleteTarget by remember { mutableStateOf<DocListItemDto?>(null) }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .padding(horizontal = 14.dp)
                .padding(top = 12.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PageHeader(
                title = "文档管理",
                subtitle = "跨课程汇总所有知识库文档：AI 速览、预览内搜索、重命名，以及跳转查看原文件",
                actions = {
                    SecondaryButton("刷新", onClick = { vm.reload() }, enabled = !state.working && !state.loading)
                },
            )
            MessageChannel(vm)
        }

        Box(Modifier.weight(1f)) {
            when {
                state.loading -> LoadingView("加载文档列表中…", Modifier.fillMaxSize())

                state.error != null -> ErrorState(
                    state.error.orEmpty(),
                    onRetry = { vm.reload() },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                else -> DocsList(
                    vm = vm,
                    state = state,
                    onPreview = { doc ->
                        val kbId = doc.kbId
                        if (kbId == null) vm.warnMissingKb() else navController.navigate(Routes.docPreview(kbId, doc.docId))
                    },
                    onRename = { renameTarget = it },
                    onAskDelete = { deleteTarget = it },
                )
            }
        }
    }

    renameTarget?.let { doc ->
        val kbId = doc.kbId
        InputDialog(
            title = "重命名文档",
            label = "文档名",
            initial = doc.fileName,
            placeholder = "如：并发编程笔记.md",
            onDismiss = { renameTarget = null },
            onSubmit = { name ->
                renameTarget = null
                if (kbId == null) vm.warnMissingKb() else vm.renameDoc(kbId, doc.docId, name)
            },
        )
    }

    deleteTarget?.let { doc ->
        ConfirmDialog(
            title = "删除文档",
            body = "确认删除「${doc.fileName}」？文本、向量索引与原文件将一并清理。",
            confirmText = "确认删除",
            danger = true,
            onConfirm = {
                deleteTarget = null
                val kbId = doc.kbId
                if (kbId != null) vm.deleteDoc(kbId, doc.docId, doc.fileName)
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun DocsList(vm: DocsViewModel, state: DocsViewModel.State, onPreview: (DocListItemDto) -> Unit, onRename: (DocListItemDto) -> Unit, onAskDelete: (DocListItemDto) -> Unit) {
    val visible = vm.visibleDocs()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ZhixueCard(title = "筛选", subtitle = "${visible.size} 个文档") {
                SelectField(
                    label = "课程",
                    options = vm.courseOptions(),
                    selected = vm.selectedCourseKey(),
                    onSelect = { key -> vm.onCourseFilterChange(key.takeIf { it != ALL_COURSES_LABEL }) },
                )
                Spacer(Modifier.height(8.dp))
                LabeledField(
                    label = "文件名关键词",
                    value = state.keyword,
                    onValueChange = vm::onKeywordChange,
                    placeholder = "按文件名搜索…",
                )
                if (state.courseScopeId > 0L && state.courseFilter == null) {
                    Spacer(Modifier.height(6.dp))
                    MetaLine("当前只看该课程的文档；课程下拉选「全部课程」可看全部")
                }
            }
        }

        when {
            state.docs.isEmpty() -> item {
                EmptyView(
                    title = "暂无文档",
                    hint = "先到「我的课程」上传资料",
                )
            }

            visible.isEmpty() -> item {
                EmptyView(
                    title = "没有匹配「${state.keyword.trim()}」的文档",
                    hint = "换个关键词，或把课程筛选切回「全部课程」",
                )
            }

            else -> items(visible, key = { "${it.kbId ?: 0L}-${it.docId}" }) { doc ->
                DocListRow(
                    doc = doc,
                    enabled = !state.working,
                    onPreview = { onPreview(doc) },
                    onRename = { onRename(doc) },
                    onDelete = { onAskDelete(doc) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocListRow(
    doc: DocListItemDto,
    enabled: Boolean,
    onPreview: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalZhixuePalette.current
    ZhixueCard {
        DocTitleLine(title = doc.fileName, hasOverview = doc.hasOverview == true)
        MetaLine(
            listOfNotNull(
                doc.courseName?.takeIf { it.isNotBlank() } ?: "未归属课程",
                doc.kbName?.takeIf { it.isNotBlank() } ?: "未知知识库",
                doc.fileType?.takeIf { it.isNotBlank() } ?: "未知类型",
                "${doc.chunkCount ?: 0} 个片段",
                fmtDateTime(doc.createdAt).takeIf { it.isNotBlank() },
            ).joinToString(" · ") + if (doc.fileUrl.isNullOrBlank()) " ·（纯文本入库，无原文件）" else ""
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SecondaryButton("预览", onClick = onPreview, enabled = enabled)
            SecondaryButton("重命名", onClick = onRename, enabled = enabled)
            SecondaryButton("删除", onClick = onDelete, enabled = enabled && doc.kbId != null, danger = true)
        }
        if (doc.fileUrl.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "该文档为粘贴文本入库，预览页只显示解析出的正文",
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
    }
}

@Composable
private fun DocTitleLine(title: String, hasOverview: Boolean) {
    Row {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = LocalZhixuePalette.current.text,
        )
        if (hasOverview) TagBadge("✨速览", TagTone.Info)
    }
}
