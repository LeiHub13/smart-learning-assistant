package com.zhixue.assistant.ui.screens.learn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.data.model.ExamRecordDto
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavController
import com.zhixue.assistant.ui.components.LocalToast
import com.zhixue.assistant.ui.components.LoadingView
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.StatTile
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.util.fmtDateTime

// 成绩单：本场成绩 + 逐题批改 + 我的记录。
@Composable
fun ExamReportScreen(recordId: Long) {
    val container = LocalAppContainer.current
    val navController = LocalNavController.current
    val toast = LocalToast.current
    val vm: ExamReportViewModel = viewModel(factory = ExamReportViewModel.factory(container))
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.init(recordId) }
    LaunchedEffect(Unit) { vm.navigate.collect { navController.navigate(it) } }
    LaunchedEffect(Unit) { vm.toast.collect { toast(it) } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { PageHeader(title = "成绩单", subtitle = "逐题判分、解析与 AI 批改") }
        when (val r = state.report) {
            is UiState.Loading -> item { LoadingView("加载成绩单…") }
            is UiState.Error -> item { ErrorState(r.message, onRetry = vm::load) }
            is UiState.Ready -> {
                val report = r.data
                val okCount = report.items.count { it.answer.correct == true }
                val rate = if (report.items.isEmpty()) 0 else okCount * 100 / report.items.size
                item {
                    ZhixueCard(title = report.exam.title, subtitle = fmtDateTime(report.record.submittedAt)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile(
                                value = "${report.record.score ?: 0} / ${report.record.totalScore ?: report.exam.totalScore}",
                                label = "得分 / 满分",
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(value = "$okCount / ${report.items.size}", label = "答对", modifier = Modifier.weight(1f))
                            StatTile(value = "$rate%", label = "正确率", modifier = Modifier.weight(1f))
                            val use = vm.useTimeText()
                            if (use.isNotBlank()) {
                                StatTile(value = use, label = "用时", modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (state.myRecords.size > 1) {
                    item {
                        Text(
                            "我的记录",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = LocalZhixuePalette.current.muted,
                        )
                    }
                    items(state.myRecords, key = { "rec_${it.id}" }) { rec -> RecordRow(vm, rec) }
                }
                itemsIndexed(state.items, key = { _, it -> "ri_${it.questionId}" }) { index, item ->
                    GradedQuestionCard(
                        index = index,
                        item = item,
                        busyFavorite = item.questionId in state.busyFavorite,
                        busyCollect = item.questionId in state.busyCollect,
                        collected = item.questionId in state.collected,
                        onFavorite = { vm.toggleFavorite(item.questionId) },
                        onCollect = { vm.collect(item.questionId, item.stem) },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun RecordRow(vm: ExamReportViewModel, record: ExamRecordDto) {
    ZhixueCard {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    fmtDateTime(record.startedAt),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    record.submittedAt?.let { "已交卷 · 得分 ${record.score ?: 0} / ${record.totalScore ?: 0}" }
                        ?: "未交卷（可续考）",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalZhixuePalette.current.muted,
                )
            }
            if (record.submittedAt != null) {
                SecondaryButton("查看成绩单", onClick = { vm.viewRecord(record.id) })
            } else {
                TagBadge("进行中", TagTone.Warn)
            }
        }
    }
}
