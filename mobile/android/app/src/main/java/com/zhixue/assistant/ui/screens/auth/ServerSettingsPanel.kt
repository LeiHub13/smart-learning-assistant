package com.zhixue.assistant.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.ChipRow
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.LabeledField
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.SectionTitle
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.theme.LocalZhixuePalette

/**
 * 服务器地址编辑面板：服务器设置整页与登录页的折叠区共用同一份逻辑与文案，
 * 避免两处对「地址校验 / 探活 / 历史地址」的理解出现分叉。
 */
@Composable
fun ServerEditorCard(
    vm: ServerViewModel,
    modifier: Modifier = Modifier,
) {
    val palette = LocalZhixuePalette.current
    val recent by vm.recentServers.collectAsState()

    ZhixueCard(modifier = modifier, title = "服务器地址", subtitle = "所有接口、SSE、WebSocket 与图片都以这里为唯一来源") {
        LabeledField(
            label = "后端地址",
            value = vm.input,
            onValueChange = vm::updateInput,
            placeholder = "https://your-server 或 http://192.168.1.5:8080",
            helper = "不带协议前缀时按 https 处理；正式包只允许 HTTPS，调试包可用局域网 HTTP。",
        )
        Spacer(Modifier.height(10.dp))

        vm.error?.let {
            HintBanner(it, BannerTone.Error, onDismiss = vm::clearMessages)
            Spacer(Modifier.height(8.dp))
        }
        vm.notice?.let {
            HintBanner(it, BannerTone.Success, onDismiss = vm::clearMessages)
            Spacer(Modifier.height(8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                text = "保存并应用",
                onClick = { vm.applyAddress() },
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = "测试连接",
                onClick = vm::probeConnection,
                modifier = Modifier.weight(1f),
                enabled = vm.probe !is UiState.Loading,
            )
        }
        Spacer(Modifier.height(10.dp))

        when (val state = vm.probe) {
            null -> Text(
                "尚未测试：将向 ${vm.currentUrl}/api/health 发起一次公开请求。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )

            is UiState.Loading -> InlineLoading("正在连接 /api/health …")

            is UiState.Error -> HintBanner("探活失败：${state.message}", BannerTone.Error)

            is UiState.Ready -> {
                val result = state.data
                if (result.reachable) {
                    HintBanner(
                        "后端可达：HTTP ${result.httpStatus}，耗时 ${result.latencyMs} ms",
                        BannerTone.Success,
                    )
                    if (!result.detail.isNullOrBlank()) {
                        Text(
                            result.detail,
                            modifier = Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = palette.muted,
                            maxLines = 3,
                        )
                    }
                } else {
                    HintBanner(
                        "后端不可达：${result.detail ?: "HTTP ${result.httpStatus}"}（耗时 ${result.latencyMs} ms）",
                        BannerTone.Error,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionTitle("最近使用的地址")
        if (recent.isEmpty()) {
            Text("暂无历史地址；保存成功后会记在这里，可一键切回。", style = MaterialTheme.typography.bodySmall, color = palette.muted)
        } else {
            ChipRow(
                options = recent,
                selected = vm.currentUrl,
                onSelect = { url -> url?.let { vm.useRecent(it) } },
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "当前生效：${vm.currentUrl}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = palette.muted,
        )
        HintBanner(
            "登录态按服务器分别保存：切换地址后只会读取那台后端自己的 token，不会把 A 服务器的令牌发给 B 服务器。",
            BannerTone.Info,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
    }
}
