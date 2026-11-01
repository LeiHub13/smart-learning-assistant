package com.zhixue.assistant.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhixue.assistant.data.model.StudyCell
import com.zhixue.assistant.ui.theme.LocalIsDark
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.ui.theme.heatmapScale
import com.zhixue.assistant.util.ChartMath

data class ChartPoint(val label: String, val value: Double)

data class LineSeries(
    val key: String,
    val name: String,
    val color: Color,
    val points: List<ChartPoint>,
)

/**
 * 多系列折线图（成绩趋势）。手机上不画坐标轴刻度文字堆，改为：
 * 左侧 y 轴 0/50/100 三条参考线 + 底部首尾日期 + 点按任一位置显示当天各系列数值 + 图例可点选隐藏系列。
 */
@Composable
fun LineChart(
    series: List<LineSeries>,
    modifier: Modifier = Modifier,
    yMax: Double = 100.0,
    height: Dp = 180.dp,
    valueSuffix: String = "%",
    emptyText: String = "暂无数据，先做一组练习后这里会出现曲线",
) {
    val palette = LocalZhixuePalette.current
    val hidden = remember { mutableStateMapOf<String, Boolean>() }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val visible = series.filter { hidden[it.key] != true }
    val pointCount = visible.maxOfOrNull { it.points.size } ?: 0

    if (series.isEmpty() || pointCount == 0) {
        EmptyView(emptyText)
        return
    }

    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(visible, yMax) {
                    detectTapGestures { offset ->
                        val fraction = ((offset.x - 34.dp.toPx()) / (size.width - 44.dp.toPx())).coerceIn(0f, 1f)
                        selectedIndex = ChartMath.nearestIndex(fraction, pointCount)
                    }
                }
        ) {
            val left = 34.dp.toPx()
            val right = size.width - 10.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = size.height - 18.dp.toPx()

            // 网格与 y 轴标注
            listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEach { fraction ->
                val y = bottom - (bottom - top) * fraction.toFloat()
                drawLine(
                    color = palette.border.copy(alpha = 0.6f),
                    start = Offset(left, y),
                    end = Offset(right, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            listOf(0.0, 0.5, 1.0).forEach { fraction ->
                val y = bottom - (bottom - top) * fraction.toFloat()
                canvasText(
                    text = (yMax * fraction).toInt().toString() + valueSuffix,
                    x = left - 6.dp.toPx(),
                    y = y + 4f * density,
                    color = palette.muted,
                    sizePx = 10f * density,
                    align = TextAnchor.End,
                )
            }

            visible.forEach { line ->
                if (line.points.isEmpty()) return@forEach
                val xs = ChartMath.xPositions(line.points.size, left, right)
                val path = Path()
                line.points.forEachIndexed { index, point ->
                    val x = xs.getOrElse(index) { left }
                    val y = ChartMath.yFor(point.value, 0.0, yMax, top, bottom)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, line.color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                line.points.forEachIndexed { index, point ->
                    val x = xs.getOrElse(index) { left }
                    val y = ChartMath.yFor(point.value, 0.0, yMax, top, bottom)
                    val radius = if (index == selectedIndex) 4.dp.toPx() else 2.dp.toPx()
                    drawCircle(line.color, radius, Offset(x, y))
                }
            }

            selectedIndex?.let { index ->
                val xs = ChartMath.xPositions(pointCount, left, right)
                xs.getOrNull(index)?.let { x ->
                    drawLine(
                        color = palette.primary.copy(alpha = 0.4f),
                        start = Offset(x, top),
                        end = Offset(x, bottom),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        val readoutIndex = selectedIndex?.coerceAtMost(pointCount - 1)
        if (readoutIndex != null) {
            val label = visible.firstOrNull { it.points.size > readoutIndex }?.points?.get(readoutIndex)?.label.orEmpty()
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Text("$label  ", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = palette.text)
                visible.forEach { line ->
                    line.points.getOrNull(readoutIndex)?.let { point ->
                        Text(
                            "${line.name} ${fmtValue(point.value)}$valueSuffix   ",
                            fontSize = 12.sp,
                            color = line.color,
                        )
                    }
                }
            }
        } else {
            Text("点按曲线查看某天成绩", fontSize = 11.sp, color = palette.muted)
        }

        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            series.forEach { line ->
                val off = hidden[line.key] == true
                Row(
                    Modifier
                        .background(
                            if (off) Color.Transparent else palette.soft,
                            RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .then(Modifier.pointerInput(line.key) { detectTapGestures { hidden[line.key] = off != true } }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(line.color.copy(alpha = if (off) 0.3f else 1f), CircleShape)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        line.name,
                        fontSize = 11.sp,
                        color = if (off) palette.muted.copy(alpha = 0.6f) else palette.text,
                        fontWeight = if (off) FontWeight.Normal else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

private fun fmtValue(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)

/** 学习热力图：12 周 × 7 天，格子颜色按 5 档；点按显示当日分钟数。 */
@Composable
fun StudyHeatmap(
    cells: List<StudyCell>,
    modifier: Modifier = Modifier,
    weeks: Int = 12,
) {
    val palette = LocalZhixuePalette.current
    val dark = LocalIsDark.current
    val scale = remember(dark) { heatmapScale(dark) }
    val byDate = remember(cells) { cells.associate { it.date to it.minutes } }
    var selected by remember(cells) { mutableStateOf<Pair<String, Int>?>(null) }

    // 按周对齐：末尾补到今天，前面补空格，保证每列是完整一周
    val ordered = remember(cells) { buildHeatmapGrid(byDate.keys, weeks) }

    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height((20 * weeks).dp)
                .pointerInput(ordered, weeks) {
                    detectTapGestures { offset ->
                        val cellWidth = size.width / weeks
                        val week = (offset.x / cellWidth).toInt().coerceIn(0, weeks - 1)
                        val day = (offset.y / (size.height / 7f)).toInt().coerceIn(0, 6)
                        ordered.getOrNull(week)?.getOrNull(day)?.let { date ->
                            selected = date to (byDate[date] ?: 0)
                        }
                    }
                }
        ) {
            val cellW = size.width / weeks
            val cellH = size.height / 7f
            val gap = 2.dp.toPx()
            for (week in 0 until weeks) {
                for (day in 0 until 7) {
                    val date = ordered.getOrNull(week)?.getOrNull(day) ?: continue
                    val minutes = byDate[date] ?: 0
                    val color = scale[ChartMath.heatmapLevel(minutes)]
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(week * cellW + gap / 2, day * cellH + gap / 2),
                        size = Size(cellW - gap, cellH - gap),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("少", fontSize = 11.sp, color = palette.muted)
            Spacer(Modifier.width(4.dp))
            scale.forEach { color ->
                Box(
                    Modifier
                        .size(10.dp)
                        .background(color, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(3.dp))
            }
            Text("多", fontSize = 11.sp, color = palette.muted)
        }
        selected?.let { (date, minutes) ->
            Spacer(Modifier.height(6.dp))
            Text("$date：${minutes} 分钟", fontSize = 12.sp, color = palette.text)
        }
    }
}

/**
 * 生成 weeks×7 的日期网格：从「今天往前 weeks 周并对齐到周一」开始，晚于今天的格子为 null，
 * 这样不会画出还没到的日期，也不会像 Web 那样留一堆空白列。
 */
internal fun buildHeatmapGrid(
    dates: Set<String>,
    weeks: Int,
    today: java.time.LocalDate = java.time.LocalDate.now(),
): List<List<String?>> {
    val formatter = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
    val rawStart = today.minusDays((weeks * 7 - 1).toLong())
    val alignedStart = rawStart.minusDays((rawStart.dayOfWeek.value - 1).toLong())
    val grid = MutableList(weeks) { MutableList<String?>(7) { null } }
    for (index in 0 until weeks * 7) {
        val date = alignedStart.plusDays(index.toLong())
        if (date.isAfter(today)) break
        val week = index / 7
        val day = index % 7
        if (week < weeks) grid[week][day] = date.format(formatter)
    }
    return grid.map { it.toList() }
}

@Composable
fun MiniBarChart(
    values: List<Pair<String, Double>>,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    color: Color = LocalZhixuePalette.current.primary,
    labelEvery: Int = 3,
) {
    val palette = LocalZhixuePalette.current
    if (values.isEmpty()) {
        EmptyView("暂无数据")
        return
    }
    val max = values.maxOf { it.second }.takeIf { it > 0 } ?: 1.0
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val slot = size.width / values.size
            val barWidth = (slot * 0.6f).coerceAtLeast(2f)
            values.forEachIndexed { index, (_, value) ->
                val h = (size.height * (value / max)).toFloat().coerceAtLeast(1f)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(index * slot + (slot - barWidth) / 2, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                )
            }
            drawLine(
                color = palette.border,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            values.filterIndexed { i, _ -> i % labelEvery == 0 }.forEach { (label, _) ->
                Text(label.takeAfter(5), fontSize = 10.sp, color = palette.muted)
            }
        }
    }
}

private fun String.takeAfter(index: Int): String = if (length > index) substring(index) else this

@Composable
fun LegendDot(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
