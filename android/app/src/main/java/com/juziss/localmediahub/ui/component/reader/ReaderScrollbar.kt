package com.juziss.localmediahub.ui.component.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * 阅读器全书进度竖向可拖动滚动条。与 [com.juziss.localmediahub.ui.component.VerticalScrollbar]
 * 互斥使用——本组件绑外部 progress 语义,不绑 LazyListState。
 *
 * 进度语义:progress ∈ [0f, 1f] 表示全书进度(两种阅读模式统一)。
 * 拖动交互:松手才跳——onSeek 仅更新本地 thumb 预览,onSeekEnd 执行跳转。
 *
 * Release 处理采用"release-point-wins":Release 事件按当次 y 坐标重新计算 progress,
 * 避免因缺最后一次 Move 事件而落入 stale 位置(与 Web renderScrubber 对齐)。
 */
@Composable
fun ReaderScrollbar(
    progress: Float,
    onSeekStart: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekEnd: (Float) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val displayProgress by remember {
        derivedStateOf { if (isDragging) dragProgress else clampedProgress }
    }

    var trackPx by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = modifier
            .width(28.dp)
            .fillMaxHeight()
            .padding(vertical = 48.dp)
            .clipToBounds()
            .let { if (testTag != null) it.testTag(testTag) else it }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val y = event.changes.firstOrNull()?.position?.y ?: 0f
                        when (event.type) {
                            PointerEventType.Press -> {
                                trackPx = this.size.height.toFloat()
                                if (trackPx > 0f) {
                                    dragProgress = (y / trackPx).coerceIn(0f, 1f)
                                    isDragging = true
                                    onSeekStart()
                                    onSeek(dragProgress)
                                }
                                event.changes.firstOrNull()?.consume()
                            }
                            PointerEventType.Move -> {
                                if (isDragging && trackPx > 0f) {
                                    dragProgress = (y / trackPx).coerceIn(0f, 1f)
                                    onSeek(dragProgress)
                                }
                            }
                            PointerEventType.Release -> {
                                if (isDragging) {
                                    if (trackPx > 0f) {
                                        dragProgress = (y / trackPx).coerceIn(0f, 1f)
                                    }
                                    isDragging = false
                                    onSeekEnd(dragProgress)
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
    ) {
        val trackHeightDp = maxHeight
        val thumbHeightDp = (trackHeightDp * 0.15f).coerceAtLeast(32.dp)
        val thumbOffsetDp = (trackHeightDp - thumbHeightDp) * displayProgress
        val thumbAlpha = if (isDragging) 0.85f else 0.5f

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(4.dp)
                .align(Alignment.Center)
                .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(2.dp))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = thumbOffsetDp)
                .height(thumbHeightDp)
                .padding(horizontal = if (isDragging) 9.dp else 11.dp)
                .background(Color.White.copy(alpha = thumbAlpha), RoundedCornerShape(3.dp))
        )
    }
}
