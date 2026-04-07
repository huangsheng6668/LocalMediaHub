package com.juziss.localmediahub.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.juziss.localmediahub.data.MediaFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePreviewScreen(
    currentFile: MediaFile,
    imageUrl: String,
    imageList: List<MediaFile>,
    onBack: () -> Unit,
    onNavigate: (MediaFile) -> Unit,
    getOriginalUrl: (MediaFile) -> String,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Top bar
        TopAppBar(
            title = {
                Text(
                    currentFile.name + currentFile.extension,
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
                containerColor = Color.Transparent,
            ),
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // Image with pinch-to-zoom
        AsyncImage(
            model = imageUrl,
            contentDescription = currentFile.name,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 5f)
                        offset = Offset(
                            x = offset.x + pan.x,
                            y = offset.y + pan.y,
                        )
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.Fit,
        )

        // Navigation arrows
        val currentIndex = imageList.indexOfFirst { it.relativePath == currentFile.relativePath }

        if (currentIndex > 0) {
            FloatingActionButton(
                onClick = {
                    val prev = imageList[currentIndex - 1]
                    onNavigate(prev)
                },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Previous",
                )
            }
        }

        if (currentIndex >= 0 && currentIndex < imageList.size - 1) {
            FloatingActionButton(
                onClick = {
                    val next = imageList[currentIndex + 1]
                    onNavigate(next)
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Next",
                )
            }
        }
    }
}
