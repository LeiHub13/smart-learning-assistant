package com.zhixue.assistant.ui.screens.course

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.data.model.DocumentDto
import com.zhixue.assistant.data.model.KnowledgeBaseDto
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.ConfirmDialog
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.InputDialog
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.LabeledField
import com.zhixue.assistant.ui.components.LoadingView
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavController
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.nav.Routes
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.util.fmtDateTime

/**
 * 课程详情：知识库增删 + 每个知识库的文档列表与上传入口 + 图谱入口 + 全局向量索引重建。
 * 上传走系统文件选择器（OpenDocument），也保留粘贴文本入库，两条路径都把后端错误接住。
 */
@Composable
fun CourseDetailScreen(courseId: Long) {
    val container = LocalAppContainer.current
    val navController = LocalNavController.current
    val context = LocalContext.current
    val vm: CourseDetailViewModel = viewModel(factory = CourseDetailViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(courseId) { vm.bind(courseId) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onFilePicked(context, uri)
    }

    var renameTarget by remember { mutableStateOf<Pair<Long, DocumentDto>?>(null) }
    var deleteDocTarget by remember { mutableStateOf<Triple<Long, Long, String>?>(null) }
    var deleteKbTarget by remember { mutableStateOf<KnowledgeBaseDto?>(null) }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .padding(horizontal = 14.dp)
                .padding(top = 12.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PageHeader(
                title = state.courseName?.let { "$it · 知识库" } ?: "课程知识库",
                subtitle = "创建知识库、上传资料，供 AI 答疑与知识图谱使用",
                actions = {
                    SecondaryButton("刷新", onClick = { vm.reload() }, enabled = !state.working && !state.loading)
                },
            )
            MessageChannel(vm)
        }

        Box(Modifier.weight(1f)) {
            when {
                state.loading -> LoadingView("加载知识库中…", Modifier.fillMaxSize())
                state.error != null -> ErrorState(
                    state.error.orEmpty(),
                    onRetry = { vm.reload() },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                else -> CourseDetailList(
                    state = state,
                    vm = vm,
                    onPickFile = { picker.launch(UPLOAD_MIME_TYPES) },
                    onOpenGraph = { kbId -> navController.navigate(Routes.graph(kbId, courseId)) },
                    onPreviewDoc = { kbId, docId -> navController.navigate(Routes.docPreview(kbId, docId)) },
                    onAskRename = { kbId, doc -> renameTarget = kbId to doc },
                    onAskDeleteDoc = { kbId, doc -> deleteDocTarget = Triple(kbId, doc.id, doc.fileName) },
                    onAskDeleteKb = { kb -> deleteKbTarget = kb },
                )
            }
        }
    }

    renameTarget?.let { (kbId, doc) ->
        InputDialog(
            title = "重命名文档",
            label = "文档名",
            initial = doc.fileName,
            placeholder = "如：HashMap原理.md",
            onDismiss = { renameTarget = null },
            onSubmit = { name ->
                renameTarget = null
                vm.renameDoc(kbId, doc.id, name)
            },
        )
    }

    deleteDocTarget?.let { (kbId, docId, fileName) ->
        ConfirmDialog(
            title = "删除文档",
            body = "确认删除「$fileName」？文本、向量索引与原文件将一并清理。",
            confirmText = "确认删除",
            danger = true,
            onConfirm = {
                deleteDocTarget = null
                vm.deleteDoc(kbId, docId, fileName)
            },
            onDismiss = { deleteDocTarget = null },
        )
    }

    deleteKbTarget?.let { kb ->
        ConfirmDialog(
            title = "删除知识库",
            body = "确定删除知识库「${kb.name}」吗？其中的文档和向量索引也会一并清除，且无法恢复。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                deleteKbTarget = null
                vm.deleteKb(kb.id, kb.name)
            },
            onDismiss = { deleteKbTarget = null },
        )
    }
}

@Composable
private fun CourseDetailList(
    state: CourseDetailViewModel.State,
    vm: CourseDetailViewModel,
    onPickFile: () -> Unit,
    onOpenGraph: (Long) -> Unit,
    onPreviewDoc: (kbId: Long, docId: Long) -> Unit,
    onAskRename: (Long, DocumentDto) -> Unit,
    onAskDeleteDoc: (Long, DocumentDto) -> Unit,
    onAskDeleteKb: (KnowledgeBaseDto) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ZhixueCard(title = "新建知识库", subtitle = "名称留空时后端会建成「新知识库」") {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField(
                        label = "知识库名称",
                        value = state.newKbName,
                        onValueChange = vm::onNewKbNameChange,
                        placeholder = "如：课堂讲义",
                        maxLength = 60,
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton("创建", onClick = { vm.createKb() }, enabled = !state.working)
                }
            }
        }

        item {
            ReindexPane(state, vm)
        }

        if (state.kbs.isEmpty()) {
            item {
                EmptyView(
                    title = "还没有知识库",
                    hint = "先新建一个知识库，再上传课程资料，AI 答疑与图谱才有内容可用",
                )
            }
        } else {
            items(state.kbs, key = { it.kb.id }) { entry ->
                KbPane(
                    entry = entry,
                    state = state,
                    vm = vm,
                    onPickFile = onPickFile,
                    onOpenGraph = onOpenGraph,
                    onPreviewDoc = onPreviewDoc,
                    onAskRename = onAskRename,
                    onAskDeleteDoc = onAskDeleteDoc,
                    onAskDeleteKb = onAskDeleteKb,
                )
            }
        }
    }
}

@Composable
private fun ReindexPane(state: CourseDetailViewModel.State, vm: CourseDetailViewModel) {
    ZhixueCard(
        title = "向量索引",
        subtitle = "索引是可丢弃的派生数据；答疑检索结果异常时重建一次即可，不影响已入库文档",
    ) {
        state.reindexed?.let { text ->
            HintBanner(text, BannerTone.Success, onDismiss = vm::dismissReindexNotice)
            Spacer(Modifier.height(8.dp))
        }
        SecondaryButton(
            text = "重建向量索引",
            onClick = { vm.reindex() },
            enabled = !state.reindexing,
        )
        if (state.reindexing) {
            Spacer(Modifier.height(8.dp))
            InlineLoading("正在重建向量索引…")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KbPane(
    entry: KbWithDocs,
    state: CourseDetailViewModel.State,
    vm: CourseDetailViewModel,
    onPickFile: () -> Unit,
    onOpenGraph: (Long) -> Unit,
    onPreviewDoc: (kbId: Long, docId: Long) -> Unit,
    onAskRename: (Long, DocumentDto) -> Unit,
    onAskDeleteDoc: (Long, DocumentDto) -> Unit,
    onAskDeleteKb: (KnowledgeBaseDto) -> Unit,
) {
    val palette = LocalZhixuePalette.current
    val kb = entry.kb
    val uploading = state.uploadKbId == kb.id
    ZhixueCard(
        title = kb.name,
        subtitle = "${entry.docs.size} 个文档 · ${entry.docs.sumOf { it.chunkCount ?: 0 }} 个片段",
        actions = {
            TextButtonCompact(if (uploading) "收起上传" else "上传资料", onClick = { vm.toggleUploadPanel(kb.id) })
            TextButtonCompact("图谱", onClick = { onOpenGraph(kb.id) })
            TextButtonCompact("删除知识库", onClick = { onAskDeleteKb(kb) }, danger = true)
        },
    ) {
        if (uploading) {
            UploadPane(state, vm, onPickFile)
            Spacer(Modifier.height(12.dp))
        }

        if (entry.docs.isEmpty()) {
            Text(
                "该知识库还没有文档",
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                entry.docs.forEach { doc ->
                    DocumentRow(
                        doc = doc,
                        enabled = !state.working,
                        onPreview = { onPreviewDoc(kb.id, doc.id) },
                        onRename = { onAskRename(kb.id, doc) },
                        onDelete = { onAskDeleteDoc(kb.id, doc) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocumentRow(
    doc: DocumentDto,
    enabled: Boolean,
    onPreview: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalZhixuePalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                doc.fileName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = palette.text,
            )
            if (!doc.overview.isNullOrBlank()) {
                Spacer(Modifier.width(6.dp))
                TagBadge("✨速览", TagTone.Info)
            }
        }
        MetaLine(
            "${doc.chunkCount ?: 0} 个片段 · ${doc.fileType ?: "未知类型"} · " +
                (doc.parseStatus ?: "未解析") +
                doc.createdAt?.let { " · ${fmtDateTime(it)}" }.orEmpty()
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SecondaryButton("预览", onClick = onPreview, enabled = enabled)
            SecondaryButton("重命名", onClick = onRename, enabled = enabled)
            SecondaryButton("删除", onClick = onDelete, enabled = enabled, danger = true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UploadPane(
    state: CourseDetailViewModel.State,
    vm: CourseDetailViewModel,
    onPickFile: () -> Unit,
) {
    val picked = state.picked
    SoftPanel {
        LabeledField(
            label = "文档名",
            value = state.docName,
            onValueChange = vm::onDocNameChange,
            placeholder = "如：HashMap原理.md",
            maxLength = 120,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SecondaryButton("选择文件", onClick = onPickFile, enabled = !state.working)
            PrimaryButton("解析入库", onClick = { vm.submitUpload() }, loading = state.working)
            if (picked != null) {
                SecondaryButton("改为粘贴文本", onClick = vm::clearPicked, enabled = !state.working)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (picked != null) {
            MetaLine("已选择：${vm.describePicked()}，上传原始文件由后端解析分块")
        } else {
            LabeledField(
                label = "或直接粘贴文档内容",
                value = state.pastedText,
                onValueChange = vm::onPastedTextChange,
                placeholder = "粘贴讲义、笔记或代码说明，系统自动分块向量化",
                singleLine = false,
                minLines = 5,
                enabled = !state.working,
            )
        }
        Spacer(Modifier.height(6.dp))
        MetaLine(
            "支持 txt / md / markdown / java / json / xml / yml / yaml / sql / pdf / docx，" +
                "单文件不超过 20MB；旧版 .doc 需先在 Word 里另存为 .docx"
        )
        if (state.working) {
            Spacer(Modifier.height(8.dp))
            InlineLoading("上传解析中，请稍候…")
        }
    }
}
