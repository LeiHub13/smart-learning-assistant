package com.zhixue.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.util.InlineSpan
import com.zhixue.assistant.util.InlineStyle
import com.zhixue.assistant.util.MdBlock
import com.zhixue.assistant.util.parseMarkdown

/** 引用角标在 AnnotatedString 上的 tag，点按后据此回调对应序号。 */
const val TAG_CITATION = "citation"
const val TAG_LINK = "link"

/**
 * 原生 Markdown 渲染（不用 WebView）。
 * streaming=true 时按纯文本显示并保留换行，与 Web 端「生成中先文本、结束后排版」一致。
 */
@Composable
fun MarkdownText(
    source: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    onCitation: ((Int) -> Unit)? = null,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val palette = LocalZhixuePalette.current
    if (streaming) {
        Text(
            source,
            modifier = modifier.padding(vertical = 2.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    val blocks = remember(source) { parseMarkdown(source) }
    Column(modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val size = when (block.level) {
                        1 -> 19.sp
                        2 -> 17.sp
                        3 -> 16.sp
                        else -> 15.sp
                    }
                    Spacer(Modifier.height(if (block.level <= 2) 10.dp else 6.dp))
                    ClickableInline(
                        block.inline,
                        fontSize = size,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.text,
                        onCitation = onCitation,
                        onLinkClick = onLinkClick,
                    )
                    Spacer(Modifier.height(4.dp))
                }

                is MdBlock.Paragraph -> {
                    Spacer(Modifier.height(6.dp))
                    ClickableInline(block.inline, onCitation = onCitation, onLinkClick = onLinkClick)
                }

                is MdBlock.Bullet -> {
                    Spacer(Modifier.height(4.dp))
                    block.items.forEach { item ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                            Text("•", color = palette.muted, modifier = Modifier.width(14.dp))
                            ClickableInline(item, onCitation = onCitation, onLinkClick = onLinkClick, modifier = Modifier.weight(1f))
                        }
                    }
                }

                is MdBlock.Ordered -> {
                    Spacer(Modifier.height(4.dp))
                    block.items.forEachIndexed { index, item ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                            Text("${index + 1}.", color = palette.muted, modifier = Modifier.width(22.dp))
                            ClickableInline(item, onCitation = onCitation, onLinkClick = onLinkClick, modifier = Modifier.weight(1f))
                        }
                    }
                }

                is MdBlock.Quote -> {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(palette.soft, RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text("|", color = palette.primary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        ClickableInline(
                            block.inline,
                            color = palette.muted,
                            onCitation = onCitation,
                            onLinkClick = onLinkClick,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MdBlock.Code -> CodeBlock(block.language, block.code)

                is MdBlock.Table -> MarkdownTable(block.header, block.rows)

                MdBlock.Divider -> Spacer(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .height(1.dp)
                        .background(palette.border)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun CodeBlock(language: String?, code: String) {
    val palette = LocalZhixuePalette.current
    var copied by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        color = palette.soft,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    language ?: "code",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { copied = true }) {
                    Text(if (copied) "已复制" else "复制", fontSize = 12.sp)
                }
            }
            Text(
                code,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = palette.text,
            )
        }
    }
}

@Composable
private fun MarkdownTable(header: List<String>, rows: List<List<String>>) {
    val palette = LocalZhixuePalette.current
    Spacer(Modifier.height(6.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = palette.card,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(palette.soft).padding(horizontal = 8.dp, vertical = 6.dp)) {
                header.forEach { cell ->
                    Text(
                        cell,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier.width(96.dp),
                        maxLines = 2,
                    )
                }
            }
            rows.forEach { row ->
                Row(
                    Modifier
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .background(palette.card),
                ) {
                    row.forEach { cell ->
                        Text(cell, fontSize = 13.sp, modifier = Modifier.width(96.dp), maxLines = 3)
                    }
                }
            }
        }
    }
}

/**
 * 行内文本 + 可点按角标/链接：用 TextLayoutResult 把触点映射到字符偏移，
 * 再读 AnnotatedString 上的 tag，避免依赖已被移除的旧 ClickableText 方案。
 */
@Composable
private fun ClickableInline(
    spans: List<InlineSpan>,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 15.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    color: Color = LocalZhixuePalette.current.text,
    onCitation: ((Int) -> Unit)? = null,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val palette = LocalZhixuePalette.current
    val annotated = remember(spans, palette) { spans.toAnnotatedString(palette) }
    val layoutState = remember { mutableStateOf<TextLayoutResult?>(null) }
    val interactive = spans.any { it.style == InlineStyle.Citation || it.style == InlineStyle.Link }

    Text(
        text = annotated,
        modifier = if (interactive) {
            modifier.pointerInput(annotated) {
                detectTapGestures { position ->
                    val layout = layoutState.value ?: return@detectTapGestures
                    val offset = layout.getOffsetForPosition(position)
                    annotated.getStringAnnotations(TAG_CITATION, offset, offset).firstOrNull()?.let {
                        onCitation?.invoke(it.item.toIntOrNull() ?: 0)
                        return@detectTapGestures
                    }
                    annotated.getStringAnnotations(TAG_LINK, offset, offset).firstOrNull()?.let {
                        onLinkClick?.invoke(it.item)
                    }
                }
            }
        } else modifier,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = fontSize,
            fontWeight = fontWeight,
            color = color,
            lineHeight = (fontSize.value * 1.62).sp,
        ),
        onTextLayout = { layoutState.value = it },
    )
}

private fun List<InlineSpan>.toAnnotatedString(palette: com.zhixue.assistant.ui.theme.ZhixuePalette) =
    buildAnnotatedString {
        forEach { span ->
            pushStringAnnotation(
                tag = when (span.style) {
                    InlineStyle.Citation -> TAG_CITATION
                    InlineStyle.Link -> TAG_LINK
                    else -> "text"
                },
                annotation = span.value ?: span.text,
            )
            withStyle(
                when (span.style) {
                    InlineStyle.Bold -> SpanStyle(fontWeight = FontWeight.SemiBold, color = palette.text)
                    InlineStyle.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
                    InlineStyle.Code -> SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = palette.soft,
                        color = palette.primaryDeep,
                        fontSize = 14.sp,
                    )

                    InlineStyle.Link -> SpanStyle(color = palette.primary, textDecoration = TextDecoration.Underline)
                    InlineStyle.Citation -> SpanStyle(color = palette.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    InlineStyle.Plain -> SpanStyle(color = palette.text)
                }
            ) {
                append(if (span.style == InlineStyle.Citation) "[${span.text}]" else span.text)
            }
            pop()
        }
    }

@Composable
fun PlainMarkdownFallback(source: String, modifier: Modifier = Modifier) {
    MarkdownText(source, modifier)
}
