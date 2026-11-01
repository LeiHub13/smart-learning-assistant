package com.zhixue.assistant.core

import kotlinx.serialization.json.Json

/**
 * 后端返回的 LocalDateTime 形如 2026-11-01T14:32:30（Asia/Shanghai 墙上时间，无偏移量），
 * 因此一律按字符串接收，展示时再格式化，避免时区二次换算。
 */
val ZhixueJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}
