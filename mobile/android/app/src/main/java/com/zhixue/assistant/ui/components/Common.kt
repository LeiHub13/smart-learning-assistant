package com.zhixue.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.zhixue.assistant.ui.theme.LocalZhixuePalette

/** 与 Web 卡片一致的 8dp 圆角 + 1px 描边，不用 M3 默认大圆角阴影。 */
val cardShape = RoundedCornerShape(10.dp)

@Composable
fun ZhixueCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    onClick: (() -> Unit)? = null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalZhixuePalette.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = palette.card),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(contentPadding)) {
            if (title != null || subtitle != null) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        if (title != null) {
                            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                        if (subtitle != null) {
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = palette.muted)
                        }
                    }
                    actions()
                }
            }
            content()
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(vertical = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = LocalZhixuePalette.current.muted,
    )
}

@Composable
fun LoadingView(text: String = "加载中…", modifier: Modifier = Modifier) {
    val palette = LocalZhixuePalette.current
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = palette.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(10.dp))
            Text(text, color = palette.muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun InlineLoading(text: String = "处理中…") {
    val palette = LocalZhixuePalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = palette.primary, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = palette.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun EmptyView(
    title: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val palette = LocalZhixuePalette.current
    Column(
        modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = palette.text, textAlign = TextAlign.Center)
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                textAlign = TextAlign.Center,
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            PrimaryButton(actionText, onClick = onAction)
        }
    }
}

/**
 * 首屏失败：给出原因 + 重试，而不是只显示空列表。
 * onRetry 放在最后一个参数，方便 `ErrorState(msg) { retry() }` 这种尾随 lambda 写法。
 */
@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: () -> Unit) {
    val palette = LocalZhixuePalette.current
    Column(
        modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = palette.danger, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        SecondaryButton("重新加载", onClick = onRetry)
    }
}

/** 页面内联提示条（对应 Web 的 .hint / .err 顶部横幅）。 */
@Composable
fun HintBanner(
    text: String,
    tone: BannerTone = BannerTone.Info,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val palette = LocalZhixuePalette.current
    val bg = when (tone) {
        BannerTone.Info -> palette.accentSubtle
        BannerTone.Success -> palette.okSoft
        BannerTone.Warn -> Color(0xFFFFF8E5)
        BannerTone.Error -> palette.badSoft
    }
    val fg = when (tone) {
        BannerTone.Info -> palette.primary
        BannerTone.Success -> palette.success
        BannerTone.Warn -> palette.warn
        BannerTone.Error -> palette.danger
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = bg,
        border = androidx.compose.foundation.BorderStroke(1.dp, fg.copy(alpha = 0.35f)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = fg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = fg, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

enum class BannerTone { Info, Success, Warn, Error }

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val palette = LocalZhixuePalette.current
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(8.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = palette.primary,
            contentColor = palette.onPrimary,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = palette.onPrimary,
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val palette = LocalZhixuePalette.current
    val tone = if (danger) palette.danger else palette.primary
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 40.dp),
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, tone.copy(alpha = 0.5f)),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = tone),
    ) {
        Text(text, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    maxLength: Int? = null,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    helper: String? = null,
    minLines: Int = 1,
) {
    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = LocalZhixuePalette.current.muted)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { next -> onValueChange(next.take(maxLength ?: Int.MAX_VALUE)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = if (minLines > 1) (minLines * 40).dp else 0.dp),
            placeholder = { Text(placeholder, color = LocalZhixuePalette.current.muted.copy(alpha = 0.7f)) },
            singleLine = singleLine,
            enabled = enabled,
            minLines = minLines,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(8.dp),
        )
        if (helper != null) {
            Text(
                helper,
                style = MaterialTheme.typography.bodySmall,
                color = LocalZhixuePalette.current.muted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 下拉选择（课程、类型、难度等），label 为空则只显示当前值。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectField(
    label: String?,
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emptyLabel: String = "请选择",
) {
    var expanded by remember { mutableStateOf(false) }
    val currentText = options.firstOrNull { it.first == selected }?.second ?: emptyLabel
    Column(modifier.fillMaxWidth()) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = LocalZhixuePalette.current.muted)
            Spacer(Modifier.height(4.dp))
        }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
            OutlinedTextField(
                value = currentText,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                shape = RoundedCornerShape(8.dp),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (key, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            onSelect(key)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun CheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = LocalZhixuePalette.current.muted)
            }
        }
    }
}

@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = LocalZhixuePalette.current.muted)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun TagBadge(text: String, tone: TagTone = TagTone.Neutral, modifier: Modifier = Modifier) {
    val palette = LocalZhixuePalette.current
    val (bg, fg) = when (tone) {
        TagTone.Neutral -> palette.soft to palette.muted
        TagTone.Info -> palette.accentSubtle to palette.primary
        TagTone.Success -> palette.okSoft to palette.success
        TagTone.Danger -> palette.badSoft to palette.danger
        TagTone.Warn -> Color(0xFFFFF8E5) to palette.warn
    }
    Box(
        modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
}

enum class TagTone { Neutral, Info, Success, Danger, Warn }

@Composable
fun ChipRow(
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(if (selected == option) null else option) },
                label = { Text(option) },
                shape = RoundedCornerShape(16.dp),
            )
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String? = null,
    confirmText: String = "确定",
    dismissText: String = "取消",
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalZhixuePalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = if (body != null) {
            { Text(body, style = MaterialTheme.typography.bodyMedium) }
        } else null,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = if (danger) palette.danger else palette.primary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissText) } },
        shape = RoundedCornerShape(12.dp),
    )
}

@Composable
fun InputDialog(
    title: String,
    label: String,
    initial: String,
    placeholder: String = "",
    singleLine: Boolean = true,
    maxLength: Int? = null,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LabeledField(
                label = label,
                value = value,
                onValueChange = { value = it },
                placeholder = placeholder,
                singleLine = singleLine,
                maxLength = maxLength,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(value.trim()) }, enabled = value.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun BackIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
    }
}

/** 页面顶部说明 + 操作，Web 的 .page-head 对应物。 */
@Composable
fun PageHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalZhixuePalette.current.muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

/** 掌握度、得分率一类的进度条。 */
@Composable
fun ProgressBar(percent: Int, color: Color = LocalZhixuePalette.current.primary, label: String? = null) {
    val palette = LocalZhixuePalette.current
    Column(Modifier.fillMaxWidth()) {
        if (label != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text("$percent%", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(palette.soft)
        ) {
            Box(
                Modifier
                    .fillMaxWidth((percent.coerceIn(0, 100)) / 100f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, accent: Color? = null) {
    val palette = LocalZhixuePalette.current
    Column(
        modifier
            .background(palette.card, RoundedCornerShape(10.dp))
            .border(1.dp, palette.border, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = accent ?: palette.text)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = palette.muted)
    }
}
