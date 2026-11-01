package com.zhixue.assistant.ui.screens.course

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.LabeledField
import com.zhixue.assistant.ui.components.LoadingView
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalToast
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.SectionTitle
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import kotlinx.coroutines.launch

/** 正文按固定长度分块渲染：一次性把十万字符塞进一个 Text 会明显掉帧。 */
private const val CHUNK_SIZE = 2000

private const val TRUNCATED_SUFFIX = "…（预览截断）"

/**
 * 文档预览页：元信息、AI 速览（生成/重新生成）、文档内搜索（命中高亮 + 上下跳转 + 滚动定位）、
 * 下载文本副本、查看原文件（PDF 交给系统应用，其它类型走系统浏览器）。
 */
@Composable
fun DocPreviewScreen(kbId: Long, docId: Long) {
    val container = LocalAppContainer.current
    val vm: DocPreviewViewModel = viewModel(factory = DocPreviewViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val saveFile = rememberFileSaver()

    LaunchedEffect(kbId, docId) { vm.bind(kbId, docId) }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .padding(horizontal = 14.dp)
                .padding(top = 12.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PageHeader(
                title = state.preview?.fileName?.takeIf { it.isNotBlank() } ?: "文档预览",
                subtitle = "正文只读取解析结果，超长文档会被后端截断",
                actions = {
                    SecondaryButton("刷新", onClick = { vm.reload() }, enabled = !state.loading && !state.overviewLoading)
                },
            )
            MessageChannel(vm)
        }

        Box(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            when {
                state.loading -> LoadingView("加载文档正文中…", Modifier.fillMaxSize())

                state.error != null -> ErrorState(state.error.orEmpty(), onRetry = { vm.reload() })

                else -> {
                    if (state.preview == null) {
                        EmptyView(
                            title = "没有可预览的内容",
                            hint = "后端没有返回正文，可能是解析尚未完成或文档内容为空",
                            actionText = "重新加载",
                            onAction = { vm.reload() },
                        )
                    } else {
                        PreviewBody(state, vm, saveFile)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewBody(
    state: DocPreviewViewModel.State,
    vm: DocPreviewViewModel,
    saveFile: (String, ByteArray, String) -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val preview = state.preview ?: return
    val body = preview.text?.takeIf { it.isNotBlank() } ?: "（无文本内容：索引未完成或文档为空）"

    Column(Modifier.fillMaxSize()) {
        MetaLine(
            listOfNotNull(
                preview.fileName,
                preview.fileType?.takeIf { it.isNotBlank() } ?: "未知类型",
                "${preview.chunkCount ?: 0} 个片段",
                "${preview.text?.length ?: 0} 字",
                preview.parseStatus ?: "未解析",
            ).joinToString(" · ")
        )

        OverviewCard(state, vm)

        Spacer(Modifier.height(10.dp))
        SearchCard(state, vm)

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = "下载文本副本",
                onClick = {
                    saveFile(
                        "${preview.fileName.ifBlank { "文档" }}.txt",
                        preview.text.orEmpty().toByteArray(Charsets.UTF_8),
                        "text/plain",
                    )
                },
            )
            if (preview.fileUrl.isNullOrBlank()) {
                MetaLine("（纯文本入库，无原文件）", Modifier.weight(1f))
            } else {
                SecondaryButton(
                    text = "查看原文件",
                    onClick = {
                        val url = preview.fileUrl.orEmpty()
                        scope.launch {
                            openOriginalFile(context, container, preview.fileName, url) { message -> toast(message) }
                        }
                    },
                )
                MetaLine("PDF 交给系统应用打开", Modifier.weight(1f))
            }
        }

        if (body.endsWith(TRUNCATED_SUFFIX)) {
            Spacer(Modifier.height(8.dp))
            HintBanner(
                "文档较长，后端只回传了截断后的预览正文；需要全文请用「查看原文件」",
                BannerTone.Warn,
            )
        }

        Spacer(Modifier.height(8.dp))
        DocTextList(body, state)
    }
}

@Composable
private fun OverviewCard(state: DocPreviewViewModel.State, vm: DocPreviewViewModel) {
    val palette = LocalZhixuePalette.current
    val overview = state.overview
    ZhixueCard(
        title = "AI 速览",
        actions = {
            SecondaryButton(
                text = when {
                    state.overviewLoading -> "生成中…"
                    overview != null -> "重新生成"
                    else -> "生成速览"
                },
                onClick = { vm.generateOverview() },
                enabled = !state.overviewLoading,
            )
        },
    ) {
        when {
            state.overviewLoading -> InlineLoading("AI 正在通读全文提炼要点…")
            overview == null -> MetaLine("尚未生成速览——点右上按钮，AI 通读全文提炼要点与可能考点")
            else -> {
                if (overview.summary.isNotBlank()) {
                    Text(overview.summary, style = MaterialTheme.typography.bodyMedium, color = palette.text)
                }
                if (overview.points.isNotEmpty()) {
                    SectionTitle("核心要点")
                    overview.points.forEach { point ->
                        Text(
                            "• $point",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.text,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
                if (overview.examPoints.isNotEmpty()) {
                    SectionTitle("可能考点")
                    overview.examPoints.forEach { point ->
                        Text(
                            "• $point",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.text,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchCard(state: DocPreviewViewModel.State, vm: DocPreviewViewModel) {
    val hits = state.hits
    ZhixueCard(title = "文档内搜索") {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledField(
                label = "关键词",
                value = state.query,
                onValueChange = vm::onQueryChange,
                placeholder = "在本文档内搜索…",
                modifier = Modifier.weight(1f),
            )
            SecondaryButton("上一个", onClick = { vm.jumpMatch(-1) }, enabled = hits.isNotEmpty())
            SecondaryButton("下一个", onClick = { vm.jumpMatch(1) }, enabled = hits.isNotEmpty())
        }
        Spacer(Modifier.height(6.dp))
        when {
            state.query.isBlank() -> MetaLine("输入关键词后显示命中数量，可逐个跳转")
            hits.isEmpty() -> MetaLine("无匹配")
            else -> MetaLine("第 ${state.matchIndex + 1} / ${hits.size} 处命中，用「上一个 / 下一个」循环跳转")
        }
    }
}

@Composable
private fun ColumnScope.DocTextList(body: String, state: DocPreviewViewModel.State) {
    val listState = rememberLazyListState()
    val chunks = remember(body) { body.chunked(CHUNK_SIZE) }
    val hits = state.hits
    val activeHit = hits.getOrNull(state.matchIndex)

    LaunchedEffect(activeHit?.start) {
        val target = activeHit ?: return@LaunchedEffect
        listState.animateScrollToItem((target.start / CHUNK_SIZE).coerceIn(0, chunks.lastIndex))
    }

    SoftPanel(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
        ) {
            itemsIndexed(chunks) { index, chunk ->
                DocTextChunk(
                    chunk = chunk,
                    start = index * CHUNK_SIZE,
                    query = state.query,
                    hits = hits,
                    activeHit = activeHit,
                )
            }
        }
    }
}

/** 一块正文 + 命中标注：命中底色用 warn，当前命中用主色描出来，与 Web 的 mark/.on 一致。 */
@Composable
private fun DocTextChunk(
    chunk: String,
    start: Int,
    query: String,
    hits: List<DocHit>,
    activeHit: DocHit?,
) {
    val palette = LocalZhixuePalette.current
    val annotated = remember(chunk, query, hits, activeHit) {
        buildAnnotatedString {
            if (query.isBlank()) {
                append(chunk)
                return@buildAnnotatedString
            }
            var cursor = 0
            hits.forEach { hit ->
                if (hit.end <= start || hit.start >= start + chunk.length) return@forEach
                val from = (hit.start - start).coerceAtLeast(0)
                val to = (hit.end - start).coerceAtMost(chunk.length)
                if (from > cursor) append(chunk.substring(cursor, from))
                withStyle(
                    SpanStyle(
                        background = if (hit == activeHit) {
                            palette.primary.copy(alpha = 0.45f)
                        } else {
                            palette.warn.copy(alpha = 0.3f)
                        },
                        color = palette.text,
                    )
                ) {
                    append(chunk.substring(from, to))
                }
                cursor = to
            }
            if (cursor < chunk.length) append(chunk.substring(cursor))
        }
    }
    Text(
        annotated,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 21.sp,
        color = palette.text,
    )
}
