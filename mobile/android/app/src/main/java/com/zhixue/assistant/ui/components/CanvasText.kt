package com.zhixue.assistant.ui.components

import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Canvas 内画文字：Compose 的 drawText 在不同版本上位置不稳定，这里直接用 nativeCanvas，
 * 坐标完全受我们的缩放/平移矩阵控制，图谱标签和坐标轴刻度都靠它。
 */
private fun textPaint(color: Color, sizePx: Float, bold: Boolean): Paint = Paint().apply {
    isAntiAlias = true
    textSize = sizePx
    this.color = color.toArgb()
    isFakeBoldText = bold
}

/** 以 (x, y) 为基线绘制文本；align 决定水平锚点。 */
fun DrawScope.canvasText(
    text: String,
    x: Float,
    y: Float,
    color: Color,
    sizePx: Float,
    bold: Boolean = false,
    align: TextAnchor = TextAnchor.Start,
) {
    if (text.isEmpty()) return
    val paint = textPaint(color, sizePx, bold)
    val dx = when (align) {
        TextAnchor.Start -> 0f
        TextAnchor.Center -> -paint.measureText(text) / 2f
        TextAnchor.End -> -paint.measureText(text)
    }
    drawContext.canvas.nativeCanvas.drawText(text, x + dx, y, paint)
}

enum class TextAnchor { Start, Center, End }
