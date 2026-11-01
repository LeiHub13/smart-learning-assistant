package com.zhixue.assistant.ui.screens.learn

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import com.zhixue.assistant.data.model.FavoriteItem
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavController
import com.zhixue.assistant.ui.components.LocalToast
import com.zhixue.assistant.ui.components.LoadingView
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.theme.LocalZhixuePalette

// 收藏夹：收藏的好题集中回顾，支持一键重练。
@Composable
fun FavoritesScreen() {
    val container = LocalAppContainer.current
    val navController = LocalNavController.current
    val toast = LocalToast.current
    val vm: FavoritesViewModel = viewModel(factory = FavoritesViewModel.factory(container))
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.init() }
    LaunchedEffect(Unit) { vm.navigate.collect { navController.navigate(it) } }
    LaunchedEffect(Unit) { vm.toast.collect { toast(it) } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PageHeader(title = "收藏夹", subtitle = "收藏的好题集中回顾，支持一键重练")
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
                        "${state.count} 道收藏题目",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = LocalZhixuePalette.current.primaryDeep,
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton("一键重练", onClick = vm::goRetrain, enabled = state.count > 0)
                }
            }
        }
        when (val r = state.items) {
            is UiState.Loading -> item { LoadingView("加载收藏…") }
            is UiState.Error -> item { ErrorState(r.message, onRetry = vm::retry) }
            is UiState.Ready -> {
                if (r.data.isEmpty()) {
                    item {
                        ZhixueCard {
                            EmptyView(
                                title = if (state.courseId != null) "该课程暂无收藏" else "暂无收藏",
                                hint = if (state.courseId != null) "切换课程或去练习页收藏好题" else "在练习/考试报告页点击收藏，好题会集中到这里",
                            )
                        }
                    }
                } else {
                    itemsIndexed(r.data, key = { _, it -> "fav_${it.questionId}" }) { index, item ->
                        FavoriteCard(vm, state, item, index)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun FavoriteCard(
    vm: FavoritesViewModel,
    state: FavoritesViewModel.State,
    item: FavoriteItem,
    index: Int,
) {
    val palette = LocalZhixuePalette.current
    // 收藏的题可能从未做过：仅在有作答记录时展示「你的答案」
    val userAnswer = if (item.lastAnswer != null) item.lastAnswer else ""
    ZhixueCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TagBadge("#${index + 1}", TagTone.Info)
            if (state.courseId == null && !item.courseName.isNullOrBlank()) {
                TagBadge(item.courseName.orEmpty(), TagTone.Neutral)
            }
            if (item.questionId in state.collected) {
                TagBadge("已收录", TagTone.Success)
            } else if (item.questionId in state.busyCollect) {
                InlineLoading("收录中…")
            } else {
                SecondaryButton(
                    "收录错题本",
                    onClick = { vm.collect(item) },
                    modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (item.questionId in state.busyRemove) {
                InlineLoading("移除中…")
            } else {
                SecondaryButton(
                    "取消收藏",
                    onClick = { vm.remove(item.questionId) },
                    modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.stem, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        QuestionTagRow(type = item.type, kp = item.kpName, difficulty = item.difficulty)
        OptionsComparison(item.options, item.answer, userAnswer)
        AnswerCompareRows(userAnswer.takeIf { it.isNotBlank() }, item.answer)
        if (item.lastCorrect != null && userAnswer.isNotBlank()) {
            HintBanner(
                text = if (item.lastCorrect == true) "上次作答：正确" else "上次作答：错误",
                tone = if (item.lastCorrect == true) BannerTone.Success else BannerTone.Error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        val expanded = item.questionId in state.expanded
        if (!item.analysis.isNullOrBlank()) {
            SecondaryButton(if (expanded) "收起解析" else "查看解析", onClick = { vm.toggleExpand(item.questionId) })
            if (expanded) {
                Text(
                    item.analysis.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .background(palette.soft, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                )
            }
        }
    }
}
