package com.juziss.localmediahub.data

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    suspend fun downloadFile(
        file: MediaFile,
        videoStreamUrl: String,
        imageUrl: String,
        onMessage: (String) -> Unit
    ) {
        val gson = Gson()
        // Text files use imageUrl (= /api/v1/media/original) because Task 8
        // extended MediaOriginal to allow TextExtensions. This is the download
        // URL for books; the TextReaderActivity consumes the local file or the
        // /api/v1/books/* endpoints for chapter rendering.
        val inputData = Data.Builder()
            .putString("type", "file")
            .putString("file_json", gson.toJson(file))
            .putString("url", if (file.mediaType == "video") videoStreamUrl else imageUrl)
            .build()

        val request = OneTimeWorkRequest.Builder(DownloadWorker::class.java)
            .setInputData(inputData)
            .addTag("download_file_${file.relativePath}")
            .build()

        // KEEP: re-tapping the same file while its download is enqueued or
        // running is a no-op (previously every tap spawned a duplicate worker
        // downloading the same bytes concurrently); once SUCCEEDED/FAILED the
        // same unique name can be enqueued again.
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            "download_file_${file.relativePath}",
            ExistingWorkPolicy.KEEP,
            request,
        )
        onMessage("已加入后台下载队列")
    }

    suspend fun downloadFolder(
        folder: Folder,
        onMessage: (String) -> Unit
    ) {
        val gson = Gson()
        val inputData = Data.Builder()
            .putString("type", "folder")
            .putString("folder_json", gson.toJson(folder))
            .build()

        val request = OneTimeWorkRequest.Builder(DownloadWorker::class.java)
            .setInputData(inputData)
            .addTag("download_folder_${folder.relativePath}")
            .build()

        WorkManager.getInstance(appContext).enqueueUniqueWork(
            "download_folder_${folder.relativePath}",
            ExistingWorkPolicy.KEEP,
            request,
        )
        onMessage("已加入后台下载队列")
    }
}
