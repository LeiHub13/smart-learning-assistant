package com.zhixue.assistant.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 后端 /api/auth/me 的所有值都是字符串（isAdmin 为 "true"/"false"），必须原样接收。 */
@Serializable
data class MeDto(
    val id: String = "",
    val isAdmin: String = "false",
    val username: String = "",
    val nickname: String = "",
    val email: String = "",
    val avatar: String = "",
    val mailMute: String = "",
) {
    val userId: Long get() = id.toLongOrNull() ?: 0L
    val admin: Boolean get() = isAdmin.equals("true", ignoreCase = true)

    /** 通知偏好：mailMute 存的是「不发送邮件」的类型集合。 */
    fun mutedMailTypes(): Set<String> =
        mailMute.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}

@Serializable
data class Tokens(
    val accessToken: String = "",
    val refreshToken: String? = null,
)

/** 某个服务器下的完整登录态。token 与 me 缓存必须一起保存、一起清理。 */
@Serializable
data class SavedSession(
    val tokens: Tokens,
    val me: MeDto? = null,
    /** 上次正在进行的答疑会话 id（服务器相关，绝不能跨服务器复用）。 */
    val lastChatSessionId: Long? = null,
    /** 上次进入的对话厅频道，同样按服务器隔离。 */
    val lastHallChannel: String? = null,
)

/**
 * 登录态按「服务器地址」分片保存：切到另一台后端时只会读取那台后端自己的 token，
 * 不会出现把 A 服务器的 JWT / 会话 id 发给 B 服务器的情况。
 */
class SessionStore(
    private val store: KeyValueStore,
    private val server: ServerConfig,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _session = MutableStateFlow<SavedSession?>(null)
    val session: StateFlow<SavedSession?> = _session.asStateFlow()

    val accessToken: String? get() = _session.value?.tokens?.accessToken?.takeIf { it.isNotBlank() }
    val isLoggedIn: Boolean get() = accessToken != null
    val me: MeDto? get() = _session.value?.me

    init {
        server.onChange { _ -> restore() }
        restore()
    }

    private fun key(): String = KEY + server.baseUrl

    fun restore() {
        _session.value = store.getString(key())?.let {
            runCatching { json.decodeFromString<SavedSession>(it) }.getOrNull()
        }
    }

    fun signIn(tokens: Tokens, me: MeDto? = null) {
        val carriedMe = _session.value
            ?.takeIf { it.tokens.accessToken == tokens.accessToken }
            ?.me
        persist(SavedSession(tokens = tokens, me = me ?: carriedMe))
    }

    fun saveMe(me: MeDto) = update { it.copy(me = me) }

    fun saveLastChatSession(id: Long?) = update { it.copy(lastChatSessionId = id) }

    fun saveLastHallChannel(channel: String?) = update { it.copy(lastHallChannel = channel) }

    private fun update(block: (SavedSession) -> SavedSession) {
        _session.value?.let { persist(block(it)) }
    }

    private fun persist(session: SavedSession) {
        _session.value = session
        store.put(key(), json.encodeToString(SavedSession.serializer(), session))
    }

    /** 退出：只清当前服务器分片，其它后端地址的登录态保持不动。 */
    fun signOut() {
        _session.value = null
        store.remove(key())
    }
}

private const val KEY = "session."
