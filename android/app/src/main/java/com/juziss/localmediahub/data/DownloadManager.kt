package com.juziss.localmediahub.data

import android.content.Context
import androidx.work.Data
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
        val inputData = Data.Builder()
            .putString("type", "file")
            .putString("file_json", gson.toJson(file))
            .putString("url", if (file.mediaType == "video") videoStreamUrl else imageUrl)
            .build()

        val request = OneTimeWorkRequest.Builder(DownloadWorker::class.java)
            .setInputData(inputData)
            .addTag("download_file_${file.relativePath}")
            .build()

        WorkManager.getInstance(appContext).enqueue(request)
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

        WorkManager.getInstance(appContext).enqueue(request)
        onMessage("已加入后台下载队列")
    }
}
