package com.xxt.zl.ai.ruyiremote.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 手绘图标。
 *
 * 没有引 `material-icons-extended`：它会给 APK 增加近万张矢量图（十几 MB），
 * 而这里真正用到的只有七个笔画简单的形状。统一 24×24 视框、2dp 线宽、圆头圆角，
 * 保证与 Material 字体的视觉重量接近。
 */
private const val BOX = 24f

@Composable
private fun Glyph(modifier: Modifier, draw: androidx.compose.ui.graphics.drawscope.DrawScope.(Float) -> Unit) {
    Canvas(modifier) {
        // unit = 1 个「设计像素」；所有笔画按 24×24 视框下的坐标书写，缩放由这里统一。
        draw(size.minDimension / BOX)
    }
}

/** 返回：一个左向折角。 */
@Composable
fun GlyphBack(size: Dp = 20.dp, tint: Color) {
    Glyph(Modifier.size(size)) { u ->
        val stroke = Stroke(width = 2.1f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(14.5f * u, 5.5f * u)
            lineTo(8f * u, 12f * u)
            lineTo(14.5f * u, 18.5f * u)
        }
        drawPath(path, tint, style = stroke)
    }
}

/** 前进/进入：右向折角。 */
@Composable
fun GlyphChevron(size: Dp = 18.dp, tint: Color) {
    Glyph(Modifier.size(size)) { u ->
        val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(9.5f * u, 5.5f * u)
            lineTo(16f * u, 12f * u)
            lineTo(9.5f * u, 18.5f * u)
        }
        drawPath(path, tint, style = stroke)
    }
}

/** 新建：十字。 */
@Composable
fun GlyphPlus(size: Dp = 20.dp, tint: Color) {
    Glyph(Modifier.size(size)) { u ->
        val w = 2.1f * u
        drawLine(tint, Offset(12f * u, 5f * u), Offset(12f * u, 19f * u), w, StrokeCap.Round)
        drawLine(tint, Offset(5f * u, 12f * u), Offset(19f * u, 12f * u), w, StrokeCap.Round)
    }
}

/** 模型：四角星 + 小星点。只用于模型入口，保持线条轻一点。 */
@Composable
fun GlyphSpark(size: Dp = 18.dp, tint: Color) {
    Glyph(Modifier.size(size)) { u ->
        val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val star = Path().apply {
            moveTo(10f * u, 2f * u)
            lineTo(12.3f * u, 8.2f * u)
            lineTo(18.5f * u, 10.5f * u)
            lineTo(12.3f * u, 12.8f * u)
            lineTo(10f * u, 19f * u)
            lineTo(7.7f * u, 12.8f * u)
            lineTo(1.5f * u, 10.5f * u)
            lineTo(7.7f * u, 8.2f * u)
            close()
        }
        drawPath(star, tint, style = stroke)
        drawLine(tint, Offset(19.5f * u, 3.5f * u), Offset(19.5f * u, 8.5f * u), 1.4f * u, StrokeCap.Round)
        drawLine(tint, Offset(17f * u, 6f * u), Offset(22f * u, 6f * u), 1.4f * u, StrokeCap.Round)
    }
}

/** 刷新：开口圆环 + 箭头。 */
@Composable
fun GlyphRefresh(size: Dp = 20.dp, tint: Color) {
    Glyph(Modifier.size(size)) { u ->
        val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawArc(
            color = tint,
            startAngle = 55f,
            sweepAngle = 285f,
            useCenter = false,
            topLeft = Offset(4.6f * u, 4.6f * u),
            size = Size(14.8f * u, 14.8f * u),
            style = stroke,
        )
        // 箭头画在环的起点（约 55° 位置）外侧
        val path = Path().apply {
            moveTo(17.4f * u, 3.6f * u)
            lineTo(18.6f * u, 9.6f * u)
            lineTo(12.8f * u, 8.2f * u)
        }
        drawPath(path, tint, style = stroke)
    }
}

/** 设置：三根带滑块的横线（比齿轮好画也更现代）。 */
@Composable
fun GlyphTune(size: Dp = 20.dp, tint: Color) {
    Glyph(Modifier.size(size)) { u ->
        val w = 2f * u
        val rows = listOf(6.5f to 15f, 12f to 9f, 17.5f to 13.5f)
        for ((y, knobX) in rows) {
            drawLine(tint, Offset(4f * u, y * u), Offset(20f * u, y * u), w, StrokeCap.Round)
            drawCircle(tint, radius = 2.6f * u, center = Offset(knobX * u, y * u))
        }
    }
}

/** 发送：向上的箭头。 */
@Composable
fun GlyphSend(size: Dp = 20.dp, tint: Color) {
    Glyph(Modifier.size(size)) { u ->
        val w = 2.2f * u
        drawLine(tint, Offset(12f * u, 19f * u), Offset(12f * u, 5.5f * u), w, StrokeCap.Round)
        val path = Path().apply {
            moveTo(6.2f * u, 11.2f * u)
            lineTo(12f * u, 5.4f * u)
            lineTo(17.8f * u, 11.2f * u)
        }
        drawPath(path, tint, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 停止：圆角方块。 */
@Composable
fun GlyphStop(size: Dp = 16.dp, tint: Color) {
    Glyph(Modifier.size(size)) { u ->
        drawRoundRect(
            color = tint,
            topLeft = Offset(6.5f * u, 6.5f * u),
            size = Size(11f * u, 11f * u),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.6f * u),
        )
    }
}

/** 电脑：显示器 + 底座。 */
@Composable
fun GlyphMonitor(size: Dp = 22.dp, tint: Color) {
    Glyph(Modifier.size(size)) { u ->
        val stroke = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            color = tint,
            topLeft = Offset(3.2f * u, 4.6f * u),
            size = Size(17.6f * u, 12.4f * u),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.4f * u),
            style = stroke,
        )
        drawLine(tint, Offset(12f * u, 17.2f * u), Offset(12f * u, 20f * u), 1.9f * u, StrokeCap.Round)
        drawLine(tint, Offset(8.4f * u, 20f * u), Offset(15.6f * u, 20f * u), 1.9f * u, StrokeCap.Round)
    }
}

/** 选中：对勾。 */
@Composable
fun GlyphCheck(size: Dp = 16.dp, tint: Color) {
    Glyph(Modifier.size(size)) { u ->
        val path = Path().apply {
            moveTo(5.5f * u, 12.6f * u)
            lineTo(10f * u, 17f * u)
            lineTo(18.5f * u, 7.4f * u)
        }
        drawPath(path, tint, style = Stroke(width = 2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 关闭：叉。 */
@Composable
fun GlyphClose(size: Dp = 18.dp, tint: Color) {
    Glyph(Modifier.size(size)) { u ->
        val w = 2f * u
        drawLine(tint, Offset(6.4f * u, 6.4f * u), Offset(17.6f * u, 17.6f * u), w, StrokeCap.Round)
        drawLine(tint, Offset(17.6f * u, 6.4f * u), Offset(6.4f * u, 17.6f * u), w, StrokeCap.Round)
    }
}

/** 复制：两层错位的圆角方框。 */
@Composable
fun GlyphCopy(size: Dp = 18.dp, tint: Color) {
    Glyph(Modifier.size(size)) { u ->
        val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            color = tint,
            topLeft = Offset(9f * u, 9f * u),
            size = Size(11f * u, 11f * u),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.6f * u),
            style = stroke,
        )
        val path = Path().apply {
            moveTo(15f * u, 6.2f * u)
            lineTo(15f * u, 5.4f * u)
            quadraticBezierTo(15f * u, 4f * u, 13.6f * u, 4f * u)
            lineTo(5.4f * u, 4f * u)
            quadraticBezierTo(4f * u, 4f * u, 4f * u, 5.4f * u)
            lineTo(4f * u, 13.6f * u)
            quadraticBezierTo(4f * u, 15f * u, 5.4f * u, 15f * u)
            lineTo(6.2f * u, 15f * u)
        }
        drawPath(path, tint, style = stroke)
    }
}

/** 分享：方框里向上射出的箭头。方框顶边留口，箭头从口里穿出去。 */
@Composable
fun GlyphShare(size: Dp = 18.dp, tint: Color) {
    Glyph(Modifier.size(size)) { u ->
        val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val box = Path().apply {
            moveTo(9.2f * u, 8.8f * u)
            lineTo(6.8f * u, 8.8f * u)
            lineTo(5.2f * u, 10.4f * u)
            lineTo(5.2f * u, 18.6f * u)
            lineTo(6.8f * u, 20.2f * u)
            lineTo(17.2f * u, 20.2f * u)
            lineTo(18.8f * u, 18.6f * u)
            lineTo(18.8f * u, 10.4f * u)
            lineTo(17.2f * u, 8.8f * u)
            lineTo(14.8f * u, 8.8f * u)
        }
        drawPath(box, tint, style = stroke)
        drawLine(tint, Offset(12f * u, 15f * u), Offset(12f * u, 4.2f * u), 1.8f * u, StrokeCap.Round)
        val head = Path().apply {
            moveTo(8.8f * u, 7.2f * u)
            lineTo(12f * u, 4f * u)
            lineTo(15.2f * u, 7.2f * u)
        }
        drawPath(head, tint, style = stroke)
    }
}

/** 图片：方框里的山与太阳。 */
@Composable
fun GlyphImage(size: Dp = 18.dp, tint: Color) {
    Glyph(Modifier.size(size)) { u ->
        val stroke = Stroke(width = 1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawRoundRect(
            color = tint,
            topLeft = Offset(4.4f * u, 5.4f * u),
            size = Size(15.2f * u, 13.2f * u),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f * u),
            style = stroke,
        )
        drawCircle(tint, radius = 1.5f * u, center = Offset(9.2f * u, 9.6f * u))
        val hill = Path().apply {
            moveTo(5.6f * u, 16.6f * u)
            lineTo(10.2f * u, 12.2f * u)
            lineTo(13.4f * u, 15.2f * u)
            lineTo(15.4f * u, 13.4f * u)
            lineTo(18.4f * u, 16.6f * u)
        }
        drawPath(hill, tint, style = stroke)
    }
}

/** 文件：带折角的纸。 */
@Composable
fun GlyphFile(size: Dp = 16.dp, tint: Color) {
    Glyph(Modifier.size(size)) { u ->
        val stroke = Stroke(width = 1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(13.4f * u, 4.2f * u)
            lineTo(6.8f * u, 4.2f * u)
            lineTo(5f * u, 6f * u)
            lineTo(5f * u, 18f * u)
            lineTo(6.8f * u, 19.8f * u)
            lineTo(17.2f * u, 19.8f * u)
            lineTo(19f * u, 18f * u)
            lineTo(19f * u, 9.8f * u)
            lineTo(13.4f * u, 4.2f * u)
        }
        drawPath(path, tint, style = stroke)
        val fold = Path().apply {
            moveTo(13.2f * u, 4.4f * u)
            lineTo(13.2f * u, 8.4f * u)
            lineTo(14.6f * u, 9.8f * u)
            lineTo(18.8f * u, 9.8f * u)
        }
        drawPath(fold, tint, style = stroke)
    }
}

/** 菜单：三条粗细不同的横线（第一条最长）。 */
@Composable
fun GlyphMenu(size: Dp = 20.dp, tint: Color) {
    Glyph(Modifier.size(size)) { u ->
        val w = 2f * u
        drawLine(tint, Offset(4.5f * u, 7f * u), Offset(19.5f * u, 7f * u), w, StrokeCap.Round)
        drawLine(tint, Offset(4.5f * u, 12f * u), Offset(15f * u, 12f * u), w, StrokeCap.Round)
        drawLine(tint, Offset(4.5f * u, 17f * u), Offset(17.5f * u, 17f * u), w, StrokeCap.Round)
    }
}

/** 更多：三个等距圆点，用于单条消息的动作菜单。 */
@Composable
fun GlyphMore(size: Dp = 18.dp, tint: Color) {
    Glyph(Modifier.size(size)) { u ->
        for (x in listOf(6f, 12f, 18f)) {
            drawCircle(tint, radius = 1.7f * u, center = Offset(x * u, 12f * u))
        }
    }
}

/** 勾在圈里：工具调用完成的紧凑状态图标。 */
@Composable
fun GlyphCheckCircle(size: Dp = 16.dp, tint: Color) {
    Glyph(Modifier.size(size)) { u ->
        val stroke = Stroke(width = 1.7f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(tint, radius = 9f * u, center = Offset(12f * u, 12f * u), style = stroke)
        val path = Path().apply {
            moveTo(7.8f * u, 12.2f * u)
            lineTo(10.8f * u, 15.2f * u)
            lineTo(16.4f * u, 9f * u)
        }
        drawPath(path, tint, style = stroke)
    }
}
