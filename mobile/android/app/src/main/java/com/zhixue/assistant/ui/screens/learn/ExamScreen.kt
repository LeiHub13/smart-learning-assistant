package com.zhixue.assistant.ui.screens.learn

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.data.model.ExamDto
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.CheckboxRow
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.LabeledField
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavController
import com.zhixue.assistant.ui.components.LoadingView
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.SelectField
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.theme.LocalZhixuePalette

// 在线考试：列表 + 创建（手动/自动组卷）。
@Composable
fun ExamScreen() {
    val container = LocalAppContainer.current
    val navController = LocalNavController.current
    val vm: ExamViewModel = viewModel(factory = ExamViewModel.factory(container))
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.init() }
    LaunchedEffect(Unit) { vm.navigate.collect { navController.navigate(it) } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PageHeader(title = "在线考试", subtitle = "组卷 → 限时作答 → 客观题自动判分 / 主观题 AI 批改")
        }
        state.error?.let { err ->
            item { HintBanner(err, tone = BannerTone.Error, onDismiss = vm::dismissError) }
        }
        item {
            ZhixueCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        CourseSelect(
                            courses = state.courses,
                            selected = state.courseId,
                            onSelect = vm::setCourse,
                            includeAll = true,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(
                        text = if (state.createOpen) "收起" else "创建考试",
                        onClick = vm::toggleCreate,
                    )
                    SecondaryButton("刷新列表", onClick = vm::refresh)
                }
            }
        }
        if (state.createOpen) {
            item { ExamCreateCard(vm, state) }
            if (state.manual) {
                items(state.bank, key = { "pick_${it.id}" }) { q ->
                    ZhixueCard {
                        CheckboxRow(
                            label = "【${q.type}】${q.stem}",
                            checked = q.id in state.pickedIds,
                            onCheckedChange = { vm.togglePick(q.id) },
                            description = listOfNotNull(q.kpName, q.difficulty ?: "未标注").joinToString(" · "),
                        )
                    }
                }
                item {
                    ZhixueCard {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "已勾选 ${state.pickedIds.size} 题",
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalZhixuePalette.current.muted,
                                modifier = Modifier.weight(1f),
                            )
                            PrimaryButton(
                                text = if (state.creating) "创建中…" else "确认创建",
                                onClick = vm::create,
                                loading = state.creating,
                            )
                        }
                    }
                }
            }
        }
        item {
            Text(
                "考试列表",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        when (val e = state.exams) {
            is UiState.Loading -> item { LoadingView("加载考试…") }
            is UiState.Error -> item { ErrorState(e.message, onRetry = vm::refresh) }
            is UiState.Ready -> {
                if (e.data.isEmpty()) {
                    item { EmptyView(title = "暂无考试", hint = "点击「创建考试」开一场") }
                } else {
                    items(e.data, key = { "exam_${it.id}" }) { exam -> ExamRow(vm, exam) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ExamCreateCard(vm: ExamViewModel, state: ExamViewModel.State) {
    ZhixueCard(title = "创建考试") {
        if (state.courses.isEmpty()) {
            EmptyView(title = "暂无课程", hint = "请先创建或加入课程")
            return@ZhixueCard
        }
        LabeledField(label = "考试标题", value = state.title, onValueChange = vm::setTitle, placeholder = "如：期中测验")
        Spacer(Modifier.height(8.dp))
        LabeledField(
            label = "时长（分钟，留空不限；5~180）",
            value = state.durationInput,
            onValueChange = vm::setDuration,
            placeholder = "如：60",
            keyboardType = KeyboardType.Number,
        )
        Spacer(Modifier.height(8.dp))
        SelectField(
            label = "组卷方式",
            options = listOf("auto" to "课程内随机抽题", "manual" to "手动选题"),
            selected = if (state.manual) "manual" else "auto",
            onSelect = { vm.setManual(it == "manual") },
        )
        if (!state.manual) {
            Spacer(Modifier.height(8.dp))
            LabeledField(
                label = "题目数量（1~20）",
                value = state.countInput,
                onValueChange = vm::setCount,
                keyboardType = KeyboardType.Number,
            )
        }
        if (state.manual) {
            Spacer(Modifier.height(8.dp))
            if (state.bankLoading) {
                InlineLoading("加载题库…")
            }
            state.bankError?.let { HintBanner(it, tone = BannerTone.Error) }
            if (!state.bankLoading && state.bankError == null && state.bank.isEmpty()) {
                Text(
                    "该课程暂无题目可勾选",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalZhixuePalette.current.muted,
                )
            }
            if (state.bank.isNotEmpty()) {
                Text(
                    "从下方题目列表勾选（已勾选 ${state.pickedIds.size} 题）",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalZhixuePalette.current.muted,
                )
            }
        }
        if (!state.manual) {
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                text = if (state.creating) "创建中…" else "确认创建",
                onClick = vm::create,
                loading = state.creating,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ExamRow(vm: ExamViewModel, exam: ExamDto) {
    val palette = LocalZhixuePalette.current
    ZhixueCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(exam.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagBadge(exam.courseName ?: "—", TagTone.Neutral)
                    TagBadge(if (exam.durationMin > 0) "${exam.durationMin} 分钟" else "不限时", TagTone.Info)
                    TagBadge("总分 ${exam.totalScore}", TagTone.Neutral)
                    if (exam.owner == true) TagBadge("我创建", TagTone.Warn)
                }
                Spacer(Modifier.height(4.dp))
                val attempts = exam.attemptCount ?: 0
                Text(
                    if (attempts > 0 && (exam.myBestScore ?: -1) >= 0) {
                        "我的成绩：最好 ${exam.myBestScore} / ${exam.totalScore}（$attempts 次）"
                    } else {
                        "我的成绩：未参加"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                )
            }
            PrimaryButton(
                text = "进入考试",
                onClick = { vm.enterExam(exam.id) },
                modifier = Modifier.defaultMinSize(minWidth = 96.dp, minHeight = 44.dp),
            )
        }
    }
}
