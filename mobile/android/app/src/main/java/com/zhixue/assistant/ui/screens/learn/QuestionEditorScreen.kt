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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.core.PreferencesStore
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.LabeledField
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavController
import com.zhixue.assistant.ui.components.LocalToast
import com.zhixue.assistant.ui.components.LoadingView
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.SelectField
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.theme.LocalZhixuePalette

// 题目录入/编辑（questionId = -1 表示新建）。
@Composable
fun QuestionEditorScreen(questionId: Long) {
    val container = LocalAppContainer.current
    val navController = LocalNavController.current
    val toast = LocalToast.current
    val context = LocalContext.current
    val prefs = androidx.compose.runtime.remember(context) {
        PreferencesStore(context.applicationContext.getSharedPreferences("zhixue_prefs", android.content.Context.MODE_PRIVATE))
    }
    val vm: QuestionEditorViewModel = viewModel(factory = QuestionEditorViewModel.factory(container, prefs))
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.init(questionId) }
    LaunchedEffect(Unit) { vm.saved.collect { navController.popBackStack() } }
    LaunchedEffect(Unit) { vm.toast.collect { toast(it) } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PageHeader(
                title = if (state.isNew) "录入题目" else "编辑题目",
                subtitle = "客观题自动判分；问答题由 AI 按参考答案批改",
            )
        }
        if (state.loading || !state.coursesLoaded) {
            item { LoadingView("加载题目…") }
        }
        state.error?.let { err ->
            item { HintBanner(err, tone = BannerTone.Error, onDismiss = vm::dismissError) }
        }
        if (state.notFound) {
            item {
                ErrorState("未找到该题目，可能已被删除或无权访问", onRetry = vm::retryLoad)
            }
        }
        if (!state.loading && state.coursesLoaded && !state.notFound) {
            item {
                ZhixueCard(title = "基本信息") {
                    CourseSelect(
                        courses = state.courses,
                        selected = state.courseId,
                        onSelect = vm::setCourse,
                        enabled = !state.courseLocked,
                    )
                    if (state.courseLocked) {
                        Text(
                            "编辑不允许修改课程",
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalZhixuePalette.current.muted,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    SelectField(
                        label = "类型",
                        options = QuestionEditorConsts.TYPES.map { it to it },
                        selected = state.type,
                        onSelect = vm::setType,
                    )
                    Spacer(Modifier.height(8.dp))
                    SelectField(
                        label = "难度",
                        options = QuestionEditorConsts.DIFFS.map { it to it },
                        selected = state.difficulty,
                        onSelect = vm::setDifficulty,
                    )
                    Spacer(Modifier.height(8.dp))
                    LabeledField(
                        label = "知识点",
                        value = state.kpName,
                        onValueChange = vm::setKpName,
                        placeholder = "如：HashMap / 多线程",
                    )
                }
            }
            item {
                ZhixueCard(title = "题干与答案") {
                    LabeledField(
                        label = "题干",
                        value = state.stem,
                        onValueChange = vm::setStem,
                        placeholder = "题目内容",
                        singleLine = false,
                        minLines = 3,
                    )
                }
            }
            if (state.type == "单选" || state.type == "多选") {
                item {
                    ZhixueCard(title = "选项") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "选项（至少两个有效；字母自动编号可改）",
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalZhixuePalette.current.muted,
                                modifier = Modifier.weight(1f),
                            )
                            SecondaryButton("+ 加选项", onClick = vm::addOption)
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
                itemsIndexed(state.options, key = { i, _ -> "opt_$i" }) { index, row ->
                    ZhixueCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LabeledField(
                                label = "字母",
                                value = row.key,
                                onValueChange = { vm.setOptionKey(index, it) },
                                modifier = Modifier.width(110.dp),
                            )
                            LabeledField(
                                label = "内容",
                                value = row.text,
                                onValueChange = { vm.setOptionText(index, it) },
                                placeholder = "选项 ${row.key}",
                                modifier = Modifier.weight(1f),
                            )
                            SecondaryButton(
                                text = "删",
                                onClick = { vm.removeOption(index) },
                                enabled = state.options.size > 2,
                            )
                        }
                    }
                }
                item {
                    ZhixueCard {
                        if (state.type == "单选") {
                            SelectField(
                                label = "正确答案",
                                options = state.options.filter { it.key.isNotBlank() }.map { it.key to "${it.key}. ${it.text}" },
                                selected = state.answer,
                                onSelect = vm::setAnswer,
                                emptyLabel = "如：A",
                            )
                        } else {
                            LabeledField(
                                label = "正确答案",
                                value = state.answer,
                                onValueChange = vm::setAnswer,
                                placeholder = "如：ABD（按选项字母拼接）",
                                helper = "多选答案由所选字母拼接，须全部来自已有选项",
                            )
                        }
                    }
                }
            }
            if (state.type == "判断") {
                item {
                    ZhixueCard {
                        SelectField(
                            label = "答案（判断题选项固定 对/错）",
                            options = listOf("对" to "对", "错" to "错"),
                            selected = state.answer.takeIf { it == "对" || it == "错" } ?: "对",
                            onSelect = vm::setAnswer,
                        )
                    }
                }
            }
            if (state.type == "问答") {
                item {
                    ZhixueCard {
                        LabeledField(
                            label = "参考答案",
                            value = state.answer,
                            onValueChange = vm::setAnswer,
                            placeholder = "参考答案内容",
                            singleLine = false,
                            minLines = 3,
                        )
                    }
                }
            }
            item {
                ZhixueCard(title = "解析") {
                    LabeledField(
                        label = "解析",
                        value = state.analysis,
                        onValueChange = vm::setAnalysis,
                        placeholder = "解题思路（可选）",
                        singleLine = false,
                        minLines = 3,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("取消", onClick = { navController.popBackStack() })
                    PrimaryButton(
                        text = if (state.saving) "保存中…" else "保存",
                        onClick = vm::save,
                        loading = state.saving,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private object QuestionEditorConsts {
    val TYPES = listOf("单选", "多选", "判断", "问答")
    val DIFFS = listOf("基础", "进阶", "综合")
}
