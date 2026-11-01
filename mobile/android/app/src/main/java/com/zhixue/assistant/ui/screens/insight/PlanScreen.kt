package com.zhixue.assistant.ui.screens.insight

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.data.model.CalendarCell
import com.zhixue.assistant.data.model.PlanDetail
import com.zhixue.assistant.data.model.PlanDto
import com.zhixue.assistant.data.model.PlanTaskDto
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.ConfirmDialog
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.LabeledField
import com.zhixue.assistant.ui.components.LoadingView
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.ProgressBar
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.SelectField
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.theme.LocalIsDark
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.util.fmtDate
import com.zhixue.assistant.util.fmtDateTime

@Composable
fun PlanScreen() {
    val container = LocalAppContainer.current
    val vm: PlanViewModel = viewModel(factory = PlanViewModel.factory(container))
    val state by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    val checking by vm.checkingTaskIds.collectAsState()
    val message by vm.message.collectAsState()
    var pendingDelete by remember { mutableStateOf<PlanDto?>(null) }

    Box(Modifier.fillMaxSize()) {
        when (val current = state) {
            is UiState.Loading -> LoadingView("正在加载学习计划…")
            is UiState.Error -> ErrorState(message = current.message, onRetry = { vm.load() })
            is UiState.Ready -> PlanContent(
                data = current.data,
                vm = vm,
                busy = busy,
                checking = checking,
                message = message,
                askDelete = { pendingDelete = it },
            )
        }
    }

    pendingDelete?.let { plan ->
        ConfirmDialog(
            title = "删除计划",
            body = "删除「${plan.goal}」及其全部打卡记录？此操作不可恢复。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                pendingDelete = null
                vm.deletePlan(plan.id)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun PlanContent(
    data: PlanData,
    vm: PlanViewModel,
    busy: Boolean,
    checking: Set<Long>,
    message: UiMessage?,
    askDelete: (PlanDto) -> Unit,
) {
    val goal by vm.goal.collectAsState()
    val days by vm.days.collectAsState()
    val courseKey by vm.courseKey.collectAsState()
    val options = remember(data.courses) {
        if (data.courses.isEmpty()) listOf(NO_COURSE_KEY to "不指定课程")
        else courseOptions(data.courses, includeAll = "不指定课程")
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                PageHeader(
                    title = "学习计划",
                    subtitle = "AI 根据你的目标与掌握度生成逐日计划，每日打卡跟踪进度",
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

        item { CalendarCard(data) { delta -> vm.shiftMonth(delta) } }

        item {
            ZhixueCard(title = "创建新计划") {
                LabeledField(
                    label = "学习目标",
                    value = goal,
                    onValueChange = { vm.setGoal(it) },
                    placeholder = "如：掌握 Java 集合框架",
                    singleLine = false,
                    minLines = 2,
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        LabeledField(
                            label = "天数（1-30）",
                            value = days,
                            onValueChange = { vm.setDays(it) },
                            keyboardType = KeyboardType.Number,
                            helper = "超出范围后端按 7 天生成",
                        )
                    }
                    Box(Modifier.weight(2f)) {
                        SelectField(label = "课程", options = options, selected = courseKey, onSelect = { vm.setCourse(it) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                PrimaryButton(text = "生成计划", onClick = { vm.createPlan() }, loading = busy)
                if (busy) {
                    Spacer(Modifier.height(6.dp))
                    InlineLoading("AI 正在生成逐日计划，可能需要十几秒…")
                }
            }
        }

        item {
            Text(
                "我的计划（${data.plans.size}）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (data.plans.isEmpty()) {
            item {
                ZhixueCard {
                    EmptyView(
                        title = "暂无学习计划",
                        hint = "填写目标与天数，AI 会结合你的掌握度生成逐日计划",
                    )
                }
            }
        } else {
            data.plans.forEach { plan ->
                item(key = "plan-${plan.id}") {
                    PlanHeaderCard(
                        plan = plan,
                        detail = data.details[plan.id],
                        expanded = data.openPlanId == plan.id,
                        onToggle = { vm.toggle(plan.id) },
                    )
                }
                if (data.openPlanId == plan.id) {
                    val detail = data.details[plan.id]
                    if (detail == null) {
                        item(key = "plan-loading-${plan.id}") {
                            ZhixueCard { InlineLoading("正在加载计划详情…") }
                        }
                    } else {
                        val groups = PlanViewModel.groupTasks(detail.tasks)
                        items(groups, key = { "group-${plan.id}-${it.dayNo}-${it.taskDate}" }) { group ->
                            TaskGroupCard(group = group, checking = checking, onCheckIn = { vm.checkIn(it) })
                        }
                        item(key = "plan-footer-${plan.id}") {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                SecondaryButton("删除计划", onClick = { askDelete(plan) }, danger = true)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanHeaderCard(
    plan: PlanDto,
    detail: PlanDetail?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val palette = LocalZhixuePalette.current
    ZhixueCard(onClick = onToggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (expanded) "▾" else "▸",
                color = palette.muted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                plan.goal,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TagBadge(
                text = if (plan.status == "done") "已完成" else "进行中",
                tone = if (plan.status == "done") TagTone.Success else TagTone.Info,
            )
        }
        Text(
            "${plan.days} 天" + (plan.createdAt?.let { " · 创建于 ${fmtDate(it)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (expanded && detail != null) {
            Spacer(Modifier.height(10.dp))
            ProgressBar(percent = detail.progressPercent(), label = "完成 ${detail.doneCount}/${detail.tasks.size} 项任务")
        }
    }
}

@Composable
private fun TaskGroupCard(
    group: PlanTaskGroup,
    checking: Set<Long>,
    onCheckIn: (PlanTaskDto) -> Unit,
) {
    val palette = LocalZhixuePalette.current
    ZhixueCard(
        title = buildString {
            append("第 ")
            append(group.dayNo?.toString() ?: "—")
            append(" 天")
            if (!group.taskDate.isNullOrBlank()) append(" · ${group.taskDate}")
        }
    ) {
        group.tasks.forEach { task ->
            val inFlight = checking.contains(task.id)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(
                    checked = task.done == true,
                    onCheckedChange = { onCheckIn(task) },
                    enabled = !inFlight,
                )
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (task.done == true) palette.muted else palette.text,
                    )
                    task.taskLines.forEach { line ->
                        Text(
                            "· $line",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (!task.focusKp.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        TagBadge("重点：${task.focusKp}", TagTone.Neutral)
                    }
                    if (inFlight) {
                        Spacer(Modifier.height(4.dp))
                        InlineLoading("提交中…")
                    } else if (task.done == true && !task.doneAt.isNullOrBlank()) {
                        Text(
                            "打卡于 ${fmtDateTime(task.doneAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.success,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------- 打卡日历 ----------

private data class CalendarDay(
    val date: String?,
    val dayOfMonth: Int?,
    val total: Int,
    val done: Int,
)

/** 周一起排：前置空格 + 当月每天 + 末尾补空，保证整周对齐。 */
private fun buildCalendarMonth(month: String, cells: List<CalendarCell>): List<CalendarDay> {
    val yearMonth = monthKeyOf(month) ?: return emptyList()
    val byDate = cells.associateBy { it.date }
    val leading = (yearMonth.atDay(1).dayOfWeek.value + 6) % 7
    val out = MutableList(leading) { CalendarDay(null, null, 0, 0) }
    for (day in 1..yearMonth.lengthOfMonth()) {
        val date = yearMonth.atDay(day).toString()
        val cell = byDate[date]
        out += CalendarDay(date, day, cell?.total ?: 0, cell?.done ?: 0)
    }
    while (out.size % 7 != 0) out += CalendarDay(null, null, 0, 0)
    return out
}

@Composable
private fun CalendarCard(data: PlanData, onShift: (Int) -> Unit) {
    val palette = LocalZhixuePalette.current
    val dark = LocalIsDark.current
    val days = remember(data.month, data.calendar) { buildCalendarMonth(data.month, data.calendar) }
    var selected by remember(data.month) { mutableStateOf<CalendarDay?>(null) }

    ZhixueCard(
        title = "打卡日历 · ${data.month}",
        actions = {
            TextButton(onClick = { onShift(-1) }) { Text("‹", fontSize = 18.sp) }
            TextButton(onClick = { onShift(1) }) { Text("›", fontSize = 18.sp) }
        },
    ) {
        if (data.calendarError != null) {
            HintBanner(text = "打卡日历加载失败：${data.calendarError}", tone = BannerTone.Warn)
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = palette.muted)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                week.forEach { day ->
                    Box(Modifier.weight(1f).padding(2.dp)) {
                        if (day.date != null) {
                            val allDone = day.total > 0 && day.done >= day.total
                            val partial = day.total > 0 && day.done > 0 && !allDone
                            val bg = when {
                                allDone -> palette.okSoft
                                partial -> if (dark) palette.warn.copy(alpha = 0.22f) else Color(0xFFFDF3E4)
                                else -> palette.soft
                            }
                            val borderColor = when {
                                allDone -> palette.okLine
                                partial -> palette.warn.copy(alpha = 0.5f)
                                else -> palette.border
                            }
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp)
                                    .background(bg, RoundedCornerShape(8.dp))
                                    .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                                    .clickable { selected = day }
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                            ) {
                                Text("${day.dayOfMonth}", style = MaterialTheme.typography.bodySmall)
                                if (day.total > 0) {
                                    Text(
                                        "${day.done}/${day.total}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 10.sp,
                                        color = palette.muted,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("绿=全部完成 · 橙=部分完成 · 灰=无任务", style = MaterialTheme.typography.bodySmall, color = palette.muted)
        selected?.let { day ->
            Spacer(Modifier.height(6.dp))
            Text(
                "${day.date}：完成 ${day.done}/${day.total}",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.text,
            )
        }
    }
}
