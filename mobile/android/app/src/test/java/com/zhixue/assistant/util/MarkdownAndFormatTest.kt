package com.zhixue.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownAndFormatTest {

    @Test
    fun `解析标题列表代码块与分割线`() {
        val source = """
            # 标题一
            正文段落，包含 **加粗** 与 `代码`。

            - 第一项
            - 第二项

            1. 步骤一
            2. 步骤二

            ```kotlin
            val a = 1
            ```

            ---
        """.trimIndent()
        val blocks = parseMarkdown(source)
        assertTrue(blocks.any { it is MdBlock.Heading && it.level == 1 })
        assertTrue(blocks.any { it is MdBlock.Bullet && it.items.size == 2 })
        assertTrue(blocks.any { it is MdBlock.Ordered && it.items.size == 2 })
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertTrue(code.code.contains("val a = 1"))
        assertTrue(blocks.any { it is MdBlock.Divider })
    }

    @Test
    fun `行内解析识别加粗斜体行内代码与链接`() {
        val spans = parseInline("看 **重点** 和 *斜体*，还有 `x++`，参考 [资料](https://a.b/c)")
        assertEquals(InlineStyle.Bold, spans.first { it.text == "重点" }.style)
        assertEquals(InlineStyle.Italic, spans.first { it.text == "斜体" }.style)
        assertEquals(InlineStyle.Code, spans.first { it.text == "x++" }.style)
        val link = spans.first { it.style == InlineStyle.Link }
        assertEquals("资料", link.text)
        assertEquals("https://a.b/c", link.value)
    }

    @Test
    fun `独立角标识别为引用且不吞掉普通方括号`() {
        val citation = parseInline("答案见 [3]。")
        assertTrue(citation.any { it.style == InlineStyle.Citation && it.text == "3" })
        // 与 Web mdToHtml 同源：成对的 [数字] 都按角标处理（数组下标写法也会跟着变角标）
        val arrayish = parseInline("数组 a[2] 的值")
        assertTrue(arrayish.any { it.style == InlineStyle.Citation && it.text == "2" })
        val partial = parseInline("参考 [资料 链接")
        assertTrue(partial.none { it.style == InlineStyle.Citation })
    }

    @Test
    fun `表格解析保留表头与行`() {
        val table = parseMarkdown("| 名称 | 分值 |\n|---|---|\n| 题一 | 10 |")
            .filterIsInstance<MdBlock.Table>()
            .single()
        assertEquals(listOf("名称", "分值"), table.header)
        assertEquals(listOf("题一", "10"), table.rows.first())
    }

    @Test
    fun `未闭合的粗体标记按原文输出不丢字符`() {
        val spans = parseInline("这里 **没有闭合")
        assertEquals("这里 **没有闭合", spans.joinToString("") { it.text })
    }

    @Test
    fun `选项与批改结果解析容错`() {
        val options = parseOptions("""[{"k":"A","v":"对"},{"k":"B","v":"错"}]""")
        assertEquals(2, options.size)
        assertEquals("对", options[0].text)
        assertTrue(parseOptions("不是 JSON").isEmpty())

        val review = parseReview("""{"summary":"思路正确","points":[{"point":"定义","hit":true,"note":"到位"}]}""")
        assertEquals("思路正确", review?.summary)
        assertTrue(review!!.points.first().hit)

        val fallback = parseReview("老师手写的评语")
        assertEquals("老师手写的评语", fallback?.summary)
        assertTrue(fallback!!.points.isEmpty())
    }

    @Test
    fun `推荐追问只取字符串数组且最多三条`() {
        val three = parseFollowups("""["a","b","c","d"]""")
        assertEquals(3, three.size)
        assertTrue(parseFollowups("null").isEmpty())
        assertTrue(parseFollowups("不是数组").isEmpty())
    }

    @Test
    fun `文档速览 JSON 字符串解析`() {
        val overview = parseOverviewJson("""{"summary":"总述","points":["p1","p2"],"examPoints":["e1"]}""")
        assertEquals(listOf("p1", "p2"), overview?.points)
        assertEquals(listOf("e1"), overview?.examPoints)
        assertNull(parseOverviewJson(""))
    }

    @Test
    fun `后端墙上时间按本地格式化`() {
        assertEquals("11-01 14:32", fmtDateTime("2026-11-01T14:32:30"))
        assertEquals("14:32", fmtClock("2026-11-01T14:32:30"))
        assertEquals("2026-11-01", fmtDate("2026-11-01T14:32:30"))
        assertEquals("", fmtDateTime(null))
        assertEquals("", fmtDateTime("完全不是时间"))
    }

    @Test
    fun `自动标题压缩空白并截断`() {
        assertEquals("请解释 牛顿定律", autoTitle("请解释\n  牛顿定律"))
        val long = "一".repeat(30)
        assertEquals(21, autoTitle(long).length)
        assertTrue(autoTitle(long).endsWith("…"))
    }

    @Test
    fun `价格与文件大小展示`() {
        assertEquals("¥9.90", fmtFen(990))
        assertEquals("¥19.90", fmtFen(1990))
        assertEquals("未知大小", humanizeSize(null))
        assertEquals("1.0 KB", humanizeSize(1024))
        assertEquals("2.0 MB", humanizeSize(2L * 1024 * 1024))
    }

    @Test
    fun `引用 id 串解析`() {
        assertEquals(listOf("1", "2", "3"), parseIdCsv("1, 2 ,3"))
        assertTrue(parseIdCsv(null).isEmpty())
    }
}
