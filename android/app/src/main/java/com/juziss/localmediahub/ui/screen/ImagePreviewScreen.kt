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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlin.math.min
import com.juziss.localmediahub.R
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
    onImageVisible: (MediaFile) -> Unit = {},
) {
    val currentIndex = imageList.indexOfFirst { it.relativePath == currentFile.relativePath }
        .coerceAtLeast(0)
    val listState = rememberLazyListState()

    var visibleIndex by remember { mutableIntStateOf(currentIndex) }
    LaunchedEffect(listState.firstVisibleItemIndex) {
        visibleIndex = listState.firstVisibleItemIndex
        if (visibleIndex in imageList.indices) {
            onImageVisible(imageList[visibleIndex])
        }
    }

    val context = LocalContext.current
    // Preload requests decode at the same capped size as the visible item
    // (ZoomableImageItem), so swiping through large photos does not decode
    // neighbors at full resolution (memory spike).
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val preloadCap = 2048
    val preloadWidth = min(with(density) { configuration.screenWidthDp.dp.toPx() }.toInt(), preloadCap)
    val preloadHeight = min(with(density) { configuration.screenHeightDp.dp.toPx() }.toInt(), preloadCap)
    LaunchedEffect(visibleIndex) {
        if (imageList.isNotEmpty()) {
            val loader = coil3.SingletonImageLoader.get(context)
            // Preload next image
            if (visibleIndex < imageList.lastIndex) {
                val nextFile = imageList[visibleIndex + 1]
                val nextUrl = getOriginalUrl(nextFile)
                if (nextUrl.isNotBlank()) {
                    val req = coil3.request.ImageRequest.Builder(context)
                        .data(nextUrl)
                        .size(preloadWidth, preloadHeight)
                        .build()
                    loader.enqueue(req)
                }
            }
            // Preload previous image
            if (visibleIndex > 0) {
                val prevFile = imageList[visibleIndex - 1]
                val prevUrl = getOriginalUrl(prevFile)
                if (prevUrl.isNotBlank()) {
                    val req = coil3.request.ImageRequest.Builder(context)
                        .data(prevUrl)
                        .size(preloadWidth, preloadHeight)
                        .build()
                    loader.enqueue(req)
                }
            }
        }
    }

    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(currentIndex) {
        if (!initialized && imageList.isNotEmpty()) {
            listState.scrollToItem(currentIndex)
            initialized = true
        }
    }

    // Auto-hide top bar: show on tap or scrollbar drag, hide after 3 seconds.
    // The restart key is a timestamp — the previous boolean-flip hack used a
    // meaningless variable name and relied on the flip to restart the effect.
    var showTopBar by remember { mutableStateOf(true) }
    var hideTrigger by remember { mutableStateOf(0L) }

    fun resetHideTimer() {
        showTopBar = true
        hideTrigger = System.currentTimeMillis()
    }

    LaunchedEffect(hideTrigger) {
        if (hideTrigger == 0L) return@LaunchedEffect
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

        // Top bar 鈥?auto-hide with fade animation
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
                            contentDescription = stringResource(R.string.a11y_back),
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
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val cap = 2048
    val reqWidth = min(with(density) { configuration.screenWidthDp.dp.toPx() }.toInt(), cap)
    val reqHeight = min(with(density) { configuration.screenHeightDp.dp.toPx() }.toInt(), cap)
    val request = remember(imageUrl, reqWidth, reqHeight) {
        ImageRequest.Builder(context)
            .data(imageUrl)
            .size(reqWidth, reqHeight)
            .build()
    }

    var scale by rememberSaveable(file.relativePath) { mutableFloatStateOf(1f) }
    var offsetX by rememberSaveable(file.relativePath) { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable(file.relativePath) { mutableFloatStateOf(0f) }
    var hasMoved by remember { mutableStateOf(false) } // UI auxiliary, accept reset

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
                            offsetX += panChange.x
                            offsetY += panChange.y
                            scale = newScale
                            down.consume()
                        } else if (newScale > 1f && scale <= 1f) {
                            scale = newScale
                            down.consume()
                        } else {
                            scale = newScale.coerceAtLeast(1f)
                            offsetX = 0f
                            offsetY = 0f
                        }
                    } while (event.changes.any { it.pressed })

                    // Simple tap (no zoom/pan) 鈫?toggle top bar
                    if (!hasMoved) {
                        onTap()
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale.coerceAtLeast(1f)
                scaleY = scale.coerceAtLeast(1f)
                translationX = if (scale <= 1f) 0f else offsetX
                translationY = if (scale <= 1f) 0f else offsetY
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            contentDescription = file.name,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth,
        )
    }
}