package com.zhixue.assistant.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException

/** 一帧 SSE：event 为 event: 字段（缺省 "message"），data 为多条 data: 行以 \n 拼接。 */
data class SseFrame(val event: String, val data: String)

/**
 * 按行喂入、遇空行成帧。后端答疑/讲义流只发 data: 行，通知流用 event:refresh 触发刷新，两种都要支持。
 * 分行由 [SseClient] 用 Okio 完成：readUtf8Line 会等待整行，UTF-8 多字节被 TCP 分片也不会解码错。
 */
class SseFrameParser {
    private var event = ""
    private val dataLines = mutableListOf<String>()
    private var sawAnything = false

    /** 返回 null 表示当前帧尚未结束。 */
    fun line(raw: String): SseFrame? {
        val text = raw.removeSuffix("\r")
        if (text.isEmpty()) {
            if (!sawAnything) return null
            val frame = SseFrame(event.ifBlank { "message" }, dataLines.joinToString("\n"))
            event = ""
            dataLines.clear()
            sawAnything = false
            return frame
        }
        if (text.startsWith(":")) return null
        sawAnything = true
        val separator = text.indexOf(':')
        val field = if (separator < 0) text else text.substring(0, separator)
        var value = if (separator < 0) "" else text.substring(separator + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> dataLines += value
            else -> Unit
        }
        return null
    }
}

sealed interface SseMessage {
    data class Delta(val text: String) : SseMessage
    data class Final(val json: JsonObject) : SseMessage
    data class ServerError(val message: String) : SseMessage

    /** 流关闭但没收到结束事件：服务端可能仍在生成，只能提示「稍后查看结果」，不能当作失败或退款。 */
    data object Interrupted : SseMessage

    /** 通知流的 event:refresh。 */
    data object Refresh : SseMessage
}

/**
 * POST SSE 读取器，边生成边上屏。
 * 预检失败（如「AI 次数不足」）后端返回 HTTP 200 + application/json 信封，这里抛出 [ApiException]，
 * 与 REST 走同一套错误通道；生成中途失败才有 data:{"error":...}。
 */
class SseClient(
    private val client: OkHttpClient,
    private val server: ServerConfig,
    private val session: SessionStore,
) {

    fun stream(path: String, bodyJson: String): Flow<SseMessage> = channelFlow {
        val builder = Request.Builder()
            .url(server.resolve(path))
            .post(bodyJson.toRequestBody(JSON_MEDIA))
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
        session.accessToken?.let { builder.header("Authorization", "Bearer $it") }

        val call = client.newCall(builder.build())
        launch(Dispatchers.IO) {
            try {
                call.execute().use { response -> consume(response) { send(it) } }
            } catch (e: IOException) {
                // 主动取消（切页面/退出登录）不报中断；异常断流才提示「稍后查看」
                if (!call.isCanceled()) send(SseMessage.Interrupted)
            }
        }
    }

    /** 通知流：GET + 长连接，只关心 event:refresh；连接关闭时返回，由调用方重连。 */
    fun keepAlive(path: String): Flow<SseMessage> = channelFlow {
        val builder = Request.Builder().url(server.resolve(path)).header("Accept", "text/event-stream")
        session.accessToken?.let { builder.header("Authorization", "Bearer $it") }
        val call = client.newCall(builder.build())
        launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw ApiException(response.code, "通知流连接失败(${response.code})")
                    val source = response.body?.source() ?: return@use
                    readFrames(source) { frame -> if (frame.event == "refresh") send(SseMessage.Refresh) }
                }
            } catch (e: IOException) {
                if (!call.isCanceled()) send(SseMessage.Interrupted)
            }
        }
    }

    private suspend fun consume(response: Response, emit: suspend (SseMessage) -> Unit) {
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        if (contentType.contains("application/json")) {
            val text = response.body?.string().orEmpty()
            val envelope = ApiClient.decodeEnvelope(text)
            throw ApiException(
                envelope?.code ?: response.code,
                envelope?.message?.takeIf { it.isNotBlank() } ?: "请求失败(${response.code})",
            )
        }
        if (!response.isSuccessful) {
            throw ApiException(response.code, "生成请求失败(${response.code})")
        }
        val source = response.body?.source() ?: throw ApiException(-1, "响应没有内容")
        var terminated = false
        readFrames(source) { frame ->
            val json = runCatching { ZhixueJson.parseToJsonElement(frame.data).jsonObject }.getOrNull()
            if (json == null) {
                if (frame.event == "refresh") emit(SseMessage.Refresh)
                return@readFrames
            }
            when {
                json.containsKey("error") -> {
                    terminated = true
                    emit(SseMessage.ServerError(json.textOf("error") ?: "生成失败，请重试"))
                }

                json.containsKey("delta") -> emit(SseMessage.Delta(json.textOf("delta").orEmpty()))
                else -> {
                    terminated = true
                    emit(SseMessage.Final(json))
                }
            }
        }
        if (!terminated) emit(SseMessage.Interrupted)
    }

    private suspend fun readFrames(source: BufferedSource, onFrame: suspend (SseFrame) -> Unit) {
        val parser = SseFrameParser()
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            parser.line(line)?.let { onFrame(it) }
        }
        // 服务端偶有结尾不发空行的情况，补发最后一帧
        parser.line("")?.let { onFrame(it) }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun JsonObject.textOf(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
