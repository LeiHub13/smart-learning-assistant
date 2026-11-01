package com.zhixue.assistant.ui.screens.learn

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.data.model.PaperItem
import com.zhixue.assistant.data.model.PracticeHistoryItem
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.CheckboxRow
import com.zhixue.assistant.ui.components.ConfirmDialog
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.LabeledField
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavController
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.SelectField
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.nav.Routes
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.util.fmtDateTime

// 题库练习：mode ∈ normal | mistake | favorite | manual（courseId=-1 表示未选课程）。
@Composable
fun PracticeScreen(mode: String, courseId: Long, kp: String, auto: Boolean, ids: String) {
    val container = LocalAppContainer.current
    val navController = LocalNavController.current
    val vm: PracticeViewModel = viewModel(factory = PracticeViewModel.factory(container))
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.init(mode, courseId, kp, auto, ids) }
    LaunchedEffect(Unit) { vm.navigate.collect { navController.navigate(it) } }

    val paper = state.paper
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PageHeader(
                title = "题库练习",
                subtitle = "客观题自动判分，主观题 AI 按参考答案打分并点评",
            )
        }
        state.error?.let { err ->
            item { HintBanner(err, tone = BannerTone.Error, onDismiss = vm::dismissError) }
        }

        if (paper == null) {
            item { PracticeSetup(vm, state) }
            if (state.browseOpen) {
                items(state.visibleQuestions(), key = { "bq_${it.id}" }) { q -> BrowseQuestionRow(vm, state, q) }
            }
            item { SectionTitleCompat("最近练习") }
            when (val h = state.history) {
                is UiState.Loading -> item { InlineLoading("加载练习记录…") }
                is UiState.Error -> item { ErrorState(h.message, onRetry = vm::retryHistory) }
                is UiState.Ready -> {
                    if (h.data.isEmpty()) {
                        item { EmptyView(title = "暂无练习记录") }
                    } else {
                        items(h.data, key = { "hist_${it.id}" }) { HistoryRow(it) { navController.navigate(Routes.practiceReport(it.id)) } }
                        if (state.historyCount() < state.historyTotal) {
                            item {
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
                                    if (state.historyLoadingMore) InlineLoading("加载中…") else SecondaryButton("加载更多", onClick = vm::loadMoreHistory)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            item { PaperHeader(vm, paper) }
            item { AnswerSheet(vm, paper) }
            item { QuestionAnswering(vm, paper) }
            item { PaperFooter(vm, paper, state) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (state.confirmExit) {
        ConfirmDialog(
            title = "退出练习",
            body = "退出后本次作答不会保存，也不生成练习记录。确定退出吗？",
            confirmText = "确定退出",
            dismissText = "继续作答",
            danger = true,
            onConfirm = vm::confirmExit,
            onDismiss = vm::cancelExit,
        )
    }
}

@Composable
private fun SectionTitleCompat(text: String) {
    Text(
        text,
        modifier = Modifier.padding(vertical = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = LocalZhixuePalette.current.muted,
    )
}

@Composable
private fun PracticeSetup(vm: PracticeViewModel, state: PracticeViewModel.State) {
    ZhixueCard(title = "练习设置") {
        if (state.loadingCourses) {
            InlineLoading("加载课程…")
            return@ZhixueCard
        }
        if (state.courses.isEmpty()) {
            EmptyView(title = "暂无课程", hint = "请先到「我的课程」创建或加入课程")
            return@ZhixueCard
        }
        CourseSelect(courses = state.courses, selected = state.courseId, onSelect = vm::setCourse)
        Spacer(Modifier.height(8.dp))
        SelectField(
            label = "题目数量",
            options = listOf(5, 10, 15, 20, 30).map { it.toString() to "$it 题" },
            selected = state.count.toString(),
            onSelect = { it.toIntOrNull()?.let(vm::setCount) },
        )
        Spacer(Modifier.height(8.dp))
        LabeledField(
            label = "知识点（可选）",
            value = state.kp,
            onValueChange = vm::setKp,
            placeholder = "如：HashMap / 多线程",
            helper = "留空则从整库抽题；填写后只在含该知识点的题目中抽题",
        )
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            text = if (state.fetching) "抽题中…" else "开始练习",
            onClick = vm::startPractice,
            loading = state.fetching,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("错题重练", onClick = vm::startMistakeRetrain, enabled = !state.fetching, modifier = Modifier.weight(1f))
            SecondaryButton("收藏重练", onClick = vm::startFavoriteRetrain, enabled = !state.fetching, modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "专项练习：从课程题库选题（一次最多 20 题）",
                style = MaterialTheme.typography.bodySmall,
                color = LocalZhixuePalette.current.muted,
                modifier = Modifier.weight(1f),
            )
            TextButtonCompat(if (state.browseOpen) "收起" else "展开选题", onClick = vm::toggleBrowse)
        }
        if (state.browseOpen) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton("加载 / 刷新题目", onClick = vm::loadCourseQuestions, enabled = !state.browseLoading)
                Text("已选 ${state.selectedIds.size} / 20", style = MaterialTheme.typography.bodySmall, color = LocalZhixuePalette.current.muted)
            }
            if (state.browseLoading) {
                Spacer(Modifier.height(6.dp))
                InlineLoading("加载题目…")
            }
            state.browseError?.let { HintBanner(it, tone = BannerTone.Error) }

            val visible = state.visibleQuestions()
            if (visible.isNotEmpty()) {
                val kpOptions = state.kpOptions()
                if (kpOptions.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChipCompat(text = "全部 kp", selected = state.browseKp == null, onClick = { vm.setBrowseKp(null) })
                        kpOptions.forEach { k ->
                            FilterChipCompat(text = k, selected = state.browseKp == k, onClick = { vm.setBrowseKp(if (state.browseKp == k) null else k) })
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("全选", onClick = vm::selectAll, enabled = visible.isNotEmpty())
                    PrimaryButton(
                        text = "练习选中的 ${state.selectedIds.size} 题",
                        onClick = vm::startSelected,
                        enabled = state.selectedIds.isNotEmpty() && !state.fetching,
                        loading = state.fetching,
                    )
                }
                Spacer(Modifier.height(4.dp))
            } else if (!state.browseLoading && state.browseError == null) {
                Text(
                    "点「加载 / 刷新题目」查看本课程全部题目",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalZhixuePalette.current.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun PaperHeader(vm: PracticeViewModel, paper: PracticeViewModel.Paper) {
    ZhixueCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${vm.paperCourseName() ?: "练习"} · 本次 ${paper.items.size} 题",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (paper.kp.isNotBlank()) TagBadge("专项：${paper.kp}", TagTone.Info)
                    when {
                        paper.mistake -> TagBadge("错题重练", TagTone.Danger)
                        paper.favorite -> TagBadge("收藏重练", TagTone.Warn)
                        else -> TagBadge("作答后提交", TagTone.Neutral)
                    }
                }
            }
            SecondaryButton("退出练习", onClick = vm::requestExit)
        }
    }
}

@Composable
private fun AnswerSheet(vm: PracticeViewModel, paper: PracticeViewModel.Paper) {
    val palette = LocalZhixuePalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        paper.items.forEachIndexed { i, q ->
            val answered = paper.answers[q.id]?.isNotBlank() == true
            val current = i == paper.index
            Box(
                Modifier
                    .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                    .background(
                        when {
                            current -> palette.primary
                            answered -> palette.okSoft
                            else -> palette.card
                        },
                        RoundedCornerShape(8.dp),
                    )
                    .border(
                        1.dp,
                        when {
                            current -> palette.primary
                            answered -> palette.okLine
                            else -> palette.border
                        },
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { vm.selectQuestion(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${i + 1}",
                    color = when {
                        current -> palette.onPrimary
                        answered -> palette.success
                        else -> palette.muted
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun QuestionAnswering(vm: PracticeViewModel, paper: PracticeViewModel.Paper) {
    val q = paper.items.getOrNull(paper.index) ?: return
    ZhixueCard {
        Text(
            "${paper.index + 1}. 【${q.type}】",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(q.stem, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!q.kpName.isNullOrBlank()) TagBadge(q.kpName, TagTone.Neutral)
            if (!q.difficulty.isNullOrBlank()) TagBadge(q.difficulty, TagTone.Neutral)
        }
        Spacer(Modifier.height(8.dp))
        AnswerEditor(
            type = q.type,
            optionsRaw = q.options,
            value = paper.answers[q.id].orEmpty(),
            onChange = { vm.setAnswer(q.id, it) },
        )
    }
}

@Composable
private fun BrowseQuestionRow(vm: PracticeViewModel, state: PracticeViewModel.State, q: PaperItem) {
    ZhixueCard {
        Row(verticalAlignment = Alignment.Top) {
            CheckboxRow(
                label = q.stem,
                checked = q.id in state.selectedIds,
                onCheckedChange = { vm.toggleSelect(q.id) },
                description = listOf(q.type, q.kpName ?: "—", q.difficulty ?: "—").joinToString(" · "),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PaperFooter(vm: PracticeViewModel, paper: PracticeViewModel.Paper, state: PracticeViewModel.State) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SecondaryButton("上一题", onClick = { vm.selectQuestion(paper.index - 1) }, enabled = paper.index > 0)
        SecondaryButton("下一题", onClick = { vm.selectQuestion(paper.index + 1) }, enabled = paper.index < paper.items.lastIndex)
        Text(
            "已答 ${paper.answeredCount}/${paper.items.size}",
            style = MaterialTheme.typography.bodySmall,
            color = LocalZhixuePalette.current.muted,
            modifier = Modifier.weight(1f),
        )
    }
    PrimaryButton(
        text = if (state.submitting) "批改中…" else "提交并 AI 批改",
        onClick = vm::submit,
        modifier = Modifier.fillMaxWidth(),
        loading = state.submitting,
    )
}

@Composable
private fun HistoryRow(item: PracticeHistoryItem, onView: (PracticeHistoryItem) -> Unit) {
    ZhixueCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    buildString {
                        append(item.courseName ?: "—")
                        append(" · ")
                        append(fmtDateTime(item.createdAt))
                        append(" · 得分 ")
                        append(item.score)
                        append("/").append(item.totalScore)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalZhixuePalette.current.muted,
                )
            }
            SecondaryButton("查看报告", onClick = { onView(item) })
        }
    }
}

@Composable
private fun TextButtonCompat(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = Modifier.defaultMinSize(minHeight = 44.dp)) {
        Text(text, fontSize = 13.sp)
    }
}

@Composable
private fun FilterChipCompat(text: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, fontSize = 12.sp) },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.defaultMinSize(minHeight = 40.dp),
    )
}
