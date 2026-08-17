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
         *
         * Note: the budget is `declared * 2` directly (NOT
         * `maxOf(declared * 2, 64MB)`) — the tests pin the semantics that a
         * 3x-declared overage aborts even below 64MB; the 64MB figure is the
         * *undeclared-size fallback*, not a floor raised above `declared * 2`.
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

                // Resume support: already-downloaded bytes live in <name>.part
                // and the request continues with Range: bytes=N-. A completed
                // .part is atomically renamed into place; on failure it stays
                // on disk so the WorkManager retry resumes instead of
                // restarting from byte 0.
                var offset = if (partFile.exists()) partFile.length() else 0L
                val downloadResult = repository.downloadStreamResumable(url, offset)
                if (downloadResult !is NetworkResult.Success) {
                    val errorMsg = (downloadResult as? NetworkResult.Error)?.message ?: "未知错误"
                    showToast("下载失败: $errorMsg")
                    updateNotification(notificationId, "下载失败: ${file.name}", errorMsg)
                    return@withContext Result.failure()
                }

                val dl = downloadResult.data
                if (dl.code == 416) {
                    // Range Not Satisfiable: the .part file already covers the
                    // entire content (the server rejected the resume offset).
                    if (!partFile.renameTo(localFile)) {
                        localFile.delete()
                        if (!partFile.renameTo(localFile)) {
                            throw java.io.IOException("无法完成已下载文件: ${localFile.name}")
                        }
                    }
                    downloadsStore.addDownload(file, localFile.absolutePath)
                    showToast("${file.name} 已下载完成！")
                    updateNotification(notificationId, "下载成功", file.name)
                    return@withContext Result.success()
                }
                if (dl.code == 200) {
                    // Server ignored the Range header (or offset was 0): full
                    // body — drop any partial data and restart from scratch.
                    offset = 0L
                    if (partFile.exists()) partFile.delete()
                }

                val responseBody = dl.body
                val remainingBytes = responseBody.contentLength()
                val totalBytes = offset + remainingBytes
                var bytesWritten = offset
                responseBody.byteStream().use { inputStream ->
                    FileOutputStream(partFile, offset > 0).use { outputStream ->
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

                // Atomic promotion: only a fully-downloaded file becomes
                // visible under its final name.
                if (!partFile.renameTo(localFile)) {
                    localFile.delete()
                    if (!partFile.renameTo(localFile)) {
                        throw java.io.IOException("无法重命名已下载文件: ${localFile.name}")
                    }
                }

                downloadsStore.addDownload(file, localFile.absolutePath)

                // Task 14: best-effort offline sidecar. After a .txt/.epub lands
                // on disk, also fetch /api/v1/books/info?path=<originalPath> and
                // save the JSON next to the file as <filename>.json. Failure is
                // tolerated — a missing sidecar only disables offline rendering
                // and the reader falls back to the online /api/v1/books/* flow.
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
                        android.util.Log.w(
                            "DownloadWorker",
                            "sidecar fetch failed for ${file.name}: ${e.message}"
                        )
                    }
                }

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

                        // L-7: zip-bomb guard inputs. `declared` is the
                        // compressed size we budget the extraction against:
                        // prefer the HTTP Content-Length; this server streams
                        // the zip chunked (no length), so fall back to the
                        // fully-downloaded temp zip's on-disk size — the same
                        // quantity, exact.
                        val declaredSize = responseBody.contentLength().takeIf { it > 0 }
                            ?: tempFile.length()
                        var extractedBytes = 0L
                        // Tracks every file THIS run created (including the
                        // half-written one), so an abort can scrub the
                        // half-extracted output without touching unrelated
                        // downloads already living in destDirectory.
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
                                            // Check inside the copy loop so a
                                            // single inflated entry cannot blow
                                            // past the budget unchecked.
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

                        if (budgetExceeded) {
                            // Scrub the half-extracted output, then surface as
                            // a hard failure (outer catch -> Result.failure).
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
