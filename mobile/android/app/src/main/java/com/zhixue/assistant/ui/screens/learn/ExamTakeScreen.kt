package com.zhixue.assistant.ui.screens.learn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavController
import com.zhixue.assistant.ui.components.LoadingView
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.theme.LocalZhixuePalette

// 考试作答：倒计时基于 deadlineAt，1 秒一跳；不自动交卷，超时仍允许提交。
@Composable
fun ExamTakeScreen(examId: Long) {
    val container = LocalAppContainer.current
    val navController = LocalNavController.current
    val vm: ExamTakeViewModel = viewModel(factory = ExamTakeViewModel.factory(container))
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.init(examId) }
    LaunchedEffect(Unit) { vm.navigate.collect { navController.navigate(it) } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (val c = state.content) {
            is UiState.Loading -> item { LoadingView("进入考试…") }
            is UiState.Error -> item { ErrorState(c.message, onRetry = vm::load) }
            is UiState.Ready -> {
                val exam = c.data.first
                val questions = c.data.second
                item {
                    ZhixueCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${exam.title} · ${questions.size} 题 · 共 ${exam.totalScore} 分",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "已答 ${vm.answeredCount()}/${questions.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LocalZhixuePalette.current.muted,
                                )
                            }
                            Text(
                                vm.remainText(),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (state.timedOut || state.urgent) LocalZhixuePalette.current.danger else LocalZhixuePalette.current.text,
                            )
                        }
                        if (state.timedOut) {
                            Spacer(Modifier.height(8.dp))
                            HintBanner("已超时：不自动交卷，超时提交仍受理", tone = BannerTone.Warn)
                        }
                    }
                }
                state.submitError?.let { err ->
                    item { HintBanner(err, tone = BannerTone.Error, onDismiss = vm::dismissSubmitError) }
                }
                itemsIndexed(questions, key = { _, q -> "eq_${q.id}" }) { index, q ->
                    ExamQuestionCard(vm, index, q, state.answers[q.id].orEmpty())
                }
                item {
                    PrimaryButton(
                        text = if (state.submitting) "批改中…" else "交卷",
                        onClick = vm::submit,
                        loading = state.submitting,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ExamQuestionCard(vm: ExamTakeViewModel, index: Int, q: PaperItem, value: String) {
    ZhixueCard {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                "${index + 1}. 【${q.type}】${q.stem}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            q.score?.let { TagBadge("$it 分", TagTone.Neutral) }
        }
        if (!q.kpName.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            TagBadge(q.kpName, TagTone.Info)
        }
        Spacer(Modifier.height(8.dp))
        AnswerEditor(
            type = q.type,
            optionsRaw = q.options,
            value = value,
            onChange = { vm.setAnswer(q.id, it) },
        )
    }
}
