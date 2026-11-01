package com.zhixue.assistant.ui.screens.insight

import com.zhixue.assistant.data.model.CourseCard

/** 页面内的一次性操作反馈（成功/失败横幅）。 */
data class UiMessage(val text: String, val error: Boolean)

/** 「-1」在本模块一律表示「不限课程 / 交给后端默认」，与路由参数约定一致
 * （Routes.notes()、Routes.NO_COURSE 都用 -1）。
 */
internal const val NO_COURSE_KEY = "-1"

internal fun courseOptions(courses: List<CourseCard>, includeAll: String? = null): List<Pair<String, String>> {
    val mapped = courses.map { it.id.toString() to it.name }
    return if (includeAll == null) mapped else listOf(NO_COURSE_KEY to includeAll) + mapped
}

/** 下拉的 key 是字符串，这里还原成课程 id；-1/非法值 → null（后端按默认课程处理）。 */
internal fun String?.toCourseIdOrNull(): Long? = this?.toLongOrNull()?.takeIf { it > 0 }

/** 学习时长人性化：不足 1 小时只显示分，整小时不显示「0 分」。 */
internal fun humanMinutes(minutes: Int): String = when {
    minutes <= 0 -> "0 分"
    minutes < 60 -> "$minutes 分"
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    else -> "${minutes / 60} 小时 ${minutes % 60} 分"
}

/** token 量级缩写，与管理看板 Web 的 fmtTokens 一致。 */
internal fun humanTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000 -> "%.1fk".format(tokens / 1000.0)
    else -> tokens.toString()
}

/** 折叠态摘要：取首个非空行，超 60 字截断（与 NotesView 的 preview 一致）。 */
internal fun firstLinePreview(text: String, max: Int = 60): String {
    val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return if (line.length > max) line.take(max) + "…" else line
}
