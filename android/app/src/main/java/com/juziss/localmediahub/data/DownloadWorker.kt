package com.juziss.localmediahub.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.juziss.localmediahub.network.NetworkResult
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Rate-limits progress notifications so chunked downloads (`totalBytes <= 0`)
 * and fast multi-file ZIP extractions cannot flood `NotificationManager` with
 * hundreds of Binder IPC calls per second.
 */
internal class NotificationThrottle(
    private val minIntervalMs: Long = 500L,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private var lastUpdateMs: Long = Long.MIN_VALUE / 2
    private var lastPercent: Int = -2

    fun shouldNotify(percent: Int = -1): Boolean {
        val now = clock()
        if (percent == 100 && lastPercent != 100) {
            lastPercent = 100
            lastUpdateMs = now
            return true
        }
        if (percent >= 0 && percent == lastPercent) {
            return false
        }
        if (now - lastUpdateMs < minIntervalMs) {
            return false
        }
        lastPercent = percent
        lastUpdateMs = now
        return true
    }
}

/**
 * $O(1)$ lookup table matching ZIP entry relative paths to [MediaFile] metadata,
 * disambiguating duplicate basenames in nested subfolders (e.g. `Ch1/001.jpg` vs `Ch2/001.jpg`).
 */
internal class MediaMetadataIndex(
    files: List<MediaFile>,
    folderRelativePath: String = "",
) {
    private val byRelPath = HashMap<String, MediaFile>(files.size * 2)
    private val byName = HashMap<String, MediaFile>(files.size)

    init {
        val folderPrefix = normalizePath(folderRelativePath)
        for (file in files) {
            val rel = normalizePath(file.relativePath)
            if (rel.isNotEmpty()) {
                byRelPath.putIfAbsent(rel, file)
                if (folderPrefix.isNotEmpty() && rel.startsWith("$folderPrefix/")) {
                    val stripped = rel.removePrefix("$folderPrefix/")
                    if (stripped.isNotEmpty()) {
                        byRelPath.putIfAbsent(stripped, file)
                    }
                }
            }
            val baseName = file.name.lowercase()
            if (baseName.isNotEmpty()) {
                byName.putIfAbsent(baseName, file)
            }
        }
    }

    fun match(zipEntryName: String): MediaFile? {
        val normEntry = normalizePath(zipEntryName)
        if (normEntry.isEmpty()) return null
        byRelPath[normEntry]?.let { return it }
        val baseName = normEntry.substringAfterLast('/')
        return byName[baseName]
    }

    private fun normalizePath(raw: String): String =
        raw.replace('\\', '/').trim('/').lowercase()
}

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

    companion object {
        /** L-7: absolute cap on total uncompressed bytes a single folder zip may produce (4GB). */
        const val MAX_UNCOMPRESSED_BYTES = 4L * 1024 * 1024 * 1024

        /**
         * Task 12 (L-7): zip-bomb budget predicate (pure, unit-tested).
         *
         * Aborts once the cumulative extracted size exceeds 2x the declared
         * (compressed) size; when nothing was declared (`declared == 0`, e.g.
         * a chunked response) a flat 64MB allowance applies instead. The
         * absolute [MAX_UNCOMPRESSED_BYTES] cap applies regardless.
         */
        fun shouldAbortUnzip(extracted: Long, declared: Long): Boolean {
            if (extracted > MAX_UNCOMPRESSED_BYTES) return true
            val budget = if (declared > 0) declared * 2 else 64L * 1024 * 1024
            return extracted > budget
        }
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

            val notificationId = file.relativePath.hashCode().let { if (it == 0) 1 else it }
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
                val destDirectory = File(applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "LocalMediaHub")
                if (!destDirectory.exists()) {
                    destDirectory.mkdirs()
                }
                val localFile = safeResolveChild(destDirectory, file.name)
                    ?: throw SecurityException("非法文件名，已拒绝下载")
                val partFile = File(localFile.parentFile, localFile.name + ".part")

                var offset = if (partFile.exists()) partFile.length() else 0L
                val downloadResult = repository.downloadStreamResumable(url, offset)
                if (downloadResult !is NetworkResult.Success) {
                    val errorMsg = (downloadResult as? NetworkResult.Error)?.message ?: "未知错误"
                    showToast("下载失败: $errorMsg")
                    updateNotification(notificationId, "下载失败: ${file.name}", errorMsg, ongoing = false)
                    return@withContext Result.failure()
                }

                val dl = downloadResult.data
                if (dl.code == 416) {
                    // Range Not Satisfiable: close response body first to avoid connection leak.
                    dl.body.close()
                    if (!partFile.renameTo(localFile)) {
                        localFile.delete()
                        if (!partFile.renameTo(localFile)) {
                            throw java.io.IOException("无法完成已下载文件: ${localFile.name}")
                        }
                    }
                    downloadsStore.addDownload(file, localFile.absolutePath)
                    showToast("${file.name} 已下载完成！")
                    updateNotification(notificationId, "下载成功", file.name, ongoing = false)
                    return@withContext Result.success()
                }
                if (dl.code == 200) {
                    offset = 0L
                    if (partFile.exists()) partFile.delete()
                }

                val responseBody = dl.body
                val remainingBytes = responseBody.contentLength()
                val totalBytes = if (remainingBytes > 0) offset + remainingBytes else -1L
                var bytesWritten = offset
                val throttle = NotificationThrottle()
                responseBody.byteStream().use { inputStream ->
                    FileOutputStream(partFile, offset > 0).use { outputStream ->
                        val buffer = ByteArray(128 * 1024)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            bytesWritten += bytesRead
                            if (totalBytes > 0) {
                                val progressPercent = ((bytesWritten * 100) / totalBytes).toInt().coerceIn(0, 100)
                                if (throttle.shouldNotify(progressPercent)) {
                                    updateNotification(notificationId, "正在下载 ${file.name}", "$progressPercent%", ongoing = true)
                                }
                            } else if (throttle.shouldNotify()) {
                                val mbDownloaded = String.format("%.2f MB", bytesWritten / 1024.0 / 1024.0)
                                updateNotification(notificationId, "正在下载 ${file.name}", mbDownloaded, ongoing = true)
                            }
                        }
                    }
                }

                if (!partFile.renameTo(localFile)) {
                    localFile.delete()
                    if (!partFile.renameTo(localFile)) {
                        throw java.io.IOException("无法重命名已下载文件: ${localFile.name}")
                    }
                }

                downloadsStore.addDownload(file, localFile.absolutePath)

                val ext = localFile.extension.lowercase()
                if (ext == "txt" || ext == "epub") {
                    try {
                        val sidecarResult = repository.downloadBookInfoSidecar(file.path)
                        if (sidecarResult is NetworkResult.Success) {
                            sidecarResult.data.use { body ->
                                val sidecar = File(localFile.parentFile, "${localFile.name}.json")
                                body.byteStream().use { input ->
                                    FileOutputStream(sidecar).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        android.util.Log.w(
                            "DownloadWorker",
                            "sidecar fetch failed for ${file.name}: ${e.message}"
                        )
                    }
                }

                showToast("${file.name} 下载成功！")
                updateNotification(notificationId, "下载成功", file.name, ongoing = false)
                return@withContext Result.success()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                showToast("下载失败: ${e.message}")
                updateNotification(notificationId, "下载失败: ${file.name}", e.message ?: "未知错误", ongoing = false)
                return@withContext Result.failure()
            }
        } else if (type == "folder") {
            val folderJson = inputData.getString("folder_json") ?: return@withContext Result.failure()
            val folder = gson.fromJson(folderJson, Folder::class.java)

            val notificationId = folder.relativePath.hashCode().let { if (it == 0) 1 else it }
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
                    updateNotification(notificationId, "下载失败: ${folder.name}", "读取结构失败: $errorMsg", ongoing = false)
                    return@withContext Result.failure()
                }

                val filesMetadata = metadataResult.data
                if (filesMetadata.isEmpty()) {
                    showToast("该目录下没有找到可下载的媒体资源")
                    updateNotification(notificationId, "下载结束: ${folder.name}", "未找到媒体资源", ongoing = false)
                    return@withContext Result.success()
                }

                updateNotification(notificationId, "正在下载目录 ${folder.name}", "下载 ZIP 包中...", ongoing = true)
                val zipResult = repository.downloadFolderZip(folder.relativePath)
                if (zipResult !is NetworkResult.Success) {
                    val errorMsg = (zipResult as? NetworkResult.Error)?.message ?: "未知错误"
                    showToast("连接服务端失败: $errorMsg")
                    updateNotification(notificationId, "下载失败: ${folder.name}", "连接失败: $errorMsg", ongoing = false)
                    return@withContext Result.failure()
                }

                val responseBody = zipResult.data
                val destDirectory = File(applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "LocalMediaHub")
                if (!destDirectory.exists()) {
                    destDirectory.mkdirs()
                }

                val metadataIndex = MediaMetadataIndex(filesMetadata, folder.relativePath)
                val incomingEntries = mutableListOf<DownloadEntry>()
                val tempFile = File(applicationContext.cacheDir, "download_temp_${System.currentTimeMillis()}.zip")
                try {
                    val totalBytes = responseBody.contentLength()
                    var bytesWritten = 0L
                    val downloadThrottle = NotificationThrottle()
                    responseBody.byteStream().use { inputStream ->
                        FileOutputStream(tempFile).use { outputStream ->
                            val buffer = ByteArray(128 * 1024)
                            var bytesRead: Int
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                                bytesWritten += bytesRead
                                if (totalBytes > 0) {
                                    val progressPercent = ((bytesWritten * 100) / totalBytes).toInt().coerceIn(0, 100)
                                    if (downloadThrottle.shouldNotify(progressPercent)) {
                                        updateNotification(notificationId, "正在下载目录 ${folder.name}", "正在下载 ZIP 包: $progressPercent%", ongoing = true)
                                    }
                                } else if (downloadThrottle.shouldNotify()) {
                                    val mbDownloaded = String.format("%.2f MB", bytesWritten / 1024.0 / 1024.0)
                                    updateNotification(notificationId, "正在下载目录 ${folder.name}", "正在下载 ZIP 包: $mbDownloaded", ongoing = true)
                                }
                            }
                        }
                    }

                    updateNotification(notificationId, "正在下载目录 ${folder.name}", "准备解压中...", ongoing = true)
                    val unzipThrottle = NotificationThrottle()
                    ZipFile(tempFile).use { zipFile ->
                        val entriesCount = zipFile.size()
                        val entries = zipFile.entries()
                        val buffer = ByteArray(64 * 1024)
                        var extractedCount = 0

                        val declaredSize = responseBody.contentLength().takeIf { it > 0 }
                            ?: tempFile.length()
                        var extractedBytes = 0L
                        val extractedThisRun = mutableListOf<File>()
                        var budgetExceeded = false

                        while (entries.hasMoreElements() && !budgetExceeded) {
                            val zipEntry = entries.nextElement()
                            if (!zipEntry.isDirectory) {
                                val extractedFile = safeResolveChild(destDirectory, zipEntry.name)
                                if (extractedFile == null) {
                                    continue
                                }
                                extractedFile.parentFile?.mkdirs()
                                extractedThisRun.add(extractedFile)

                                zipFile.getInputStream(zipEntry).use { zipInputStream ->
                                    FileOutputStream(extractedFile).use { outputStream ->
                                        var len: Int
                                        while (zipInputStream.read(buffer).also { len = it } > 0) {
                                            outputStream.write(buffer, 0, len)
                                            extractedBytes += len
                                            if (shouldAbortUnzip(extractedBytes, declaredSize)) {
                                                budgetExceeded = true
                                                break
                                            }
                                        }
                                    }
                                }
                                if (budgetExceeded) break

                                extractedCount++
                                val entryFileName = File(zipEntry.name).name
                                if (unzipThrottle.shouldNotify()) {
                                    updateNotification(
                                        notificationId,
                                        "正在解压 ${folder.name}",
                                        "正在提取 ($extractedCount/$entriesCount): $entryFileName",
                                        ongoing = true,
                                    )
                                }

                                val matchedMetadata = metadataIndex.match(zipEntry.name)
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

                        if (budgetExceeded) {
                            extractedThisRun.forEach { it.deleteRecursively() }
                            throw SecurityException("unzip budget exceeded")
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
                    updateNotification(notificationId, "下载成功: ${folder.name}", "已下载 ${incomingEntries.size} 个文件", ongoing = false)
                } else {
                    showToast("未找到有效的多媒体提取文件")
                    updateNotification(notificationId, "下载结束: ${folder.name}", "未提取到文件", ongoing = false)
                }
                return@withContext Result.success()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                showToast("下载目录失败: ${e.message}")
                updateNotification(notificationId, "下载失败: ${folder.name}", e.message ?: "未知错误", ongoing = false)
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

    private fun updateNotification(
        notificationId: Int,
        title: String,
        progress: String,
        ongoing: Boolean = false,
    ) {
        val channelId = "downloads"
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setContentText(progress)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(ongoing)
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
