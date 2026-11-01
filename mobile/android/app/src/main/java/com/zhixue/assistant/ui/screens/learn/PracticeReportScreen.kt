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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavController
import com.zhixue.assistant.ui.components.LocalToast
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.StatTile
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.util.fmtDateTime

// 练习报告：得分/满分 + 逐题解析 + 收藏/收录 + 一键重练入口。
@Composable
fun PracticeReportScreen(practiceId: Long) {
    val container = LocalAppContainer.current
    val navController = LocalNavController.current
    val toast = LocalToast.current
    val vm: PracticeReportViewModel = viewModel(factory = PracticeReportViewModel.factory(container))
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.init(practiceId) }
    LaunchedEffect(Unit) { vm.navigate.collect { navController.navigate(it) } }
    LaunchedEffect(Unit) { vm.toast.collect { toast(it) } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { PageHeader(title = "练习报告", subtitle = "逐题判分、解析与 AI 批改") }
        when (val d = state.detail) {
            is UiState.Loading -> item { com.zhixue.assistant.ui.components.LoadingView("加载报告…") }
            is UiState.Error -> item { ErrorState(d.message, onRetry = vm::load) }
            is UiState.Ready -> {
                val detail = d.data
                val okCount = detail.items.count { it.pq.correct == true }
                val rate = if (detail.items.isEmpty()) 0 else (okCount * 100 / detail.items.size)
                item {
                    ZhixueCard(title = detail.title.ifBlank { "练习 #${detail.id}" }, subtitle = fmtDateTime(detail.createdAt)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatTile(value = "${detail.score} / ${detail.totalScore}", label = "得分 / 满分", modifier = Modifier.weight(1f))
                            StatTile(value = "$okCount / ${detail.items.size}", label = "答对", modifier = Modifier.weight(1f))
                            StatTile(value = "$rate%", label = "正确率", modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryButton("再做一组", onClick = vm::again, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SecondaryButton("错题一键重练", onClick = vm::retrainMistakes, modifier = Modifier.weight(1f))
                            SecondaryButton("收藏一键重练", onClick = vm::retrainFavorites, modifier = Modifier.weight(1f))
                        }
                    }
                }
                itemsIndexed(state.items, key = { _, it -> "item_${it.questionId}" }) { index, item ->
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
