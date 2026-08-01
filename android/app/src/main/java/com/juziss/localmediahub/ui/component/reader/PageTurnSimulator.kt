package com.juziss.localmediahub.ui.component.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath

/**
 * 仿真翻页：顶层（当前章）用贝塞尔 Path 裁剪，随 [progress]（0..1）卷走。
 * 卷曲边界处画一道渐变阴影模拟折痕。底层显示下一章（由调用方叠放）。
 *
 * 可见区语义：
 *  - [reverse]=false（NEXT）：可见区 = 左侧矩形（0 .. edge），edge 从 w 扫到 0
 *    → 从右往左卷走（下一章从右侧进入）。progress=0 全屏；progress=1 空。
 *  - [reverse]=true（PREV）：可见区 = 右侧矩形（w-edge .. w），w-edge 从 w 扫到 0
 *    → 从左往右卷走（下一章从左侧进入）。progress=0 全屏；progress=1 空。
 */
@Composable
fun PageTurnSimulator(
    progress: Float,
    reverse: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val edge = (1f - progress) * w // 卷曲边界 x（next 时是可见区右边界；prev 时是可见区左边界）
        val p = if (reverse) {
            // PREV：可见区在右侧 (w-edge .. w)，贝塞尔弧在左边界 w-edge
            Path().apply {
                moveTo(w - edge, 0f)
                lineTo(w, 0f)
                lineTo(w, h)
                cubicTo(
                    (w - edge) + 30f, h * 0.66f,
                    (w - edge) - 30f, h * 0.33f,
                    w - edge, h,
                )
                close()
            }
        } else {
            // NEXT：可见区在左侧 (0 .. edge)，贝塞尔弧在右边界 edge
            Path().apply {
                moveTo(0f, 0f)
                lineTo(edge, 0f)
                cubicTo(
                    edge + 30f, h * 0.33f,
                    edge - 30f, h * 0.66f,
                    edge, h,
                )
                lineTo(0f, h)
                close()
            }
        }
        clipPath(p) {
            // 阴影带：画在卷曲边界（next=右边界 edge；prev=左边界 w-edge）附近
            val shadowEdge = if (reverse) w - edge else edge
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.18f), Color.Transparent),
                    start = Offset(shadowEdge - 24f, 0f),
                    end = Offset(shadowEdge + 24f, 0f),
                ),
            )
        }
    }
}
