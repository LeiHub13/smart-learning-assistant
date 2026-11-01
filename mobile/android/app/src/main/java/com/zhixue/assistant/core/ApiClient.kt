package com.zhixue.assistant.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder

/**
 * 唯一的 REST 出口：负责拼绝对地址、带 JWT、解 {code,message,data} 信封、把两类错误通道翻译成异常。
 * 业务层只看到数据或 ApiException / NetworkException。
 */
class ApiClient(
    private val server: ServerConfig,
    private val session: SessionStore,
    private val client: OkHttpClient,
    private val onSessionExpired: () -> Unit = {},
) {

    val baseUrl: String get() = server.baseUrl

    /** 后端返回的 /files/... 相对地址要按当前服务器解析。 */
    fun resolve(path: String): String = when {
        path.isBlank() -> ""
        path.startsWith("http://") || path.startsWith("https://") -> path
        else -> baseUrl + (if (path.startsWith("/")) path else "/$path")
    }

    fun query(vararg pairs: Pair<String, Any?>): String {
        val parts = pairs.filter { it.second != null }.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v.toString(), "UTF-8")}"
        }
        return if (parts.isEmpty()) "" else "?$parts"
    }

    suspend fun urlFor(path: String, q: String = ""): String = resolve(path) + q

    /** 公开接口探活（登录页「测试连接」）。 */
    suspend fun probe(): ProbeResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            val request = Request.Builder().url(resolve("/api/health")).get().build()
            client.newCall(request).execute().use { response ->
                ProbeResult(
                    reachable = response.isSuccessful || response.code in 400..499,
                    httpStatus = response.code,
                    latencyMs = System.currentTimeMillis() - started,
                    detail = runCatching { response.body?.string()?.take(160) }.getOrNull(),
                )
            }
        } catch (e: IOException) {
            ProbeResult(reachable = false, httpStatus = 0, latencyMs = System.currentTimeMillis() - started, detail = describe(e))
        }
    }

    @PublishedApi
    internal suspend fun execute(
        method: String,
        path: String,
        bodyJson: String?,
        extraHeaders: Map<String, String> = emptyMap(),
        authenticated: Boolean = true,
    ): JsonElement? = withContext(Dispatchers.IO) {
        val requestBody: RequestBody? = bodyJson?.toRequestBody(JSON_MEDIA)
        val builder = Request.Builder().url(resolve(path))
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> if (requestBody == null) builder.delete() else builder.delete(requestBody)
            "POST" -> builder.post(requestBody ?: EMPTY_BODY)
            "PUT" -> builder.put(requestBody ?: EMPTY_BODY)
            else -> builder.method(method, requestBody)
        }
        if (authenticated) {
            session.accessToken?.let { builder.header("Authorization", "Bearer $it") }
        }
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }

        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: SocketTimeoutException) {
            throw NetworkException("连接后端超时：请确认后端已启动、地址可达；Android 16+ 访问局域网还需允许「本地网络」权限", e)
        } catch (e: IOException) {
            throw NetworkException(describe(e), e)
        }

        response.use { resp ->
            val text = resp.body?.string() ?: ""
            if (resp.code == 401) {
                session.signOut()
                onSessionExpired()
                throw ApiException(401, messageOf(text) ?: "未登录或登录已过期")
            }
            val envelope = decodeEnvelope(text)
            if (envelope == null) {
                if (!resp.isSuccessful) {
                    throw ApiException(resp.code, "请求失败(${resp.code})")
                }
                throw ApiException(-1, "后端返回了非 JSON 内容(HTTP ${resp.code})，请检查地址是否指向 API 服务")
            }
            if (envelope.code != 0) {
                if (envelope.code == 401) {
                    session.signOut()
                    onSessionExpired()
                }
                throw ApiException(envelope.code, envelope.message.ifBlank { "请求失败(${resp.code})" })
            }
            envelope.data
        }
    }

    /** 少数直接返回文本/文件的接口（导出、下载）。 */
    suspend fun download(path: String, authenticated: Boolean = true): ByteArray = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(resolve(path)).get()
        if (authenticated) session.accessToken?.let { builder.header("Authorization", "Bearer $it") }
        try {
            client.newCall(builder.build()).execute().use { resp ->
                if (resp.code == 401) {
                    session.signOut()
                    onSessionExpired()
                    throw ApiException(401, "未登录或登录已过期")
                }
                if (!resp.isSuccessful) {
                    // 导出类接口失败时后端仍可能返回 JSON 信封
                    throw ApiException(resp.code, messageOf(resp.body?.string().orEmpty()) ?: "下载失败(${resp.code})")
                }
                resp.body?.bytes() ?: ByteArray(0)
            }
        } catch (e: IOException) {
            throw NetworkException(describe(e), e)
        }
    }

    /** 表单 POST（少数接口用 form），以及 multipart 上传。 */
    suspend fun postForm(path: String, fields: Map<String, String>): JsonElement? = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        val builder = Request.Builder().url(resolve(path)).post(form)
        session.accessToken?.let { builder.header("Authorization", "Bearer $it") }
        try {
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (resp.code == 401) {
                    session.signOut(); onSessionExpired(); throw ApiException(401, "未登录或登录已过期")
                }
                val env = decodeEnvelope(text) ?: throw ApiException(-1, "后端返回了非 JSON 内容")
                if (env.code != 0) throw ApiException(env.code, env.message)
                env.data
            }
        } catch (e: IOException) {
            throw NetworkException(describe(e), e)
        }
    }

    @PublishedApi
    internal suspend fun uploadInternal(
        path: String,
        fileField: String,
        fileName: String,
        bytes: ByteArray,
        mime: String,
        fields: Map<String, String>,
    ): JsonElement? = withContext(Dispatchers.IO) {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            fields.forEach { (k, v) -> addFormDataPart(k, v) }
            addFormDataPart(
                fileField,
                fileName,
                bytes.toRequestBody(mime.toMediaType()),
            )
        }.build()
        val builder = Request.Builder().url(resolve(path)).post(multipart)
        session.accessToken?.let { builder.header("Authorization", "Bearer $it") }
        try {
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (resp.code == 401) {
                    session.signOut(); onSessionExpired(); throw ApiException(401, "未登录或登录已过期")
                }
                val env = decodeEnvelope(text)
                    ?: throw ApiException(resp.code, messageOf(text) ?: "上传失败(${resp.code})")
                if (env.code != 0) throw ApiException(env.code, env.message)
                env.data
            }
        } catch (e: IOException) {
            throw NetworkException(describe(e), e)
        }
    }

    suspend inline fun <reified T> get(
        path: String,
        q: String = "",
    ): T = unwrapData<T>(execute("GET", if (q.isEmpty()) path else "$path$q", null))

    /** data 可能为 null 的接口用这个：null 不是错误，而是「还没有数据」。 */
    suspend inline fun <reified T> getOrNull(path: String, q: String = ""): T? =
        unwrapOrNull<T>(execute("GET", if (q.isEmpty()) path else "$path$q", null))

    suspend inline fun <reified T> post(path: String, bodyJson: String? = "{}"): T =
        unwrapData<T>(execute("POST", path, bodyJson))

    suspend inline fun <reified T> put(path: String, bodyJson: String? = "{}"): T =
        unwrapData<T>(execute("PUT", path, bodyJson))

    suspend inline fun <reified T> delete(path: String): T = unwrapData<T>(execute("DELETE", path, null))

    /** data 为 null 的接口用这些：不因为「没有数据」而报错。 */
    suspend fun postVoid(path: String, bodyJson: String? = "{}") {
        execute("POST", path, bodyJson)
    }

    suspend fun putVoid(path: String, bodyJson: String? = "{}") {
        execute("PUT", path, bodyJson)
    }

    suspend fun deleteVoid(path: String) {
        execute("DELETE", path, null)
    }

    suspend inline fun <reified T> upload(
        path: String,
        fileField: String = "file",
        fileName: String,
        bytes: ByteArray,
        mime: String = "application/octet-stream",
        fields: Map<String, String> = emptyMap(),
    ): T = unwrapData<T>(uploadInternal(path, fileField, fileName, bytes, mime, fields))

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody("application/json".toMediaType())

        internal fun decodeEnvelope(text: String): ApiEnvelope? =
            runCatching { ZhixueJson.decodeFromString(ApiEnvelope.serializer(), text) }.getOrNull()

        /** 只取 message 字段，用于非 2xx 且结构未知的响应。 */
        internal fun messageOf(text: String): String? = decodeEnvelope(text)?.message?.takeIf { it.isNotBlank() }

        internal fun describe(e: IOException): String = when {
            e is java.net.ConnectException -> "无法连接后端，请确认服务已启动且地址正确"
            e is java.net.UnknownHostException -> "无法解析后端域名，请检查地址或网络"
            e is javax.net.ssl.SSLException -> "后端证书校验失败（${e.message}）"
            e.message?.contains("CLEARTEXT", ignoreCase = true) == true ->
                "该地址使用 HTTP 明文，正式包已禁止；请使用 HTTPS 或调试包"
            else -> e.message ?: "网络请求失败"
        }
    }
}

data class ProbeResult(
    val reachable: Boolean,
    val httpStatus: Int,
    val latencyMs: Long,
    val detail: String?,
)
