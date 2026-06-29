package com.juziss.localmediahub.data

import android.content.Context
import android.os.Environment
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.DownloadEntry
import com.juziss.localmediahub.data.DownloadsStore
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.network.NetworkResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: MediaRepository,
    private val downloadsStore: DownloadsStore
) {
    suspend fun downloadFile(
        file: MediaFile,
        videoStreamUrl: String,
        imageUrl: String,
        onMessage: (String) -> Unit
    ) {
        try {
            onMessage("开始下载 ${file.name}...")
            val url = if (file.mediaType == "video") {
                videoStreamUrl
            } else {
                imageUrl
            }

            val downloadResult = repository.downloadFileStream(url)
            if (downloadResult !is NetworkResult.Success) {
                val errorMsg = (downloadResult as? NetworkResult.Error)?.message ?: "未知错误"
                onMessage("下载失败: $errorMsg")
                return
            }

            withContext(Dispatchers.IO) {
                val responseBody = downloadResult.data
                val destDirectory = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "LocalMediaHub")
                if (!destDirectory.exists()) {
                    destDirectory.mkdirs()
                }
                val localFile = File(destDirectory, file.name)

                responseBody.byteStream().use { inputStream ->
                    FileOutputStream(localFile).use { outputStream ->
                        val buffer = ByteArray(128 * 1024)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                        }
                    }
                }

                // Register download entry in DataStore
                downloadsStore.addDownload(file, localFile.absolutePath)
            }
            onMessage("${file.name} 下载成功！")
        } catch (e: Exception) {
            onMessage("下载失败: ${e.message}")
        }
    }

    suspend fun downloadFolder(
        folder: Folder,
        onMessage: (String) -> Unit
    ) {
        try {
            // 1. Fetch metadata first
            onMessage("正在读取目录 \"${folder.name}\" 结构...")
            val metadataResult = repository.getFolderFilesRecursive(folder.relativePath)
            if (metadataResult !is NetworkResult.Success) {
                val errorMsg = (metadataResult as? NetworkResult.Error)?.message ?: "未知错误"
                onMessage("读取结构失败: $errorMsg")
                return
            }

            val filesMetadata = metadataResult.data
            if (filesMetadata.isEmpty()) {
                onMessage("该目录下没有找到可下载的媒体资源")
                return
            }

            // 2. Request the dynamic ZIP stream
            val zipResult = repository.downloadFolderZip(folder.relativePath)
            if (zipResult !is NetworkResult.Success) {
                val errorMsg = (zipResult as? NetworkResult.Error)?.message ?: "未知错误"
                onMessage("连接服务端失败: $errorMsg")
                return
            }

            onMessage("开始流式极速下载并解压 \"${folder.name}\"...")

            withContext(Dispatchers.IO) {
                val responseBody = zipResult.data
                val destDirectory = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "LocalMediaHub")
                if (!destDirectory.exists()) {
                    destDirectory.mkdirs()
                }

                val incomingEntries = mutableListOf<DownloadEntry>()

                val tempFile = File(appContext.cacheDir, "download_temp_${System.currentTimeMillis()}.zip")
                try {
                    responseBody.byteStream().use { inputStream ->
                        FileOutputStream(tempFile).use { outputStream ->
                            val buffer = ByteArray(128 * 1024)
                            var bytesRead: Int
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                            }
                        }
                    }

                    ZipFile(tempFile).use { zipFile ->
                        val entries = zipFile.entries()
                        val buffer = ByteArray(64 * 1024)
                        while (entries.hasMoreElements()) {
                            val zipEntry = entries.nextElement()
                            if (!zipEntry.isDirectory) {
                                val extractedFile = File(destDirectory, zipEntry.name)
                                extractedFile.parentFile?.mkdirs()

                                zipFile.getInputStream(zipEntry).use { zipInputStream ->
                                    FileOutputStream(extractedFile).use { outputStream ->
                                        var len: Int
                                        while (zipInputStream.read(buffer).also { len = it } > 0) {
                                            outputStream.write(buffer, 0, len)
                                        }
                                    }
                                }

                                val entryFileName = File(zipEntry.name).name
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
                    onMessage("成功下载并流式提取了 ${incomingEntries.size} 个媒体文件！")
                } else {
                    onMessage("未找到有效的多媒体提取文件")
                }
            }
        } catch (e: Exception) {
            onMessage("下载目录失败: ${e.message}")
        }
    }
}

/**
 * Returns true only when [candidate] resolves to a path strictly inside [destDir].
 * Guards ZIP extraction against Zip Slip: entries whose canonical path escapes
 * the destination directory (e.g. `../escape.mp4` or absolute paths) return false.
 */
internal fun isInside(destDir: File, candidate: File): Boolean {
    val destCanonical = destDir.canonicalPath + File.separator
    return candidate.canonicalPath.startsWith(destCanonical)
}
