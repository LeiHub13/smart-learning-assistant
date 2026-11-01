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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.data.model.AdminDashboard
import com.zhixue.assistant.data.model.AdminUserPage
import com.zhixue.assistant.data.model.AdminUserRow
import com.zhixue.assistant.data.model.AnnouncementDto
import com.zhixue.assistant.data.model.DashboardPoint
import com.zhixue.assistant.data.model.SceneStat
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.CheckboxRow
import com.zhixue.assistant.ui.components.ChipRow
import com.zhixue.assistant.ui.components.ConfirmDialog
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.LabeledField
import com.zhixue.assistant.ui.components.LoadingView
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.MiniBarChart
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.SectionTitle
import com.zhixue.assistant.ui.components.StatTile
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.components.cardShape
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.util.fmtDateTime
import kotlin.math.ceil

@Composable
fun AdminScreen() {
    val container = LocalAppContainer.current
    val vm: AdminViewModel = viewModel(factory = AdminViewModel.factory(container))
    val dashboard by vm.dashboard.collectAsState()
    val users by vm.users.collectAsState()
    val announcements by vm.announcements.collectAsState()
    val metric by vm.metric.collectAsState()
    val keyword by vm.keyword.collectAsState()
    val title by vm.title.collectAsState()
    val content by vm.content.collectAsState()
    val notifyEmail by vm.notifyEmail.collectAsState()
    val publishing by vm.publishing.collectAsState()
    val recallingId by vm.recallingId.collectAsState()
    val message by vm.message.collectAsState()
    var pendingRecall by remember { mutableStateOf<AnnouncementDto?>(null) }

    Box(Modifier.fillMaxSize()) {
        when (val current = dashboard) {
            is UiState.Loading -> LoadingView("正在加载看板数据…")
            is UiState.Error -> ErrorState(message = current.message, onRetry = { vm.load() })
            is UiState.Ready -> AdminContent(
                dashboard = current.data,
                vm = vm,
                users = users,
                announcements = announcements,
                metric = metric,
                keyword = keyword,
                title = title,
                content = content,
                notifyEmail = notifyEmail,
                publishing = publishing,
                recallingId = recallingId,
                message = message,
                askRecall = { pendingRecall = it },
            )
        }
    }

    pendingRecall?.let { announcement ->
        ConfirmDialog(
            title = "撤回公告",
            body = "撤回公告「${announcement.title}」？所有用户铃铛里的这条公告也会一并消失（已发出的邮件无法收回）。",
            confirmText = "撤回",
            danger = true,
            onConfirm = {
                pendingRecall = null
                vm.recall(announcement)
            },
            onDismiss = { pendingRecall = null },
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun AdminContent(
    dashboard: AdminDashboard,
    vm: AdminViewModel,
    users: UiState<AdminUserPage>?,
    announcements: UiState<List<AnnouncementDto>>,
    metric: AdminMetric,
    keyword: String,
    title: String,
    content: String,
    notifyEmail: Boolean,
    publishing: Boolean,
    recallingId: Long?,
    message: UiMessage?,
    askRecall: (AnnouncementDto) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                PageHeader(
                    title = "系统看板",
                    subtitle = "用户规模、活跃度与 LLM 用量趋势（仅管理员可见）",
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

        item { OverviewStats(dashboard) }

        if (!dashboard.llmAvailable) {
            item { HintBanner(text = "ai-service 观测不可达，LLM 数据暂缺", tone = BannerTone.Warn) }
        }

        item { TrendCard(dashboard, metric) { vm.setMetric(it) } }

        item { SectionTitle("按场景统计（近 14 天）") }
        if (dashboard.byScene.isEmpty()) {
            item { ZhixueCard { EmptyView("暂无场景数据") } }
        } else {
            item(key = "scene-head") { SceneHeaderRow() }
            items(dashboard.byScene, key = { "scene-${it.scene}" }) { scene -> SceneRow(scene) }
        }

        item { SectionTitle("用户列表") }
        item(key = "users-search") {
            ZhixueCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        LabeledField(
                            label = "搜索",
                            value = keyword,
                            onValueChange = { vm.setKeyword(it) },
                            placeholder = "用户名 / 昵称 / 邮箱",
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    SecondaryButton("搜索", onClick = { vm.searchUsers() })
                }
            }
        }
        when (val current = users) {
            null, is UiState.Loading -> item(key = "users-loading") {
                ZhixueCard { InlineLoading("正在加载用户…") }
            }
            is UiState.Error -> item(key = "users-error") {
                ErrorState(message = current.message, onRetry = { vm.loadUsers(1) })
            }
            is UiState.Ready -> {
                val page = current.data
                if (page.records.isEmpty()) {
                    item(key = "users-empty") { ZhixueCard { EmptyView("无匹配用户") } }
                } else {
                    item(key = "users-total") {
                        Text(
                            "共 ${page.total} 人 · 第 ${page.page} 页",
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalZhixuePalette.current.muted,
                        )
                    }
                    items(page.records, key = { "user-${it.id}" }) { row -> UserRowCard(row) }
                    item(key = "users-pager") {
                        PagerRow(page) { vm.loadUsers(it) }
                    }
                }
            }
        }

        item { SectionTitle("公告管理") }
        item(key = "announce-form") {
            AnnouncementFormCard(
                title = title,
                content = content,
                notifyEmail = notifyEmail,
                publishing = publishing,
                onTitleChange = { vm.setTitle(it) },
                onContentChange = { vm.setContent(it) },
                onNotifyEmailChange = { vm.setNotifyEmail(it) },
                onPublish = { vm.publish() },
            )
        }
        when (val current = announcements) {
            is UiState.Loading -> item(key = "ann-loading") { ZhixueCard { InlineLoading("正在加载公告…") } }
            is UiState.Error -> item(key = "ann-error") {
                ErrorState(message = current.message, onRetry = { vm.loadAnnouncements() })
            }
            is UiState.Ready -> {
                if (current.data.isEmpty()) {
                    item(key = "ann-empty") {
                        ZhixueCard { EmptyView(title = "暂无公告", hint = "发布后会推送到全部用户的铃铛通知") }
                    }
                } else {
                    items(current.data, key = { "ann-${it.id}" }) { announcement ->
                        AnnouncementRow(
                            announcement = announcement,
                            recalling = recallingId == announcement.id,
                            onRecall = { askRecall(announcement) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewStats(dashboard: AdminDashboard) {
    val tiles = listOf(
        "${dashboard.users.total}" to "用户总数",
        "${dashboard.users.newToday}" to "今日注册",
        "${dashboard.users.new7d}" to "近 7 天注册",
        "${dashboard.dauToday}" to "今日活跃",
        "${dashboard.llmTodayCalls}" to "今日 LLM 调用",
        humanTokens(dashboard.llmTodayTokens) to "今日 tokens",
    )
    ZhixueCard(title = "核心指标") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (value, label) ->
                        StatTile(value, label, modifier = Modifier.weight(1f))
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun TrendCard(dashboard: AdminDashboard, metric: AdminMetric, onSelect: (AdminMetric) -> Unit) {
    val palette = LocalZhixuePalette.current
    ZhixueCard(title = "近 14 天趋势") {
        if (dashboard.series.isEmpty()) {
            EmptyView("暂无趋势数据")
        } else {
            ChipRow(
                options = AdminMetric.entries.map { it.label },
                selected = metric.label,
                onSelect = { label -> AdminMetric.entries.firstOrNull { it.label == label }?.let(onSelect) },
            )
            Spacer(Modifier.height(8.dp))
            MiniBarChart(values = dashboard.series.map { point -> point.shortDate() to metric.valueOf(point).toDouble() })
            Text(
                "${metric.label} · 共 ${dashboard.series.size} 天",
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun DashboardPoint.shortDate(): String =
    if (date.length >= 10) date.substring(5, 10) else date

@Composable
private fun SceneHeaderRow() {
    val palette = LocalZhixuePalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val head: @Composable (String, Float) -> Unit = { text, weight ->
            Text(text, style = MaterialTheme.typography.bodySmall, color = palette.muted, modifier = Modifier.weight(weight))
        }
        head("场景", 2f)
        head("调用", 1f)
        head("失败", 1f)
        head("均耗时", 1.4f)
        head("tokens", 1.2f)
    }
}

@Composable
private fun SceneRow(scene: SceneStat) {
    val palette = LocalZhixuePalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(palette.card, cardShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(adminSceneLabel(scene.scene), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(2f))
        Text("${scene.calls}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            "${scene.failures}",
            style = MaterialTheme.typography.bodySmall,
            color = if (scene.failures > 0) palette.danger else palette.text,
            modifier = Modifier.weight(1f),
        )
        Text("${scene.avgMs} ms", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.4f))
        Text(humanTokens(scene.tokens), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.2f))
    }
}

@Composable
private fun UserRowCard(row: AdminUserRow) {
    val palette = LocalZhixuePalette.current
    ZhixueCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.username.ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (row.admin == true) {
                Spacer(Modifier.width(6.dp))
                TagBadge("管理员", TagTone.Warn)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                row.nickname.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            buildString {
                append(row.email?.takeIf { it.isNotBlank() } ?: "未绑定邮箱")
                append(" · 练习 ${row.practiceCount} 次")
                append(" · 最近学习 ${row.lastStudy?.takeIf { it.isNotBlank() } ?: "—"}")
                append(" · 注册 ${fmtDateTime(row.createdAt).ifBlank { "—" }}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun PagerRow(page: AdminUserPage, onJump: (Int) -> Unit) {
    val palette = LocalZhixuePalette.current
    val totalPages = ceil(page.total.toDouble() / page.size.coerceAtLeast(1)).toInt().coerceAtLeast(1)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        SecondaryButton("上一页", onClick = { onJump(page.page - 1) }, enabled = page.page > 1)
        Text(
            "第 ${page.page} 页 / 共 $totalPages 页",
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
        )
        SecondaryButton("下一页", onClick = { onJump(page.page + 1) }, enabled = page.page < totalPages)
    }
}

@Composable
private fun AnnouncementFormCard(
    title: String,
    content: String,
    notifyEmail: Boolean,
    publishing: Boolean,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onNotifyEmailChange: (Boolean) -> Unit,
    onPublish: () -> Unit,
) {
    ZhixueCard(title = "发布公告") {
        LabeledField(
            label = "标题",
            value = title,
            onValueChange = onTitleChange,
            placeholder = "公告标题（≤$ANNOUNCEMENT_TITLE_MAX 字）",
            helper = "${title.length}/$ANNOUNCEMENT_TITLE_MAX 字",
        )
        Spacer(Modifier.height(10.dp))
        LabeledField(
            label = "正文",
            value = content,
            onValueChange = onContentChange,
            placeholder = "公告内容，发布后将推送到全部用户的铃铛通知…",
            singleLine = false,
            minLines = 4,
            maxLength = ANNOUNCEMENT_CONTENT_MAX,
            helper = "${content.length}/$ANNOUNCEMENT_CONTENT_MAX 字",
        )
        Spacer(Modifier.height(6.dp))
        CheckboxRow(
            label = "同时发送邮件通知",
            checked = notifyEmail,
            onCheckedChange = onNotifyEmailChange,
            description = "勾选后公告会同步发邮件给绑定邮箱的用户；邮件无法随撤回收回，不勾则仅进铃铛、撤回零残留",
        )
        Spacer(Modifier.height(8.dp))
        PrimaryButton("发布公告", onClick = onPublish, loading = publishing)
    }
}

@Composable
private fun AnnouncementRow(
    announcement: AnnouncementDto,
    recalling: Boolean,
    onRecall: () -> Unit,
) {
    val palette = LocalZhixuePalette.current
    ZhixueCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                announcement.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (announcement.emailSent == true) {
                Spacer(Modifier.width(6.dp))
                TagBadge("已发邮件", TagTone.Info)
            }
            Spacer(Modifier.width(8.dp))
            SecondaryButton("撤回", onClick = onRecall, enabled = !recalling, danger = true)
        }
        Text(
            buildString {
                val time = fmtDateTime(announcement.createdAt)
                append(if (time.isBlank()) "—" else time)
                append(" · ")
                append(announcement.adminName ?: "管理员")
            },
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            firstLinePreview(announcement.content, max = 120),
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (recalling) {
            Spacer(Modifier.height(4.dp))
            InlineLoading("正在撤回…")
        }
    }
}
