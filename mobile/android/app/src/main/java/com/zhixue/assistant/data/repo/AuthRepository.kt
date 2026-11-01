package com.zhixue.assistant.data.repo

import com.zhixue.assistant.core.ApiClient
import com.zhixue.assistant.core.MeDto
import com.zhixue.assistant.core.SessionStore
import com.zhixue.assistant.core.Tokens
import com.zhixue.assistant.core.jsonBody
import com.zhixue.assistant.core.putOpt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put

/** 通知偏好可选类型，与后端 MailType 一一对应。 */
val MAIL_NOTIFY_TYPES = listOf("review", "plan", "system", "exam", "practice", "dm", "friend", "announcement")

@Serializable
data class AuthPayload(val accessToken: String = "", val refreshToken: String? = null)

class AuthRepository(
    private val api: ApiClient,
    private val session: SessionStore,
) {

    val currentMe: MeDto? get() = session.me

    suspend fun login(username: String, password: String): Tokens {
        val body = jsonBody {
            put("username", username.trim())
            put("password", password)
        }
        val payload = api.post<AuthPayload>("/api/auth/login", body)
        return Tokens(payload.accessToken, payload.refreshToken)
    }

    suspend fun register(username: String, password: String, email: String, code: String, nickname: String?): Tokens {
        val body = jsonBody {
            put("username", username.trim())
            put("password", password)
            put("email", email.trim())
            put("code", code.trim())
            putOpt("nickname", nickname?.trim()?.takeIf { it.isNotEmpty() })
        }
        val payload = api.post<AuthPayload>("/api/auth/register", body)
        return Tokens(payload.accessToken, payload.refreshToken)
    }

    /** scene 只接受 register / reset_password，其它值后端直接拒绝。 */
    suspend fun sendEmailCode(email: String, scene: String) {
        api.postVoid("/api/auth/email-code", jsonBody {
            put("email", email.trim())
            put("scene", scene)
        })
    }

    suspend fun resetPassword(email: String, code: String, newPassword: String) {
        api.postVoid("/api/auth/reset-password", jsonBody {
            put("email", email.trim())
            put("code", code.trim())
            put("newPassword", newPassword)
        })
    }

    suspend fun fetchMe(): MeDto {
        val me = api.get<MeDto>("/api/auth/me")
        session.saveMe(me)
        return me
    }

    suspend fun updateNickname(nickname: String): MeDto {
        api.putVoid("/api/auth/me/profile", jsonBody { put("nickname", nickname.trim()) })
        return fetchMe()
    }

    suspend fun updateUsername(username: String): MeDto {
        api.putVoid("/api/auth/me/username", jsonBody { put("username", username.trim()) })
        return fetchMe()
    }

    suspend fun updateEmail(email: String): MeDto {
        api.putVoid("/api/auth/me/email", jsonBody { put("email", email.trim()) })
        return fetchMe()
    }

    suspend fun changePassword(oldPassword: String, newPassword: String) {
        api.putVoid("/api/auth/me/password", jsonBody {
            put("oldPassword", oldPassword)
            put("newPassword", newPassword)
        })
    }

    /** 邮箱验证码方式改密：与 Web 端一致，走公开的 reset-password（登录态下也可用）。 */
    suspend fun changePasswordByEmail(email: String, code: String, newPassword: String) =
        resetPassword(email, code, newPassword)

    /** mailMute 是「不发邮件」的类型集合；全空字符串表示全部接收。 */
    suspend fun saveNotifyPref(mutedTypes: Set<String>): MeDto {
        val joined = mutedTypes.joinToString(",")
        api.putVoid("/api/auth/me/notify-pref", jsonBody { put("mailMute", joined) })
        return fetchMe()
    }

    /** 头像限制：jpg/png/webp，2MB 以内，由后端返回可访问相对地址。 */
    suspend fun uploadAvatar(fileName: String, bytes: ByteArray, mime: String): MeDto {
        val result = api.upload<AvatarResult>("/api/auth/me/avatar", fileName = fileName, bytes = bytes, mime = mime)
        return fetchMe().let { me -> if (result.avatar.isNotBlank()) me.copy(avatar = result.avatar) else me }
    }

    @Serializable
    private data class AvatarResult(val avatar: String = "")

    /** 后端没有登出接口：清本地登录态即可（token 无黑名单）。 */
    fun signOut() = session.signOut()

    suspend fun probe() = api.probe()
}
