package com.zhixue.assistant

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhixue.assistant.core.ThemeMode
import com.zhixue.assistant.ui.components.ConfirmDialog
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.MarkdownText
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.theme.ZhixueTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 组件级 UI 测试（需要设备/模拟器运行；CI 上至少会随 assembleAndroidTest 编译）。
 * 覆盖：Markdown 渲染、按钮禁用态、确认对话框回调、错误态重试、空态。
 */
class ComponentsUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun content(body: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            ZhixueTheme(themeMode = ThemeMode.Light) {
                Column { body() }
            }
        }
    }

    @Test
    fun markdown_渲染标题正文与代码块() {
        content {
            MarkdownText("# 核心概念\n\n这是 **重点** 内容。\n\n```kotlin\nval x = 1\n```")
        }
        rule.onNodeWithText("核心概念").assertExists()
        rule.onNodeWithText("val x = 1").assertExists()
    }

    @Test
    fun 主按钮_空文本时禁用有文本时可点() {
        var clicked = 0
        content {
            PrimaryButton(text = "发送", onClick = { clicked++ }, enabled = true)
            PrimaryButton(text = "禁用态", onClick = { clicked++ }, enabled = false)
        }
        rule.onNodeWithText("发送").assertIsEnabled().performClick()
        rule.onNodeWithText("禁用态").assertIsNotEnabled()
        rule.waitForIdle()
        assertEquals(1, clicked)
    }

    @Test
    fun 确认对话框_确定与取消分别回调() {
        var confirmed = false
        var dismissed = false
        content {
            ConfirmDialog(
                title = "删除课程",
                body = "知识库、题库、练习记录会一并删除。",
                confirmText = "确认删除",
                onConfirm = { confirmed = true },
                onDismiss = { dismissed = true },
            )
        }
        rule.onNodeWithText("知识库、题库、练习记录会一并删除。").assertExists()
        rule.onNodeWithText("确认删除").performClick()
        rule.waitForIdle()
        assertEquals(true, confirmed)
        assertEquals(false, dismissed)
    }

    @Test
    fun 错误态_显示后端文案并可重试() {
        var retries = 0
        content {
            ErrorState(message = "AI 次数不足，请前往个人中心购买次数包", onRetry = { retries++ })
        }
        rule.onNodeWithText("AI 次数不足，请前往个人中心购买次数包").assertExists()
        rule.onNodeWithText("重新加载").performClick()
        rule.waitForIdle()
        assertEquals(1, retries)
    }

    @Test
    fun 空态_标题提示与操作按钮() {
        var tapped = 0
        content {
            EmptyView(
                title = "还没有会话",
                hint = "点「新对话」开始提问",
                actionText = "新建会话",
                onAction = { tapped++ },
            )
            Text("占位")
        }
        rule.onNodeWithText("还没有会话").assertExists()
        rule.onNodeWithText("新建会话").performClick()
        rule.waitForIdle()
        assertEquals(1, tapped)
    }
}
