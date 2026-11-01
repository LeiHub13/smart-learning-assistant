package com.zhixue.assistant.ui.screens.insight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.data.model.SearchDocHit
import com.zhixue.assistant.data.model.SearchResults
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.LabeledField
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavigator
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.SectionTitle
import com.zhixue.assistant.ui.components.SelectField
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.theme.LocalZhixuePalette

@Composable
fun SearchScreen(query: String) {
    val container = LocalAppContainer.current
    val vm: SearchViewModel = viewModel(factory = SearchViewModel.factory(container))
    val keyword by vm.keyword.collectAsState()
    val courseKey by vm.courseKey.collectAsState()
    val courses by vm.courses.collectAsState()
    val result by vm.result.collectAsState()
    val executedKeyword by vm.executedKeyword.collectAsState()
    val message by vm.message.collectAsState()
    val navigator = LocalNavigator.current

    LaunchedEffect(query) { vm.searchFromRoute(query) }

    val options = remember(courses) { courseOptions(courses, includeAll = "全部课程") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                PageHeader(
                    title = "全局搜索",
                    subtitle = "覆盖题目 · 笔记 · 考试 · 知识库文档片段",
                )
                val banner = message
                if (banner != null) {
                    Spacer(Modifier.height(6.dp))
                    HintBanner(
                        text = banner.text,
                        tone = if (banner.error) BannerTone.Error else BannerTone.Info,
                        onDismiss = { vm.dismissMessage() },
                    )
                }
                Spacer(Modifier.height(8.dp))
                ZhixueCard {
                    LabeledField(
                        label = "关键词",
                        value = keyword,
                        onValueChange = { vm.setKeyword(it) },
                        placeholder = "搜索题目 / 笔记 / 文档 / 考试…",
                    )
                    Spacer(Modifier.height(10.dp))
                    SelectField(
                        label = "课程范围",
                        options = options,
                        selected = courseKey,
                        onSelect = { vm.setCourse(it) },
                        emptyLabel = "全部课程",
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PrimaryButton(text = "搜索", onClick = { vm.search() })
                        if (result is UiState.Loading) {
                            Spacer(Modifier.width(12.dp))
                            InlineLoading("正在检索…")
                        }
                    }
                }
            }
        }

        when (val current = result) {
            null -> item(key = "search-idle") {
                ZhixueCard {
                    EmptyView(
                        title = "输入关键词开始搜索",
                        hint = "一次查询覆盖：题目 · 笔记 · 考试 · 知识库文档片段",
                    )
                }
            }
            is UiState.Loading -> item(key = "search-loading") {
                ZhixueCard { InlineLoading("正在检索…") }
            }
            is UiState.Error -> item(key = "search-error") {
                ErrorState(message = current.message, onRetry = { vm.search() })
            }
            is UiState.Ready -> searchResultRows(current.data, executedKeyword, navigator)
        }
    }
}

/** 四组结果：每组限量展示，组内条目直接进页面 LazyColumn，不做嵌套滚动容器。 */
private fun LazyListScope.searchResultRows(
    results: SearchResults,
    keyword: String,
    navigator: (String) -> Unit,
) {
    val questions = results.questions.take(SEARCH_QUESTION_LIMIT)
    val notes = results.notes.take(SEARCH_NOTE_LIMIT)
    val exams = results.exams.take(SEARCH_EXAM_LIMIT)
    val docs = results.docs.take(SEARCH_DOC_LIMIT)

    if (questions.isEmpty() && notes.isEmpty() && exams.isEmpty() && docs.isEmpty()) {
        item(key = "search-none") {
            ZhixueCard {
                EmptyView(
                    title = "没有找到与「$keyword」相关的内容",
                    hint = "换个关键词，或把课程范围放宽到「全部课程」再试一次",
                )
            }
        }
        return
    }

    searchGroup("question", "题目", questions, { it.id }, "无匹配题目") { hit ->
        HitCard(
            title = hit.stem,
            tags = listOfNotNull(hit.type.takeIf { it.isNotBlank() }),
            meta = hit.kpName?.let { "知识点：$it" },
            actionText = "去练习 →",
            onAction = { navigator("/practice") },
        )
    }

    searchGroup("note", "学习笔记", notes, { it.id }, "无匹配笔记") { hit ->
        HitCard(
            title = hit.title,
            tags = listOfNotNull(hit.kpName?.takeIf { it.isNotBlank() }),
            meta = firstLinePreview(hit.content),
            actionText = "去笔记 →",
            onAction = { navigator("/notes") },
        )
    }

    searchGroup("exam", "在线考试", exams, { it.id }, "无匹配考试") { hit ->
        HitCard(
            title = hit.title,
            tags = emptyList(),
            meta = "总分 ${hit.totalScore}",
            actionText = "去考试 →",
            onAction = { navigator("/exam") },
        )
    }

    searchGroup("doc", "知识库文档片段", docs, { it.chunkId }, "无匹配文档片段") { hit ->
        DocHitCard(hit)
    }
}

private fun <T> LazyListScope.searchGroup(
    keyPrefix: String,
    title: String,
    rows: List<T>,
    keyOf: (T) -> Any,
    emptyText: String,
    itemContent: @Composable (T) -> Unit,
) {
    item(key = "$keyPrefix-head") { SectionTitle("$title（${rows.size}）") }
    if (rows.isEmpty()) {
        item(key = "$keyPrefix-empty") {
            ZhixueCard { EmptyView(emptyText) }
        }
    } else {
        items(rows, key = { row -> "$keyPrefix-${keyOf(row)}" }) { row -> itemContent(row) }
    }
}

@Composable
private fun HitCard(
    title: String,
    tags: List<String>,
    meta: String?,
    actionText: String,
    onAction: () -> Unit,
) {
    val palette = LocalZhixuePalette.current
    ZhixueCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title.ifBlank { "（无标题）" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            tags.forEach { tag ->
                Spacer(Modifier.width(6.dp))
                TagBadge(tag, TagTone.Neutral)
            }
        }
        if (!meta.isNullOrBlank()) {
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton(actionText, onClick = onAction)
    }
}

@Composable
private fun DocHitCard(hit: SearchDocHit) {
    val palette = LocalZhixuePalette.current
    ZhixueCard {
        Text(hit.docName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "知识库：${hit.kbName}",
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            hit.content,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(palette.soft, RoundedCornerShape(8.dp))
                .padding(10.dp),
        )
    }
}
