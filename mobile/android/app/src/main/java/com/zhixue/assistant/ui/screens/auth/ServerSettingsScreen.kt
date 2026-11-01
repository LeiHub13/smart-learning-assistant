package com.zhixue.assistant.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.SectionTitle
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.theme.LocalZhixuePalette

/**
 * 服务器设置：改地址（校验后生效）、记住历史、一键切回最近地址、`GET /api/health` 探活。
 * 逻辑与登录页折叠面板共用同一份 [ServerViewModel] / [ServerEditorCard]。
 */
@Composable
fun ServerSettingsScreen() {
    val container = LocalAppContainer.current
    val palette = LocalZhixuePalette.current
    val vm: ServerViewModel = viewModel(factory = ServerViewModel.factory(container))

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageHeader(
            title = "服务器设置",
            subtitle = "后端地址决定所有接口、图片与实时连接的去处；登录态按服务器分别保存。",
        )

        ServerEditorCard(vm = vm)

        ZhixueCard(title = "该填哪个地址") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ServerAddressHint(
                    "本机 / 模拟器调试",
                    "Android 模拟器访问宿主机的后端用 http://10.0.2.2:8080（仅调试包允许 HTTP）。",
                )
                ServerAddressHint(
                    "局域网真机",
                    "手机与电脑同一 WiFi 时填电脑的局域网地址，例如 http://192.168.1.5:8080。",
                )
                ServerAddressHint(
                    "线上部署",
                    "填带证书的 HTTPS 域名；正式包拒绝 http://，会直接给出拒绝原因。",
                )
            }
        }

        ZhixueCard(title = "切换地址会发生什么") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ServerAddressHint(
                    "登录态独立",
                    "每个地址各自保存自己的 token 与用户信息，切换后读取那台服务器自己的登录态。",
                )
                ServerAddressHint(
                    "缓存与连接重建",
                    "课程缓存清空，对话厅 WebSocket 断开重连，通知长连接与学习心跳按新地址重新启动。",
                )
                ServerAddressHint(
                    "会话编号不复用",
                    "答疑会话 id、对话厅频道都是服务器相关的，切换后不会把旧 id 发给新后端。",
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "当前生效地址：${vm.currentUrl}",
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
        )
    }
}

@Composable
private fun ServerAddressHint(title: String, body: String) {
    val palette = LocalZhixuePalette.current
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(body, style = MaterialTheme.typography.bodySmall, color = palette.muted)
    }
}
