package com.juziss.localmediahub.data

import com.juziss.localmediahub.data.SearchResult
import com.juziss.localmediahub.data.Tag
import com.juziss.localmediahub.data.TagCreateRequest
import com.juziss.localmediahub.network.NetworkResult
import com.juziss.localmediahub.network.RetrofitClient
import retrofit2.HttpException
import java.io.IOException

/**
 * Repository layer: wraps API calls with error handling.
 */
class MediaRepository {

    private val api
        get() = RetrofitClient.api

    private val baseUrl
        get() = RetrofitClient.getBaseUrl()

    // ── Folders ───────────────────────────────────────────────

    suspend fun getFolders(): NetworkResult<List<Folder>> = safeApiCall {
        api.getFolders()
    }

    suspend fun browseFolder(relativePath: String): NetworkResult<BrowseResult> = safeApiCall {
        val url = "$baseUrl/api/v1/folders/$relativePath/browse"
        api.browseFolder(url)
    }

    // ── Videos ────────────────────────────────────────────────

    suspend fun getVideos(
        page: Int = 1,
        pageSize: Int = 50,
    ): NetworkResult<PaginatedMediaFiles> = safeApiCall {
        api.getVideos(page, pageSize)
    }

    // ── Images ────────────────────────────────────────────────

    suspend fun getImages(
        page: Int = 1,
        pageSize: Int = 50,
    ): NetworkResult<PaginatedMediaFiles> = safeApiCall {
        api.getImages(page, pageSize)
    }

    // ── Search ──────────────────────────────────────────────

    suspend fun search(query: String, currentPath: String = ""): NetworkResult<SearchResult> = safeApiCall {
        api.search(query, path = currentPath.ifEmpty { null })
    }

    // ── System browse ──────────────────────────────────────────

    suspend fun getSystemDrives(): NetworkResult<List<String>> = safeApiCall {
        api.getSystemDrives()
    }

    suspend fun browseSystemPath(path: String): NetworkResult<SystemBrowseResult> = safeApiCall {
        api.browseSystemPath(path)
    }

    // ── Health check ──────────────────────────────────────────

    suspend fun healthCheck(): NetworkResult<Map<String, String>> = try {
        val response = api.healthCheck()
        if (response.isSuccessful) {
            NetworkResult.Success(response.body() ?: emptyMap())
        } else {
            NetworkResult.Error("Server returned ${response.code()}", response.code())
        }
    } catch (e: Exception) {
        NetworkResult.Error(e.toUserMessage())
    }

    // ── Tags ──────────────────────────────────────────────

    suspend fun getTags(): NetworkResult<List<Tag>> = safeApiCall {
        api.getTags()
    }

    suspend fun createTag(name: String, color: String = "#808080"): NetworkResult<Tag> = safeApiCall {
        api.createTag(TagCreateRequest(name = name, color = color))
    }

    suspend fun deleteTag(tagId: String): NetworkResult<Unit> = try {
        val response = api.deleteTag("$baseUrl/api/v1/tags/$tagId")
        if (response.isSuccessful) {
            NetworkResult.Success(Unit)
        } else {
            NetworkResult.Error("Failed to delete tag: ${response.code()}", response.code())
        }
    } catch (e: Exception) {
        NetworkResult.Error(e.toUserMessage())
    }

    suspend fun tagFile(tagId: String, filePath: String): NetworkResult<Map<String, String>> = safeApiCall {
        api.tagFile("$baseUrl/api/v1/tags/$tagId/files/$filePath")
    }

    suspend fun untagFile(tagId: String, filePath: String): NetworkResult<Map<String, String>> = safeApiCall {
        api.untagFile("$baseUrl/api/v1/tags/$tagId/files/$filePath")
    }

    suspend fun getTaggedFiles(tagId: String): NetworkResult<List<String>> = safeApiCall {
        api.getTaggedFiles("$baseUrl/api/v1/tags/$tagId/files")
    }

    // ── URL builders ──────────────────────────────────────────

    fun getVideoStreamUrl(relativePath: String): String {
        return "$baseUrl/api/v1/videos/$relativePath/stream"
    }

    fun getThumbnailUrl(relativePath: String): String {
        return "$baseUrl/api/v1/images/$relativePath/thumbnail"
    }

    fun getOriginalImageUrl(relativePath: String): String {
        return "$baseUrl/api/v1/images/$relativePath/original"
    }

    // ── System URL builders (absolute paths) ──────────────────

    fun getSystemVideoStreamUrl(absolutePath: String): String {
        return "$baseUrl/api/v1/system/stream?path=${java.net.URLEncoder.encode(absolutePath, "UTF-8")}"
    }

    fun getSystemThumbnailUrl(absolutePath: String): String {
        return "$baseUrl/api/v1/system/thumbnail?path=${java.net.URLEncoder.encode(absolutePath, "UTF-8")}"
    }

    fun getSystemOriginalImageUrl(absolutePath: String): String {
        return "$baseUrl/api/v1/system/original?path=${java.net.URLEncoder.encode(absolutePath, "UTF-8")}"
    }

    // ── Helper ────────────────────────────────────────────────

    private suspend fun <T> safeApiCall(block: suspend () -> T): NetworkResult<T> = try {
        NetworkResult.Success(block())
    } catch (e: HttpException) {
        NetworkResult.Error(
            "Server error: ${e.code()} ${e.message()}",
            e.code(),
        )
    } catch (e: IOException) {
        NetworkResult.Error(
            "Network error: ${e.message ?: "Unable to connect to server"}"
        )
    } catch (e: Exception) {
        NetworkResult.Error(e.toUserMessage())
    }

    private fun Exception.toUserMessage(): String = when (this) {
        is java.net.ConnectException -> "Cannot connect to server. Check IP and port."
        is java.net.SocketTimeoutException -> "Connection timed out."
        is java.net.UnknownHostException -> "Unknown host. Check the server address."
        else -> message ?: "An unexpected error occurred."
    }
}
