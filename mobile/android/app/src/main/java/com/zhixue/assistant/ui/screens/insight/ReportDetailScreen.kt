package com.zhixue.assistant.ui.screens.insight

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.LoadingView
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalToast
import com.zhixue.assistant.ui.components.MarkdownText
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.util.fmtDateTime

@Composable
fun ReportDetailScreen(reportId: Long) {
    val container = LocalAppContainer.current
    val vm: ReportDetailViewModel = viewModel(factory = ReportDetailViewModel.factory(container))
    val state by vm.state.collectAsState()
    val downloading by vm.downloading.collectAsState()
    val downloadMessage by vm.downloadMessage.collectAsState()
    val toast = LocalToast.current
    val context = LocalContext.current

    LaunchedEffect(reportId) { vm.load(reportId) }
    LaunchedEffect(downloadMessage) {
        val text = downloadMessage
        if (text != null) {
            toast(text)
            vm.consumeDownloadMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val current = state) {
            is UiState.Loading -> LoadingView("正在加载报告…")
            is UiState.Error -> ErrorState(message = current.message, onRetry = { vm.load(reportId) })
            is UiState.Ready -> {
                val report = current.data
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        PageHeader(
                            title = report.title.ifBlank { "学习报告 #${report.id}" },
                            subtitle = buildString {
                                append("周期 ")
                                append(report.period.ifBlank { "—" })
                                val created = fmtDateTime(report.createdAt)
                                if (created.isNotBlank()) {
                                    append(" · 生成于 ")
                                    append(created)
                                }
                            },
                        )
                    }

                    item {
                        ZhixueCard(title = "报告正文") {
                            if (report.content.isBlank()) {
                                EmptyView("报告正文为空，可能是后端生成时未写入内容")
                            } else {
                                MarkdownText(report.content)
                            }
                        }
                    }

                    item {
                        ZhixueCard(title = "导出") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PrimaryButton(
                                    text = "下载 PDF",
                                    onClick = { vm.downloadPdf(context) },
                                    loading = downloading,
                                )
                                if (downloading) {
                                    Spacer(Modifier.width(12.dp))
                                    InlineLoading("正在生成 PDF…")
                                }
                            }
                            Text(
                                "PDF 由后端渲染，优先保存到系统「下载」目录；无法写入时自动转到分享面板。",
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalZhixuePalette.current.muted,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
