package com.zhixue.assistant.ui.screens.learn

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.data.model.MistakeRecord
import com.zhixue.assistant.data.model.PaperItem
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavController
import com.zhixue.assistant.ui.components.LocalToast
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.util.friendlyTime

// 错题本：练习与考试答错的题自动归集，支持重练与举一反三变式作答。
@Composable
fun MistakeBookScreen() {
    val container = LocalAppContainer.current
    val navController = LocalNavController.current
    val toast = LocalToast.current
    val vm: MistakeBookViewModel = viewModel(factory = MistakeBookViewModel.factory(container))
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.init() }
    LaunchedEffect(Unit) { vm.navigate.collect { navController.navigate(it) } }
    LaunchedEffect(Unit) { vm.toast.collect { toast(it) } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PageHeader(
                title = "错题本",
                subtitle = "练习与考试答错的题自动归集，也可在 AI 答疑中收录；重练答对后自动出本",
            )
        }
        item {
            ZhixueCard {
                CourseSelect(
                    courses = state.courses,
                    selected = state.courseId,
                    onSelect = vm::setCourse,
                    includeAll = true,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${state.total} 道错题待攻克",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = LocalZhixuePalette.current.primaryDeep,
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton("开始重练", onClick = vm::goRetrain, enabled = state.total > 0)
                }
            }
        }
        when (val r = state.records) {
            is UiState.Loading -> item { com.zhixue.assistant.ui.components.LoadingView("加载错题…") }
            is UiState.Error -> item { ErrorState(r.message, onRetry = vm::retry) }
            is UiState.Ready -> {
                if (r.data.isEmpty()) {
                    item {
                        ZhixueCard {
                            EmptyView(
                                title = if (state.courseId != null) "该课程暂无错题" else "暂无错题",
                                hint = if (state.courseId != null) "这门课掌握得不错，保持下去！" else "完成一次练习后，答错的题会自动归集到这里",
                            )
                        }
                    }
                } else {
                    itemsIndexed(r.data, key = { _, it -> "mk_${it.question.id}" }) { index, record ->
                        MistakeCard(vm, state, record, index)
                    }
                    if (vm.recordCount() < state.total) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
                                if (state.loadingMore) InlineLoading("加载中…") else SecondaryButton("加载更多", onClick = vm::loadMore)
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun MistakeCard(
    vm: MistakeBookViewModel,
    state: MistakeBookViewModel.State,
    record: MistakeRecord,
    index: Int,
) {
    val q = record.question
    ZhixueCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TagBadge("#${index + 1}", TagTone.Info)
            if (record.wrongCount > 0) {
                TagBadge("错 ${record.wrongCount} 次", TagTone.Danger)
            } else {
                TagBadge("手动收录", TagTone.Neutral)
            }
            if (q.id in state.collected) TagBadge("已收录", TagTone.Success)
            Spacer(Modifier.weight(1f))
            Text(
                friendlyTime(record.lastWrongAt),
                style = MaterialTheme.typography.bodySmall,
                color = LocalZhixuePalette.current.muted,
            )
        }
        Spacer(Modifier.height(8.dp))
        QuestionTagRow(type = q.type, kp = q.kpName, difficulty = q.difficulty)
        Spacer(Modifier.height(6.dp))
        Text(q.stem, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        OptionsComparison(q.options, q.answer, record.lastWrongAnswer)
        AnswerCompareRows(record.lastWrongAnswer, q.answer)

        if (!q.analysis.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            val expanded = q.id in state.expanded
            SecondaryButton(if (expanded) "收起解析" else "查看解析", onClick = { vm.toggleExpand(q.id) })
            if (expanded) {
                Text(
                    q.analysis.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalZhixuePalette.current.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .background(LocalZhixuePalette.current.soft, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val variantBusy = q.id in state.variant && state.variant[q.id]?.phase == MistakeBookViewModel.VariantPhase.Loading
            SecondaryButton(
                text = if (variantBusy) "生成中…" else "举一反三",
                onClick = { vm.openVariants(record) },
                enabled = !variantBusy,
            )
            Text(
                "AI 围绕该题考点出几道变式，检验是否真正掌握",
                style = MaterialTheme.typography.bodySmall,
                color = LocalZhixuePalette.current.muted,
                modifier = Modifier.weight(1f),
            )
        }

        state.variant[q.id]?.let { v ->
            Spacer(Modifier.height(10.dp))
            VariantPanel(vm, q.id, v)
        }
    }
}

@Composable
private fun VariantPanel(vm: MistakeBookViewModel, sourceId: Long, v: MistakeBookViewModel.VariantState) {
    when (v.phase) {
        MistakeBookViewModel.VariantPhase.Loading -> {
            InlineLoading("正在围绕「${v.kp ?: "该题考点"}」生成变式题…")
        }

        MistakeBookViewModel.VariantPhase.Answer -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("变式练习（${v.paper.size} 题）", fontWeight = FontWeight.SemiBold)
                Text("原题：${v.stem}", style = MaterialTheme.typography.bodySmall, color = LocalZhixuePalette.current.muted)
                v.paper.forEachIndexed { i, q ->
                    VariantQuestionCard(vm, sourceId, v, i, q)
                }
                v.error?.let { HintBanner(it, tone = BannerTone.Error) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("取消", onClick = { vm.closeVariants(sourceId) })
                    PrimaryButton("提交并 AI 批改", onClick = { vm.submitVariants(sourceId) }, loading = v.submitting)
                }
            }
        }

        MistakeBookViewModel.VariantPhase.Result -> {
            val report = v.report
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("变式练习结果", fontWeight = FontWeight.SemiBold)
                if (report != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        com.zhixue.assistant.ui.components.StatTile(
                            value = "${report.score} / ${report.totalScore}",
                            label = "总分",
                            modifier = Modifier.weight(1f),
                        )
                        com.zhixue.assistant.ui.components.StatTile(
                            value = "${report.items.count { it.pq.correct == true }} / ${report.items.size}",
                            label = "答对",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    report.items.forEachIndexed { i, item ->
                        GradedQuestionCard(
                            index = i,
                            item = gradedOf(item),
                            busyFavorite = false,
                            busyCollect = false,
                            collected = false,
                            onFavorite = {},
                            onCollect = {},
                            showCollect = false,
                        )
                    }
                }
                PrimaryButton("完成", onClick = { vm.closeVariants(sourceId) })
            }
        }
    }
}

@Composable
private fun VariantQuestionCard(vm: MistakeBookViewModel, sourceId: Long, v: MistakeBookViewModel.VariantState, index: Int, q: PaperItem) {
    ZhixueCard {
        Text("${index + 1}. 【${q.type}】${q.stem}", fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        AnswerEditor(
            type = q.type,
            optionsRaw = q.options,
            value = v.answers[q.id].orEmpty(),
            onChange = { vm.setVariantAnswer(sourceId, q.id, it) },
        )
    }
}

