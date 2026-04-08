package com.juziss.localmediahub.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.ui.component.VerticalScrollbar
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePreviewScreen(
    currentFile: MediaFile,
    imageList: List<MediaFile>,
    onBack: () -> Unit,
    getOriginalUrl: (MediaFile) -> String,
) {
    val currentIndex = imageList.indexOfFirst { it.relativePath == currentFile.relativePath }
        .coerceAtLeast(0)
    val listState = rememberLazyListState()

    var visibleIndex by remember { mutableIntStateOf(currentIndex) }
    LaunchedEffect(listState.firstVisibleItemIndex) {
        visibleIndex = listState.firstVisibleItemIndex
    }

    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(currentIndex) {
        if (!initialized && imageList.isNotEmpty()) {
            listState.scrollToItem(currentIndex)
            initialized = true
        }
    }

    // Auto-hide top bar: show on tap or scrollbar drag, hide after 3 seconds
    var showTopBar by remember { mutableStateOf(true) }
    var hideJob by remember { mutableStateOf(true) }

    fun resetHideTimer() {
        showTopBar = true
        hideJob = !hideJob // flip to restart LaunchedEffect
    }

    LaunchedEffect(hideJob) {
        delay(3000)
        showTopBar = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Main content: LazyColumn + scrollbar side by side
        Row(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(imageList, key = { _, file -> file.relativePath }) { _, file ->
                    ZoomableImageItem(
                        file = file,
                        imageUrl = getOriginalUrl(file),
                        onTap = { resetHideTimer() },
                    )
                }
            }

            VerticalScrollbar(
                listState = listState,
                itemCount = imageList.size,
                modifier = Modifier.fillMaxHeight(),
                onDragStateChanged = { dragging ->
                    if (dragging) resetHideTimer()
                },
            )
        }

        // Top bar — auto-hide with fade animation
        AnimatedVisibility(
            visible = showTopBar,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopAppBar(
                title = {
                    Text(
                        "${visibleIndex + 1} / ${imageList.size}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f),
                ),
                modifier = Modifier.fillMaxWidth(),
                windowInsets = WindowInsets(0),
            )
        }
    }
}

@Composable
private fun ZoomableImageItem(
    file: MediaFile,
    imageUrl: String,
    onTap: () -> Unit = {},
) {
    var scale by remember(file.relativePath) { mutableFloatStateOf(1f) }
    var offset by remember(file.relativePath) { mutableStateOf(Offset.Zero) }
    var hasMoved by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 300.dp)
            .background(Color.Black)
            .pointerInput(file.relativePath) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    hasMoved = false
                    do {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()

                        if (panChange != Offset.Zero || zoomChange != 1f) {
                            hasMoved = true
                        }

                        val newScale = (scale * zoomChange).coerceIn(0.5f, 5f)

                        if (newScale > 1f && scale > 1f) {
                            offset = Offset(
                                x = offset.x + panChange.x,
                                y = offset.y + panChange.y,
                            )
                            scale = newScale
                            down.consume()
                        } else if (newScale > 1f && scale <= 1f) {
                            scale = newScale
                            down.consume()
                        } else {
                            scale = newScale.coerceAtLeast(1f)
                            offset = Offset.Zero
                        }
                    } while (event.changes.any { it.pressed })

                    // Simple tap (no zoom/pan) → toggle top bar
                    if (!hasMoved) {
                        onTap()
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale.coerceAtLeast(1f)
                scaleY = scale.coerceAtLeast(1f)
                translationX = if (scale <= 1f) 0f else offset.x
                translationY = if (scale <= 1f) 0f else offset.y
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = file.name,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
        )
    }
}