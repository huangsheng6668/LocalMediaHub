package com.juziss.localmediahub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.PlaybackProgressEntry
import com.juziss.localmediahub.data.RecentActivityStore
import com.juziss.localmediahub.data.RecentMediaEntry
import com.juziss.localmediahub.data.ServerConfigStore
import com.juziss.localmediahub.ui.screen.BrowseScreen
import com.juziss.localmediahub.ui.screen.ConnectionScreen
import com.juziss.localmediahub.ui.screen.DownloadsScreen
import com.juziss.localmediahub.ui.screen.HomeScreen
import com.juziss.localmediahub.ui.screen.ImagePreviewScreen
import com.juziss.localmediahub.ui.screen.VideoPlayerScreen
import com.juziss.localmediahub.ui.theme.LocalMediaHubTheme
import com.juziss.localmediahub.viewmodel.BrowseViewModel
import com.juziss.localmediahub.viewmodel.HomeViewModel
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

/**
 * EntryPoint exposing the singleton Stores to the @Composable layer (which
 * cannot use @Inject field injection). All Stores are @Singleton-scoped in the
 * Hilt graph, so the instances retrieved here are the same ones injected into
 * the ViewModels.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppStoresEntryPoint {
    fun recentActivityStore(): RecentActivityStore
    fun serverConfig(): ServerConfigStore
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalMediaHubTheme {
                LocalMediaHubApp()
            }
        }
    }
}

@Composable
fun LocalMediaHubApp() {
    val navController = rememberNavController()

    // Retrieve singleton Stores via Hilt EntryPoint. They can't be field-injected
    // into a top-level @Composable, so we pull them from the Hilt component graph
    // attached to the Application. These are the same @Singleton instances the
    // ViewModels receive through constructor injection.
    val context = LocalContext.current
    val appStores = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            AppStoresEntryPoint::class.java,
        )
    }
    val recentActivityStore = appStores.recentActivityStore()
    val serverConfig = appStores.serverConfig()

    // Shared state for passing media data between screens
    // Keep media navigation payloads in memory only. Saving whole MediaFile objects and
    // image lists can stall the UI when opening large folders or image sets.
    // Round 20: rememberSaveable for process-death recovery.
    // MediaFile is @Parcelize so it serializes to SavedStateHandle automatically.
    var currentVideoFile by rememberSaveable { mutableStateOf<MediaFile?>(null) }
    var currentVideoUrl by rememberSaveable { mutableStateOf("") }
    var currentVideoUsesSystemUrl by rememberSaveable { mutableStateOf(false) }
    var currentVideoStartPositionMs by rememberSaveable { mutableLongStateOf(0L) }

    var currentImageFile by remember { mutableStateOf<MediaFile?>(null) }
    var imageList by remember { mutableStateOf<List<MediaFile>>(emptyList()) }
    var currentImageUsesSystemUrl by remember { mutableStateOf(false) }
    var currentImageIsLocal by remember { mutableStateOf(false) }
 
    val appScope = rememberCoroutineScope()
    val browseViewModel: BrowseViewModel = hiltViewModel()
    val homeViewModel: HomeViewModel = hiltViewModel()
    val downloadedEntries by browseViewModel.downloadedFiles.collectAsState(initial = emptyList())

    NavHost(navController = navController, startDestination = "connection") {
        composable("connection") {
            ConnectionScreen(
                onConnected = {
                    navController.navigate("home") {
                        popUpTo("connection") { inclusive = false }
                    }
                },
                onBrowseOffline = {
                    navController.navigate("downloads")
                }
            )
        }

        composable("home") {
            HomeScreen(
                onOpenLibrary = { library ->
                    browseViewModel.setShowFavoritesOnly(false)
                    browseViewModel.browseFolder(library.path, library.name)
                    navController.navigate("browse")
                },
                onResumeBrowse = { location ->
                    browseViewModel.setShowFavoritesOnly(false)
                    if (location.isSystemBrowse) {
                        browseViewModel.browseSystemPath(location.path, location.title)
                    } else {
                        browseViewModel.browseFolder(location.path, location.title)
                    }
                    navController.navigate("browse")
                },
                onOpenFavorites = {
                    browseViewModel.loadRoots()
                    browseViewModel.setShowFavoritesOnly(true)
                    navController.navigate("browse")
                },
                onOpenCollection = { tag ->
                    browseViewModel.openCollection(tag)
                    navController.navigate("browse")
                },
                onContinueWatching = { entry ->
                    openPlaybackProgress(
                        entry = entry,
                        homeViewModel = homeViewModel,
                        navController = navController,
                        onVideoReady = { file, url, positionMs, isSystemBrowse ->
                            currentVideoFile = file
                            currentVideoUrl = url
                            currentVideoStartPositionMs = positionMs
                            currentVideoUsesSystemUrl = isSystemBrowse
                        },
                    )
                },
                onOpenRecentMedia = { entry ->
                    openRecentMedia(
                        entry = entry,
                        homeViewModel = homeViewModel,
                        navController = navController,
                        onVideoReady = { file, url ->
                            currentVideoFile = file
                            currentVideoUrl = url
                        },
                        onImageReady = { file, images ->
                            currentImageFile = file
                            imageList = images
                            currentImageUsesSystemUrl = entry.isSystemBrowse
                            currentImageIsLocal = false
                        },
                    )
                },
                onFavoriteClick = { file ->
                    appScope.launch {
                        recentActivityStore.addRecentMedia(
                            file = file,
                            isSystemBrowse = homeViewModel.isFavoriteSystemBrowse(file),
                        )
                    }
                    if (file.mediaType == "video") {
                        currentVideoFile = file
                        currentVideoUrl = homeViewModel.getFavoriteStreamUrl(file)
                        currentVideoUsesSystemUrl = homeViewModel.isFavoriteSystemBrowse(file)
                        currentVideoStartPositionMs = 0L
                        navController.navigate("videoPlayer")
                    } else {
                        currentImageFile = file
                        imageList = listOf(file)
                        currentImageUsesSystemUrl = homeViewModel.isFavoriteSystemBrowse(file)
                        currentImageIsLocal = false
                        navController.navigate("imagePreview")
                    }
                },
                onDisconnect = {
                    appScope.launch {
                        serverConfig.clearConfig()
                    }
                    navController.navigate("connection") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                downloadedEntries = downloadedEntries,
                onOpenDownloads = { navController.navigate("downloads") },
                onDownloadClick = { entry ->
                    if (entry.file.mediaType == "video") {
                        currentVideoFile = entry.file
                        currentVideoUrl = "file://${entry.localPath}"
                        currentVideoUsesSystemUrl = false
                        currentVideoStartPositionMs = 0L
                        navController.navigate("videoPlayer")
                    } else {
                        currentImageFile = entry.file
                        imageList = listOf(entry.file)
                        currentImageIsLocal = true
                        currentImageUsesSystemUrl = false
                        navController.navigate("imagePreview")
                    }
                },
                viewModel = homeViewModel,
            )
        }

        composable("browse") {
            BrowseScreen(
                onExitBrowse = { navController.popBackStack() },
                onVideoClick = { file ->
                    appScope.launch {
                        recentActivityStore.addRecentMedia(
                            file = file,
                            isSystemBrowse = browseViewModel.isSystemBrowseMode(),
                        )
                    }
                    currentVideoFile = file
                    currentVideoUrl = browseViewModel.getVideoStreamUrl(file)
                    currentVideoUsesSystemUrl = browseViewModel.isSystemBrowseMode()
                    currentVideoStartPositionMs = 0L
                    navController.navigate("videoPlayer")
                },
                onImageClick = { file, images ->
                    appScope.launch {
                        recentActivityStore.addRecentMedia(
                            file = file,
                            isSystemBrowse = browseViewModel.isSystemBrowseMode(),
                        )
                    }
                    currentImageFile = file
                    imageList = images
                    currentImageUsesSystemUrl = browseViewModel.isSystemBrowseMode()
                    currentImageIsLocal = false
                    navController.navigate("imagePreview")
                },
                onFavoriteVideoClick = { file, isSystemBrowse ->
                    appScope.launch {
                        recentActivityStore.addRecentMedia(
                            file = file,
                            isSystemBrowse = isSystemBrowse,
                        )
                    }
                    currentVideoFile = file
                    currentVideoUrl = browseViewModel.getFavoriteVideoStreamUrl(file)
                    currentVideoUsesSystemUrl = isSystemBrowse
                    currentVideoStartPositionMs = 0L
                    navController.navigate("videoPlayer")
                },
                onFavoriteImageClick = { file, images, isSystemBrowse ->
                    appScope.launch {
                        recentActivityStore.addRecentMedia(
                            file = file,
                            isSystemBrowse = isSystemBrowse,
                        )
                    }
                    currentImageFile = file
                    imageList = images
                    currentImageUsesSystemUrl = isSystemBrowse
                    currentImageIsLocal = false
                    navController.navigate("imagePreview")
                },
                viewModel = browseViewModel,
            )
        }

        composable("videoPlayer") {
            if (currentVideoFile != null) {
                val context = androidx.compose.ui.platform.LocalContext.current
                VideoPlayerScreen(
                    streamUrl = currentVideoUrl,
                    initialPositionMs = currentVideoStartPositionMs,
                    onProgress = { positionMs, durationMs ->
                        val file = currentVideoFile
                        if (file != null) {
                            appScope.launch {
                                recentActivityStore.savePlaybackProgress(
                                    file = file,
                                    isSystemBrowse = currentVideoUsesSystemUrl,
                                    positionMs = positionMs,
                                    durationMs = durationMs,
                                )
                            }
                        }
                    },
                    onBack = { navController.popBackStack() },
                    onDelete = {
                        val file = currentVideoFile
                        if (file != null) {
                            appScope.launch {
                                when (val result = browseViewModel.deletePathSync(file.path, false)) {
                                    is com.juziss.localmediahub.network.NetworkResult.Success -> {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            android.widget.Toast.makeText(context, context.getString(R.string.main_delete_success), android.widget.Toast.LENGTH_SHORT).show()
                                            navController.popBackStack()
                                            browseViewModel.refreshCurrentDirectory()
                                        }
                                    }
                                    is com.juziss.localmediahub.network.NetworkResult.Error -> {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            android.widget.Toast.makeText(context, context.getString(R.string.main_delete_failed, result.message), android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
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
                        if (currentImageIsLocal) {
                            val localEntry = downloadedEntries.find { it.file.relativePath == mediaFile.relativePath }
                            if (localEntry != null) {
                                "file://${localEntry.localPath}"
                            } else {
                                ""
                            }
                        } else if (currentImageUsesSystemUrl) {
                            browseViewModel.getFavoriteOriginalImageUrl(mediaFile)
                        } else {
                            browseViewModel.getOriginalImageUrl(mediaFile)
                        }
                    },
                )
            }
        }
 
        composable("downloads") {
            DownloadsScreen(
                onBack = { navController.popBackStack() },
                onVideoClick = { file, localPath ->
                    currentVideoFile = file
                    currentVideoUrl = "file://$localPath"
                    currentVideoUsesSystemUrl = false
                    currentVideoStartPositionMs = 0L
                    navController.navigate("videoPlayer")
                },
                onImageClick = { file, images ->
                    currentImageFile = file
                    imageList = images
                    currentImageUsesSystemUrl = false
                    currentImageIsLocal = true
                    navController.navigate("imagePreview")
                },
                viewModel = browseViewModel
            )
        }
    }
}

private fun openRecentMedia(
    entry: RecentMediaEntry,
    homeViewModel: HomeViewModel,
    navController: NavHostController,
    onVideoReady: (MediaFile, String) -> Unit,
    onImageReady: (MediaFile, List<MediaFile>) -> Unit,
) {
    if (entry.file.mediaType == "video") {
        onVideoReady(entry.file, homeViewModel.getVideoStreamUrl(entry))
        navController.navigate("videoPlayer")
        return
    }

    onImageReady(entry.file, listOf(entry.file))
    navController.navigate("imagePreview")
}

private fun openPlaybackProgress(
    entry: PlaybackProgressEntry,
    homeViewModel: HomeViewModel,
    navController: NavHostController,
    onVideoReady: (MediaFile, String, Long, Boolean) -> Unit,
) {
    onVideoReady(
        entry.file,
        homeViewModel.getVideoStreamUrl(entry),
        entry.positionMs,
        entry.isSystemBrowse,
    )
    navController.navigate("videoPlayer")
}
