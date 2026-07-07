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
import com.juziss.localmediahub.data.isCompleted
import com.juziss.localmediahub.data.isValidProgress
import com.juziss.localmediahub.data.shouldFocusRestart
import com.juziss.localmediahub.ui.component.ResumePlaybackDialog
import com.juziss.localmediahub.ui.component.ResumePlaybackRequest
import com.juziss.localmediahub.ui.component.VideoOpenAction
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

    var resumeRequest by remember { mutableStateOf<ResumePlaybackRequest?>(null) }

    val appScope = rememberCoroutineScope()
    val browseViewModel: BrowseViewModel = hiltViewModel()
    val homeViewModel: HomeViewModel = hiltViewModel()
    val downloadedEntries by browseViewModel.downloadedFiles.collectAsState(initial = emptyList())

    val playVideo = { file: MediaFile, url: String, positionMs: Long, isSys: Boolean ->
        currentVideoFile = file
        currentVideoUrl = url
        currentVideoStartPositionMs = positionMs
        currentVideoUsesSystemUrl = isSys
        navController.navigate("videoPlayer")
    }

    NavHost(navController = navController, startDestination = "connection") {
        composable("connection") {
            ConnectionScreen(
                onConnected = {
                    navController.navigate("home") {
                        popUpTo("connection") { inclusive = true }
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
                    appScope.launch {
                        openRecentMedia(
                            entry = entry,
                            homeViewModel = homeViewModel,
                            recentActivityStore = recentActivityStore,
                            onVideoReady = { file, url, positionMs ->
                                currentVideoFile = file
                                currentVideoUrl = url
                                currentVideoStartPositionMs = positionMs
                                currentVideoUsesSystemUrl = entry.isSystemBrowse
                            },
                            onShowResumeDialog = { req -> resumeRequest = req },
                            onImageReady = { file, images ->
                                currentImageFile = file
                                imageList = images
                                currentImageUsesSystemUrl = entry.isSystemBrowse
                                currentImageIsLocal = false
                            },
                            navigateToVideoPlayer = { navController.navigate("videoPlayer") },
                            navigateToImagePreview = { navController.navigate("imagePreview") },
                        )
                    }
                },
                onFavoriteClick = { file ->
                    if (file.mediaType == "video") {
                        appScope.launch {
                            val isSystemBrowse = homeViewModel.isFavoriteSystemBrowse(file)
                            val streamUrl = homeViewModel.getFavoriteStreamUrl(file)
                            when (val action = checkPlaybackProgress(file, isSystemBrowse, recentActivityStore)) {
                                is VideoOpenAction.PlayDirectly ->
                                    playVideo(file, streamUrl, action.positionMs, isSystemBrowse)
                                is VideoOpenAction.ShowCompletedDialog ->
                                    resumeRequest = ResumePlaybackRequest(
                                        file = file,
                                        isSystemBrowse = isSystemBrowse,
                                        streamUrl = streamUrl,
                                        positionMs = action.positionMs,
                                        durationMs = action.durationMs,
                                    )
                            }
                        }
                    } else {
                        appScope.launch {
                            recentActivityStore.addRecentMedia(
                                file = file,
                                isSystemBrowse = homeViewModel.isFavoriteSystemBrowse(file),
                            )
                        }
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
                        appScope.launch {
                            val streamUrl = "file://${entry.localPath}"
                            when (val action = checkPlaybackProgress(entry.file, false, recentActivityStore)) {
                                is VideoOpenAction.PlayDirectly ->
                                    playVideo(entry.file, streamUrl, action.positionMs, false)
                                is VideoOpenAction.ShowCompletedDialog ->
                                    resumeRequest = ResumePlaybackRequest(
                                        file = entry.file,
                                        isSystemBrowse = false,
                                        streamUrl = streamUrl,
                                        positionMs = action.positionMs,
                                        durationMs = action.durationMs,
                                    )
                            }
                        }
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
                        val isSystemBrowse = browseViewModel.isSystemBrowseMode()
                        val streamUrl = browseViewModel.getVideoStreamUrl(file)
                        when (val action = checkPlaybackProgress(file, isSystemBrowse, recentActivityStore)) {
                            is VideoOpenAction.PlayDirectly ->
                                playVideo(file, streamUrl, action.positionMs, isSystemBrowse)
                            is VideoOpenAction.ShowCompletedDialog ->
                                resumeRequest = ResumePlaybackRequest(
                                    file = file,
                                    isSystemBrowse = isSystemBrowse,
                                    streamUrl = streamUrl,
                                    positionMs = action.positionMs,
                                    durationMs = action.durationMs,
                                )
                        }
                    }
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
                        val streamUrl = browseViewModel.getFavoriteVideoStreamUrl(file)
                        when (val action = checkPlaybackProgress(file, isSystemBrowse, recentActivityStore)) {
                            is VideoOpenAction.PlayDirectly ->
                                playVideo(file, streamUrl, action.positionMs, isSystemBrowse)
                            is VideoOpenAction.ShowCompletedDialog ->
                                resumeRequest = ResumePlaybackRequest(
                                    file = file,
                                    isSystemBrowse = isSystemBrowse,
                                    streamUrl = streamUrl,
                                    positionMs = action.positionMs,
                                    durationMs = action.durationMs,
                                )
                        }
                    }
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
                    appScope.launch {
                        val streamUrl = "file://$localPath"
                        when (val action = checkPlaybackProgress(file, false, recentActivityStore)) {
                            is VideoOpenAction.PlayDirectly ->
                                playVideo(file, streamUrl, action.positionMs, false)
                            is VideoOpenAction.ShowCompletedDialog ->
                                resumeRequest = ResumePlaybackRequest(
                                    file = file,
                                    isSystemBrowse = false,
                                    streamUrl = streamUrl,
                                    positionMs = action.positionMs,
                                    durationMs = action.durationMs,
                                )
                        }
                    }
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

    resumeRequest?.let { req ->
        val focusResume = !shouldFocusRestart(req.positionMs, req.durationMs)
        ResumePlaybackDialog(
            request = req,
            focusResume = focusResume,
            onRestart = {
                appScope.launch {
                    recentActivityStore.clearPlaybackProgress(req.file, req.isSystemBrowse)
                    playVideo(req.file, req.streamUrl, 0L, req.isSystemBrowse)
                    resumeRequest = null
                }
            },
            onResume = {
                playVideo(req.file, req.streamUrl, req.positionMs, req.isSystemBrowse)
                resumeRequest = null
            },
            onDismiss = {
                resumeRequest = null
            },
        )
    }
}

private suspend fun openRecentMedia(
    entry: RecentMediaEntry,
    homeViewModel: HomeViewModel,
    recentActivityStore: RecentActivityStore,
    onVideoReady: (MediaFile, String, Long) -> Unit,
    onShowResumeDialog: (ResumePlaybackRequest) -> Unit,
    onImageReady: (MediaFile, List<MediaFile>) -> Unit,
    navigateToVideoPlayer: () -> Unit,
    navigateToImagePreview: () -> Unit,
) {
    if (entry.file.mediaType == "video") {
        val streamUrl = homeViewModel.getVideoStreamUrl(entry)
        when (val action = checkPlaybackProgress(entry.file, entry.isSystemBrowse, recentActivityStore)) {
            is VideoOpenAction.PlayDirectly -> {
                onVideoReady(entry.file, streamUrl, action.positionMs)
                navigateToVideoPlayer()
            }
            is VideoOpenAction.ShowCompletedDialog -> {
                onShowResumeDialog(
                    ResumePlaybackRequest(
                        file = entry.file,
                        isSystemBrowse = entry.isSystemBrowse,
                        streamUrl = streamUrl,
                        positionMs = action.positionMs,
                        durationMs = action.durationMs,
                    )
                )
            }
        }
        return
    }

    onImageReady(entry.file, listOf(entry.file))
    navigateToImagePreview()
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

/**
 * 检查视频是否有可恢复的播放进度,并返回应执行的动作。
 * 副作用:把该视频加入"最近打开"列表。
 */
private suspend fun checkPlaybackProgress(
    file: MediaFile,
    isSystemBrowse: Boolean,
    store: RecentActivityStore,
): VideoOpenAction {
    store.addRecentMedia(file, isSystemBrowse)
    val progress = store.getPlaybackProgress(file, isSystemBrowse)
        ?: return VideoOpenAction.PlayDirectly(0L)
    return if (!isValidProgress(progress.positionMs, progress.durationMs)) {
        VideoOpenAction.PlayDirectly(0L)
    } else if (isCompleted(progress.positionMs, progress.durationMs)) {
        VideoOpenAction.ShowCompletedDialog(progress.positionMs, progress.durationMs)
    } else {
        VideoOpenAction.PlayDirectly(progress.positionMs)
    }
}
