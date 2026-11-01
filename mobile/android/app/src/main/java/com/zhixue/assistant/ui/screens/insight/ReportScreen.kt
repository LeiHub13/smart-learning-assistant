package com.zhixue.assistant.ui.screens.insight

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.data.model.ReportDto
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.LoadingView
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavController
import com.zhixue.assistant.ui.components.MarkdownText
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.SelectField
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.nav.Routes
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.util.fmtDateTime

@Composable
fun ReportScreen() {
    val container = LocalAppContainer.current
    val vm: ReportViewModel = viewModel(factory = ReportViewModel.factory(container))
    val state by vm.state.collectAsState()
    val generating by vm.generating.collectAsState()
    val message by vm.message.collectAsState()

    Box(Modifier.fillMaxSize()) {
        when (val current = state) {
            is UiState.Loading -> LoadingView("正在加载学习报告…")
            is UiState.Error -> ErrorState(message = current.message, onRetry = { vm.load() })
            is UiState.Ready -> ReportContent(current.data, vm, generating, message)
        }
    }
}

@Composable
private fun ReportContent(
    data: ReportData,
    vm: ReportViewModel,
    generating: Boolean,
    message: UiMessage?,
) {
    val navController = LocalNavController.current
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
            Column {
                PageHeader(
                    title = "学习报告",
                    subtitle = "AI 聚合周期学习数据，生成 Markdown 周报并支持 PDF 导出",
                ) {
                    SecondaryButton("刷新", onClick = { vm.load() })
                }
                val banner = message
                if (banner != null) {
                    HintBanner(
                        text = banner.text,
                        tone = if (banner.error) BannerTone.Error else BannerTone.Success,
                        onDismiss = { vm.dismissMessage() },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        item {
            ZhixueCard(title = "生成周报") {
                SelectField(
                    label = "课程",
                    options = options,
                    selected = data.courseId.toString(),
                    onSelect = { vm.selectCourse(it) },
                )
                Spacer(Modifier.height(10.dp))
                PrimaryButton(text = "生成周报", onClick = { vm.generate() }, loading = generating)
                if (generating) {
                    Spacer(Modifier.height(6.dp))
                    InlineLoading("AI 正在汇总本周学习数据，请稍候…")
                }
            }
        }

        item {
            Text(
                "报告列表（${data.reports.size}）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (data.reports.isEmpty()) {
            item {
                ZhixueCard {
                    EmptyView(
                        title = "暂无报告",
                        hint = "选择课程后点击「生成周报」，AI 会汇总本周练习、掌握度与学习时长",
                    )
                }
            }
        } else {
            items(data.reports, key = { it.id }) { report ->
                ReportRow(
                    report = report,
                    expanded = data.expandedId == report.id,
                    onToggle = { vm.toggleExpanded(report.id) },
                    onOpenDetail = { navController.navigate(Routes.reportDetail(report.id)) },
                )
            }
        }
    }
}

@Composable
private fun ReportRow(
    report: ReportDto,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    val palette = LocalZhixuePalette.current
    ZhixueCard(onClick = onToggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (expanded) "▾" else "▸",
                color = palette.muted,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text(
                report.title.ifBlank { "学习报告 #${report.id}" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                fmtDateTime(report.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            MarkdownText(report.content)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SecondaryButton("查看详情与 PDF", onClick = onOpenDetail)
            }
        }
    }
}
