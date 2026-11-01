package com.zhixue.assistant.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 图表几何与分档计算单独放这里，方便 JVM 单测（Compose 只负责画）。 */
object ChartMath {

    /** 等距 x 坐标；单点时放中间，避免除零。 */
    fun xPositions(count: Int, left: Float, right: Float): List<Float> {
        if (count <= 0) return emptyList()
        if (count == 1) return listOf((left + right) / 2f)
        val step = (right - left) / (count - 1)
        return (0 until count).map { left + step * it }
    }

    /** 把 [min,max] 的值映射到画布 [bottom, top]（y 轴向下为正，所以是反向）。 */
    fun yFor(value: Double, min: Double, max: Double, top: Float, bottom: Float): Float {
        val span = (max - min).let { if (it == 0.0) 1.0 else it }
        val ratio = ((value - min) / span).coerceIn(0.0, 1.0)
        return (bottom - (bottom - top) * ratio).toFloat()
    }

    /** 点按位置换算到最近的横轴索引，用于「点击看某天数值」。 */
    fun nearestIndex(fraction: Float, count: Int): Int {
        if (count <= 0) return 0
        return (fraction * (count - 1)).toInt().coerceIn(0, count - 1)
    }

    /** 热力图分档与 Web 的 level() 一致：无 / <30m / <1h / <2h / ≥2h。 */
    fun heatmapLevel(minutes: Int): Int = when {
        minutes <= 0 -> 0
        minutes < 30 -> 1
        minutes < 60 -> 2
        minutes < 120 -> 3
        else -> 4
    }

    /**
     * 连续学习天数：从今天（或最后一个有记录的日期）往前数，与 DashboardView 客户端算法一致。
     * 今天完全没学也不打断连胜——Web 是从昨天起算的，这里保持同样的宽容度。
     */
    fun streakDays(datesWithMinutes: Map<String, Int>, today: LocalDate = LocalDate.now()): Int {
        val keys = datesWithMinutes.filter { it.value > 0 }.keys.mapNotNull { runCatching { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull() }.toSet()
        if (keys.isEmpty()) return 0
        var cursor = if (keys.contains(today)) today else today.minusDays(1)
        if (!keys.contains(cursor)) return 0
        var count = 0
        while (keys.contains(cursor)) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    fun totalMinutes(datesWithMinutes: Map<String, Int>): Int = datesWithMinutes.values.sum()

    fun activeDays(datesWithMinutes: Map<String, Int>): Int = datesWithMinutes.values.count { it > 0 }

    /** 最「卷」的一天，供热力图旁边的统计显示。 */
    fun bestDay(datesWithMinutes: Map<String, Int>): Pair<String, Int>? =
        datesWithMinutes.filter { it.value > 0 }.maxByOrNull { it.value }?.toPair()
}
