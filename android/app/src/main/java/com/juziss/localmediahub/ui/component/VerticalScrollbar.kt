package com.juziss.localmediahub.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * A vertical scrollbar that tracks [LazyListState] position and supports drag-to-seek.
 * The entire track area is interactive: tap to jump, drag to scrub.
 * Placed alongside the LazyColumn in a Row.
 */
@Composable
fun VerticalScrollbar(
    listState: LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier,
    onDragStateChanged: (Boolean) -> Unit = {},
) {
    if (itemCount <= 0) return

    val listProgress by remember {
        derivedStateOf {
            if (itemCount <= 1) 0f
            else (listState.firstVisibleItemIndex.toFloat()
                    + listState.firstVisibleItemScrollOffset.coerceAtLeast(0) / 1000f) /
                    (itemCount - 1).coerceAtLeast(1)
        }
    }

    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    // Notify parent when drag state changes
    LaunchedEffect(isDragging) {
        onDragStateChanged(isDragging)
    }

    val displayProgress by remember {
        derivedStateOf { if (isDragging) dragProgress else listProgress }
    }

    // Scroll the list when drag progress changes
    LaunchedEffect(dragProgress) {
        if (isDragging && itemCount > 1) {
            val targetIndex = (dragProgress * (itemCount - 1)).toInt()
                .coerceIn(0, itemCount - 1)
            listState.scrollToItem(targetIndex)
        }
    }

    val visibleItemCount = listState.layoutInfo.visibleItemsInfo.size
    val thumbRatio = (visibleItemCount.toFloat() / itemCount.coerceAtLeast(1)).coerceIn(0.05f, 1f)

    BoxWithConstraints(
        modifier = modifier
            .width(28.dp)
            .padding(vertical = 48.dp)
            .clipToBounds()
            .pointerInput(itemCount) {
                val trackPx = this.size.height.toFloat()

                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Press -> {
                                val y = event.changes.first().position.y
                                if (trackPx > 0f) {
                                    dragProgress = (y / trackPx).coerceIn(0f, 1f)
                                    isDragging = true
                                }
                                event.changes.first().consume()
                            }
                            PointerEventType.Move -> {
                                if (isDragging) {
                                    val y = event.changes.first().position.y
                                    if (trackPx > 0f) {
                                        dragProgress = (y / trackPx).coerceIn(0f, 1f)
                                    }
                                    event.changes.first().consume()
                                }
                            }
                            PointerEventType.Release -> {
                                isDragging = false
                            }
                        }
                    }
                }
            }
    ) {
        val trackHeightDp = maxHeight
        val thumbHeightDp = (trackHeightDp * thumbRatio).coerceAtLeast(32.dp)

        // Background track
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(4.dp)
                .align(Alignment.Center)
                .background(
                    Color.White.copy(alpha = 0.15f),
                    RoundedCornerShape(2.dp)
                )
        )

        // Thumb — positioned via padding-top (moves layout, not just visual)
        val thumbOffsetDp = (trackHeightDp - thumbHeightDp) * displayProgress
        val thumbAlpha = if (isDragging) 0.85f else 0.5f

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = thumbOffsetDp)
                .height(thumbHeightDp)
                .padding(horizontal = if (isDragging) 9.dp else 11.dp)
                .background(
                    Color.White.copy(alpha = thumbAlpha),
                    RoundedCornerShape(3.dp)
                )
        )
    }
}
