package com.zhixue.assistant.util

/**
 * 轻量 Markdown 解析：Web 端用 mdToHtml + v-html，Android 不能套 WebView，
 * 因此这里解析成块级结构交给 Compose 渲染。覆盖答疑/讲义实际用到的语法：
 * 标题、段落、有序/无序列表、围栏代码、行内代码、加粗/斜体、链接、引用、分割线、表格、
 * 以及答疑特有的 [1] 这类引用角标。
 */
enum class InlineStyle { Plain, Bold, Italic, Code, Link, Citation }

data class InlineSpan(
    val text: String,
    val style: InlineStyle = InlineStyle.Plain,
    /** Link 时为 url；Citation 时为角标序号。 */
    val value: String? = null,
)

sealed class MdBlock {
    data class Heading(val level: Int, val inline: List<InlineSpan>) : MdBlock()
    data class Paragraph(val inline: List<InlineSpan>) : MdBlock()
    data class Bullet(val items: List<List<InlineSpan>>) : MdBlock()
    data class Ordered(val items: List<List<InlineSpan>>) : MdBlock()
    data class Code(val language: String?, val code: String) : MdBlock()
    data class Quote(val inline: List<InlineSpan>) : MdBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock()
    data object Divider : MdBlock()
}

private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val bulletRegex = Regex("^\\s*[-*+]\\s+(.*)$")
private val orderedRegex = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")
private val fenceRegex = Regex("^\\s*```(\\S*)\\s*$")
private val hrRegex = Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$")
private val quoteRegex = Regex("^>\\s?(.*)$")
private val citationRegex = Regex("^\\[(\\d{1,3})\\]")

fun parseMarkdown(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = source.replace("\r\n", "\n").split("\n")
    var i = 0
    val paragraph = StringBuilder()

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) blocks += MdBlock.Paragraph(parseInline(text))
        paragraph.setLength(0)
    }

    while (i < lines.size) {
        val line = lines[i]
        when {
            fenceRegex.matches(line) -> {
                flushParagraph()
                val lang = fenceRegex.find(line)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                val body = StringBuilder()
                i++
                while (i < lines.size && !fenceRegex.matches(lines[i])) {
                    body.appendLine(lines[i])
                    i++
                }
                blocks += MdBlock.Code(lang, body.toString().trimEnd('\n'))
                if (i < lines.size) i++
                continue
            }

            hrRegex.matches(line) -> {
                flushParagraph()
                blocks += MdBlock.Divider
            }

            headingRegex.matches(line) -> {
                flushParagraph()
                val m = headingRegex.find(line)!!
                blocks += MdBlock.Heading(m.groupValues[1].length, parseInline(m.groupValues[2].trim()))
            }

            quoteRegex.matches(line) -> {
                flushParagraph()
                val text = StringBuilder()
                while (i < lines.size && quoteRegex.matches(lines[i])) {
                    text.append(quoteRegex.find(lines[i])!!.groupValues[1]).append(' ')
                    i++
                }
                blocks += MdBlock.Quote(parseInline(text.toString().trim()))
                continue
            }

            looksLikeTableRow(line, lines.getOrNull(i + 1)) -> {
                flushParagraph()
                val header = splitTableRow(line)
                val separator = splitTableRow(lines[i + 1])
                if (separator.all { it.matches(Regex(":?-{2,}:?")) } || separator.all { it.matches(Regex("[:\\-\\s]+")) }) {
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                        rows += splitTableRow(lines[i])
                        i++
                    }
                    blocks += MdBlock.Table(header, rows)
                    continue
                }
                blocks += MdBlock.Paragraph(parseInline(line))
            }

            bulletRegex.matches(line) -> {
                flushParagraph()
                val items = mutableListOf<List<InlineSpan>>()
                while (i < lines.size && bulletRegex.matches(lines[i])) {
                    items += parseInline(bulletRegex.find(lines[i])!!.groupValues[1].trim())
                    i++
                }
                blocks += MdBlock.Bullet(items)
                continue
            }

            orderedRegex.matches(line) -> {
                flushParagraph()
                val items = mutableListOf<List<InlineSpan>>()
                while (i < lines.size && orderedRegex.matches(lines[i])) {
                    items += parseInline(orderedRegex.find(lines[i])!!.groupValues[2].trim())
                    i++
                }
                blocks += MdBlock.Ordered(items)
                continue
            }

            line.isBlank() -> flushParagraph()

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
        i++
    }
    flushParagraph()
    return blocks
}

private fun looksLikeTableRow(line: String, next: String?): Boolean =
    line.contains('|') && next != null && next.contains('-') && next.contains('|')

private fun splitTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

/**
 * 行内解析。顺序敏感：先 `code`（内部不再解析），再链接、加粗、斜体，最后普通文本；
 * 独立的 [数字] 识别成引用角标（答疑场景点按可看原文）。
 */
fun parseInline(text: String): List<InlineSpan> {
    val spans = mutableListOf<InlineSpan>()
    var plain = StringBuilder()

    fun flush() {
        if (plain.isNotEmpty()) {
            spans += InlineSpan(plain.toString())
            plain = StringBuilder()
        }
    }

    var i = 0
    while (i < text.length) {
        val rest = text.substring(i)
        when {
            rest.startsWith("`") -> {
                val close = rest.indexOf('`', 1)
                if (close > 0) {
                    flush()
                    spans += InlineSpan(rest.substring(1, close), InlineStyle.Code)
                    i += close + 1
                } else {
                    plain.append(text[i]); i++
                }
            }

            rest.startsWith("**") || rest.startsWith("__") -> {
                val marker = rest.substring(0, 2)
                val close = rest.indexOf(marker, 2)
                if (close > 0) {
                    flush()
                    spans += InlineSpan(rest.substring(2, close), InlineStyle.Bold)
                    i += close + 2
                } else {
                    plain.append(text[i]); i++
                }
            }

            rest.startsWith("[") -> {
                val linkMatch = Regex("^\\[([^\\]]*)\\]\\(([^)\\s]+)\\)").find(rest)
                val citationMatch = citationRegex.find(rest)
                when {
                    linkMatch != null -> {
                        flush()
                        spans += InlineSpan(linkMatch.groupValues[1], InlineStyle.Link, linkMatch.groupValues[2])
                        i += linkMatch.value.length
                    }

                    citationMatch != null -> {
                        flush()
                        spans += InlineSpan(citationMatch.groupValues[1], InlineStyle.Citation, citationMatch.groupValues[1])
                        i += citationMatch.value.length
                    }

                    else -> {
                        plain.append(text[i]); i++
                    }
                }
            }

            (rest.startsWith("*") || rest.startsWith("_")) && !rest.startsWith("**") -> {
                val marker = rest[0]
                // 单个 * 需要成对且内部非空才按斜体处理，否则当普通字符（例如列表符号残留、乘号）
                val close = rest.indexOf(marker, 1)
                val inner = if (close > 1) rest.substring(1, close) else ""
                if (close > 1 && inner.none { it.isWhitespace() } && inner.isNotEmpty()) {
                    flush()
                    spans += InlineSpan(inner, InlineStyle.Italic)
                    i += close + 1
                } else {
                    plain.append(text[i]); i++
                }
            }

            else -> {
                plain.append(text[i]); i++
            }
        }
    }
    flush()
    return spans
}

/** 流式生成期间把 Markdown 近似成可读纯文本（与 uni-app 版行为一致：先文本，结束再排版）。 */
fun stripMarkdown(text: String): String = text
    .replace(Regex("```[\\s\\S]*?```"), " ")
    .replace(Regex("[#*_`>]+"), "")
    .replace(Regex("\\n{3,}"), "\n\n")
