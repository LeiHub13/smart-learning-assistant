package com.zhixue.assistant.ui.screens.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.Tokens
import com.zhixue.assistant.core.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 登录页三态，与 Web 的 `mode = login | register | forgot` 一一对应。 */
enum class AuthMode { Login, Register, Forgot }

/**
 * 登录 / 注册 / 找回密码。
 * 前端校验规则与 `frontend/src/views/LoginView.vue` 保持一致，
 * 业务失败（HTTP 200 + code≠0）一律把后端 message 原样显示（例如「发送太频繁，请 60 秒后再试」）。
 */
class AuthViewModel(private val container: AppContainer) : ViewModel() {

    var mode by mutableStateOf(AuthMode.Login)
        private set

    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var email by mutableStateOf("")
    var code by mutableStateOf("")
    var nickname by mutableStateOf("")
    var newPassword by mutableStateOf("")
    var confirmPassword by mutableStateOf("")

    var submitting by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    /** 验证码重发倒计时（秒），后端冷却 60 秒。 */
    var countdown by mutableStateOf(0)
        private set

    private var countdownJob: Job? = null

    val titleText: String
        get() = when (mode) {
            AuthMode.Login -> "欢迎回来"
            AuthMode.Register -> "创建账号"
            AuthMode.Forgot -> "重置密码"
        }

    val subtitleText: String
        get() = when (mode) {
            AuthMode.Login -> "登录你的智学助手账号"
            AuthMode.Register -> "注册一个新账号开始学习"
            AuthMode.Forgot -> "通过绑定邮箱的验证码设置新密码"
        }

    val submitText: String
        get() = when (mode) {
            AuthMode.Login -> "登 录"
            AuthMode.Register -> "注 册"
            AuthMode.Forgot -> "重置密码"
        }

    fun switchMode(next: AuthMode) {
        mode = next
        error = null
        notice = null
    }

    fun clearMessages() {
        error = null
        notice = null
    }

    /** 发送邮箱验证码：场景由当前模式决定，冷却与后端 `app.email-code.cooldown-seconds` 一致。 */
    fun sendEmailCode() {
        val mail = email.trim()
        if (!EMAIL_PATTERN.matches(mail)) {
            error = "请输入正确的邮箱地址"
            return
        }
        viewModelScope.launch {
            sending = true
            error = null
            notice = null
            val scene = if (mode == AuthMode.Register) SCENE_REGISTER else SCENE_RESET_PASSWORD
            val result = runCatching { container.auth.sendEmailCode(mail, scene) }
            if (result.isSuccess) {
                notice = CODE_SENT_HINT
                startCountdown()
            } else {
                error = result.exceptionOrNull()?.userMessage().orEmpty()
            }
            sending = false
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (second in CODE_COOLDOWN_SECONDS downTo 1) {
                countdown = second
                delay(1000)
            }
            countdown = 0
        }
    }

    fun submit() {
        if (submitting) return
        when (mode) {
            AuthMode.Forgot -> submitResetPassword()
            AuthMode.Login -> submitLogin()
            AuthMode.Register -> submitRegister()
        }
    }

    private fun submitLogin() {
        if (username.isBlank() || password.isEmpty()) {
            error = "请输入用户名和密码"
            return
        }
        viewModelScope.launch {
            submitting = true
            error = null
            notice = null
            val result = runCatching { container.auth.login(username, password) }
            result.onSuccess { signIn(it.accessToken, it.refreshToken) }
                .onFailure { error = it.userMessage() }
            submitting = false
        }
    }

    private fun submitRegister() {
        if (username.isBlank() || password.isEmpty()) {
            error = "请输入用户名和密码"
            return
        }
        if (password.length < MIN_PASSWORD_LENGTH) {
            error = "密码至少 $MIN_PASSWORD_LENGTH 位"
            return
        }
        if (!EMAIL_PATTERN.matches(email.trim())) {
            error = "请输入正确的邮箱地址"
            return
        }
        if (!CODE_PATTERN.matches(code.trim())) {
            error = if (code.isBlank()) "请填写邮箱验证码" else "验证码为 6 位数字"
            return
        }
        viewModelScope.launch {
            submitting = true
            error = null
            notice = null
            val result = runCatching {
                container.auth.register(
                    username = username,
                    password = password,
                    email = email,
                    code = code,
                    nickname = nickname.takeIf { it.isNotBlank() },
                )
            }
            result.onSuccess { signIn(it.accessToken, it.refreshToken) }
                .onFailure { error = it.userMessage() }
            submitting = false
        }
    }

    private fun submitResetPassword() {
        if (!EMAIL_PATTERN.matches(email.trim())) {
            error = "请输入正确的邮箱地址"
            return
        }
        if (!CODE_PATTERN.matches(code.trim())) {
            error = if (code.isBlank()) "请填写邮箱验证码" else "验证码为 6 位数字"
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
        viewModelScope.launch {
            submitting = true
            error = null
            notice = null
            val result = runCatching {
                container.auth.resetPassword(email.trim(), code.trim(), newPassword)
            }
            if (result.isSuccess) {
                // 重置成功后清空敏感字段并回到登录态表单，和 Web 行为一致
                code = ""
                newPassword = ""
                confirmPassword = ""
                mode = AuthMode.Login
                notice = "密码已重置，请使用新密码登录"
            } else {
                error = result.exceptionOrNull()?.userMessage().orEmpty()
            }
            submitting = false
        }
    }

    /** 登录成功：先落 token（按服务器分片保存），再启动通知/心跳/课程缓存。 */
    private fun signIn(accessToken: String, refreshToken: String?) {
        if (accessToken.isBlank()) {
            error = "后端未返回访问令牌，请联系管理员"
            return
        }
        container.session.signIn(
            com.zhixue.assistant.core.Tokens(accessToken = accessToken, refreshToken = refreshToken)
        )
        container.onSignedIn()
    }

    override fun onCleared() {
        countdownJob?.cancel()
        super.onCleared()
    }

    companion object {
        const val SCENE_REGISTER = "register"
        const val SCENE_RESET_PASSWORD = "reset_password"
        const val CODE_SENT_HINT = "验证码已发送，请登录邮箱查收（10 分钟内有效）"
        const val CODE_COOLDOWN_SECONDS = 60
        const val MIN_PASSWORD_LENGTH = 6

        /** 与 Web 端 /api/auth 一致的邮箱校验。 */
        private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        private val CODE_PATTERN = Regex("^\\d{6}$")

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { AuthViewModel(container) }
        }
    }
}
