package com.zhixue.assistant.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * 后端错误有两种通道，必须区分处理：
 *  - 未登录 / token 失效：真实 HTTP 401 + {"code":401,"message":"未登录或登录已过期"}
 *  - 业务失败：HTTP 200 + code=400/403/500 + 可直接展示给用户的中文 message
 * 客户端一律按 code 分支，不看 HTTP 状态码。
 */
class ApiException(
    val code: Int,
    override val message: String,
) : Exception(message) {
    val isUnauthorized: Boolean get() = code == 401
}

/** 网络层失败（断网、超时、DNS、TLS），与业务错误分开，便于 UI 给出「重试」而不是「失败原因」。 */
class NetworkException(
    override val message: String = "网络连接失败，请检查后端地址与网络",
    netCause: Throwable? = null,
) : Exception(message, netCause)

/** 后端统一信封 {code, message, data}。 */
@Serializable
data class ApiEnvelope(
    val code: Int = -1,
    val message: String = "",
    val data: JsonElement? = null,
)

/**
 * data 为 null 的接口（大量 DELETE / PUT 只回 code）在 T=Unit 时直接返回 Unit；
 * 其余类型遇到 null 视为异常数据，避免把 null 悄悄塞进非空字段。
 */
inline fun <reified T> unwrapData(data: JsonElement?): T {
    if (data == null || data is JsonNull) {
        if (Unit is T) return Unit as T
        throw ApiException(-1, "服务端未返回数据")
    }
    return ZhixueJson.decodeFromString(data.toString())
}

/** 允许 data 为 null 的接口（如尚未生成过的知识图谱）。 */
inline fun <reified T> unwrapOrNull(data: JsonElement?): T? =
    if (data == null || data is JsonNull) null else ZhixueJson.decodeFromString(data.toString())
