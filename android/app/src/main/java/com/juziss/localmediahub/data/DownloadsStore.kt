package com.juziss.localmediahub.data

import android.content.Context
import android.os.Environment
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

data class DownloadEntry(
    val file: MediaFile,
    val localPath: String,
    val addedAt: Long = System.currentTimeMillis()
)

class DownloadsStore(private val context: Context) {

    private val Context.dataStore by preferencesDataStore(name = "downloads")
    private val gson = Gson()

    private val downloadsKey = stringSetPreferencesKey("download_files_json")

    /** Emits all registered downloads, filtering out files that no longer exist on disk. */
    val downloadedFiles: Flow<List<DownloadEntry>> = context.dataStore.data.map { preferences ->
        val entries = preferences[downloadsKey]?.mapNotNull { json ->
            try {
                gson.fromJson(json, DownloadEntry::class.java)
            } catch (_: Exception) {
                null
            }
        } ?: emptyList()

        // Dynamically check if the local file exists.
        // This guarantees that broken/deleted files are filtered out on the fly.
        entries.filter { entry ->
            val file = File(entry.localPath)
            file.exists() && file.isFile
        }.sortedByDescending { it.addedAt }
    }

    /** Add a file to downloads list. */
    suspend fun addDownload(file: MediaFile, customPath: String? = null) {
        val path = customPath ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), file.name).absolutePath
        val entry = DownloadEntry(file, path)
        
        context.dataStore.edit { preferences ->
            val current = preferences[downloadsKey] ?: emptySet()
            val filtered = current.filterNot { json ->
                try {
                    val existing = gson.fromJson(json, DownloadEntry::class.java)
                    existing.file.relativePath == file.relativePath
                } catch (_: Exception) {
                    false
                }
            }.toSet()
            val json = gson.toJson(entry)
            preferences[downloadsKey] = filtered + json
        }
    }

    /** Remove a downloaded file from downloads list and delete the file. */
    suspend fun removeDownload(relativePath: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[downloadsKey] ?: emptySet()
            var localPathToDelete: String? = null
            val match = current.find { json ->
                try {
                    val entry = gson.fromJson(json, DownloadEntry::class.java)
                    if (entry.file.relativePath == relativePath) {
                        localPathToDelete = entry.localPath
                        true
                    } else {
                        false
                    }
                } catch (_: Exception) {
                    false
                }
            }
            if (match != null) {
                preferences[downloadsKey] = current - match
                localPathToDelete?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        file.delete()
                    }
                }
            }
        }
    }

    /** Remove multiple downloaded files from downloads list and delete the files. */
    suspend fun removeDownloads(relativePaths: List<String>) {
        context.dataStore.edit { preferences ->
            val current = preferences[downloadsKey] ?: emptySet()
            val pathsSet = relativePaths.toSet()
            val matchesToRemove = mutableSetOf<String>()
            val localPathsToDelete = mutableListOf<String>()

            for (json in current) {
                try {
                    val entry = gson.fromJson(json, DownloadEntry::class.java)
                    if (entry.file.relativePath in pathsSet) {
                        matchesToRemove.add(json)
                        localPathsToDelete.add(entry.localPath)
                    }
                } catch (_: Exception) {
                    // skip
                }
            }

            if (matchesToRemove.isNotEmpty()) {
                preferences[downloadsKey] = current - matchesToRemove
                for (path in localPathsToDelete) {
                    val file = File(path)
                    if (file.exists()) {
                        file.delete()
                    }
                }
            }
        }
    }

    /** Add multiple files to downloads list. */
    suspend fun addDownloads(entries: List<DownloadEntry>) {
        context.dataStore.edit { preferences ->
            val current = preferences[downloadsKey] ?: emptySet()
            val incomingPaths = entries.map { it.file.relativePath }.toSet()
            
            val filtered = current.filterNot { json ->
                try {
                    val existing = gson.fromJson(json, DownloadEntry::class.java)
                    existing.file.relativePath in incomingPaths
                } catch (_: Exception) {
                    false
                }
            }.toSet()
            
            val newJsons = entries.map { gson.toJson(it) }
            preferences[downloadsKey] = filtered + newJsons
        }
    }
}
