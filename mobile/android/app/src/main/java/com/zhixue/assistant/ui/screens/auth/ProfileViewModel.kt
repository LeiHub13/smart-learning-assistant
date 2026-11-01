package com.zhixue.assistant.ui.screens.auth

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.MeDto
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.repo.MAIL_NOTIFY_TYPES
import com.zhixue.assistant.util.FileActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 通知类型的中文名，与 Web 端 ProfileView 的 NOTIFY_TYPES 完全一致（key 顺序即展示顺序）。 */
val MAIL_TYPE_LABELS: Map<String, String> = mapOf(
    "review" to "复习提醒",
    "plan" to "学习计划",
    "system" to "系统通知",
    "exam" to "考试",
    "practice" to "练习",
    "dm" to "私信",
    "friend" to "好友",
    "announcement" to "公告",
)

/** 导出目标：路径与文件名与后端 ExportController 一致，价格/条数一律不写死。 */
enum class ExportKind(val path: String, val fileName: String) {
    Practice("/api/export/practice.xlsx", "练习记录.xlsx"),
    Exams("/api/export/exams.xlsx", "考试成绩.xlsx"),
}

/** 前端本地校验失败（体积、格式），消息文案与后端保持一致，便于用户对照。 */
private class FieldReject(message: String) : Exception(message)

/**
 * 个人中心：资料、头像、通知偏好、密码与数据导出。
 * 校验规则与 `frontend/src/views/ProfileView.vue` 一致，业务失败一律显示后端 message 原文。
 */
class ProfileViewModel(private val container: AppContainer) : ViewModel() {

    /** `/api/auth/me` 的刷新状态：null 表示还在用本地缓存，不需要整页 Loading。 */
    var load by mutableStateOf<UiState<MeDto>?>(null)
        private set

    var usernameDraft by mutableStateOf("")
    var nicknameDraft by mutableStateOf("")
    var emailDraft by mutableStateOf("")

    /** 未勾选（不发邮件）的通知类型集合。 */
    var mutedTypes by mutableStateOf(setOf<String>())
        private set

    var savingUsername by mutableStateOf(false)
        private set
    var savingNickname by mutableStateOf(false)
        private set
    var savingEmail by mutableStateOf(false)
        private set
    var savingPref by mutableStateOf(false)
        private set
    var savingPwd by mutableStateOf(false)
        private set
    var uploading by mutableStateOf(false)
        private set
    var exporting by mutableStateOf<ExportKind?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set
    var msg by mutableStateOf<String?>(null)
        private set

    /** 一次性提示：页面消费后调用 [consumeToast]，用于导出结果这类需要离屏说明的动作。 */
    var toast by mutableStateOf<String?>(null)
        private set

    var passwordByEmail by mutableStateOf(false)
    var oldPassword by mutableStateOf("")
    var newPassword by mutableStateOf("")
    var confirmPassword by mutableStateOf("")
    var resetCode by mutableStateOf("")
    var countdown by mutableStateOf(0)
        private set

    private var countdownJob: Job? = null
    private var synced: MeDto? = null

    val mutedLabels: String
        get() = MAIL_NOTIFY_TYPES.filter { mutedTypes.contains(it) }
            .joinToString("、") { MAIL_TYPE_LABELS[it] ?: it }

    /** me 变化后同步草稿；同一份数据重复回调不会覆盖用户尚未保存的输入。 */
    fun onMeChanged(me: MeDto?) {
        if (me == null) return
        val previous = synced
        synced = me
        if (previous != null &&
            previous.username == me.username &&
            previous.nickname == me.nickname &&
            previous.email == me.email &&
            previous.mailMute == me.mailMute
        ) return
        usernameDraft = me.username
        nicknameDraft = me.nickname
        emailDraft = me.email
        mutedTypes = me.mutedMailTypes()
    }

    fun refresh() {
        viewModelScope.launch {
            load = UiState.Loading
            load = runCatching { container.auth.fetchMe() }
                .fold({ UiState.Ready(it) }, { UiState.Error(it.userMessage()) })
        }
    }

    fun clearMessages() {
        error = null
        msg = null
    }

    fun consumeToast() {
        toast = null
    }

    fun signOut() = container.onSignedOut()

    // ---------- 资料 ----------

    fun saveUsername(me: MeDto?) {
        val value = usernameDraft.trim()
        if (me?.admin == true) {
            error = "管理员账号不允许修改用户名"
            return
        }
        if (value.isEmpty()) {
            error = "用户名不能为空"
            return
        }
        if (value == me?.username) {
            error = "新用户名与当前用户名相同"
            return
        }
        launchAction({ savingUsername = it }) {
            container.auth.updateUsername(value)
            "用户名已保存，下次登录请使用新用户名"
        }
    }

    fun saveNickname() {
        val value = nicknameDraft.trim()
        if (value.isEmpty()) {
            error = "昵称不能为空"
            return
        }
        launchAction({ savingNickname = it }) {
            container.auth.updateNickname(value)
            "昵称已保存"
        }
    }

    fun saveEmail() {
        val value = emailDraft.trim()
        if (value.isEmpty()) {
            error = "请填写邮箱地址"
            return
        }
        if (!EMAIL_PATTERN.matches(value)) {
            error = "邮箱格式不正确"
            return
        }
        launchAction({ savingEmail = it }) {
            container.auth.updateEmail(value)
            "邮箱已保存"
        }
    }

    // ---------- 通知偏好 ----------

    /** 勾选语义 = 发送邮件；保存时把未勾选的类型拼成 mailMute 逗号串。 */
    fun toggleMailType(type: String, checked: Boolean) {
        mutedTypes = if (checked) mutedTypes - type else mutedTypes + type
    }

    fun saveNotifyPref() {
        launchAction({ savingPref = it }) {
            container.auth.saveNotifyPref(mutedTypes)
            "通知邮件偏好已保存"
        }
    }

    // ---------- 密码 ----------

    fun togglePasswordMode() {
        passwordByEmail = !passwordByEmail
        error = null
        msg = null
        oldPassword = ""
        resetCode = ""
        newPassword = ""
        confirmPassword = ""
    }

    fun savePassword() {
        if (newPassword.length < MIN_PASSWORD_LENGTH) {
            error = "新密码至少 $MIN_PASSWORD_LENGTH 位"
            return
        }
        if (newPassword != confirmPassword) {
            error = "两次输入的新密码不一致"
            return
        }
        launchAction({ savingPwd = it }) {
            container.auth.changePassword(oldPassword, newPassword)
            oldPassword = ""
            newPassword = ""
            confirmPassword = ""
            "密码已修改"
        }
    }

    fun sendPasswordCode(me: MeDto?) {
        val mail = me?.email?.trim().orEmpty()
        if (mail.isEmpty()) {
            error = "尚未绑定邮箱，请先在资料设置中保存邮箱"
            return
        }
        viewModelScope.launch {
            error = null
            msg = null
            val result = runCatching {
                container.auth.sendEmailCode(mail, AuthViewModel.SCENE_RESET_PASSWORD)
            }
            if (result.isSuccess) {
                msg = "验证码已发送至 $mail，10 分钟内有效"
                startCountdown()
            } else {
                error = result.exceptionOrNull()?.userMessage().orEmpty()
            }
        }
    }

    fun savePasswordByEmail(me: MeDto?) {
        val mail = me?.email?.trim().orEmpty()
        if (mail.isEmpty()) {
            error = "尚未绑定邮箱，无法使用验证码修改密码"
            return
        }
        if (!CODE_PATTERN.matches(resetCode.trim())) {
            error = if (resetCode.isBlank()) "请填写邮箱验证码" else "验证码为 6 位数字"
            return
        }
        if (newPassword.length < MIN_PASSWORD_LENGTH) {
            error = "新密码至少 $MIN_PASSWORD_LENGTH 位"
            return
        }
        if (newPassword != confirmPassword) {
            error = "两次输入的新密码不一致"
            return
        }
        launchAction({ savingPwd = it }) {
            container.auth.changePasswordByEmail(mail, resetCode.trim(), newPassword)
            resetCode = ""
            newPassword = ""
            confirmPassword = ""
            passwordByEmail = false
            "密码已修改"
        }
    }

    // ---------- 头像 ----------

    fun uploadAvatar(context: Context, uri: Uri) {
        viewModelScope.launch {
            uploading = true
            error = null
            msg = null
            val result = runCatching {
                val image = readImage(context, uri)
                container.auth.uploadAvatar(image.fileName, image.bytes, image.mime)
            }
            if (result.isSuccess) {
                msg = "头像已更新"
                toast = msg
            } else {
                error = result.exceptionOrNull()?.userMessage().orEmpty()
            }
            uploading = false
        }
    }

    private suspend fun readImage(context: Context, uri: Uri): AvatarImage = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = queryDisplayName(context, uri)
        val mime = normalizeMime(resolver.getType(uri).orEmpty().ifBlank { FileActions.mimeFor(displayName) })
        if (!AVATAR_TYPES.contains(mime)) throw FieldReject("仅支持 jpg/png/webp 图片")
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw FieldReject("无法读取所选图片，请重新选择")
        if (bytes.isEmpty()) throw FieldReject("图片是空文件，请重新选择")
        if (bytes.size > MAX_AVATAR_BYTES) throw FieldReject("头像不能超过 2MB")
        val name = displayName.ifBlank { "avatar.${mime.substringAfter('/', "jpg")}" }
        AvatarImage(fileName = name, bytes = bytes, mime = mime)
    }

    /** 部分相册/相机把 JPEG 报成 image/jpg，后端按标准 MIME 白名单校验，这里先归一化。 */
    private fun normalizeMime(raw: String): String = when (raw) {
        "image/jpg" -> "image/jpeg"
        else -> raw
    }

    private fun queryDisplayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull().orEmpty()

    private data class AvatarImage(val fileName: String, val bytes: ByteArray, val mime: String)

    // ---------- 数据导出 ----------

    fun export(context: Context, kind: ExportKind) {
        if (exporting != null) return
        viewModelScope.launch {
            exporting = kind
            error = null
            msg = null
            val result = runCatching {
                val bytes = container.api.download(kind.path)
                if (bytes.isEmpty()) throw FieldReject("后端返回了空文件，请稍后重试")
                val mime = FileActions.mimeFor(kind.fileName)
                val saved = FileActions.saveToDownloads(context, kind.fileName, bytes, mime)
                if (saved.uri == null) {
                    // 下载目录不可用（存储受限/被拒绝）时回落到系统分享面板
                    FileActions.share(context, kind.fileName, bytes, mime)
                    "${kind.fileName} 无法写入下载目录，已打开分享面板，请选择保存位置。"
                } else {
                    saved.message
                }
            }
            if (result.isSuccess) {
                msg = result.getOrNull()
                toast = msg
            } else {
                error = result.exceptionOrNull()?.userMessage().orEmpty()
            }
            exporting = null
        }
    }

    private fun startCountdown() {
        // 重发时先停掉上一轮，避免两个倒计时协程互相覆盖；ViewModel 清理由 viewModelScope 负责
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (second in CODE_COOLDOWN_SECONDS downTo 1) {
                countdown = second
                delay(1000)
            }
            countdown = 0
        }
    }

    /** 统一的「置忙 → 调接口 → 成功后写提示」流程；接口内部已经重新拉取 /me，草稿会跟着刷新。 */
    private fun launchAction(
        busy: (Boolean) -> Unit,
        block: suspend () -> String,
    ) {
        viewModelScope.launch {
            busy(true)
            error = null
            msg = null
            val result = runCatching { block() }
            if (result.isSuccess) {
                msg = result.getOrNull().orEmpty()
                toast = msg
            } else {
                error = result.exceptionOrNull()?.userMessage().orEmpty()
            }
            busy(false)
        }
    }

    override fun onCleared() {
        countdownJob?.cancel()
        super.onCleared()
    }

    companion object {
        const val CODE_COOLDOWN_SECONDS = 60
        const val MIN_PASSWORD_LENGTH = 6
        const val MAX_AVATAR_BYTES = 2 * 1024 * 1024L

        val AVATAR_TYPES = setOf("image/jpeg", "image/png", "image/webp")

        /** 通知类型中文名，与 Web 的 NOTIFY_TYPES 一致。 */
        val MAIL_TYPES: List<Pair<String, String>> = MAIL_NOTIFY_TYPES.map { it to (MAIL_TYPE_LABELS[it] ?: it) }

        private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        private val CODE_PATTERN = Regex("^\\d{6}$")

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { ProfileViewModel(container) }
        }
    }
}
