package com.zhixue.assistant.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.LabeledField
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.cardShape
import com.zhixue.assistant.ui.theme.LocalZhixuePalette

/**
 * 登录 / 注册 / 找回密码三态。表单校验与错误提示完全对齐 Web 的 LoginView，
 * 后端业务错误（含「发送太频繁，请 60 秒后再试」）原样显示。
 */
@Composable
fun LoginScreen() {
    val container = LocalAppContainer.current
    val palette = LocalZhixuePalette.current
    val vm: AuthViewModel = viewModel(factory = AuthViewModel.factory(container))
    val serverVm: ServerViewModel = viewModel(factory = ServerViewModel.factory(container))
    var serverOpen by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .safeDrawingPadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.text),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("智", color = palette.canvas, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(Modifier.size(8.dp))
                Text("智学助手", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(palette.card)
                    .border(1.dp, palette.border, cardShape)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (vm.mode == AuthMode.Forgot) {
                    TextButton(onClick = { vm.switchMode(AuthMode.Login) }) {
                        Text("← 返回登录", color = palette.primary)
                    }
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.soft)
                            .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ModeTab("登 录", vm.mode == AuthMode.Login, Modifier.weight(1f)) {
                            vm.switchMode(AuthMode.Login)
                        }
                        ModeTab("注 册", vm.mode == AuthMode.Register, Modifier.weight(1f)) {
                            vm.switchMode(AuthMode.Register)
                        }
                    }
                }

                Column {
                    Text(vm.titleText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(vm.subtitleText, style = MaterialTheme.typography.bodySmall, color = palette.muted)
                }

                when (vm.mode) {
                    AuthMode.Login -> LoginForm(vm)
                    AuthMode.Register -> RegisterForm(vm)
                    AuthMode.Forgot -> ForgotForm(vm)
                }

                vm.error?.let {
                    HintBanner(it, BannerTone.Error, onDismiss = vm::clearMessages)
                }
                vm.notice?.let {
                    HintBanner(it, BannerTone.Success, onDismiss = vm::clearMessages)
                }

                PrimaryButton(
                    text = vm.submitText,
                    onClick = vm::submit,
                    modifier = Modifier.fillMaxWidth(),
                    loading = vm.submitting,
                )

                if (vm.mode == AuthMode.Login) {
                    TextButton(onClick = { vm.switchMode(AuthMode.Forgot) }) {
                        Text("忘记密码？", color = palette.primary)
                    }
                }
            }

            // 折叠的服务器设置：未登录时也能改后端地址并探活
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.card)
                    .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                    .clickable { serverOpen = !serverOpen }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("服务器设置", fontWeight = FontWeight.SemiBold)
                    Text(
                        serverVm.currentUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                        maxLines = 1,
                    )
                }
                Text(if (serverOpen) "收起" else "修改地址 / 测试连接", color = palette.primary, style = MaterialTheme.typography.bodySmall)
            }
            if (serverOpen) {
                ServerEditorCard(vm = serverVm)
            }

            Text(
                "登录即表示数据保存在你所配置的这台后端上。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
    }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalZhixuePalette.current
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) palette.card else palette.soft)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) palette.text else palette.muted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LoginForm(vm: AuthViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LabeledField(
            label = "用户名",
            value = vm.username,
            onValueChange = { vm.username = it },
            placeholder = "请输入用户名",
        )
        PasswordField(
            label = "密码",
            value = vm.password,
            onValueChange = { vm.password = it },
            placeholder = "请输入密码",
        )
    }
}

@Composable
private fun RegisterForm(vm: AuthViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LabeledField(
            label = "用户名",
            value = vm.username,
            onValueChange = { vm.username = it },
            placeholder = "登录账号，全局唯一",
            maxLength = 32,
        )
        PasswordField(
            label = "密码",
            value = vm.password,
            onValueChange = { vm.password = it },
            placeholder = "至少 6 位",
            helper = "至少 6 位",
        )
        LabeledField(
            label = "昵称（可选）",
            value = vm.nickname,
            onValueChange = { vm.nickname = it },
            placeholder = "留空时与用户名相同",
            maxLength = 20,
        )
        LabeledField(
            label = "邮箱",
            value = vm.email,
            onValueChange = { vm.email = it },
            placeholder = "name@qq.com，用于接收验证码",
            keyboardType = KeyboardType.Email,
        )
        CodeRow(
            vm = vm,
            codeLabel = "邮箱验证码",
        )
    }
}

@Composable
private fun ForgotForm(vm: AuthViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LabeledField(
            label = "邮箱",
            value = vm.email,
            onValueChange = { vm.email = it },
            placeholder = "注册 / 绑定的邮箱",
            keyboardType = KeyboardType.Email,
        )
        CodeRow(vm = vm, codeLabel = "邮箱验证码")
        PasswordField(
            label = "新密码",
            value = vm.newPassword,
            onValueChange = { vm.newPassword = it },
            placeholder = "至少 6 位",
        )
        PasswordField(
            label = "确认新密码",
            value = vm.confirmPassword,
            onValueChange = { vm.confirmPassword = it },
            placeholder = "再输入一次新密码",
        )
    }
}

/** 验证码输入 + 发送按钮（60 秒倒计时禁用重发）。 */
@Composable
private fun CodeRow(vm: AuthViewModel, codeLabel: String) {
    val palette = LocalZhixuePalette.current
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LabeledField(
            label = codeLabel,
            value = vm.code,
            onValueChange = { next -> vm.code = next.filter { it.isDigit() }.take(6) },
            placeholder = "6 位验证码",
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f),
        )
        SecondaryButton(
            text = when {
                vm.countdown > 0 -> "${vm.countdown}s 后重发"
                vm.sending -> "发送中…"
                else -> "发送验证码"
            },
            onClick = vm::sendEmailCode,
            enabled = vm.countdown == 0 && !vm.sending,
        )
    }
    Text(
        "验证码 10 分钟内有效；重复点击会收到后端的冷却提示。",
        style = MaterialTheme.typography.bodySmall,
        color = palette.muted,
    )
}
