package com.zhixue.assistant.ui.screens.hall

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhixue.assistant.data.model.HallMessageDto
import com.zhixue.assistant.ui.components.LocalNavigator
import com.zhixue.assistant.ui.components.LocalToast
import com.zhixue.assistant.ui.components.MarkdownText
import com.zhixue.assistant.ui.components.UserAvatar
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.util.friendlyTime

/** AI 助教头像底色（Web 的 .ai-av 固定色）。 */
private val AiAvatarBg = Color(0xFF59636E)

/**
 * 通用消息行：头像 + 「昵称 时间」+ 气泡。自己的消息靠右、主色底（与 Web 的 .m.me 一致）。
 * 富文本一律交给 markdownContent（MarkdownText），纯文本走 plainText。
 */
@Composable
fun HallMessageRow(
    nickname: String,
    timeRaw: String?,
    mine: Boolean,
    modifier: Modifier = Modifier,
    avatarPath: String? = null,
    avatarUserId: Long? = null,
    aiAvatar: Boolean = false,
    plainText: String? = null,
    markdownContent: String? = null,
    streaming: Boolean = false,
    errorText: String? = null,
    timeText: String? = null,
    sources: String? = null,
    onCitation: ((Int) -> Unit)? = null,
    onLinkClick: ((String) -> Unit)? = null,
    onOpenSources: (() -> Unit)? = null,
) {
    val palette = LocalZhixuePalette.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        val bubbleMax = maxWidth * 0.82f
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            if (!mine) HallAvatar(aiAvatar, nickname, avatarPath, avatarUserId)
            Column(
                Modifier.widthIn(max = bubbleMax).padding(horizontal = 8.dp),
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        nickname,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.muted,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            errorText != null -> "回答失败"
                            timeText != null -> timeText
                            else -> friendlyTime(timeRaw)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.muted,
                    )
                }
                Surface(
                    modifier = Modifier.padding(top = 3.dp),
                    shape = RoundedCornerShape(
                        topStart = if (mine) 8.dp else 3.dp,
                        topEnd = if (mine) 3.dp else 8.dp,
                        bottomStart = 8.dp,
                        bottomEnd = 8.dp,
                    ),
                    color = when {
                        errorText != null -> palette.badSoft
                        mine -> palette.primary
                        else -> palette.soft
                    },
                    border = if (mine || errorText != null) null else
                        androidx.compose.foundation.BorderStroke(1.dp, palette.border),
                ) {
                    when {
                        errorText != null -> Text(
                            errorText,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.danger,
                        )

                        markdownContent != null -> MarkdownText(
                            source = markdownContent,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            streaming = streaming,
                            onCitation = onCitation,
                            onLinkClick = onLinkClick,
                        )

                        else -> Text(
                            plainText.orEmpty(),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (mine) palette.onPrimary else palette.text,
                        )
                    }
                }
                if (!sources.isNullOrBlank() && onOpenSources != null) {
                    HallSourceChips(sources, onOpenSources)
                }
            }
            if (mine) HallAvatar(false, nickname, avatarPath, avatarUserId)
        }
    }
}

@Composable
private fun HallAvatar(ai: Boolean, name: String, avatarPath: String?, userId: Long?) {
    if (ai) {
        Box(
            Modifier
                .size(30.dp)
                .background(AiAvatarBg, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("AI", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        return
    }
    UserAvatar(
        name = name,
        avatarPath = avatarPath?.takeIf { it.isNotBlank() },
        size = 30.dp,
        userId = userId,
        rounded = false,
    )
}

/** 进出场 / 发送失败等居中灰字（Web 的 .sys-line）。 */
@Composable
fun HallNoticeLine(text: String, modifier: Modifier = Modifier) {
    val palette = LocalZhixuePalette.current
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 5.dp),
        style = MaterialTheme.typography.bodySmall,
        color = palette.muted,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

/** 引用来源芯片：点击跳当前课程的文档管理（与 Web 的 .src-chip 同色）。 */
@Composable
fun HallSourceChips(sources: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalZhixuePalette.current
    val items = remember(sources) {
        sources.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
    if (items.isEmpty()) return
    Surface(
        modifier = modifier.padding(top = 6.dp).widthIn(max = 320.dp),
        shape = RoundedCornerShape(6.dp),
        color = palette.accentSubtle,
    ) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("引用来源：", style = MaterialTheme.typography.labelSmall, color = palette.primary)
            items.forEach { label ->
                Text(
                    "[$label]",
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .clickable(onClick = onOpen)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    color = palette.primary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** 在线 / 离线小圆点。 */
@Composable
fun HallOnlineDot(online: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalZhixuePalette.current
    Box(
        modifier
            .size(8.dp)
            .background(if (online) palette.success else palette.border, CircleShape),
    )
}

/** 未读角标：>99 显示 99+。 */
@Composable
fun HallUnreadBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val palette = LocalZhixuePalette.current
    Box(
        modifier
            .background(palette.danger, RoundedCornerShape(8.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 私聊/频道消息行：把 UI 模型翻译成通用气泡。 */
@Composable
fun HallChannelEntryRow(entry: HallEntry.Channel, meId: Long, onOpenDocs: () -> Unit) {
    val message: HallMessageDto = entry.message
    val mine = !message.fromAi && message.userId == meId
    val openLink = rememberLinkOpener()
    HallMessageRow(
        nickname = when {
            message.fromAi -> "AI 助教"
            mine -> "我"
            else -> message.nickname.ifBlank { "用户${message.userId}" }
        },
        timeRaw = message.timeRaw,
        mine = mine,
        aiAvatar = message.fromAi,
        avatarPath = message.avatar,
        avatarUserId = if (message.fromAi) null else message.userId,
        plainText = if (message.fromAi) null else message.content,
        markdownContent = if (message.fromAi) message.content else null,
        // 只有 AI 消息带 sources，用户消息这里恒为空
        sources = message.sources?.takeIf { it.isNotBlank() },
        onOpenSources = onOpenDocs,
        onCitation = { onOpenDocs() },
        onLinkClick = openLink,
    )
}

@Composable
fun HallDirectEntryRow(entry: HallEntry.Direct) {
    HallMessageRow(
        nickname = if (entry.mine) "我" else entry.nickname.ifBlank { "用户${entry.userId}" },
        timeRaw = entry.timeRaw,
        mine = entry.mine,
        avatarPath = entry.avatar,
        avatarUserId = entry.userId,
        plainText = entry.content,
    )
}

/** 把 Markdown 里的链接交给系统浏览器，站内路径交给应用导航（与 Web 的 <a> 行为等价）。 */
@Composable
fun rememberLinkOpener(): (String) -> Unit {
    val context = LocalContext.current
    val navigate = LocalNavigator.current
    val toast = LocalToast.current
    return remember(context, navigate, toast) {
        { href ->
            when {
                href.startsWith("/") -> navigate(href)
                href.startsWith("http://") || href.startsWith("https://") -> {
                    val opened = runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(href)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.isSuccess
                    if (!opened) toast("无法打开链接：$href")
                }

                else -> toast("无法打开链接：$href")
            }
        }
    }
}
