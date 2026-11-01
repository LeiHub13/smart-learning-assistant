package com.zhixue.assistant.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 请求体统一在这里拼装：后端 PUT 接口是「字段缺省 = 不修改」的局部更新语义
 * （例如答疑会话的 socratic/feynman 三态、课程 id 不允许清空），所以 null 必须「不写这个 key」而不是写 null。
 */
fun jsonBody(block: JsonObjectBuilder.() -> Unit): String = buildJsonObject(block).toString()

/**
 * 统一的可空写入：数值/布尔/字符串/列表/JsonElement 都支持，调用方不需要额外 import。
 * null 一律跳过，配合后端「字段缺省 = 不修改」的局部更新语义。
 */
fun JsonObjectBuilder.putOpt(key: String, value: Any?) {
    if (value == null) return
    val element: JsonElement = when (value) {
        is JsonElement -> value
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is List<*> -> buildJsonArray {
            value.forEach { item ->
                add(
                    when (item) {
                        is JsonPrimitive -> item
                        is Number -> JsonPrimitive(item)
                        is Boolean -> JsonPrimitive(item)
                        else -> JsonPrimitive(item?.toString())
                    }
                )
            }
        }

        else -> JsonPrimitive(value.toString())
    }
    put(key, element)
}

fun longArrayJson(values: List<Long>?): JsonArray? =
    values?.let { list -> buildJsonArray { list.forEach { add(JsonPrimitive(it)) } } }
