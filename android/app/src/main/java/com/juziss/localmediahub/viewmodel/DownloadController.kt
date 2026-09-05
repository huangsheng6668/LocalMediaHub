package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.data.DownloadEntry
import com.juziss.localmediahub.data.DownloadManager
import com.juziss.localmediahub.data.DownloadsStore
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * BrowseViewModel delegate responsible for download state and operations:
 * listing downloaded files, removing downloads, and downloading individual
 * files or entire folders.
 *
 * Round 18 refactor: extracted verbatim from BrowseViewModel. The
 * `downloadedFiles` flow is a passthrough from [DownloadsStore]. Functions
 * that previously wrapped their bodies in `viewModelScope.launch` now take a
 * `scope: CoroutineScope` parameter so the caller (BrowseViewModel) supplies
 * the coroutine scope.
 *
 * `downloadFile` needs the video stream URL and original image URL for the
 * file being downloaded — these are passed as lambdas so the delegate stays
 * decoupled from the navigator's URL builders.
 *
 * Internal to the viewmodel package — NOT exposed to UI. The ViewModel
 * re-exposes the relevant flows/functions for backward compat.
 */
internal class DownloadController(
    private val downloadManager: DownloadManager,
    private val downloadsStore: DownloadsStore,
) {

    val downloadedFiles: Flow<List<DownloadEntry>> = downloadsStore.downloadedFiles

    fun removeDownload(file: MediaFile, scope: CoroutineScope) {
        scope.launch {
            downloadsStore.removeDownload(file.relativePath)
        }
    }

    fun removeDownloads(relativePaths: List<String>, scope: CoroutineScope) {
        scope.launch {
            downloadsStore.removeDownloads(relativePaths)
        }
    }

    fun downloadFile(
        file: MediaFile,
        videoStreamUrl: String,
        imageUrl: String,
        onMessage: (String) -> Unit,
        scope: CoroutineScope
    ) {
        scope.launch {
            downloadManager.downloadFile(
                file = file,
                videoStreamUrl = videoStreamUrl,
                imageUrl = imageUrl,
                onMessage = onMessage
            )
        }
    }

    fun downloadFolder(
        folder: Folder,
        onMessage: (String) -> Unit,
        scope: CoroutineScope
    ) {
        scope.launch {
            downloadManager.downloadFolder(
                folder = folder,
                onMessage = onMessage
            )
        }
    }
}
