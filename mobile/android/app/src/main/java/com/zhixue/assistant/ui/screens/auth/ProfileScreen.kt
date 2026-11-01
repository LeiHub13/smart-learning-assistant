package com.zhixue.assistant.ui.screens.auth

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.core.MeDto
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.CheckboxRow
import com.zhixue.assistant.ui.components.ChipRow
import com.zhixue.assistant.ui.components.ConfirmDialog
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.LabeledField
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavController
import com.zhixue.assistant.ui.components.LocalToast
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.UserAvatar
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.nav.Routes
import com.zhixue.assistant.ui.theme.LocalZhixuePalette

/**
 * 个人中心：头像、用户名/昵称/邮箱、通知邮件偏好、两种改密方式、数据导出与账号入口。
 * 校验规则与 Web 的 ProfileView 一致；所有失败提示都显示后端原文。
 */
@Composable
fun ProfileScreen() {
    val container = LocalAppContainer.current
    val palette = LocalZhixuePalette.current
    val context = LocalContext.current
    val toast = LocalToast.current
    val navController = LocalNavController.current
    val vm: ProfileViewModel = viewModel(factory = ProfileViewModel.factory(container))

    val session by container.session.session.collectAsState()
    val me = session?.me
    val quota by container.quota.collectAsState()

    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(me) { vm.onMeChanged(me) }
    LaunchedEffect(vm.toast) {
        vm.toast?.let {
            toast(it)
            vm.consumeToast()
        }
    }

    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> if (uri != null) vm.uploadAvatar(context, uri) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageHeader(
            title = "个人中心",
            subtitle = "资料、通知邮件、密码与数据都在这里；改动立即同步到当前后端。",
        )

        if (me == null) {
            when (val state = vm.load) {
                is UiState.Error -> ErrorState(
                    message = "账号信息读取失败：${state.message}",
                    onRetry = vm::refresh,
                )

                else -> InlineLoading("正在读取账号信息…")
            }
        } else {
            vm.error?.let { HintBanner(it, BannerTone.Error, onDismiss = vm::clearMessages) }
            vm.msg?.let { HintBanner(it, BannerTone.Success, onDismiss = vm::clearMessages) }

            IdentityCard(
                me = me,
                vm = vm,
                onPickAvatar = {
                    avatarPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
            )

            ProfileFieldsCard(me = me, vm = vm)

            NotifyPrefCard(vm = vm)

            PasswordCard(me = me, vm = vm)

            ExportCard(vm = vm, onExport = { kind -> vm.export(context, kind) })

            ZhixueCard(title = "账号与其他", subtitle = "次数包、服务器地址与退出登录") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("AI 次数与套餐", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = when {
                                    quota == null -> "正在读取额度…"
                                    quota?.enabled == true -> "剩余 ${quota?.balance ?: 0} 次"
                                    else -> "当前未开启次数包"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.muted,
                            )
                        }
                        SecondaryButton("查看套餐", onClick = { navController.navigate(Routes.BILLING) })
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("服务器设置", fontWeight = FontWeight.SemiBold)
                            Text(
                                container.server.baseUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.muted,
                                maxLines = 1,
                            )
                        }
                        SecondaryButton("修改地址", onClick = { navController.navigate(Routes.SERVER) })
                    }
                    SignOutRow(onSignOut = vm::signOut)
                }
            }
        }
    }
}

@Composable
private fun IdentityCard(
    me: MeDto,
    vm: ProfileViewModel,
    onPickAvatar: () -> Unit,
) {
    val palette = LocalZhixuePalette.current
    ZhixueCard(title = "头像与身份") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(
                name = me.nickname.ifBlank { me.username },
                avatarPath = me.avatar.ifBlank { null },
                size = 60.dp,
                userId = me.userId,
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    me.nickname.ifBlank { me.username },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "@${me.username} · ${me.email.ifBlank { "尚未绑定邮箱" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                )
                if (me.admin) {
                    Spacer(Modifier.height(6.dp))
                    TagBadge("管理员账号", TagTone.Info)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SecondaryButton(
                text = if (vm.uploading) "上传中…" else "更换头像",
                onClick = onPickAvatar,
                enabled = !vm.uploading,
            )
            if (vm.uploading) InlineLoading("正在上传头像…")
        }
        Text(
            "支持 JPG / PNG / WebP，不超过 2MB；格式或体积不合规会被拒绝，失败时显示后端原因。",
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
        )
    }
}

@Composable
private fun ProfileFieldsCard(me: MeDto, vm: ProfileViewModel) {
    val palette = LocalZhixuePalette.current
    ZhixueCard(title = "基本资料", subtitle = "这些信息会显示在个人中心与对话厅") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FieldWithSave(
                label = "用户名",
                value = vm.usernameDraft,
                onValueChange = { vm.usernameDraft = it },
                placeholder = "登录账号，全局唯一",
                maxLength = 32,
                enabled = !me.admin && !vm.savingUsername,
                helper = if (me.admin) "管理员账号不允许修改用户名" else "修改后下次登录请使用新用户名",
                saveText = "保存用户名",
                saving = vm.savingUsername,
                onSave = { vm.saveUsername(me) },
            )
            FieldWithSave(
                label = "昵称",
                value = vm.nicknameDraft,
                onValueChange = { vm.nicknameDraft = it },
                placeholder = "展示在页面上的名字",
                maxLength = 20,
                enabled = !vm.savingNickname,
                helper = "最多 20 个字",
                saveText = "保存昵称",
                saving = vm.savingNickname,
                onSave = vm::saveNickname,
            )
            FieldWithSave(
                label = "通知邮箱",
                value = vm.emailDraft,
                onValueChange = { vm.emailDraft = it },
                placeholder = "用于接收复习提醒与公告",
                keyboardType = KeyboardType.Email,
                enabled = !vm.savingEmail,
                helper = "邮箱验证码找回/改密也依赖它",
                saveText = "保存邮箱",
                saving = vm.savingEmail,
                onSave = vm::saveEmail,
            )
        }
        Text(
            "保存后页面显示的账号信息会立即从后端重新拉取。",
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 「一个字段一个保存按钮」，与 Web 的 field-control 布局一致。 */
@Composable
private fun FieldWithSave(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    saveText: String,
    saving: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    maxLength: Int? = null,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    helper: String? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LabeledField(
            label = label,
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            maxLength = maxLength,
            enabled = enabled,
            keyboardType = keyboardType,
            helper = helper,
            modifier = Modifier.weight(1f),
        )
        SecondaryButton(
            text = if (saving) "保存中…" else saveText,
            onClick = onSave,
            enabled = enabled && !saving,
        )
    }
}

@Composable
private fun NotifyPrefCard(vm: ProfileViewModel) {
    val palette = LocalZhixuePalette.current
    ZhixueCard(title = "通知邮件偏好", subtitle = "勾选的类型会发送邮件；站内通知不受影响") {
        Column {
            ProfileViewModel.MAIL_TYPES.forEach { (type, label) ->
                CheckboxRow(
                    label = label,
                    checked = !vm.mutedTypes.contains(type),
                    onCheckedChange = { vm.toggleMailType(type, it) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PrimaryButton(
                text = "保存偏好",
                onClick = vm::saveNotifyPref,
                loading = vm.savingPref,
            )
            Text(
                text = if (vm.mutedTypes.isEmpty()) "当前：全部类型接收邮件" else "当前不收邮件：${vm.mutedLabels}",
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
    }
}

@Composable
private fun PasswordCard(me: MeDto, vm: ProfileViewModel) {
    val palette = LocalZhixuePalette.current
    ZhixueCard(title = "修改密码", subtitle = "可以使用原密码，也可以用邮箱验证码") {
        ChipRow(
            options = listOf("原密码方式", "邮箱验证码方式"),
            selected = if (vm.passwordByEmail) "邮箱验证码方式" else "原密码方式",
            onSelect = { picked ->
                val wantEmail = picked == "邮箱验证码方式"
                if (wantEmail != vm.passwordByEmail) vm.togglePasswordMode()
            },
        )
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!vm.passwordByEmail) {
                PasswordField(
                    label = "原密码",
                    value = vm.oldPassword,
                    onValueChange = { vm.oldPassword = it },
                    placeholder = "当前使用的密码",
                )
            } else {
                LabeledField(
                    label = "绑定邮箱",
                    value = me.email.ifBlank { "未绑定" },
                    onValueChange = {},
                    enabled = false,
                )
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField(
                        label = "邮箱验证码",
                        value = vm.resetCode,
                        onValueChange = { next -> vm.resetCode = next.filter { it.isDigit() }.take(6) },
                        placeholder = "6 位验证码",
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = when {
                            vm.countdown > 0 -> "${vm.countdown}s 后重发"
                            else -> "发送验证码"
                        },
                        onClick = { vm.sendPasswordCode(me) },
                        enabled = vm.countdown == 0 && me.email.isNotBlank(),
                    )
                }
                if (me.email.isBlank()) {
                    Text(
                        "尚未绑定邮箱，请先到「基本资料」中保存邮箱。",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.warn,
                    )
                }
            }
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
            )
            PrimaryButton(
                text = if (vm.passwordByEmail) "确认修改" else "修改密码",
                onClick = { if (vm.passwordByEmail) vm.savePasswordByEmail(me) else vm.savePassword() },
                loading = vm.savingPwd,
            )
        }
    }
}

@Composable
private fun ExportCard(vm: ProfileViewModel, onExport: (ExportKind) -> Unit) {
    val palette = LocalZhixuePalette.current
    ZhixueCard(title = "我的数据导出", subtitle = "导出为 Excel：优先写入系统「下载」目录，不可用时改用分享面板") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(
                text = if (vm.exporting == ExportKind.Practice) "导出中…" else "导出练习记录",
                onClick = { onExport(ExportKind.Practice) },
                enabled = vm.exporting == null,
            )
            SecondaryButton(
                text = if (vm.exporting == ExportKind.Exams) "导出中…" else "导出考试成绩",
                onClick = { onExport(ExportKind.Exams) },
                enabled = vm.exporting == null,
            )
        }
        if (vm.exporting != null) {
            Spacer(Modifier.height(8.dp))
            InlineLoading("正在生成并保存文件…")
        }
        Text(
            "导出内容只包含你自己的数据。",
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun SignOutRow(onSignOut: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    SecondaryButton("退出登录", onClick = { confirm = true }, danger = true)
    if (confirm) {
        ConfirmDialog(
            title = "退出登录",
            body = "只清除这台服务器在本机的登录态，其他后端的登录状态不受影响。",
            confirmText = "退出",
            danger = true,
            onConfirm = {
                confirm = false
                onSignOut()
            },
            onDismiss = { confirm = false },
        )
    }
}
