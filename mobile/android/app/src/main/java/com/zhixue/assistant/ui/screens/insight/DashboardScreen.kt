package com.zhixue.assistant.ui.screens.insight

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.data.model.RecommendItem
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.ChartPoint
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.LineChart
import com.zhixue.assistant.ui.components.LineSeries
import com.zhixue.assistant.ui.components.LoadingView
import com.zhixue.assistant.ui.components.LocalNavController
import com.zhixue.assistant.ui.components.LocalNavigator
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.StatTile
import com.zhixue.assistant.ui.components.StudyHeatmap
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.components.cardShape
import com.zhixue.assistant.ui.nav.Routes
import com.zhixue.assistant.ui.theme.LocalIsDark
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.ui.theme.trendPalette
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import com.zhixue.assistant.util.ChartMath

@Composable
fun DashboardScreen() {
    val container = LocalAppContainer.current
    val vm: DashboardViewModel = viewModel(factory = DashboardViewModel.factory(container))
    val state by vm.state.collectAsState()

    Box(Modifier.fillMaxSize()) {
        when (val current = state) {
            is UiState.Loading -> LoadingView("正在加载首页数据…")
            is UiState.Error -> ErrorState(message = current.message, onRetry = { vm.reload() })
            is UiState.Ready -> DashboardContent(current.data, onRetry = { vm.reload() })
        }
    }
}

@Composable
private fun DashboardContent(data: DashboardData, onRetry: () -> Unit) {
    val palette = LocalZhixuePalette.current
    val dark = LocalIsDark.current
    val colors = remember(dark) { trendPalette(dark) }
    val navigator = LocalNavigator.current
    val navController = LocalNavController.current
    val minuteMap = remember(data.study) { data.study.calendar.associate { it.date to it.minutes } }
    val streak = remember(minuteMap) { ChartMath.streakDays(minuteMap) }
    val bestDay = remember(minuteMap) { ChartMath.bestDay(minuteMap) }
    val heatTotal = remember(minuteMap) { ChartMath.totalMinutes(minuteMap) }
    val heatActive = remember(minuteMap) { ChartMath.activeDays(minuteMap) }
    val expandedSnippets = remember { mutableStateMapOf<Int, Boolean>() }
    var failureDismissed by remember(data.partialFailure) { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                PageHeader(
                    title = "你好，${data.greetingName}",
                    subtitle = "今天也保持学习节奏 —— 概览、推荐与快捷入口",
                ) {
                    if (data.unreadCount > 0) {
                        TagBadge("未读 ${data.unreadCount}", TagTone.Danger)
                        Spacer(Modifier.width(8.dp))
                    }
                    SecondaryButton("刷新", onClick = onRetry)
                }
                if (data.partialFailure != null && !failureDismissed) {
                    HintBanner(
                        text = data.partialFailure,
                        tone = BannerTone.Warn,
                        onDismiss = { failureDismissed = true },
                    )
                }
            }
        }

        item {
            ZhixueCard(title = "数据概览") {
                val tiles = listOf(
                    humanMinutes(data.study.totalMinutes) to "累计学习",
                    "${data.study.activeDays}" to "活跃天数",
                    "$streak 天" to "连续学习",
                    "${data.practiceCount}" to "练习次数",
                    "${data.favoriteCount}" to "收藏题目",
                    "${data.mistakeCount}" to "待攻克错题",
                    "${data.dueCardCount}" to "今日到期闪卡",
                )
                tiles.chunked(3).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { (value, label) ->
                            StatTile(value, label, modifier = Modifier.weight(1f))
                        }
                        // 最后一行可能不满 3 个，补空位保持等宽
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        item {
            ZhixueCard(title = "快捷入口") {
                val entries = listOf(
                    "练习" to Routes.practice(),
                    "错题本" to Routes.MISTAKES,
                    "收藏" to Routes.FAVORITES,
                    "闪卡" to Routes.FLASHCARDS,
                    "考试" to Routes.EXAM,
                    "答疑" to Routes.chat(),
                    "学情" to Routes.PROGRESS,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    entries.chunked(3).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { (label, route) ->
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .background(palette.soft, RoundedCornerShape(8.dp))
                                        .clickable { navController.navigate(route) { launchSingleTop = true } }
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(label, color = palette.primary, fontWeight = FontWeight.Medium)
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        item {
            ZhixueCard(title = "最近成绩曲线", subtitle = "图例可点选隐藏某门课程") {
                if (data.trends.isEmpty()) {
                    EmptyView("暂无练习数据，去「题库练习」做一组题后生成")
                } else {
                    LineChart(
                        series = data.trends.mapIndexed { index, trend ->
                            LineSeries(
                                key = trend.courseId.toString(),
                                name = trend.courseName,
                                color = colors[index % colors.size],
                                points = trend.points.map { ChartPoint(it.date, it.rate) },
                            )
                        },
                        yMax = 100.0,
                        valueSuffix = "%",
                        emptyText = "暂无练习数据，去「题库练习」做一组题后生成",
                    )
                }
            }
        }

        item {
            ZhixueCard(title = "学习热力图（近 12 周）") {
                if (data.study.calendar.isEmpty()) {
                    EmptyView("暂无课程时长数据（页面停留期间每分钟自动记录）")
                } else {
                    Text(
                        "总计 ${humanMinutes(heatTotal)} · 活跃 $heatActive 天",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                    )
                    bestDay?.let { (date, minutes) ->
                        Text(
                            "最卷一天：$date（$minutes 分）",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted,
                            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                        )
                    }
                    StudyHeatmap(cells = data.study.calendar, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        item {
            Text(
                "今日推荐",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (data.recommend.isEmpty()) {
            item {
                ZhixueCard {
                    EmptyView("暂无推荐，做一组练习后生成")
                }
            }
        } else {
            itemsIndexed(data.recommend) { index, item ->
                RecommendCard(
                    item = item,
                    expanded = expandedSnippets[index] == true,
                    onToggleSnippet = { expandedSnippets[index] = expandedSnippets[index] != true },
                    open = { route -> navigator(route) },
                )
            }
        }
    }
}

@Composable
private fun RecommendCard(
    item: RecommendItem,
    expanded: Boolean,
    onToggleSnippet: () -> Unit,
    open: (String) -> Unit,
) {
    val palette = LocalZhixuePalette.current
    val (typeLabel, tone) = when (item.type) {
        "startup" -> "起步引导" to TagTone.Info
        "review_kp" -> "待复习" to TagTone.Warn
        "practice_kp" -> "专项练习" to TagTone.Info
        "document", "related_doc" -> "相关资料" to TagTone.Success
        else -> "推荐" to TagTone.Neutral
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape,
        color = palette.card,
        border = BorderStroke(1.dp, palette.border),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TagBadge(typeLabel, tone)
                Spacer(Modifier.width(8.dp))
                Text(
                    item.title,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (!item.reason.isNullOrBlank()) {
                Text(
                    item.reason.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (!item.kpName.isNullOrBlank()) {
                Text(
                    "知识点：${item.kpName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item.action?.takeIf { it.isNotBlank() }?.let { action ->
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton(
                        text = if (item.type == "startup") "去摸底 →" else "去做题 →",
                        onClick = { open(action) },
                    )
                }
            }
            item.snippet?.takeIf { it.isNotBlank() }?.let { snippet ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clickable(onClick = onToggleSnippet),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (expanded) "收起资料片段" else "展开资料片段",
                        color = palette.primary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (expanded) {
                    Text(
                        snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .background(palette.soft, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}
