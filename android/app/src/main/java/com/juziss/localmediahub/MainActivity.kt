package com.juziss.localmediahub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.juziss.localmediahub.data.FavoritesStore
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.ui.screen.BrowseScreen
import com.juziss.localmediahub.ui.screen.ConnectionScreen
import com.juziss.localmediahub.ui.screen.ImagePreviewScreen
import com.juziss.localmediahub.ui.screen.VideoPlayerScreen
import com.juziss.localmediahub.ui.theme.LocalMediaHubTheme
import com.juziss.localmediahub.viewmodel.BrowseViewModel
import com.juziss.localmediahub.viewmodel.BrowseViewModelFactory
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.BitmapFactoryDecoder
import com.juziss.localmediahub.native.NativeDecoderFactory

class MainActivity : ComponentActivity(), ImageLoaderFactory {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalMediaHubTheme {
                LocalMediaHubApp()
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(NativeDecoderFactory.Factory())
                add(BitmapFactoryDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}

@Composable
fun LocalMediaHubApp() {
    val navController = rememberNavController()

    // Shared state for passing media data between screens
    var currentVideoFile by remember { mutableStateOf<MediaFile?>(null) }
    var currentVideoUrl by remember { mutableStateOf("") }

    var currentImageFile by remember { mutableStateOf<MediaFile?>(null) }
    var imageList by remember { mutableStateOf<List<MediaFile>>(emptyList()) }

    val context = LocalContext.current
    val favoritesStore = remember { FavoritesStore(context) }
    val browseViewModel: BrowseViewModel = viewModel(
        factory = BrowseViewModelFactory(favoritesStore),
    )

    NavHost(navController = navController, startDestination = "connection") {
        composable("connection") {
            ConnectionScreen(
                onConnected = {
                    navController.navigate("browse") {
                        popUpTo("connection") { inclusive = false }
                    }
                },
            )
        }

        composable("browse") {
            BrowseScreen(
                onFolderClick = { _, _ -> /* handled internally */ },
                onVideoClick = { file ->
                    currentVideoFile = file
                    currentVideoUrl = browseViewModel.getVideoStreamUrl(file)
                    navController.navigate("videoPlayer")
                },
                onImageClick = { file, images ->
                    currentImageFile = file
                    imageList = images
                    navController.navigate("imagePreview")
                },
                viewModel = browseViewModel,
            )
        }

        composable("videoPlayer") {
            if (currentVideoFile != null) {
                VideoPlayerScreen(
                    streamUrl = currentVideoUrl,
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable("imagePreview") {
            val file = currentImageFile
            if (file != null) {
                ImagePreviewScreen(
                    currentFile = file,
                    imageList = imageList,
                    onBack = { navController.popBackStack() },
                    getOriginalUrl = { mediaFile ->
                        browseViewModel.getOriginalImageUrl(mediaFile)
                    },
                )
            }
        }
    }
}
