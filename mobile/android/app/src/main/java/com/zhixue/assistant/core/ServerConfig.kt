package com.zhixue.assistant.core

import java.util.concurrent.atomic.AtomicReference

/**
 * 后端地址：编译期默认值 + 运行时可改（模拟器 10.0.2.2 / 局域网 IP / 线上域名）。
 * 所有 REST、SSE、WebSocket、/files 资源都以这里为唯一来源，切换服务器后登录态按 serverKey 各自隔离。
 */
class ServerConfig(
    private val store: KeyValueStore,
    defaultBaseUrl: String,
    private val allowHttp: Boolean,
) {
    private val key = "server.base_url"
    private val ref = AtomicReference(normalize(defaultBaseUrl).getOrNull() ?: normalize(defaultBaseUrl).getOrThrow())

    val baseUrl: String get() = ref.get()

    /** 变更服务器需要通知订阅者（重连 WebSocket、停心跳、重载登录态）。 */
    private val listeners = mutableListOf<(String) -> Unit>()

    fun onChange(listener: (String) -> Unit) = synchronized(listeners) { listeners += listener }

    /** 归一化并校验；返回失败原因供 UI 直接展示。 */
    fun normalize(raw: String): Result<String> = runCatching {
        val trimmed = raw.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "后端地址不能为空" }
        val withScheme = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
                trimmed.startsWith("ws://") || trimmed.startsWith("wss://") -> trimmed
            else -> "https://$trimmed"
        }
        val httpish = withScheme
            .replaceFirst("ws://", "http://")
            .replaceFirst("wss://", "https://")
            .trimEnd('/')
        val javaUri = java.net.URI(httpish)
        val host = javaUri.host
        require(!host.isNullOrBlank()) { "后端地址格式不正确，示例：http://192.168.1.5:8080" }
        require(httpish.startsWith("http://") || httpish.startsWith("https://")) { "仅支持 http/https 地址" }
        if (httpish.startsWith("http://")) {
            val local = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" ||
                host.startsWith("192.168.") || host.startsWith("10.")
            check(allowHttp || local) { "正式包只允许 HTTPS，请配置证书或使用调试包连接局域网后端" }
        }
        httpish
    }

    /** 立即生效并持久化；同一地址重复设置不触发监听。 */
    fun applyBaseUrl(raw: String): Result<String> {
        val normalized = normalize(raw).getOrThrow()
        val changed = normalized != ref.get()
        ref.set(normalized)
        store.put(key, normalized)
        if (changed) synchronized(listeners) { listeners.toList() }.forEach { it(normalized) }
        return Result.success(normalized)
    }

    /** 只校验不落盘，登录页「测试连接」用。 */
    fun previewBaseUrl(raw: String): String = normalize(raw).getOrThrow()

    fun restoredOrDefault(): String = store.getString(key)?.let { normalize(it).getOrNull() } ?: ref.get()

    /**
     * 局域网/本机地址判定。Android 16+ 访问这些地址需要「本地网络」权限，
     * 否则连接会被静默丢弃并表现为超时（shell 的 nc/curl 不受此限制，容易误判成后端没起）。
     */
    fun isLanHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "0.0.0.0") return true
        val parts = h.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        val (a, b) = parts
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }

    /** 把后端返回的相对地址（/files/...、/api/...）解析成绝对地址。 */
    fun resolve(path: String): String = when {
        path.isBlank() -> ""
        path.startsWith("http://") || path.startsWith("https://") -> path
        else -> baseUrl + (if (path.startsWith("/")) path else "/$path")
    }

    /** ws/wss 与 http/https 同源：对话厅 WebSocket、SSE 都从这里派生。 */
    fun websocketUrl(path: String): String {
        val scheme = if (baseUrl.startsWith("https://")) "wss://" else "ws://"
        val authority = baseUrl.removePrefix("http://").removePrefix("https://")
        val sep = if (path.startsWith("/")) "" else "/"
        return "$scheme$authority$sep$path"
    }

    companion object {
        const val KEY = "server.base_url"
    }
}
