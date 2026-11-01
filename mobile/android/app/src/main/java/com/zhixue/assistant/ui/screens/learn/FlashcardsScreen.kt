package com.zhixue.assistant.ui.screens.learn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.ConfirmDialog
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalToast
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.SelectField
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.util.FileActions
import com.zhixue.assistant.util.friendlyTime
import com.zhixue.assistant.util.parseDateTime
import java.time.LocalDateTime
import androidx.compose.foundation.border

// 闪卡：翻卡自评，记住升盒、没记住回炉；支持从错题本/文档生成与导出 Anki。
@Composable
fun FlashcardsScreen() {
    val container = LocalAppContainer.current
    val toast = LocalToast.current
    val context = LocalContext.current
    val vm: FlashcardViewModel = viewModel(factory = FlashcardViewModel.factory(container))
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.init() }
    LaunchedEffect(Unit) { vm.toast.collect { toast(it) } }
    LaunchedEffect(state.exportTick) {
        if (state.exportTick == 0) return@LaunchedEffect
        runCatching { container.flashcard.exportAnki() }
            .onSuccess { bytes ->
                val saved = FileActions.saveToDownloads(context, "flashcards-anki.txt", bytes, "text/plain")
                toast(saved.message)
            }
            .onFailure { e -> toast(e.userMessage()) }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PageHeader(title = "闪卡", subtitle = "问对式主动记忆：翻卡自评，记住升盒、没记住回炉；支持导出到 Anki")
        state.error?.let { err ->
            HintBanner(err, tone = BannerTone.Error, onDismiss = vm::dismissError)
        }

        ZhixueCard(title = "生成闪卡", subtitle = "共 ${state.total} 张 · 今日到期 ${state.due} 张") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    text = "导出到 Anki",
                    onClick = vm::requestExport,
                    enabled = state.canExport,
                    modifier = Modifier.heightIn(min = 44.dp),
                )
                SecondaryButton(
                    text = "刷新队列",
                    onClick = vm::loadQueue,
                    enabled = !state.loading,
                    modifier = Modifier.heightIn(min = 44.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton(
                    text = if (state.generating) "生成中…" else "从错题本生成",
                    onClick = { vm.generate("mistake") },
                    enabled = !state.generating,
                    modifier = Modifier.heightIn(min = 44.dp),
                )
                Text(
                    "会话闪卡请在答疑页生成",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalZhixuePalette.current.muted,
                )
            }
            Spacer(Modifier.height(8.dp))
            SelectField(
                label = "从文档生成",
                options = state.docs.map { it.docId.toString() to it.fileName },
                selected = state.docId?.toString(),
                onSelect = { key -> vm.setDocId(key.toLongOrNull()) },
                emptyLabel = if (state.docs.isEmpty()) "暂无文档" else "选择文档…",
            )
            Spacer(Modifier.height(6.dp))
            SecondaryButton(
                text = "从该文档生成",
                onClick = { vm.generate("doc") },
                enabled = !state.generating && state.docId != null,
                modifier = Modifier.heightIn(min = 44.dp),
            )
        }

        ZhixueCard(title = "学习队列") {
            val card = state.current
            if (state.loading) {
                InlineLoading("加载队列…")
            } else if (card != null) {
                Text(
                    "${flashSourceLabel(card.source)} · 盒 ${card.box} · ${flashDueLabel(card.dueAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalZhixuePalette.current.muted,
                )
                Spacer(Modifier.height(8.dp))
                FlashCard(
                    card = card,
                    revealed = state.revealed,
                    onReveal = vm::reveal,
                )
                Spacer(Modifier.height(10.dp))
                if (!state.revealed) {
                    PrimaryButton("显示答案", onClick = vm::reveal, modifier = Modifier.fillMaxWidth())
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton(
                            text = "😅 没记住",
                            onClick = { vm.grade("miss") },
                            enabled = !state.grading,
                            danger = true,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp),
                        )
                        PrimaryButton(
                            text = "✓ 记住了",
                            onClick = { vm.grade("ok") },
                            enabled = !state.grading,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildString {
                            append(vm.progressText())
                            append(" · 记住升盒、没记住回盒 1")
                            state.lastHint?.let { append(" · ").append(it) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalZhixuePalette.current.muted,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton("删除此卡", onClick = vm::requestDelete, danger = true, modifier = Modifier.heightIn(min = 44.dp))
                }
            } else if (state.finished) {
                EmptyView(title = "本轮完成！刷新队列继续，或再生成一批")
            } else {
                EmptyView(title = "队列为空——先从上面生成一些闪卡吧")
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (state.confirmDelete) {
        ConfirmDialog(
            title = "删除闪卡",
            body = "删除后该卡不再出现在复习队列，确定删除？",
            confirmText = "删除",
            danger = true,
            onConfirm = vm::confirmDelete,
            onDismiss = vm::cancelDelete,
        )
    }
}

@Composable
private fun FlashCard(card: com.zhixue.assistant.data.model.FlashcardDto, revealed: Boolean, onReveal: () -> Unit) {
    val palette = LocalZhixuePalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp)
            .background(palette.card, RoundedCornerShape(14.dp))
            .then(
                Modifier.border(
                    if (revealed) 2.dp else 1.dp,
                    if (revealed) palette.primary else palette.border,
                    RoundedCornerShape(14.dp),
                ),
            )
            .clickable(enabled = !revealed, onClick = onReveal)
            .padding(20.dp),
    ) {
        Text(card.front, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (!revealed) {
            Spacer(Modifier.height(16.dp))
            Text("点击卡片显示答案", style = MaterialTheme.typography.bodySmall, color = palette.muted)
        } else {
            Spacer(Modifier.height(12.dp))
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(palette.border)
            )
            AnimatedVisibility(visible = revealed, enter = fadeIn() + slideInVertically { it / 4 }) {
                Text(
                    card.back,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.success,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

private fun flashSourceLabel(source: String?): String = when (source) {
    "mistake" -> "错题"
    "doc" -> "文档"
    "chat" -> "答疑"
    else -> source.orEmpty()
}

/** 到期状态：到期（或未排期）显示「今日到期」，未到期显示下次复习日期。 */
private fun flashDueLabel(dueAt: String?): String {
    val due = parseDateTime(dueAt) ?: return "今日到期"
    return if (!due.isAfter(LocalDateTime.now())) "今日到期" else "${friendlyTime(dueAt)} 复习"
}
