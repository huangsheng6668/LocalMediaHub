package com.juziss.localmediahub.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import com.juziss.localmediahub.ble.BleConnState
import com.juziss.localmediahub.ble.BleController
import com.juziss.localmediahub.ble.BleTransportFallback
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import javax.inject.Inject
import com.juziss.localmediahub.network.NetworkResult
import com.juziss.localmediahub.network.ServerConfig

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
class MediaRepository @Inject constructor(
    private val http: OkHttpClient,
    private val serverConfig: ServerConfig,
    private val bleController: BleController,
    private val bleTransportFallback: BleTransportFallback,
) {

    private val baseUrl
        get() = serverConfig.getBaseUrl()

    private val gson = Gson()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ════════════════════════════════════════════════════════════════════════
    //  Task 3: BLE degradation signal. Flips true whenever the most recent
    //  chapter fetch was served via the BLE fallback path (HTTP failed +
    //  BLE CONNECTED). Reset to false on every successful HTTP fetch so the
    //  reader's 3-second "BLE 降级传输中" chip only surfaces while BLE is
    //  actually carrying traffic. The UI owns the auto-dismiss timer.
    // ════════════════════════════════════════════════════════════════════════
    private val _isBleDegraded = MutableStateFlow(false)
    val isBleDegraded: StateFlow<Boolean> = _isBleDegraded.asStateFlow()

    // ════════════════════════════════════════════════════════════════════════
    //  I2 fix: one-shot BLE-degradation event stream. The boolean above is
    //  sticky (stays true across consecutive BLE-served chapters), so a
    //  LaunchedEffect(isBleDegraded) only re-fires on the value CHANGE —
    //  during a prolonged outage the badge surfaced once then never again.
    //  This SharedFlow emits once PER BLE-served chapter so the reader can
    //  re-show + re-arm the 3-second auto-dismiss timer on every delivery
    //  (spec §1.2 step 4 implies per-delivery feedback).
    //
    //  replay = 0 so a consumer attaching mid-stream does NOT replay a stale
    //  emission; extraBufferCapacity = 1 so a slow consumer does not drop the
    //  emission on suspend (the reader collects on the main scope, which is
    //  never suspended long enough to matter in practice).
    // ════════════════════════════════════════════════════════════════════════
    private val _bleDegradedEvents = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val bleDegradedEvents: SharedFlow<Unit> = _bleDegradedEvents.asSharedFlow()

    // ════════════════════════════════════════════════════════════════════════
    //  Core: raw HTTP GET / POST / DELETE that return parsed JSON via TypeToken
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Generic GET that fetches JSON and deserializes via an explicit TypeToken.
     * The type is fully captured at compile time → immune to R8 type-erasure.
     *
     * forceNetwork: when true, sends `Cache-Control: no-cache` so OkHttp skips
     * its disk cache and revalidates with the server. Used after mutations
     * (delete) so refreshCurrentDirectory reflects the just-deleted entry
     * instead of a stale max-age=5 cached response.
     */
    private suspend fun <T> httpGet(
        url: String,
        type: java.lang.reflect.Type,
        forceNetwork: Boolean = false,
    ): NetworkResult<T> =
        try {
            val result = httpGetRaw<T>(url, type, forceNetwork = forceNetwork)
            NetworkResult.Success(result)
        } catch (e: Exception) {
            NetworkResult.Error(e.toUserMessage())
        }

    /**
     * Same as [httpGet] but throws on transport / server errors so callers
     * that need to discriminate between "any HTTP failure" and "success"
     * (e.g. [getBookChapter]'s BLE failover path) can catch a typed
     * [IOException]. Non-IO failures throw [HttpStatusException]. Both
     * inherit from [Exception]; the failover gate catches [IOException] only.
     */
    private suspend fun <T> httpGetRaw(
        url: String,
        type: java.lang.reflect.Type,
        forceNetwork: Boolean = false,
    ): T = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).get()
        if (forceNetwork) builder.header("Cache-Control", "no-cache")
        val request = builder.build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw HttpStatusException(resp.code)
            }
            val body = resp.body?.string()
            gson.fromJson<T>(body, type)
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

    suspend fun browseFolder(
        relativePath: String,
        forceNetwork: Boolean = false,
    ): NetworkResult<BrowseResult> =
        httpGet("$baseUrl/api/v1/folders/${normalizeRoutePath(relativePath)}/browse",
            object : TypeToken<BrowseResult>() {}.type,
            forceNetwork = forceNetwork)

    suspend fun getFolderFilesRecursive(relativePath: String): NetworkResult<List<MediaFile>> =
        httpGet("$baseUrl/api/v1/folders/${normalizeRoutePath(relativePath)}/files",
            object : TypeToken<List<MediaFile>>() {}.type)

    suspend fun downloadFolderZip(relativePath: String): NetworkResult<ResponseBody> =
        httpStream("$baseUrl/api/v1/folders/${normalizeRoutePath(relativePath)}/download")

    suspend fun downloadFileStream(url: String): NetworkResult<ResponseBody> =
        httpStream(url)

    // ── Search ────────────────────────────────────────────────

    suspend fun search(query: String, currentPath: String = ""): NetworkResult<SearchResult> {
        val q = URLEncoder.encode(query, "UTF-8")
        val p = if (currentPath.isNotEmpty()) "&path=${URLEncoder.encode(currentPath, "UTF-8")}" else ""
        return httpGet("$baseUrl/api/v1/search?q=$q&p", object : TypeToken<SearchResult>() {}.type)
    }

    // ── System browse ─────────────────────────────────────────

    suspend fun getSystemDrives(): NetworkResult<List<String>> =
        httpGet("$baseUrl/api/v1/system/drives", object : TypeToken<List<String>>() {}.type)

    suspend fun browseSystemPath(
        path: String,
        forceNetwork: Boolean = false,
    ): NetworkResult<SystemBrowseResult> =
        httpGet("$baseUrl/api/v1/system/browse?path=${URLEncoder.encode(path, "UTF-8")}",
            object : TypeToken<SystemBrowseResult>() {}.type,
            forceNetwork = forceNetwork)

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

    // ── Books (text-reader) ───────────────────────────────────

    suspend fun getBookInfo(path: String): NetworkResult<Book> =
        httpGet(
            "$baseUrl/api/v1/books/info?path=${URLEncoder.encode(path, "UTF-8")}",
            Book::class.java,
        )

    /**
     * Fetches a single chapter, transparently failing over to the BLE
     * transport when the HTTP path is unreachable.
     *
     * Spec §3.2 / §1.2 step 4: when [httpGetRaw] throws an [IOException]
     * (connect refused, socket timeout, broken pipe, …) AND the BLE link is
     * [BleConnState.CONNECTED], the repository dispatches a
     * CMD_BOOK_CHAPTER_REQ over BLE, reassembles the streamed chunks via
     * [BleTransportFallback], maps them to [BookChapterContent], and raises
     * [isBleDegraded] so the reader can surface the 3-second badge.
     *
     * All other HTTP failures (server 4xx/5xx via [HttpStatusException], or
     * BLE not connected) bypass the failover and return the original error.
     *
     * The caller MUST observe [isBleDegraded]; the flag is reset to false on
     * the next HTTP-served chapter so the badge only surfaces during actual
     * BLE traffic.
     */
    suspend fun getBookChapter(path: String, index: Int): NetworkResult<BookChapterContent> {
        val url = "$baseUrl/api/v1/books/chapter" +
            "?path=${URLEncoder.encode(path, "UTF-8")}&index=$index"
        return try {
            val content = httpGetRaw<BookChapterContent>(url, BookChapterContent::class.java)
            _isBleDegraded.value = false
            NetworkResult.Success(content)
        } catch (e: IOException) {
            // Transport-level failure: candidate for BLE failover.
            tryBleFailover(path, index, e)
        } catch (e: HttpStatusException) {
            // Server responded with a non-2xx status — NOT a transport outage,
            // so failover does not apply. Surface the original error.
            NetworkResult.Error("Server returned ${e.code}", e.code)
        }
    }

    /**
     * BLE failover branch of [getBookChapter]. Returns the BLE-sourced content
     * when [BleController] reports a CONNECTED link and the fallback engine
     * successfully reassembles a block list; otherwise returns the original
     * HTTP error so the reader surfaces the underlying network failure.
     *
     * Honors the Task 2 implementer's note: [BleTransportFallback] resets its
     * `lastFrameAtMs` (seeded at singleton construction) on each cycle — that
     * reset is now driven internally by `fetchChapterBlocks` so this method
     * simply awaits the reassembled blocks.
     */
    private suspend fun tryBleFailover(
        path: String,
        index: Int,
        httpError: IOException,
    ): NetworkResult<BookChapterContent> {
        if (bleController.connectionState.value != BleConnState.CONNECTED) {
            return NetworkResult.Error(httpError.toUserMessage())
        }
        // Suspend-bridge the BLE chunk cycle: reset + dispatch CMD_BOOK_CHAPTER_REQ
        // + AWAIT chunk arrival on the GATT callback thread, bounded by the
        // engine's per-frame timeout × retry budget. Returns null on timeout
        // (preserve spec §3.2: surface the original HTTP error on BLE
        // exhaustion rather than an empty chapter). `path`/`index` are passed
        // for logging/diagnostics and bound into the dispatch lambda.
        val blocks = bleTransportFallback.fetchChapterBlocks(path, index) {
            bleController.requestChapter(path, index)
        }
        if (blocks == null) {
            // BLE link was CONNECTED but no payload arrived / reassembly failed
            // within the timeout budget. Surface the original HTTP error and
            // DO NOT raise isBleDegraded (no traffic was actually carried).
            return NetworkResult.Error(httpError.toUserMessage())
        }
        _isBleDegraded.value = true
        // I2: emit a one-shot degradation event so the reader re-shows the
        // 3-second badge on EVERY BLE-served chapter, not just the first one
        // after the value flips true. tryEmit is safe here: extraBufferCapacity
        // = 1 guarantees no blocking; a slow consumer simply drops an emission
        // it has not yet collected (acceptable — the next chapter re-emits).
        _bleDegradedEvents.tryEmit(Unit)
        // BookChapterContent.title is unknown over the BLE chunk stream (the
        // wire format carries only the Block list, per spec §3.2). The reader
        // already has the title from getBookInfo, so empty is acceptable here.
        //
        // KNOWN LIMITATION (M3 / out-of-scope I3): the BLE chunk wire format
        // does not currently carry the chapter title, so it is left blank on
        // the BLE path. A future wire-format extension (e.g. a TitleLen +
        // Title UTF-8 field appended to the first CMD_BOOK_CHAPTER_CHUNK, or a
        // dedicated CMD_BOOK_INFO chunk) is needed to populate it. Until then
        // the reader renders the chapter with the title it already holds from
        // getBookInfo.
        return NetworkResult.Success(BookChapterContent(title = "", blocks = blocks))
    }

    /**
     * Streaming variant of [getBookInfo]: returns the raw JSON [ResponseBody]
     * so the caller (DownloadWorker) can write the bytes verbatim to the
     * `<filename>.json` sidecar next to the downloaded book file, preserving
     * the exact server response without a serialize/deserialize round-trip.
     *
     * Task 14: offline sidecar prep. Failures are tolerated by the caller —
     * a missing sidecar only means offline rendering falls back to online.
     */
    suspend fun downloadBookInfoSidecar(path: String): NetworkResult<ResponseBody> =
        httpStream("$baseUrl/api/v1/books/info?path=${URLEncoder.encode(path, "UTF-8")}")

    // ════════════════════════════════════════════════════════════════════════
    //  URL builders (unchanged — used by Coil / ExoPlayer, not Retrofit)
    // ════════════════════════════════════════════════════════════════════════

    fun getMediaStreamUrl(absolutePath: String): String =
        "$baseUrl/api/v1/media/stream?path=${URLEncoder.encode(absolutePath, "UTF-8")}"

    fun getMediaThumbnailUrl(absolutePath: String): String =
        "$baseUrl/api/v1/media/thumbnail?path=${URLEncoder.encode(absolutePath, "UTF-8")}"

    fun getMediaOriginalImageUrl(absolutePath: String): String =
        "$baseUrl/api/v1/media/original?path=${URLEncoder.encode(absolutePath, "UTF-8")}"

    // ════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════════════

    private fun normalizeRoutePath(path: String): String {
        return path.replace("\\", "/").trim('/')
    }

    private fun Exception.toUserMessage(): String = when (this) {
        is java.net.ConnectException -> "Cannot connect to server. Check IP and port."
        is SocketTimeoutException -> "Connection timed out."
        is java.net.UnknownHostException -> "Unknown host. Check the server address."
        is HttpStatusException -> "Server returned ${this.code}"
        else -> message ?: "An unexpected error occurred."
    }
}

/**
 * Internal signal from [MediaRepository.httpGetRaw] that the server responded
 * with a non-2xx HTTP status. Carries no message of its own — the repository
 * formats the user-facing string via [MediaRepository.toUserMessage]. This is
 * NOT an [IOException]; the BLE failover gate must not fire on it because the
 * transport is fine and re-sending the request would only repeat the failure.
 */
private class HttpStatusException(val code: Int) : Exception("HTTP $code")
