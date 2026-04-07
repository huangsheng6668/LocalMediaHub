package com.juziss.localmediahub.data

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

    // ── Folders ───────────────────────────────────────────────

    suspend fun getFolders(): NetworkResult<List<Folder>> = safeApiCall {
        api.getFolders()
    }

    suspend fun browseFolder(path: String): NetworkResult<BrowseResult> = safeApiCall {
        api.browseFolder(path)
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

    // ── URL builders ──────────────────────────────────────────

    fun getVideoStreamUrl(relativePath: String): String {
        return "${RetrofitClient.getBaseUrl()}/api/v1/videos/$relativePath/stream"
    }

    fun getThumbnailUrl(relativePath: String): String {
        return "${RetrofitClient.getBaseUrl()}/api/v1/images/$relativePath/thumbnail"
    }

    fun getOriginalImageUrl(relativePath: String): String {
        return "${RetrofitClient.getBaseUrl()}/api/v1/images/$relativePath/original"
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
