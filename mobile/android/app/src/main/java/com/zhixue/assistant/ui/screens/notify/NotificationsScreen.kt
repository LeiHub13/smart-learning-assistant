package com.zhixue.assistant.ui.screens.notify

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.data.model.MailLogDto
import com.zhixue.assistant.data.model.NotificationDto
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.LoadingView
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavController
import com.zhixue.assistant.ui.components.LocalNavigator
import com.zhixue.assistant.ui.components.LocalToast
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.util.fmtDateTime

/** 点开无跳转链接的通知（或邮件记录）时展示的完整内容。 */
private data class DetailSheet(
    val tag: String,
    val danger: Boolean,
    val title: String,
    val meta: String,
    val content: String,
    val error: String?,
)

@Composable
fun NotificationsScreen() {
    val container = LocalAppContainer.current
    val vm: NotificationsViewModel = viewModel(factory = NotificationsViewModel.factory(container))
    val inbox by vm.inbox.collectAsState()
    val mails by vm.mails.collectAsState()
    val unread by vm.unread.collectAsState()
    val message by vm.message.collectAsState()
    val pendingLink by vm.pendingLink.collectAsState()
    val toast = LocalToast.current
    val navigator = LocalNavigator.current
    val navController = LocalNavController.current
    var tab by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<DetailSheet?>(null) }

    LaunchedEffect(message) {
        val text = message
        if (text != null) {
            toast(text)
            vm.dismissMessage()
        }
    }
    LaunchedEffect(pendingLink) {
        val link = vm.consumePendingLink()
        if (link != null) {
            navController.popBackStack()
            navigator(link)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
            PageHeader(
                title = "通知中心",
                subtitle = if (unread > 0) "$unread 条未读通知" else "暂无未读通知",
            ) {
                TextButton(onClick = { vm.markAllRead() }, enabled = unread > 0) {
                    Text("全部已读", fontWeight = FontWeight.Medium)
                }
            }
        }

        NotificationTabs(selected = tab, onSelect = { tab = it })

        if (tab == 0) {
            when (val current = inbox) {
                is UiState.Loading -> LoadingView("正在加载通知…")
                is UiState.Error -> RetryBox { ErrorState(message = current.message, onRetry = { vm.load() }) }
                is UiState.Ready -> NotificationList(current.data, onOpen = { vm.open(it) })
            }
        } else {
            when (val current = mails) {
                is UiState.Loading -> LoadingView("正在加载邮件记录…")
                is UiState.Error -> RetryBox { ErrorState(message = current.message, onRetry = { vm.load() }) }
                is UiState.Ready -> MailList(current.data, onOpen = { detail = it.toSheet() })
            }
        }
    }

    detail?.let { sheet ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = {
                Column {
                    TagBadge(sheet.tag, if (sheet.danger) TagTone.Danger else TagTone.Info)
                    Spacer(Modifier.height(6.dp))
                    Text(sheet.title, style = MaterialTheme.typography.titleMedium)
                }
            },
            text = {
                Column {
                    if (sheet.meta.isNotBlank()) {
                        Text(sheet.meta, style = MaterialTheme.typography.bodySmall, color = LocalZhixuePalette.current.muted)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        sheet.content.ifBlank { "（无正文）" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!sheet.error.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        HintBanner(text = "发送失败原因：${sheet.error}", tone = BannerTone.Error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { detail = null }) { Text("知道了") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationTabs(selected: Int, onSelect: (Int) -> Unit) {
    TabRow(selectedTabIndex = selected) {
        Tab(selected = selected == 0, onClick = { onSelect(0) }, text = { Text("系统通知") })
        Tab(selected = selected == 1, onClick = { onSelect(1) }, text = { Text("邮箱通知") })
    }
}

@Composable
private fun RetryBox(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        content()
    }
}

@Composable
private fun NotificationList(items: List<NotificationDto>, onOpen: (NotificationDto) -> Unit) {
    if (items.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            EmptyView(title = "暂无通知", hint = "有新消息会在这里提醒你")
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }) { notification ->
            NotificationRow(notification = notification, onClick = { onOpen(notification) })
        }
    }
}

@Composable
private fun NotificationRow(notification: NotificationDto, onClick: () -> Unit) {
    val palette = LocalZhixuePalette.current
    val unreadFlag = notification.readFlag != true
    ZhixueCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (unreadFlag) palette.danger else palette.border, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                notification.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (unreadFlag) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TagBadge(notificationTypeLabel(notification.type), TagTone.Neutral)
        }
        Text(
            notification.content,
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                fmtDateTime(notification.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                modifier = Modifier.weight(1f),
            )
            if (!notification.link.isNullOrBlank()) {
                Text(
                    "点击查看并跳转 →",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.primary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun MailList(items: List<MailLogDto>, onOpen: (MailLogDto) -> Unit) {
    if (items.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            EmptyView(title = "暂无邮件发送记录", hint = "公告与提醒的邮件送达情况会显示在这里")
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }) { mail ->
            val palette = LocalZhixuePalette.current
            val sent = mail.status.equals("SENT", ignoreCase = true)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(palette.card, RoundedCornerShape(10.dp))
                    .clickable { onOpen(mail) }
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        mail.subject.ifBlank { "（无主题）" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TagBadge(if (sent) "成功" else "失败", if (sent) TagTone.Success else TagTone.Danger)
                }
                Text(
                    "${mail.email} · ${fmtDateTime(mail.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (!sent && !mail.error.isNullOrBlank()) {
                    Text(
                        "失败原因：${mail.error}",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.danger,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private fun MailLogDto.toSheet(): DetailSheet = DetailSheet(
    tag = if (status.equals("SENT", ignoreCase = true)) "发送成功" else "发送失败",
    danger = !status.equals("SENT", ignoreCase = true),
    title = subject.ifBlank { "（无主题）" },
    meta = "$email · ${fmtDateTime(createdAt)}",
    content = content,
    error = error?.takeIf { it.isNotBlank() },
)
