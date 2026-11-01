package com.zhixue.assistant.ui.nav

import com.zhixue.assistant.util.parseIdCsv

/**
 * 后端 Agent 动作返回的 `navigate`、以及站内通知的 `link` 都是 Web 路由
 * （形如 /practice?courseId=1&auto=1 或 /hall?peer=3）。这里一次性翻译成 Android 目的地，
 * 未知路径返回 null 由调用方提示，不做「静默跳到首页」这种让人找不到入口的处理。
 */
object WebRoute {

    private val simple: Map<String, String?> = mapOf(
        "/home" to Routes.HOME,
        "/chat" to null, // 需要带参数，下面单独处理
        "/hall" to null,
        "/generate" to Routes.GENERATE,
        "/practice" to null,
        "/mistakes" to Routes.MISTAKES,
        "/favorites" to Routes.FAVORITES,
        "/exam" to Routes.EXAM,
        "/bank" to Routes.BANK,
        "/flashcards" to Routes.FLASHCARDS,
        "/progress" to Routes.PROGRESS,
        "/manage" to Routes.MANAGE,
        "/hub" to Routes.HUB,
        "/docs" to null,
        "/plans" to Routes.PLANS,
        "/reports" to Routes.REPORTS,
        "/notes" to null,
        "/profile" to Routes.PROFILE,
        "/billing" to Routes.BILLING,
        "/admin" to Routes.ADMIN,
        "/search" to null,
        "/notifications" to Routes.NOTIFICATIONS,
        "/login" to Routes.LOGIN,
        "/welcome" to Routes.WELCOME,
    )

    fun toAndroid(route: String?): String? {
        val raw = route?.trim()?.removePrefix("#").orEmpty()
        if (raw.isEmpty()) return null
        val pathPart = raw.substringBefore('?')
        val query = parseQuery(raw.substringAfter('?', ""))
        val path = if (pathPart.startsWith("/")) pathPart else "/$pathPart"
        val id = path.trim('/').toLongOrNull()

        return when (path) {
            "/chat" -> Routes.chat(
                sessionId = query["sessionId"]?.toLongOrNull(),
                courseId = query["courseId"]?.toLongOrNull(),
                kbId = query["kbId"]?.toLongOrNull(),
                scope = query["kbScope"] ?: query["scope"] ?: "single",
                ask = query["ask"].orEmpty(),
                rel = query["rel"].orEmpty(),
            )

            "/hall" -> Routes.hall(query["peer"]?.toLongOrNull() ?: query["peerId"]?.toLongOrNull())

            "/practice" -> {
                val mode = when {
                    query["favorite"] == "1" -> "favorite"
                    query["mistake"] == "1" -> "mistake"
                    !query["ids"].isNullOrBlank() -> "manual"
                    else -> "normal"
                }
                Routes.practice(
                    mode = mode,
                    courseId = query["courseId"]?.toLongOrNull(),
                    kp = query["kp"].orEmpty(),
                    auto = query["auto"] == "1",
                    ids = query["ids"].orEmpty(),
                )
            }

            "/docs" -> Routes.docs(query["courseId"]?.toLongOrNull())
            "/notes" -> Routes.notes(query["courseId"]?.toLongOrNull())
            "/search" -> Routes.search(query["q"].orEmpty())
            "/course" -> id?.let { Routes.courseDetail(it) }
            "/kb" -> id?.let { Routes.graph(it) }
            else -> simple[path] ?: path.trim('/').takeIf { it.isNotEmpty() }
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=').trim()
            if (key.isEmpty()) return@mapNotNull null
            val value = java.net.URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            key to value
        }.toMap()
    }

    /** 引用 id 串（"1,2,3"）解析给引用面板复用。 */
    fun csvToIds(csv: String?): List<String> = parseIdCsv(csv)
}
