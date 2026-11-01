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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.core.PreferencesStore
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.data.model.QuestionDto
import com.zhixue.assistant.ui.components.ConfirmDialog
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.LabeledField
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavController
import com.zhixue.assistant.ui.components.LocalToast
import com.zhixue.assistant.ui.components.LoadingView
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.SelectField
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.util.fmtDateTime

// 题库管理：筛选 + 分页 + 编辑/删除/收录错题本；课程选择记忆在本地偏好。
@Composable
fun QuestionBankScreen() {
    val container = LocalAppContainer.current
    val navController = LocalNavController.current
    val toast = LocalToast.current
    val context = LocalContext.current
    val prefs = androidx.compose.runtime.remember(context) {
        PreferencesStore(context.applicationContext.getSharedPreferences("zhixue_prefs", android.content.Context.MODE_PRIVATE))
    }
    val vm: BankViewModel = viewModel(factory = BankViewModel.factory(container, prefs))
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.init() }
    LaunchedEffect(Unit) { vm.navigate.collect { navController.navigate(it) } }
    LaunchedEffect(Unit) { vm.toast.collect { toast(it) } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PageHeader(title = "题库管理", subtitle = "查看、录入、编辑课程题目；AI 生成的题目自动入库，可在此维护")
        }
        item {
            ZhixueCard {
                CourseSelect(
                    courses = state.courses,
                    selected = state.courseId,
                    onSelect = vm::setCourse,
                    includeAll = false,
                )
                Spacer(Modifier.height(8.dp))
                SelectField(
                    label = "类型",
                    options = listOf("" to "全部") + QuestionBankScreenConsts.TYPES.map { it to it },
                    selected = state.type ?: "",
                    onSelect = { vm.setType(it.takeIf { k -> k.isNotBlank() }) },
                )
                Spacer(Modifier.height(8.dp))
                SelectField(
                    label = "难度",
                    options = listOf("" to "全部") + QuestionBankScreenConsts.DIFFS.map { it to it },
                    selected = state.difficulty ?: "",
                    onSelect = { vm.setDifficulty(it.takeIf { k -> k.isNotBlank() }) },
                )
                Spacer(Modifier.height(8.dp))
                LabeledField(
                    label = "关键词",
                    value = state.keyword,
                    onValueChange = vm::setKeyword,
                    placeholder = "题干 / 知识点",
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("搜索", onClick = vm::search)
                    PrimaryButton(
                        text = "+ 录入题目",
                        onClick = vm::createQuestion,
                        enabled = state.courseId != null,
                    )
                }
            }
        }
        item {
            Text(
                "共 ${state.total} 题",
                style = MaterialTheme.typography.bodySmall,
                color = LocalZhixuePalette.current.muted,
            )
        }
        when (val r = state.records) {
            is UiState.Loading -> item { LoadingView("加载题库…") }
            is UiState.Error -> item { ErrorState(r.message, onRetry = vm::retry) }
            is UiState.Ready -> {
                if (r.data.isEmpty() && state.courseId != null) {
                    item { EmptyView(title = "本课程暂无符合条件的题目") }
                } else {
                    items(r.data, key = { "q_${it.id}" }) { q -> BankQuestionRow(vm, state, q) }
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

    state.confirmDelete?.let { target ->
        ConfirmDialog(
            title = "删除题目",
            body = "确定删除这道「${target.type}」题吗？若已被练习/考试记录或收藏引用将无法删除。",
            confirmText = "确认删除",
            danger = true,
            onConfirm = vm::confirmDelete,
            onDismiss = vm::cancelDelete,
        )
    }
}

private object QuestionBankScreenConsts {
    val TYPES = listOf("单选", "多选", "判断", "问答")
    val DIFFS = listOf("基础", "进阶", "综合")
}

@Composable
private fun BankQuestionRow(vm: BankViewModel, state: BankViewModel.State, q: QuestionDto) {
    val palette = LocalZhixuePalette.current
    ZhixueCard {
        Text(q.stem, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        QuestionTagRow(type = q.type, difficulty = q.difficulty, kp = q.kpName, extra = listOf(sourceName(q.source) to TagTone.Neutral))
        Spacer(Modifier.height(4.dp))
        Text(
            "创建于 ${fmtDateTime(q.createdAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                q.id in state.collected -> TagBadge("已收录错题本", TagTone.Success)
                q.id in state.busyCollect -> InlineLoading("收录中…")
                else -> SecondaryButton("收录错题本", onClick = { vm.collect(q) }, modifier = Modifier.defaultMinSize(minHeight = 44.dp))
            }
            Spacer(Modifier.weight(1f))
            SecondaryButton("编辑", onClick = { vm.openEditor(q.id) }, modifier = Modifier.defaultMinSize(minHeight = 44.dp))
            SecondaryButton("删除", onClick = { vm.requestDelete(q) }, danger = true, modifier = Modifier.defaultMinSize(minHeight = 44.dp))
        }
    }
}

private fun sourceName(source: String?): String = when (source) {
    "AI" -> "AI 生成"
    "SEED" -> "内置"
    "手动" -> "手动"
    null -> "-"
    else -> source
}
