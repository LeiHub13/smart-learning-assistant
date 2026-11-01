package com.zhixue.assistant.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

/** 后端把题目选项、速览、批改结果等结构存成 JSON 字符串，这里统一兜底解析。 */
data class Option(val key: String, val text: String)

fun parseOptions(raw: String?): List<Option> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        lenient.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
            val obj = el.jsonObject
            Option(obj["k"]?.jsonPrimitive?.content.orEmpty(), obj["v"]?.jsonPrimitive?.content.orEmpty())
        }
    }.getOrDefault(emptyList())
}

/** 推荐追问：t_chat_message.followups 是「JSON 数组字符串」，Web 端解析后最多显示 3 条。 */
fun parseFollowups(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        lenient.parseToJsonElement(raw).jsonArray
            .map { it.jsonPrimitive.content }
            .filter { it.isNotBlank() }
            .take(3)
    }.getOrDefault(emptyList())
}

data class ReviewPoint(val point: String, val hit: Boolean, val note: String)

data class Review(val summary: String, val points: List<ReviewPoint>)

/** 问答题 AI 批改结果：可能是 JSON，也可能是纯文本（Web 端同样两种都兼容）。 */
fun parseReview(raw: String?): Review? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val obj = lenient.parseToJsonElement(raw).jsonObject
        Review(
            summary = obj["summary"]?.jsonPrimitive?.content.orEmpty(),
            points = obj["points"]?.jsonArray?.mapNotNull { el ->
                val o = el.jsonObject
                ReviewPoint(
                    point = o["point"]?.jsonPrimitive?.content.orEmpty(),
                    hit = o["hit"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    note = o["note"]?.jsonPrimitive?.content.orEmpty(),
                )
            }.orEmpty(),
        )
    }.getOrNull() ?: Review(summary = raw, points = emptyList())
}

/** 文档 AI 速览（存 t_document.overview，JSON 字符串）。 */
data class DocOverview(val summary: String, val points: List<String>, val examPoints: List<String>)

fun parseOverviewJson(raw: String?): DocOverview? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val obj = lenient.parseToJsonElement(raw).jsonObject
        DocOverview(
            summary = obj["summary"]?.jsonPrimitive?.content.orEmpty(),
            points = obj["points"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            examPoints = obj["examPoints"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
        )
    }.getOrNull()
}

fun parseIdCsv(raw: String?): List<String> =
    raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

private val SPACE = Regex("\\s+")

/** 自动标题：压缩空白、超 20 字截断加省略号（与后端 ChatService 一致，客户端用于占位显示）。 */
fun autoTitle(text: String): String {
    val collapsed = text.replace(SPACE, " ").trim()
    return if (collapsed.length > 20) collapsed.substring(0, 20) + "…" else collapsed
}

private val dateFormatters = listOf(
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
    DateTimeFormatter.ISO_LOCAL_DATE_TIME,
)

fun parseDateTime(raw: String?): LocalDateTime? {
    if (raw.isNullOrBlank()) return null
    dateFormatters.forEach { f ->
        runCatching { return LocalDateTime.parse(raw, f) }
    }
    runCatching { return LocalDate.parse(raw.take(10)).atStartOfDay() }
    return null
}

/** 后端给的是 Asia/Shanghai 墙上时间且不带偏移，直接本地格式化，不做时区换算。 */
fun fmtDateTime(raw: String?): String =
    parseDateTime(raw)?.let { "%02d-%02d %02d:%02d".format(it.monthValue, it.dayOfMonth, it.hour, it.minute) } ?: ""

fun fmtClock(raw: String?): String =
    parseDateTime(raw)?.let { "%02d:%02d".format(it.hour, it.minute) } ?: ""

fun fmtDate(raw: String?): String = parseDateTime(raw)?.toLocalDate()?.toString() ?: ""

fun friendlyTime(raw: String?): String {
    val dt = parseDateTime(raw) ?: return ""
    val today = LocalDate.now()
    return if (dt.toLocalDate() == today) fmtClock(raw) else fmtDateTime(raw)
}

fun fmtFen(fen: Int): String = "¥%.2f".format(fen / 100.0)

fun humanizeSize(bytes: Long?): String = when {
    bytes == null || bytes <= 0 -> "未知大小"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
