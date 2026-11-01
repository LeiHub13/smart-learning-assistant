package com.zhixue.assistant.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 与 Web 端 index.html 的 CSS 变量一一对应的调色板（GitHub 风格亮/暗两套）。
 * Material3 的 colorScheme 只覆盖通用语义，卡片底色、成功/失败柔和背景、图谱与热力图取色
 * 这些「设计语言级别」的颜色由 [ZhixuePalette] 提供，组件统一从这里取，避免散落硬编码。
 */
data class ZhixuePalette(
    val primary: Color,
    val primaryDeep: Color,
    val success: Color,
    val danger: Color,
    val warn: Color,
    val background: Color,
    val card: Color,
    val canvas: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    val soft: Color,
    val hover: Color,
    val active: Color,
    val accentSubtle: Color,
    val okSoft: Color,
    val okLine: Color,
    val badSoft: Color,
    val badLine: Color,
    val onPrimary: Color,
)

val LightPalette = ZhixuePalette(
    primary = Color(0xFF0969DA),
    primaryDeep = Color(0xFF0550AE),
    success = Color(0xFF1A7F37),
    danger = Color(0xFFCF222E),
    warn = Color(0xFF9A6700),
    background = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    canvas = Color(0xFFF6F8FA),
    text = Color(0xFF1F2328),
    muted = Color(0xFF59636E),
    border = Color(0xFFD1D9E0),
    soft = Color(0xFFF6F8FA),
    hover = Color(0xFFF3F4F6),
    active = Color(0xFFEEF0F3),
    accentSubtle = Color(0xFFDDF4FF),
    okSoft = Color(0xFFEEF7EE),
    okLine = Color(0xFFC3E6CD),
    badSoft = Color(0xFFFFEBE9),
    badLine = Color(0xFFFFC1BC),
    onPrimary = Color(0xFFFFFFFF),
)

val DarkPalette = ZhixuePalette(
    primary = Color(0xFF4493F8),
    primaryDeep = Color(0xFF74B3FF),
    success = Color(0xFF3FB950),
    danger = Color(0xFFF85149),
    warn = Color(0xFFD29922),
    background = Color(0xFF0D1117),
    card = Color(0xFF161B22),
    canvas = Color(0xFF0D1117),
    text = Color(0xFFE6EDF3),
    muted = Color(0xFF9198A1),
    border = Color(0xFF3D444D),
    soft = Color(0xFF21262D),
    hover = Color(0xFF262C36),
    active = Color(0xFF2D333B),
    accentSubtle = Color(0x2A388BFD),
    okSoft = Color(0x243FB950),
    okLine = Color(0x733FB950),
    badSoft = Color(0x24F85149),
    badLine = Color(0x73F85149),
    onPrimary = Color(0xFF0D1117),
)

/** 学习热力图 5 档（<30m / <1h / <2h / ≥2h）。 */
fun heatmapScale(dark: Boolean): List<Color> = if (dark) {
    listOf(
        Color(0xFF1C2128), Color(0xFF033A16), Color(0xFF196C2E), Color(0xFF2EA043), Color(0xFF56D364),
    )
} else {
    listOf(
        Color(0xFFEBEDF0), Color(0xFF9BE9A8), Color(0xFF40C463), Color(0xFF30A14E), Color(0xFF216E39),
    )
}

/** 多系列折线图取色顺序（与 DashboardView 的 PALETTE 一致）。 */
fun trendPalette(dark: Boolean): List<Color> = listOf(
    if (dark) Color(0xFF4493F8) else Color(0xFF0969DA),
    Color(0xFF4A6CF7),
    Color(0xFF3AA675),
    Color(0xFFC05A4D),
    Color(0xFF8B6FC0),
    Color(0xFF4BACC6),
    Color(0xFFF59E0B),
)

/** 知识图谱节点类型色（与 Web ECharts 分类一致）。 */
val GRAPH_TYPE_COLORS = mapOf(
    "概念" to Color(0xFF4E7CF6),
    "术语" to Color(0xFF9A6BF0),
    "方法" to Color(0xFF18A058),
    "原理" to Color(0xFFE08C1A),
    "工具" to Color(0xFFD9534F),
)

/** 对话厅无头像时按 userId 取色的固定调色板。 */
val HALL_AVATAR_COLORS = listOf(
    Color(0xFF4F6EF7), Color(0xFF0EA5E9), Color(0xFF10B981), Color(0xFFF59E0B),
    Color(0xFFE11D48), Color(0xFF8B5CF6), Color(0xFF0D9488), Color(0xFFD97706),
)

fun hallAvatarColor(userId: Long): Color =
    HALL_AVATAR_COLORS[(kotlin.math.abs(userId) % HALL_AVATAR_COLORS.size).toInt()]
