package com.juziss.localmediahub.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import com.juziss.localmediahub.network.NetworkResult
import com.juziss.localmediahub.network.RetrofitClient

/**
 * Repository layer: wraps API calls with error handling.
 *
 * Uses raw OkHttp + explicit Gson TypeToken parsing for ALL JSON endpoints.
 * Retrofit+Gson's generic type resolution triggers "Class cannot be cast to
 * ParameterizedType" on nested generics (List<Folder>, Map<String, List<Tag>>,
 * etc.) under R8 minification. By passing explicit TypeToken types to Gson
 * directly, the full generic signature is preserved at compile time and is
 * immune to R8 type-erasure.
 */
class MediaRepository @Inject constructor() {

    private val baseUrl
        get() = RetrofitClient.getBaseUrl()

    private val gson = Gson()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(15, 5, TimeUnit.MINUTES))
            .build()
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ════════════════════════════════════════════════════════════════════════
    //  Core: raw HTTP GET / POST / DELETE that return parsed JSON via TypeToken
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Generic GET that fetches JSON and deserializes via an explicit TypeToken.
     * The type is fully captured at compile time → immune to R8 type-erasure.
     */
    private suspend fun <T> httpGet(url: String, type: java.lang.reflect.Type): NetworkResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).get().build()
                http.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        val parsed = gson.fromJson<T>(body, type)
                        NetworkResult.Success(parsed)
                    } else {
                        NetworkResult.Error("Server returned ${resp.code}", resp.code)
                    }
                }
            } catch (e: Exception) {
                NetworkResult.Error(e.toUserMessage())
            }
        }

    /** Generic POST with a JSON body. */
    private suspend fun <T> httpPost(url: String, jsonBody: String, type: java.lang.reflect.Type): NetworkResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toRequestBody(jsonMedia))
                    .build()
                http.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        val parsed = gson.fromJson<T>(body, type)
                        NetworkResult.Success(parsed)
                    } else {
                        NetworkResult.Error("Server returned ${resp.code}", resp.code)
                    }
                }
            } catch (e: Exception) {
                NetworkResult.Error(e.toUserMessage())
            }
        }

    /** Raw POST / DELETE that only cares about success/failure (no body parse). */
    private suspend fun httpEmpty(url: String, method: String, jsonBody: String? = null): NetworkResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url(url)
                when (method) {
                    "POST" -> builder.post((jsonBody ?: "").toRequestBody(jsonMedia))
                    "DELETE" -> builder.delete((jsonBody ?: "").toRequestBody(jsonMedia))
                }
                http.newCall(builder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        NetworkResult.Success(Unit)
                    } else {
                        val errBody = resp.body?.string()
                        val msg = try {
                            JsonParser.parseString(errBody).asJsonObject?.get("error")?.asString
                        } catch (_: Exception) { null } ?: "Server returned ${resp.code}"
                        NetworkResult.Error(msg, resp.code)
                    }
                }
            } catch (e: Exception) {
                NetworkResult.Error(e.toUserMessage())
            }
        }

    /** Streaming GET (returns raw ResponseBody for video/image download). */
    private suspend fun httpStream(url: String, range: String? = null): NetworkResult<ResponseBody> =
        withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url(url).get()
                if (range != null) builder.header("Range", range)
                val resp = http.newCall(builder.build()).execute()
                if (resp.isSuccessful) {
                    resp.body?.let { NetworkResult.Success(it) }
                        ?: NetworkResult.Error("Empty response body")
                } else {
                    resp.close()
                    NetworkResult.Error("Server returned ${resp.code}", resp.code)
                }
            } catch (e: Exception) {
                NetworkResult.Error(e.toUserMessage())
            }
        }

    // ════════════════════════════════════════════════════════════════════════
    //  Public API — each endpoint uses httpGet / httpPost with explicit TypeToken
    // ════════════════════════════════════════════════════════════════════════

    // ── Folders ───────────────────────────────────────────────

    suspend fun getFolders(): NetworkResult<List<Folder>> =
        httpGet("$baseUrl/api/v1/folders", object : TypeToken<List<Folder>>() {}.type)

    suspend fun browseFolder(relativePath: String): NetworkResult<BrowseResult> =
        httpGet("$baseUrl/api/v1/folders/${normalizeRoutePath(relativePath)}/browse",
            object : TypeToken<BrowseResult>() {}.type)

    suspend fun getFolderFilesRecursive(relativePath: String): NetworkResult<List<MediaFile>> =
        httpGet("$baseUrl/api/v1/folders/${normalizeRoutePath(relativePath)}/files",
            object : TypeToken<List<MediaFile>>() {}.type)

    suspend fun downloadFolderZip(relativePath: String): NetworkResult<ResponseBody> =
        httpStream("$baseUrl/api/v1/folders/${normalizeRoutePath(relativePath)}/download")

    suspend fun downloadFileStream(url: String): NetworkResult<ResponseBody> =
        httpStream(url)

    // ── Videos / Images ───────────────────────────────────────

    suspend fun getVideos(page: Int = 1, pageSize: Int = 50): NetworkResult<PaginatedMediaFiles> =
        httpGet("$baseUrl/api/v1/videos?page=$page&page_size=$pageSize",
            object : TypeToken<PaginatedMediaFiles>() {}.type)

    suspend fun getImages(page: Int = 1, pageSize: Int = 50): NetworkResult<PaginatedMediaFiles> =
        httpGet("$baseUrl/api/v1/images?page=$page&page_size=$pageSize",
            object : TypeToken<PaginatedMediaFiles>() {}.type)

    // ── Search ────────────────────────────────────────────────

    suspend fun search(query: String, currentPath: String = ""): NetworkResult<SearchResult> {
        val q = URLEncoder.encode(query, "UTF-8")
        val p = if (currentPath.isNotEmpty()) "&path=${URLEncoder.encode(currentPath, "UTF-8")}" else ""
        return httpGet("$baseUrl/api/v1/search?q=$q&p", object : TypeToken<SearchResult>() {}.type)
    }

    // ── System browse ─────────────────────────────────────────

    suspend fun getSystemDrives(): NetworkResult<List<String>> =
        httpGet("$baseUrl/api/v1/system/drives", object : TypeToken<List<String>>() {}.type)

    suspend fun browseSystemPath(path: String): NetworkResult<SystemBrowseResult> =
        httpGet("$baseUrl/api/v1/system/browse?path=${URLEncoder.encode(path, "UTF-8")}",
            object : TypeToken<SystemBrowseResult>() {}.type)

    // ── Health check ──────────────────────────────────────────

    suspend fun healthCheck(): NetworkResult<Map<String, String>> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("$baseUrl/api/v1/health").get().build()
                http.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        NetworkResult.Success(mapOf("status" to "ok"))
                    } else {
                        NetworkResult.Error("Server returned ${resp.code}", resp.code)
                    }
                }
            } catch (e: Exception) {
                NetworkResult.Error(e.toUserMessage())
            }
        }

    // ── Tags ──────────────────────────────────────────────────

    suspend fun getTags(): NetworkResult<List<Tag>> =
        httpGet("$baseUrl/api/v1/tags", object : TypeToken<List<Tag>>() {}.type)

    suspend fun createTag(name: String, color: String = "#808080"): NetworkResult<Tag> =
        httpPost("$baseUrl/api/v1/tags",
            gson.toJson(TagCreateRequest(name, color)),
            object : TypeToken<Tag>() {}.type)

    suspend fun deleteTag(tagId: String): NetworkResult<Unit> =
        httpEmpty("$baseUrl/api/v1/tags/$tagId", "DELETE")

    suspend fun tagFile(tagId: String, filePath: String): NetworkResult<Map<String, String>> =
        httpPost("$baseUrl/api/v1/tags/$tagId/files/${normalizeRoutePath(filePath)}", "{}",
            object : TypeToken<Map<String, String>>() {}.type)

    suspend fun untagFile(tagId: String, filePath: String): NetworkResult<Map<String, String>> =
        httpEmpty("$baseUrl/api/v1/tags/$tagId/files/${normalizeRoutePath(filePath)}", "DELETE")
            .let { result ->
                if (result is NetworkResult.Success) {
                    NetworkResult.Success(mapOf("status" to "ok"))
                } else result as NetworkResult<Map<String, String>>
            }

    suspend fun getTaggedFiles(tagId: String): NetworkResult<List<String>> =
        httpGet("$baseUrl/api/v1/tags/$tagId/files", object : TypeToken<List<String>>() {}.type)

    suspend fun getTaggedMedia(tagId: String): NetworkResult<List<MediaFile>> =
        httpGet("$baseUrl/api/v1/tags/$tagId/media", object : TypeToken<List<MediaFile>>() {}.type)

    suspend fun getFileTags(paths: List<String> = emptyList()): NetworkResult<Map<String, List<Tag>>> {
        val url = if (paths.isEmpty()) "$baseUrl/api/v1/tags/file-tags"
                  else "$baseUrl/api/v1/tags/file-tags?" + paths.joinToString("&") {
                      "path=${URLEncoder.encode(it, "UTF-8")}"
                  }
        return httpGet(url, object : TypeToken<Map<String, List<Tag>>>() {}.type)
    }

    // ── Delete ────────────────────────────────────────────────

    suspend fun deletePath(path: String, recursive: Boolean): NetworkResult<String> {
        val body = gson.toJson(mapOf("path" to path, "recursive" to recursive))
        return httpEmpty("$baseUrl/api/v1/system/delete", "POST", body)
            .let { result ->
                if (result is NetworkResult.Success) {
                    NetworkResult.Success("Deleted successfully")
                } else result as NetworkResult<String>
            }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  URL builders (unchanged — used by Coil / ExoPlayer, not Retrofit)
    // ════════════════════════════════════════════════════════════════════════

    fun getVideoStreamUrl(relativePath: String): String =
        "$baseUrl/api/v1/videos/${normalizeRoutePath(relativePath)}/stream"

    fun getThumbnailUrl(relativePath: String): String =
        "$baseUrl/api/v1/images/${normalizeRoutePath(relativePath)}/thumbnail"

    fun getOriginalImageUrl(relativePath: String): String =
        "$baseUrl/api/v1/images/${normalizeRoutePath(relativePath)}/original"

    fun getMediaStreamUrl(absolutePath: String): String =
        "$baseUrl/api/v1/media/stream?path=${URLEncoder.encode(absolutePath, "UTF-8")}"

    fun getMediaThumbnailUrl(absolutePath: String): String =
        "$baseUrl/api/v1/media/thumbnail?path=${URLEncoder.encode(absolutePath, "UTF-8")}"

    fun getMediaOriginalImageUrl(absolutePath: String): String =
        "$baseUrl/api/v1/media/original?path=${URLEncoder.encode(absolutePath, "UTF-8")}"

    fun getSystemVideoStreamUrl(absolutePath: String): String =
        "$baseUrl/api/v1/system/stream?path=${URLEncoder.encode(absolutePath, "UTF-8")}"

    fun getSystemThumbnailUrl(absolutePath: String): String =
        "$baseUrl/api/v1/system/thumbnail?path=${URLEncoder.encode(absolutePath, "UTF-8")}"

    fun getSystemOriginalImageUrl(absolutePath: String): String =
        "$baseUrl/api/v1/system/original?path=${URLEncoder.encode(absolutePath, "UTF-8")}"

    // ════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════════════

    private fun normalizeRoutePath(path: String): String {
        return path.replace("\\", "/").trim('/')
    }

    private fun Exception.toUserMessage(): String = when (this) {
        is java.net.ConnectException -> "Cannot connect to server. Check IP and port."
        is java.net.SocketTimeoutException -> "Connection timed out."
        is java.net.UnknownHostException -> "Unknown host. Check the server address."
        else -> message ?: "An unexpected error occurred."
    }
}
