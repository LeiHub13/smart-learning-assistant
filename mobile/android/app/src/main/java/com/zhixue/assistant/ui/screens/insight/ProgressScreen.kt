package com.zhixue.assistant.ui.screens.insight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.data.model.MasteryDto
import com.zhixue.assistant.data.model.WrongRow
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.LoadingView
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.MarkdownText
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.ProgressBar
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.SelectField
import com.zhixue.assistant.ui.components.StatTile
import com.zhixue.assistant.ui.components.StudyHeatmap
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.util.friendlyTime

@Composable
fun ProgressScreen() {
    val container = LocalAppContainer.current
    val vm: ProgressViewModel = viewModel(factory = ProgressViewModel.factory(container))
    val state by vm.state.collectAsState()
    val adviceLoading by vm.adviceLoading.collectAsState()
    val adviceError by vm.adviceError.collectAsState()

    Box(Modifier.fillMaxSize()) {
        when (val current = state) {
            is UiState.Loading -> LoadingView("正在加载学情数据…")
            is UiState.Error -> ErrorState(message = current.message, onRetry = { vm.load() })
            is UiState.Ready -> ProgressContent(current.data, adviceLoading, adviceError, vm)
        }
    }
}

@Composable
private fun ProgressContent(
    data: ProgressData,
    adviceLoading: Boolean,
    adviceError: String?,
    vm: ProgressViewModel,
) {
    val summary = data.summary
    // 没有课程时也要能查询：后端按默认课程 1 计算
    val options = remember(data.courses) {
        if (data.courses.isEmpty()) listOf(NO_COURSE_KEY to "默认课程")
        else courseOptions(data.courses)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageHeader(
                title = "学情分析",
                subtitle = "基于练习记录分析知识点掌握度，AI 生成复习建议",
            ) {
                SecondaryButton("刷新", onClick = { vm.load() })
            }
        }

        item {
            ZhixueCard(title = "统计范围") {
                SelectField(
                    label = "课程",
                    options = options,
                    selected = data.courseId.toString(),
                    onSelect = { vm.selectCourse(it) },
                )
            }
        }

        item {
            ZhixueCard(title = "总体指标") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("${summary.averageMastery}%", "平均掌握度", Modifier.weight(1f))
                    StatTile("${summary.totalAttempts}", "累计答题", Modifier.weight(1f))
                    StatTile("${summary.wrongBook.size}", "错题数", Modifier.weight(1f))
                }
            }
        }

        item {
            AdviceCard(
                advice = summary.advice.orEmpty(),
                adviceCached = summary.adviceCached == true,
                hasPractice = summary.masteries.isNotEmpty(),
                loading = adviceLoading,
                error = adviceError,
                onRefresh = { vm.refreshAdvice() },
                onDismissError = { vm.dismissAdviceError() },
            )
        }

        item {
            Text(
                "知识点掌握度",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (summary.masteries.isEmpty()) {
            item {
                ZhixueCard {
                    EmptyView("暂无练习数据，去「题库练习」做一组题后生成")
                }
            }
        } else {
            items(summary.masteries, key = { it.id }) { mastery ->
                MasteryCard(mastery)
            }
        }

        item {
            Text(
                "错题本",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (summary.wrongBook.isEmpty()) {
            item {
                ZhixueCard { EmptyView(title = "太棒了，暂无错题") }
            }
        } else {
            itemsIndexed(summary.wrongBook, key = { index, _ -> index }) { index, row ->
                WrongCard(index = index + 1, row = row)
            }
        }

        item {
            StudyTimeCard(data)
        }
    }
}

@Composable
private fun StudyTimeCard(data: ProgressData) {
    val palette = LocalZhixuePalette.current
    ZhixueCard(title = "学习时长（近 12 周）") {
        val study = data.study
        if (study == null) {
            if (data.studyError != null) {
                HintBanner(text = data.studyError, tone = BannerTone.Warn)
            } else {
                EmptyView("暂无课程时长数据（页面停留期间每分钟自动记录）")
            }
            return@ZhixueCard
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(humanMinutes(study.totalMinutes), "累计时长", Modifier.weight(1f))
            StatTile("${study.activeDays}", "活跃天数", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        if (study.calendar.isEmpty()) {
            EmptyView("暂无课程时长数据（页面停留期间每分钟自动记录）")
        } else {
            StudyHeatmap(cells = study.calendar)
        }
        Spacer(Modifier.height(10.dp))
        if (study.byCourse.isEmpty()) {
            EmptyView("暂无课程时长数据（页面停留期间每分钟自动记录）")
        } else {
            Text(
                "各课程累计",
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            study.byCourse.forEach { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(palette.soft, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.courseName ?: "未归属课程",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(humanMinutes(row.minutes), style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun AdviceCard(
    advice: String,
    adviceCached: Boolean,
    hasPractice: Boolean,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
) {
    val palette = LocalZhixuePalette.current
    ZhixueCard(
        title = "AI 复习建议",
        actions = {
            if (adviceCached) {
                TagBadge("缓存·7天内有效", TagTone.Info)
                Spacer(Modifier.width(8.dp))
            }
            if (hasPractice) {
                SecondaryButton(
                    text = when {
                        loading -> "AI 分析中…"
                        advice.isNotBlank() -> "重新生成"
                        else -> "开始分析"
                    },
                    onClick = onRefresh,
                    enabled = !loading,
                )
            }
        },
    ) {
        if (error != null) {
            HintBanner(text = error, tone = BannerTone.Error, onDismiss = onDismissError)
            Spacer(Modifier.height(8.dp))
        }
        when {
            advice.isNotBlank() -> {
                MarkdownText(advice)
                if (loading) {
                    Spacer(Modifier.height(8.dp))
                    InlineLoading("正在生成新的建议，旧建议暂时保留…")
                }
            }
            loading -> InlineLoading("AI 正在分析学情，请稍候…")
            !hasPractice -> Text(
                "暂无练习数据，去「题库练习」做一组题后再来分析",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
            )
            else -> Text(
                "尚未分析，点击右上角「开始分析」，AI 将基于你的掌握度数据给出复习建议",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
            )
        }
    }
}

@Composable
private fun MasteryCard(mastery: MasteryDto) {
    val palette = LocalZhixuePalette.current
    val color = when {
        mastery.percent >= 80 -> palette.success
        mastery.percent >= 60 -> palette.warn
        else -> palette.danger
    }
    ZhixueCard {
        ProgressBar(percent = mastery.percent, color = color, label = mastery.kpName)
        Spacer(Modifier.height(6.dp))
        Text(
            "${mastery.correctCount}/${mastery.attempts} 对" +
                (mastery.lastPracticeAt?.let { " · 上次练习 ${friendlyTime(it)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
        )
    }
}

@Composable
private fun WrongCard(index: Int, row: WrongRow) {
    val palette = LocalZhixuePalette.current
    ZhixueCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#$index", style = MaterialTheme.typography.labelLarge, color = palette.danger, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            if (!row.kpName.isNullOrBlank()) {
                TagBadge(row.kpName.orEmpty(), TagTone.Danger)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                row.wrongAt?.let { friendlyTime(it) }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
            )
        }
        Text(
            row.stem,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .background(palette.soft, RoundedCornerShape(8.dp))
                .padding(10.dp),
        )
        AnswerBlock(label = "我的答案", text = row.userAnswer?.takeIf { it.isNotBlank() } ?: "未作答", good = false)
        Spacer(Modifier.height(6.dp))
        AnswerBlock(label = "参考答案", text = row.answer.orEmpty(), good = true)
    }
}

@Composable
private fun AnswerBlock(label: String, text: String, good: Boolean) {
    val palette = LocalZhixuePalette.current
    val bg = if (good) palette.okSoft else palette.badSoft
    val fg = if (good) palette.success else palette.danger
    Column(
        Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(text.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
    }
}
