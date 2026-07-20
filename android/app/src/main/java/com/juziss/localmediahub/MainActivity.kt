package com.juziss.localmediahub

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
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

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

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
        val intent = VideoPlayerIntentBuilder.build(
            context = context,
            file = file,
            streamUrl = url,
            initialPositionMs = positionMs,
            isSystemBrowse = isSys,
        )
        context.startActivity(intent)
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
                        onVideoReady = { file, url, positionMs, isSystemBrowse ->
                            val finalUrl = resolveStreamUrl(file, url, downloadedEntries)
                            playVideo(file, finalUrl, positionMs, isSystemBrowse)
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
                                val finalUrl = resolveStreamUrl(file, url, downloadedEntries)
                                playVideo(file, finalUrl, positionMs, entry.isSystemBrowse)
                            },
                            onShowResumeDialog = { req ->
                                val finalUrl = resolveStreamUrl(req.file, req.streamUrl, downloadedEntries)
                                resumeRequest = req.copy(streamUrl = finalUrl)
                            },
                            onImageReady = { file, images ->
                                currentImageFile = file
                                imageList = images
                                currentImageUsesSystemUrl = entry.isSystemBrowse
                                currentImageIsLocal = false
                            },
                            navigateToVideoPlayer = { },
                            navigateToImagePreview = { navController.navigate("imagePreview") },
                            onTextReady = { file ->
                                val isDownloaded = isFileDownloaded(file, downloadedEntries)
                                openTextFile(context, file, isLocal = isDownloaded)
                            },
                        )
                    }
                },
                onFavoriteClick = { file ->
                    when (file.mediaType) {
                        "video" -> {
                            appScope.launch {
                                val isSystemBrowse = homeViewModel.isFavoriteSystemBrowse(file)
                                val rawUrl = homeViewModel.getFavoriteStreamUrl(file)
                                val streamUrl = resolveStreamUrl(file, rawUrl, downloadedEntries)
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
                        }
                        "image" -> {
                            appScope.launch {
                                val isSystemBrowse = homeViewModel.isFavoriteSystemBrowse(file)
                                recentActivityStore.addRecentMedia(
                                    file = file,
                                    isSystemBrowse = isSystemBrowse,
                                )
                                val sisterImages = homeViewModel.getSisterImages(file, isSystemBrowse)
                                currentImageFile = file
                                imageList = sisterImages
                                currentImageUsesSystemUrl = isSystemBrowse
                                currentImageIsLocal = false
                                navController.navigate("imagePreview")
                            }
                        }
                        "text" -> {
                            val isDownloaded = isFileDownloaded(file, downloadedEntries)
                            openTextFile(context, file, isLocal = isDownloaded)
                        }
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
                    when (entry.file.mediaType) {
                        "video" -> {
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
                        }
                        "image" -> {
                            currentImageFile = entry.file
                            imageList = listOf(entry.file)
                            currentImageIsLocal = true
                            currentImageUsesSystemUrl = false
                            navController.navigate("imagePreview")
                        }
                        "text" -> openTextFile(context, entry.file, isLocal = true)
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
                        val rawUrl = browseViewModel.getVideoStreamUrl(file)
                        val streamUrl = resolveStreamUrl(file, rawUrl, downloadedEntries)
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
                onTextClick = { file ->
                    val isDownloaded = isFileDownloaded(file, downloadedEntries)
                    openTextFile(context, file, isLocal = isDownloaded)
                },
                onFavoriteVideoClick = { file, isSystemBrowse ->
                    appScope.launch {
                        val rawUrl = browseViewModel.getFavoriteVideoStreamUrl(file)
                        val streamUrl = resolveStreamUrl(file, rawUrl, downloadedEntries)
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
                    onImageVisible = { visibleFile ->
                        currentImageFile = visibleFile
                        if (!currentImageIsLocal) {
                            appScope.launch {
                                recentActivityStore.addRecentMedia(
                                    file = visibleFile,
                                    isSystemBrowse = currentImageUsesSystemUrl,
                                )
                            }
                        }
                    }
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
                onTextClick = { file ->
                    openTextFile(context, file, isLocal = true)
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
    onTextReady: (MediaFile) -> Unit = {},
) {
    when (entry.file.mediaType) {
        "video" -> {
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
        }
        "text" -> onTextReady(entry.file)
        else -> {
            val sisterImages = homeViewModel.getSisterImages(entry.file, entry.isSystemBrowse)
            onImageReady(entry.file, sisterImages)
            navigateToImagePreview()
        }
    }
}

private fun openPlaybackProgress(
    entry: PlaybackProgressEntry,
    homeViewModel: HomeViewModel,
    onVideoReady: (MediaFile, String, Long, Boolean) -> Unit,
) {
    onVideoReady(
        entry.file,
        homeViewModel.getVideoStreamUrl(entry),
        entry.positionMs,
        entry.isSystemBrowse,
    )
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

/**
 * Routes a mediaType="text" file to the TextReaderActivity, or shows a Toast
 * for unsupported formats (e.g. .mobi, .azw3) so the user gets feedback rather
 * than a silent no-op. `isLocal` distinguishes downloaded files from remote
 * ones (server path) — TextReaderActivity uses it to pick the data source.
 */
private fun openTextFile(
    context: android.content.Context,
    file: MediaFile,
    isLocal: Boolean,
) {
    val ext = file.extension.lowercase()
    if (ext == ".txt" || ext == ".epub") {
        context.startActivity(TextReaderActivity.newIntent(context, file.path, isLocal))
    } else {
        Toast.makeText(context, "暂不支持该格式", Toast.LENGTH_SHORT).show()
    }
}

private fun isFileDownloaded(
    file: MediaFile,
    downloadedEntries: List<com.juziss.localmediahub.data.DownloadEntry>
): Boolean {
    val localEntry = downloadedEntries.find {
        it.file.relativePath == file.relativePath || it.file.path == file.path
    }
    return localEntry != null && java.io.File(localEntry.localPath).exists()
}

private fun resolveStreamUrl(
    file: MediaFile,
    rawUrl: String,
    downloadedEntries: List<com.juziss.localmediahub.data.DownloadEntry>
): String {
    val localEntry = downloadedEntries.find {
        it.file.relativePath == file.relativePath || it.file.path == file.path
    }
    if (localEntry != null && java.io.File(localEntry.localPath).exists()) {
        return "file://${localEntry.localPath}"
    }
    return rawUrl
}

