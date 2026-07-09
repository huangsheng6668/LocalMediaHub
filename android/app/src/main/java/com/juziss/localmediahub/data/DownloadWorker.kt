package com.juziss.localmediahub.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.juziss.localmediahub.network.NetworkResult
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

class DownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DownloadWorkerEntryPoint {
        fun mediaRepository(): MediaRepository
        fun downloadsStore(): DownloadsStore
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            DownloadWorkerEntryPoint::class.java
        )
        val repository = entryPoint.mediaRepository()
        val downloadsStore = entryPoint.downloadsStore()

        val type = inputData.getString("type") ?: return@withContext Result.failure()
        val gson = Gson()

        if (type == "file") {
            val fileJson = inputData.getString("file_json") ?: return@withContext Result.failure()
            val file = gson.fromJson(fileJson, MediaFile::class.java)
            val url = inputData.getString("url") ?: return@withContext Result.failure()

            val notificationId = file.relativePath.hashCode()
            val foregroundInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(
                    notificationId,
                    createNotification("正在下载 ${file.name}", "准备中..."),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                ForegroundInfo(notificationId, createNotification("正在下载 ${file.name}", "准备中..."))
            }
            setForeground(foregroundInfo)

            try {
                showToast("开始下载 ${file.name}...")
                val downloadResult = repository.downloadFileStream(url)
                if (downloadResult !is NetworkResult.Success) {
                    val errorMsg = (downloadResult as? NetworkResult.Error)?.message ?: "未知错误"
                    showToast("下载失败: $errorMsg")
                    updateNotification(notificationId, "下载失败: ${file.name}", errorMsg)
                    return@withContext Result.failure()
                }

                val responseBody = downloadResult.data
                val destDirectory = File(applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "LocalMediaHub")
                if (!destDirectory.exists()) {
                    destDirectory.mkdirs()
                }
                val localFile = safeResolveChild(destDirectory, file.name)
                    ?: throw SecurityException("非法文件名，已拒绝下载")

                val totalBytes = responseBody.contentLength()
                var bytesWritten = 0L
                responseBody.byteStream().use { inputStream ->
                    FileOutputStream(localFile).use { outputStream ->
                        val buffer = ByteArray(128 * 1024)
                        var bytesRead: Int
                        var lastProgressPercent = -1
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            bytesWritten += bytesRead
                            if (totalBytes > 0) {
                                val progressPercent = ((bytesWritten * 100) / totalBytes).toInt()
                                if (progressPercent != lastProgressPercent) {
                                    lastProgressPercent = progressPercent
                                    updateNotification(notificationId, "正在下载 ${file.name}", "$progressPercent%")
                                }
                            } else {
                                val mbDownloaded = String.format("%.2f MB", bytesWritten / 1024.0 / 1024.0)
                                updateNotification(notificationId, "正在下载 ${file.name}", mbDownloaded)
                            }
                        }
                    }
                }

                downloadsStore.addDownload(file, localFile.absolutePath)
                showToast("${file.name} 下载成功！")
                updateNotification(notificationId, "下载成功", file.name)
                return@withContext Result.success()
            } catch (e: Exception) {
                showToast("下载失败: ${e.message}")
                updateNotification(notificationId, "下载失败: ${file.name}", e.message ?: "未知错误")
                return@withContext Result.failure()
            }
        } else if (type == "folder") {
            val folderJson = inputData.getString("folder_json") ?: return@withContext Result.failure()
            val folder = gson.fromJson(folderJson, Folder::class.java)

            val notificationId = folder.relativePath.hashCode()
            val foregroundInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(
                    notificationId,
                    createNotification("正在下载目录 ${folder.name}", "读取目录结构..."),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                ForegroundInfo(notificationId, createNotification("正在下载目录 ${folder.name}", "读取目录结构..."))
            }
            setForeground(foregroundInfo)

            try {
                showToast("正在读取目录 \"${folder.name}\" 结构...")
                val metadataResult = repository.getFolderFilesRecursive(folder.relativePath)
                if (metadataResult !is NetworkResult.Success) {
                    val errorMsg = (metadataResult as? NetworkResult.Error)?.message ?: "未知错误"
                    showToast("读取结构失败: $errorMsg")
                    updateNotification(notificationId, "下载失败: ${folder.name}", "读取结构失败: $errorMsg")
                    return@withContext Result.failure()
                }

                val filesMetadata = metadataResult.data
                if (filesMetadata.isEmpty()) {
                    showToast("该目录下没有找到可下载的媒体资源")
                    updateNotification(notificationId, "下载结束: ${folder.name}", "未找到媒体资源")
                    return@withContext Result.success()
                }

                updateNotification(notificationId, "正在下载目录 ${folder.name}", "下载 ZIP 包中...")
                val zipResult = repository.downloadFolderZip(folder.relativePath)
                if (zipResult !is NetworkResult.Success) {
                    val errorMsg = (zipResult as? NetworkResult.Error)?.message ?: "未知错误"
                    showToast("连接服务端失败: $errorMsg")
                    updateNotification(notificationId, "下载失败: ${folder.name}", "连接失败: $errorMsg")
                    return@withContext Result.failure()
                }

                val responseBody = zipResult.data
                val destDirectory = File(applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "LocalMediaHub")
                if (!destDirectory.exists()) {
                    destDirectory.mkdirs()
                }

                val incomingEntries = mutableListOf<DownloadEntry>()
                val tempFile = File(applicationContext.cacheDir, "download_temp_${System.currentTimeMillis()}.zip")
                try {
                    val totalBytes = responseBody.contentLength()
                    var bytesWritten = 0L
                    responseBody.byteStream().use { inputStream ->
                        FileOutputStream(tempFile).use { outputStream ->
                            val buffer = ByteArray(128 * 1024)
                            var bytesRead: Int
                            var lastProgressPercent = -1
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                                bytesWritten += bytesRead
                                if (totalBytes > 0) {
                                    val progressPercent = ((bytesWritten * 100) / totalBytes).toInt()
                                    if (progressPercent != lastProgressPercent) {
                                        lastProgressPercent = progressPercent
                                        updateNotification(notificationId, "正在下载目录 ${folder.name}", "正在下载 ZIP 包: $progressPercent%")
                                    }
                                } else {
                                    val mbDownloaded = String.format("%.2f MB", bytesWritten / 1024.0 / 1024.0)
                                    updateNotification(notificationId, "正在下载目录 ${folder.name}", "正在下载 ZIP 包: $mbDownloaded")
                                }
                            }
                        }
                    }

                    updateNotification(notificationId, "正在下载目录 ${folder.name}", "准备解压中...")
                    ZipFile(tempFile).use { zipFile ->
                        val entriesCount = zipFile.size()
                        val entries = zipFile.entries()
                        val buffer = ByteArray(64 * 1024)
                        var extractedCount = 0
                        while (entries.hasMoreElements()) {
                            val zipEntry = entries.nextElement()
                            if (!zipEntry.isDirectory) {
                                val extractedFile = safeResolveChild(destDirectory, zipEntry.name)
                                if (extractedFile == null) {
                                    continue
                                }
                                extractedFile.parentFile?.mkdirs()

                                zipFile.getInputStream(zipEntry).use { zipInputStream ->
                                    FileOutputStream(extractedFile).use { outputStream ->
                                        var len: Int
                                        while (zipInputStream.read(buffer).also { len = it } > 0) {
                                            outputStream.write(buffer, 0, len)
                                        }
                                    }
                                }

                                extractedCount++
                                val entryFileName = File(zipEntry.name).name
                                updateNotification(
                                    notificationId,
                                    "正在解压 ${folder.name}",
                                    "正在提取 ($extractedCount/$entriesCount): $entryFileName"
                                )

                                val matchedMetadata = filesMetadata.find {
                                    it.name.equals(entryFileName, ignoreCase = true)
                                }

                                if (matchedMetadata != null) {
                                    incomingEntries.add(
                                        DownloadEntry(
                                            file = matchedMetadata,
                                            localPath = extractedFile.absolutePath,
                                            addedAt = System.currentTimeMillis()
                                        )
                                    )
                                }
                            }
                        }
                    }
                } finally {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }

                if (incomingEntries.isNotEmpty()) {
                    downloadsStore.addDownloads(incomingEntries)
                    showToast("成功下载并提取了 ${incomingEntries.size} 个媒体文件！")
                    updateNotification(notificationId, "下载成功: ${folder.name}", "已下载 ${incomingEntries.size} 个文件")
                } else {
                    showToast("未找到有效的多媒体提取文件")
                    updateNotification(notificationId, "下载结束: ${folder.name}", "未提取到文件")
                }
                return@withContext Result.success()
            } catch (e: Exception) {
                showToast("下载目录失败: ${e.message}")
                updateNotification(notificationId, "下载失败: ${folder.name}", e.message ?: "未知错误")
                return@withContext Result.failure()
            }
        }

        Result.failure()
    }

    private fun createNotification(title: String, progress: String): Notification {
        val channelId = "downloads"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setContentText(progress)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(notificationId: Int, title: String, progress: String) {
        val channelId = "downloads"
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setContentText(progress)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(false)
            .build()
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    private fun safeResolveChild(destDir: File, name: String): File? {
        val candidate = File(destDir, name)
        return if (isInside(destDir, candidate)) candidate else null
    }

    private fun showToast(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

internal fun isInside(destDir: File, candidate: File): Boolean {
    val destCanonical = destDir.canonicalPath + File.separator
    return candidate.canonicalPath.startsWith(destCanonical)
}
