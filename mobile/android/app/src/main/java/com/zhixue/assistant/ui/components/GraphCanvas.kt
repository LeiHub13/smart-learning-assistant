package com.zhixue.assistant.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhixue.assistant.data.model.GraphDto
import com.zhixue.assistant.data.model.GraphEdgeDto
import com.zhixue.assistant.data.model.GraphNodeDto
import com.zhixue.assistant.ui.theme.GRAPH_TYPE_COLORS
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class GraphNodeLayout(
    val name: String,
    val type: String,
    val x: Float,
    val y: Float,
    val radius: Float,
    val degree: Int,
)

/**
 * 手机上不搬 ECharts 的力导向：用确定性的「按类型分簇 + 簇内环形」布局。
 * 同类概念聚成一群，度数越高圆越大，既稳定可测，也比随机抖动的力导向好读。
 */
internal fun layoutGraph(
    nodes: List<GraphNodeDto>,
    edges: List<GraphEdgeDto>,
    width: Float,
    height: Float,
): List<GraphNodeLayout> {
    if (nodes.isEmpty() || width <= 0f || height <= 0f) return emptyList()
    val degree = mutableMapOf<String, Int>()
    edges.forEach { edge ->
        degree[edge.source] = (degree[edge.source] ?: 0) + 1
        degree[edge.target] = (degree[edge.target] ?: 0) + 1
    }
    val types = nodes.map { it.type }.distinct().sorted()
    val cx = width / 2f
    val cy = height / 2f
    val ring = min(width, height) * 0.32f
    val clusterRadius = min(width, height) * 0.17f
    val result = mutableListOf<GraphNodeLayout>()

    types.forEachIndexed { typeIndex, type ->
        val cluster = nodes.filter { it.type == type }
        val angle = (2 * Math.PI * typeIndex / types.size) - Math.PI / 2
        val centerX = cx + (ring * cos(angle)).toFloat()
        val centerY = cy + (ring * sin(angle)).toFloat()
        cluster.forEachIndexed { index, node ->
            val spread = if (cluster.size == 1) 0.0 else (2 * Math.PI * index / cluster.size)
            val dist = if (cluster.size == 1) 0f else clusterRadius
            val deg = degree[node.name] ?: 0
            result += GraphNodeLayout(
                name = node.name,
                type = node.type,
                x = centerX + dist * cos(spread).toFloat(),
                y = centerY + dist * sin(spread).toFloat(),
                radius = min(14f + deg * 4f, 42f),
                degree = deg,
            )
        }
    }
    return result
}

/** 与后端 toView 的裁剪一致：最多 4 个邻居，用于「去答疑」提问文案。 */
internal fun neighborsFor(edges: List<GraphEdgeDto>, name: String): List<String> {
    if (name.isEmpty()) return emptyList()
    val out = linkedSetOf<String>()
    edges.forEach { edge ->
        if (edge.source == name) out += edge.target
        if (edge.target == name) out += edge.source
    }
    out.remove(name)
    return out.take(4).toList()
}

/**
 * 知识图谱画布：双指缩放、拖动平移、点选节点后跳答疑。
 * 手势变换存在这里而不是 ViewModel，因为它是纯视觉状态；命中测试先把屏幕坐标反算回世界坐标。
 */
@Composable
fun KnowledgeGraphCanvas(
    graph: GraphDto,
    modifier: Modifier = Modifier,
    onNodeClick: (name: String, related: List<String>) -> Unit = { _, _ -> },
) {
    val palette = LocalZhixuePalette.current
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var selected by remember(graph) { mutableStateOf<String?>(null) }

    val layout = remember(graph, canvasSize) {
        layoutGraph(graph.nodes, graph.edges, canvasSize.width.toFloat(), canvasSize.height.toFloat())
    }
    val positions = remember(layout) { layout.associateBy { it.name } }

    Box(modifier.fillMaxSize().background(palette.canvas)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(graph) {
                    detectTransformGestures { centroid, gesturePan, gestureZoom, _ ->
                        val newZoom = (zoom * gestureZoom).coerceIn(0.5f, 4f)
                        pan = centroid - (centroid - pan) * (newZoom / zoom) + gesturePan
                        zoom = newZoom
                    }
                }
                .pointerInput(layout, zoom, pan) {
                    detectTapGestures { tap ->
                        val world = (tap - pan) / zoom
                        val hit = layout.firstOrNull { node ->
                            val dx = world.x - node.x
                            val dy = world.y - node.y
                            dx * dx + dy * dy <= (node.radius + 6f) * (node.radius + 6f)
                        }
                        selected = hit?.name
                        hit?.let { onNodeClick(it.name, neighborsFor(graph.edges, it.name)) }
                    }
                }
        ) {
            if (canvasSize.width == 0) return@Canvas
            withTransform({
                scale(zoom, zoom, pan)
            }) {
                graph.edges.forEach { edge ->
                    val from = positions[edge.source] ?: return@forEach
                    val to = positions[edge.target] ?: return@forEach
                    val highlight = selected != null && (selected == edge.source || selected == edge.target)
                    drawLine(
                        color = if (highlight) palette.primary else palette.border.copy(alpha = 0.85f),
                        start = Offset(from.x, from.y),
                        end = Offset(to.x, to.y),
                        strokeWidth = if (highlight) 3f else 1.4f,
                        pathEffect = if (highlight) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                    )
                    drawArrowHead(Offset(from.x, from.y), Offset(to.x, to.y), if (highlight) palette.primary else palette.border)
                }
                val relatedToSelected = selected?.let { neighborsFor(graph.edges, it) }.orEmpty()
                layout.forEach { node ->
                    val color = GRAPH_TYPE_COLORS[node.type] ?: palette.muted
                    val dimmed = selected != null && selected != node.name && node.name !in relatedToSelected
                    drawCircle(
                        color = color.copy(alpha = if (dimmed) 0.12f else 0.28f),
                        radius = node.radius,
                        center = Offset(node.x, node.y),
                    )
                    drawCircle(
                        color = color.copy(alpha = if (dimmed) 0.3f else 1f),
                        radius = node.radius,
                        center = Offset(node.x, node.y),
                        style = Stroke(width = if (selected == node.name) 4f else 1.6f),
                    )
                    canvasText(
                        text = node.name,
                        x = node.x,
                        y = node.y + node.radius + 12f * density,
                        color = if (dimmed) palette.muted.copy(alpha = 0.5f) else palette.text,
                        sizePx = 11f * density,
                        bold = selected == node.name,
                        align = TextAnchor.Center,
                    )
                }
            }
        }

        // 类型图例
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(10.dp)
                .background(palette.card.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GRAPH_TYPE_COLORS.forEach { (type, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(color, CircleShape))
                    Spacer(Modifier.size(4.dp))
                    Text(type, fontSize = 11.sp, color = palette.text)
                }
            }
        }

        selected?.let { name ->
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .background(palette.card.copy(alpha = 0.95f), RoundedCornerShape(8.dp))
                    .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 4.dp)
                    .fillMaxWidth(0.6f),
            ) {
                Text(name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = palette.text)
                val related = neighborsFor(graph.edges, name)
                if (related.isNotEmpty()) {
                    Text("相关：" + related.joinToString("、"), fontSize = 11.sp, color = palette.muted)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onNodeClick(name, related) }) {
                        Text("去答疑", fontSize = 12.sp, color = palette.primary)
                    }
                    TextButton(onClick = { selected = null }) {
                        Text("关闭", fontSize = 12.sp, color = palette.muted)
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawArrowHead(from: Offset, to: Offset, color: Color) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = kotlin.math.sqrt(dx * dx + dy * dy)
    if (length < 1f) return
    val ux = dx / length
    val uy = dy / length
    val tip = Offset(to.x - ux * 14f, to.y - uy * 14f)
    val head = 6f
    drawPath(
        Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(tip.x - ux * head - uy * head * 0.6f, tip.y - uy * head + ux * head * 0.6f)
            lineTo(tip.x - ux * head + uy * head * 0.6f, tip.y - uy * head - ux * head * 0.6f)
            close()
        },
        color.copy(alpha = 0.8f),
    )
}

@Composable
fun GraphLoading(text: String) {
    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
        Text(text, color = LocalZhixuePalette.current.muted, fontSize = 13.sp)
    }
}
