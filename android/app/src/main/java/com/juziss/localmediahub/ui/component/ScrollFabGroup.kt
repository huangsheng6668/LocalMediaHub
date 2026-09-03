package com.juziss.localmediahub.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juziss.localmediahub.R

data class ScrollFabVisibility(
    val canScrollToTop: Boolean,
    val canScrollToBottom: Boolean,
)

/**
 * Pure calculation function for determining whether the scroll-to-top and
 * scroll-to-bottom FABs should be visible.
 */
fun calculateScrollFabVisibility(
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    lastVisibleIndex: Int,
    totalItems: Int,
    visibleCount: Int,
    offsetThreshold: Int = 100,
): ScrollFabVisibility {
    if (totalItems <= 1 || visibleCount >= totalItems) {
        return ScrollFabVisibility(canScrollToTop = false, canScrollToBottom = false)
    }
    val atTop = firstVisibleIndex == 0 && firstVisibleOffset <= offsetThreshold
    val atBottom = lastVisibleIndex >= totalItems - 1
    return ScrollFabVisibility(
        canScrollToTop = !atTop,
        canScrollToBottom = !atBottom,
    )
}

/**
 * Floating Action Button pair for quick scroll to top and bottom.
 * Supports smooth fade and scale enter/exit animations for each button independently.
 */
@Composable
fun ScrollFabGroup(
    canScrollToTop: Boolean,
    canScrollToBottom: Boolean,
    onScrollToTop: () -> Unit,
    onScrollToBottom: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    if (!canScrollToTop && !canScrollToBottom) return

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(
            visible = canScrollToTop,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            FloatingActionButton(
                onClick = onScrollToTop,
                modifier = Modifier.size(40.dp),
                containerColor = containerColor,
                contentColor = contentColor,
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.content_to_top),
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = canScrollToBottom,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            FloatingActionButton(
                onClick = onScrollToBottom,
                modifier = Modifier.size(40.dp),
                containerColor = containerColor,
                contentColor = contentColor,
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.content_to_bottom),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
