package com.zhixue.assistant.ui.screens.course

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.QuestionDto
import com.zhixue.assistant.data.repo.GeneratedContentDto
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.LabeledField
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalToast
import com.zhixue.assistant.ui.components.MarkdownText
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.SectionTitle
import com.zhixue.assistant.ui.components.SelectField
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.nav.QuotaBadge
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.util.fmtDateTime
import com.zhixue.assistant.util.parseOptions
import kotlinx.coroutines.launch

/**
 * AI 内容生成页：课程 + 知识库范围 + 知识点 + 主题，生成讲义（流式）或练习题（同步入库）。
 * 断流不当失败：服务端仍在落库，提供「查看生成历史」找回已保存的讲义。
 */
@Composable
fun GenerateScreen() {
    val container = LocalAppContainer.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val vm: GenerateViewModel = viewModel(factory = GenerateViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val saveFile = rememberFileSaver()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PageHeader("讲义/练习题生成", "AI 一键生成讲义与练习题，题目自动进入题库供学生练习")
                QuotaBadge(compact = false)
                MessageChannel(vm)
            }
        }

        item { ConfigCard(vm, state) }

        if (state.interrupted) {
            item {
                ZhixueCard(title = "连接已断开") {
                    HintBanner("连接中断，请稍后查看生成结果", BannerTone.Warn)
                    Spacer(Modifier.height(8.dp))
                    MetaLine("服务端是边生成边落库的，断流不代表失败——点下面的按钮找回已保存的讲义。")
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton(
                        text = if (state.historyLoading) "拉取中…" else "查看生成历史",
                        onClick = { vm.loadHistory() },
                        enabled = !state.historyLoading,
                    )
                }
            }
        }

        if (state.history.isNotEmpty()) {
            item { SectionTitle("生成历史（讲义）") }
            items(state.history, key = { it.id }) { item ->
                HistoryRow(item) { vm.useFromHistory(item) }
            }
        }

        if (state.lecture.isNotBlank() || state.streaming) {
            item { SectionTitle("讲义预览") }
            item {
                LectureCard(
                    state = state,
                    vm = vm,
                    onDownload = {
                        scope.launch {
                            vm.onDownloadStateChanged(true)
                            try {
                                val bytes = vm.lectureBytes()
                                saveFile(
                                    "讲义-${sanitizeTopicName(state.topic)}.md",
                                    bytes,
                                    "text/markdown",
                                )
                            } catch (e: Throwable) {
                                toast(e.userMessage())
                            } finally {
                                vm.onDownloadStateChanged(false)
                            }
                        }
                    },
                )
            }
        }

        if (state.questions.isNotEmpty()) {
            item { SectionTitle("生成的题目") }
            items(state.questions, key = { it.id }) { question ->
                QuestionCard(question)
            }
        }

        if (!state.loading && state.courses.isEmpty()) {
            item {
                EmptyView(
                    title = "还没有课程",
                    hint = "先到「我的课程」创建课程并上传资料，AI 才能按课程生成内容",
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigCard(vm: GenerateViewModel, state: GenerateViewModel.State) {
    ZhixueCard(title = "生成配置", subtitle = "知识点留空时后端按「核心概念」出题；主题是讲义的必填项") {
        if (state.loading) {
            InlineLoading("加载课程与知识库…")
            Spacer(Modifier.height(8.dp))
        }
        SelectField(
            label = "课程",
            options = vm.courseOptions(),
            selected = state.courseId?.toString(),
            onSelect = vm::onCourseSelected,
            emptyLabel = "请选择课程",
        )
        Spacer(Modifier.height(8.dp))
        SelectField(
            label = "知识库（可选）",
            options = vm.kbOptions(),
            selected = state.kbChoice,
            onSelect = vm::onKbChoice,
            emptyLabel = "不使用知识库",
        )
        Spacer(Modifier.height(8.dp))
        LabeledField(
            label = "知识点（可选）",
            value = state.kp,
            onValueChange = vm::onKpChange,
            placeholder = "如：HashMap / 多线程",
            maxLength = 60,
        )
        Spacer(Modifier.height(8.dp))
        LabeledField(
            label = "讲义主题",
            value = state.topic,
            onValueChange = vm::onTopicChange,
            placeholder = "如：Java 集合框架",
            maxLength = 120,
        )
        Spacer(Modifier.height(8.dp))
        SelectField(
            label = "生成题目数",
            options = vm.countOptions(),
            selected = state.questionCount.toString(),
            onSelect = vm::onCountChange,
        )
        if (state.kbChoice.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            val hint = if (state.kp.isNotBlank()) {
                "将参考「${vm.kbLabel()}」内与「${state.kp.trim()}」相关的课程资料生成"
            } else {
                "将参考「${vm.kbLabel()}」内与主题相关的课程资料生成；不填知识点时资料参考有限"
            }
            MetaLine(hint)
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PrimaryButton(
                text = if (state.streaming) "生成中…" else "生成讲义",
                onClick = { vm.generateLecture() },
                enabled = !state.busy,
            )
            SecondaryButton(
                text = "生成练习题",
                onClick = { vm.generateQuestions() },
                enabled = !state.busy,
            )
        }
        if (state.busy) {
            Spacer(Modifier.height(8.dp))
            InlineLoading(if (state.streaming) "AI 正在逐段生成讲义…" else "AI 正在生成…")
        }
    }
}

@Composable
private fun LectureCard(
    state: GenerateViewModel.State,
    vm: GenerateViewModel,
    onDownload: () -> Unit,
) {
    ZhixueCard(
        title = "讲义预览",
        subtitle = if (state.kbSaved && state.kbName.isNotBlank()) "已存入「${state.kbName}」，智能答疑可直接引用" else null,
        actions = {
            if (state.lectureId != null) {
                SecondaryButton(
                    text = when {
                        state.kbSaved -> "✓ 已入知识库"
                        state.kbSaving -> "入库中…"
                        else -> "存入知识库"
                    },
                    onClick = { vm.saveToKnowledgeBase() },
                    enabled = !state.kbSaving && !state.kbSaved,
                )
            }
        },
    ) {
        if (state.lectureId != null) {
            TagBadge("已保存", TagTone.Success)
            Spacer(Modifier.height(8.dp))
        }
        MarkdownText(
            source = if (state.streaming) state.lecture + " ▌" else state.lecture,
            streaming = state.streaming,
        )
        if (state.lectureId != null) {
            Spacer(Modifier.height(10.dp))
            SecondaryButton(
                text = if (state.downloading) "下载中…" else "下载 .md",
                onClick = onDownload,
                enabled = !state.downloading,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(question: QuestionDto) {
    val palette = LocalZhixuePalette.current
    ZhixueCard {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TagBadge(question.type.ifBlank { "题目" }, TagTone.Info)
            question.kpName?.takeIf { it.isNotBlank() }?.let { TagBadge(it) }
            question.difficulty?.takeIf { it.isNotBlank() }?.let { TagBadge(it) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            question.stem,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = palette.text,
        )
        val options = parseOptions(question.options)
        if (options.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            SoftPanel {
                options.forEach { option ->
                    Text(
                        "${option.key}. ${option.text}",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.text,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Text(
                "参考答案：",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.success,
            )
            Text(
                question.answer?.takeIf { it.isNotBlank() } ?: "（后端未给出）",
                style = MaterialTheme.typography.bodySmall,
                color = palette.text,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (!question.analysis.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            MetaLine("解析：${question.analysis}")
        }
    }
}

@Composable
private fun HistoryRow(item: GeneratedContentDto, onPick: () -> Unit) {
    ZhixueCard(
        title = item.title.ifBlank { "未命名讲义" },
        subtitle = listOfNotNull(
            fmtDateTime(item.createdAt).takeIf { it.isNotBlank() },
            "${item.content.length} 字",
        ).joinToString(" · ") + "；点按载入这篇讲义",
        onClick = onPick,
    ) {}
}
