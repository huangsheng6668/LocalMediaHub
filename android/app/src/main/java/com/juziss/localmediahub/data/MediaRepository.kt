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
import com.juziss.localmediahub.R
import com.juziss.localmediahub.ble.BleConnState
import com.juziss.localmediahub.ble.BleController
import com.juziss.localmediahub.ble.BleDegradedState
import com.juziss.localmediahub.ble.BleProtocol
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
    //  Task 3 / Task 4: BLE degradation signal. Flips true whenever the most
    //  recent failover-capable fetch (folders / browseFolder / bookInfo /
    //  bookChapter) was served via the BLE fallback path (HTTP failed +
    //  BLE CONNECTED). Reset to false on every successful HTTP fetch so the
    //  reader's 3-second "BLE 降级传输中" chip only surfaces while BLE is
    //  actually carrying traffic. The UI owns the auto-dismiss timer.
    // ════════════════════════════════════════════════════════════════════════
    private val _isBleDegraded = MutableStateFlow(false)
    val isBleDegraded: StateFlow<Boolean> = _isBleDegraded.asStateFlow()

    // ════════════════════════════════════════════════════════════════════════
    //  I2 fix: one-shot BLE-degradation event stream. The boolean above is
    //  sticky (stays true across consecutive BLE-served requests), so a
    //  LaunchedEffect(isBleDegraded) only re-fires on the value CHANGE —
    //  during a prolonged outage the badge surfaced once then never again.
    //  This SharedFlow emits once PER BLE-served request so the reader can
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

    /**
     * Task 5: single funnel for flipping [isBleDegraded]. Mirrors the new
     * value into the process-wide [BleDegradedState] holder so the Coil
     * image-loader (built before Hilt finishes, no MediaRepository access)
     * can short-circuit image requests to a placeholder while BLE is
     * carrying traffic — see `LocalMediaHubApplication.newImageLoader`.
     *
     * Centralizing the write here keeps every flip in sync with the global
     * holder; callers MUST use this instead of `_isBleDegraded.value = ...`
     * so the mirror never drifts.
     */
    private fun setBleDegraded(value: Boolean) {
        _isBleDegraded.value = value
        BleDegradedState.setBleDegraded(value)
    }

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
            e.toNetworkError()
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
                e.toNetworkError()
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
                e.toNetworkError()
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
                e.toNetworkError()
            }
        }

    /** Response body plus the HTTP status so resumable downloads can
     *  distinguish 200 (full body) from 206 (partial, resume at offset) and
     *  416 (Range Not Satisfiable — the .part file already holds the whole
     *  content). */
    class DownloadResponse(val body: ResponseBody, val code: Int)

    /** Streaming GET that resumes at [offset] via a Range header. Accepts
     *  200/206/416; any other status surfaces as Error. */
    suspend fun downloadStreamResumable(url: String, offset: Long): NetworkResult<DownloadResponse> =
        withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url(url).get()
                if (offset > 0) builder.header("Range", "bytes=$offset-")
                val resp = http.newCall(builder.build()).execute()
                if (resp.code == 200 || resp.code == 206 || resp.code == 416) {
                    resp.body?.let { NetworkResult.Success(DownloadResponse(it, resp.code)) }
                        ?: NetworkResult.Error("Empty response body").also { resp.close() }
                } else {
                    resp.close()
                    NetworkResult.Error("Server returned ${resp.code}", resp.code)
                }
            } catch (e: Exception) {
                e.toNetworkError()
            }
        }

    // ════════════════════════════════════════════════════════════════════════
    //  Public API — each endpoint uses httpGet / httpPost with explicit TypeToken
    // ════════════════════════════════════════════════════════════════════════

    // ── Folders ───────────────────────────────────────────────

    suspend fun getFolders(): NetworkResult<List<Folder>> {
        val foldersType = object : TypeToken<List<Folder>>() {}.type
        return bleFetchOrHttp<List<Folder>>(
            httpCall = {
                val data = httpGetRaw<List<Folder>>("$baseUrl/api/v1/folders", foldersType)
                setBleDegraded(false)
                data
            },
            endpoint = BleProtocol.ENDPOINT_FOLDERS,
            path = "",
            index = 0,
            type = foldersType,
        )
    }

    suspend fun browseFolder(
        relativePath: String,
        forceNetwork: Boolean = false,
        sort: String? = null,
        order: String? = null,
        page: Int = 0,
        pageSize: Int = 0,
    ): NetworkResult<BrowseResult> {
        val route = normalizeRoutePath(relativePath)
        val browseType = object : TypeToken<BrowseResult>() {}.type
        // Server-side sort + pagination (see /api/v1/folders/*/browse): pages
        // are ordered deterministically on the server so the client can append
        // consecutive pages without re-sorting (client-side sorting would
        // scramble the page composition). No params = legacy full return.
        val query = buildList {
            if (!sort.isNullOrEmpty()) add("sort=${URLEncoder.encode(sort, "UTF-8")}")
            if (!order.isNullOrEmpty()) add("order=${URLEncoder.encode(order, "UTF-8")}")
            if (page > 0) add("page=$page")
            if (pageSize > 0) add("page_size=$pageSize")
        }.joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
        return bleFetchOrHttp<BrowseResult>(
            httpCall = {
                val data = httpGetRaw<BrowseResult>(
                    "$baseUrl/api/v1/folders/${encodePathSegments(route)}/browse$query",
                    browseType,
                    forceNetwork = forceNetwork,
                )
                setBleDegraded(false)
                data
            },
            endpoint = BleProtocol.ENDPOINT_BROWSE_FOLDER,
            path = route,
            index = 0,
            type = browseType,
        )
    }

    suspend fun getFolderFilesRecursive(relativePath: String): NetworkResult<List<MediaFile>> =
        httpGet("$baseUrl/api/v1/folders/${encodePathSegments(normalizeRoutePath(relativePath))}/files",
            object : TypeToken<List<MediaFile>>() {}.type)

    suspend fun downloadFolderZip(relativePath: String): NetworkResult<ResponseBody> =
        httpStream("$baseUrl/api/v1/folders/${encodePathSegments(normalizeRoutePath(relativePath))}/download")

    // ── Search ────────────────────────────────────────────────

    suspend fun search(query: String, currentPath: String = ""): NetworkResult<SearchResult> {
        val q = URLEncoder.encode(query, "UTF-8")
        val p = if (currentPath.isNotEmpty()) "&path=${URLEncoder.encode(currentPath, "UTF-8")}" else ""
        return httpGet("$baseUrl/api/v1/search?q=$q$p", object : TypeToken<SearchResult>() {}.type)
    }

    // ── System browse ─────────────────────────────────────────

    suspend fun getSystemDrives(): NetworkResult<List<String>> =
        httpGet("$baseUrl/api/v1/system/drives", object : TypeToken<List<String>>() {}.type)

    suspend fun browseSystemPath(
        path: String,
        forceNetwork: Boolean = false,
        sort: String? = null,
        order: String? = null,
        page: Int = 0,
        pageSize: Int = 0,
    ): NetworkResult<SystemBrowseResult> {
        // Server-side sort + pagination mirror /api/v1/folders/*/browse so the
        // Android client loads system directories incrementally; no params =
        // legacy full return.
        val query = buildList {
            add("path=${URLEncoder.encode(path, "UTF-8")}")
            if (!sort.isNullOrEmpty()) add("sort=${URLEncoder.encode(sort, "UTF-8")}")
            if (!order.isNullOrEmpty()) add("order=${URLEncoder.encode(order, "UTF-8")}")
            if (page > 0) add("page=$page")
            if (pageSize > 0) add("page_size=$pageSize")
        }.joinToString("&")
        return httpGet("$baseUrl/api/v1/system/browse?$query",
            object : TypeToken<SystemBrowseResult>() {}.type,
            forceNetwork = forceNetwork)
    }

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
                e.toNetworkError()
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
        httpPost("$baseUrl/api/v1/tags/$tagId/files/${encodePathSegments(normalizeRoutePath(filePath))}", "{}",
            object : TypeToken<Map<String, String>>() {}.type)

    suspend fun untagFile(tagId: String, filePath: String): NetworkResult<Map<String, String>> =
        httpEmpty("$baseUrl/api/v1/tags/$tagId/files/${encodePathSegments(normalizeRoutePath(filePath))}", "DELETE")
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
        bleFetchOrHttp<Book>(
            httpCall = {
                val data = httpGetRaw<Book>(
                    "$baseUrl/api/v1/books/info?path=${URLEncoder.encode(path, "UTF-8")}",
                    Book::class.java,
                )
                setBleDegraded(false)
                data
            },
            endpoint = BleProtocol.ENDPOINT_BOOK_INFO,
            path = path,
            index = 0,
            type = Book::class.java,
        )

    /**
     * Fetches a single chapter, transparently failing over to the BLE
     * transport when the HTTP path is unreachable.
     *
     * Spec §3.2 / §1.2 step 4: when the HTTP call throws an [IOException]
     * (connect refused, socket timeout, broken pipe, …) AND the BLE link is
     * [BleConnState.CONNECTED], the repository dispatches a CMD_API_REQ with
     * [BleProtocol.ENDPOINT_BOOK_CHAPTER] over BLE, reassembles the streamed
     * chunks via [BleTransportFallback], maps them to [BookChapterContent],
     * and raises [isBleDegraded] so the reader can surface the 3-second badge.
     *
     * All other HTTP failures (server 4xx/5xx via [HttpStatusException], or
     * BLE not connected) bypass the failover and return the original error.
     *
     * The caller MUST observe [isBleDegraded]; the flag is reset to false on
     * the next HTTP-served chapter so the badge only surfaces during actual
     * BLE traffic.
     *
     * Chapter-specific note: the BLE chunk wire format carries only the
     * `List<Block>` JSON (spec §3.2 — `[{"type":"text","value":"…"}, …]`), NOT
     * the full [BookChapterContent] shape, so on the BLE path the chapter
     * title is left blank — the reader already holds it from [getBookInfo].
     * KNOWN LIMITATION (M3 / out-of-scope I3): a future wire-format extension
     * is needed to carry the title end-to-end. Because of this type mismatch
     * (HTTP returns [BookChapterContent], BLE returns `List<Block>`), the
     * chapter path does NOT reuse the generic [bleFetchOrHttp] — it keeps its
     * own 3-branch try/catch and shares the failover behavior inline.
     */
    suspend fun getBookChapter(path: String, index: Int): NetworkResult<BookChapterContent> {
        val url = "$baseUrl/api/v1/books/chapter" +
            "?path=${URLEncoder.encode(path, "UTF-8")}&index=$index"
        return try {
            val content = httpGetRaw<BookChapterContent>(url, BookChapterContent::class.java)
            setBleDegraded(false)
            NetworkResult.Success(content)
        } catch (e: HttpStatusException) {
            // Server responded with a non-2xx status — NOT a transport outage,
            // so failover does not apply. Surface the original error code.
            NetworkResult.Error("Server returned ${e.code}", e.code)
        } catch (e: IOException) {
            // Transport-level failure: candidate for BLE failover.
            if (bleController.connectionState.value != BleConnState.CONNECTED) {
                e.toNetworkError()
            } else {
                val json = bleTransportFallback.fetchJson(
                    BleProtocol.ENDPOINT_BOOK_CHAPTER, path, index,
                ) {
                    bleController.requestApi(BleProtocol.ENDPOINT_BOOK_CHAPTER, path, index)
                }
                if (json == null) {
                    // BLE timed out / reassembly failed — surface the original
                    // HTTP error and DO NOT raise isBleDegraded.
                    e.toNetworkError()
                } else {
                    val blocks = try {
                        gson.fromJson<List<Block>>(
                            json,
                            object : TypeToken<List<Block>>() {}.type,
                        )
                    } catch (parseErr: Exception) {
                        null
                    }
                    if (blocks == null) {
                        e.toNetworkError()
                    } else {
                        setBleDegraded(true)
                        _bleDegradedEvents.tryEmit(Unit)
                        NetworkResult.Success(BookChapterContent(title = "", blocks = blocks))
                    }
                }
            }
        }
    }

    /**
     * Shared BLE failover branch for the list-style failover-capable endpoints
     * (folders / browseFolder / bookInfo). [getBookChapter] keeps its own
     * inline copy of this logic because its BLE wire payload (`List<Block>`)
     * differs from its HTTP return type ([BookChapterContent]).
     *
     * Behavior (preserves the prior chapter-only contract, generalized):
     *  - Run [httpCall]; on Success, reset [isBleDegraded] (done inside
     *    [httpCall] by each caller) and return the parsed body.
     *  - [HttpStatusException] (server responded non-2xx): NOT a transport
     *    outage, so failover does not apply. Surface the original error.
     *  - [IOException] (connect refused, socket timeout, …): if the BLE link
     *    is NOT [BleConnState.CONNECTED], surface the original error. Else
     *    dispatch the request over BLE via [BleTransportFallback.fetchJson]
     *    (which drives [BleController.requestApi] for the Central). Null
     *    result (timeout) → original HTTP error, [isBleDegraded] stays false.
     *    Non-null → Gson-parse with [type]; on success raise [isBleDegraded]
     *    and emit one [bleDegradedEvents] per BLE-served request (I2 fix).
     *
     * Single-coroutine contract: [BleTransportFallback] is NOT reentrant; only
     * one fetch cycle may be in flight at a time. The repository is the sole
     * caller and serializes per-request.
     *
     * @param httpCall the HTTP path; throws [HttpStatusException] on non-2xx,
     *   [IOException] on transport failure. MUST reset [isBleDegraded] on
     *   success (each caller does this inline) so the badge clears when Wi-Fi
     *   recovers.
     * @param endpoint one of `BleProtocol.ENDPOINT_*`; advisory for the engine
     *   but bound into the dispatch lambda so the Central's Go ApiProvider can
     *   route to the correct handler.
     * @param path resource path (UTF-8). Length must fit 1 byte (≤ 255).
     * @param index pagination / chapter index (uint16 BE on the wire).
     * @param type Gson [java.lang.reflect.Type] for deserializing the BLE JSON
     *   (same shape as the HTTP response for these endpoints).
     */
    private suspend fun <T> bleFetchOrHttp(
        httpCall: suspend () -> T,
        endpoint: Byte,
        path: String = "",
        index: Int = 0,
        type: java.lang.reflect.Type,
    ): NetworkResult<T> = try {
        NetworkResult.Success(httpCall())
    } catch (e: HttpStatusException) {
        // Server responded with a non-2xx status — NOT a transport outage,
        // so failover does not apply. Surface the original error code.
        NetworkResult.Error("Server returned ${e.code}", e.code)
    } catch (e: IOException) {
        // Transport-level failure: candidate for BLE failover.
        if (bleController.connectionState.value != BleConnState.CONNECTED) {
            e.toNetworkError()
        } else {
            val json = bleTransportFallback.fetchJson(endpoint, path, index) {
                bleController.requestApi(endpoint, path, index)
            }
            if (json == null) {
                // BLE link was CONNECTED but no payload arrived / reassembly
                // failed within the timeout budget. Surface the original HTTP
                // error and DO NOT raise isBleDegraded (no traffic was served).
                e.toNetworkError()
            } else {
                try {
                    val parsed = gson.fromJson<T>(json, type)
                    setBleDegraded(true)
                    // I2: emit a one-shot degradation event so the reader
                    // re-shows the 3-second badge on EVERY BLE-served request,
                    // not just the first one after the value flips true.
                    // tryEmit is safe here: extraBufferCapacity = 1 guarantees
                    // no blocking; a slow consumer simply drops an emission it
                    // has not yet collected (acceptable — the next request
                    // re-emits).
                    _bleDegradedEvents.tryEmit(Unit)
                    NetworkResult.Success(parsed)
                } catch (parseErr: Exception) {
                    // JSON deserialization failed — surface the original HTTP
                    // error rather than a misleading parse message.
                    e.toNetworkError()
                }
            }
        }
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

    fun getMediaThumbnailUrl(absolutePath: String, mtime: String = ""): String =
        buildString {
            append("$baseUrl/api/v1/media/thumbnail?path=")
            append(URLEncoder.encode(absolutePath, "UTF-8"))
            // Round: append the source modtime as a cache-busting version
            // param. Coil keys its disk cache by URL and does not revalidate
            // via ETag/Last-Modified, so without this a replaced source file
            // keeps rendering its stale thumbnail until LRU eviction. The
            // server ignores the extra param; it just changes the cache key.
            if (mtime.isNotEmpty()) {
                append("&mtime=")
                append(URLEncoder.encode(mtime, "UTF-8"))
            }
        }

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

    /** Localized resource for the well-known transport failures; null means
     *  the caller should surface [toUserMessage] verbatim. */
    private fun Exception.toUserMessageRes(): Int? = when (this) {
        is java.net.ConnectException -> R.string.error_cannot_connect
        is SocketTimeoutException -> R.string.error_timeout
        is java.net.UnknownHostException -> R.string.error_unknown_host
        else -> null
    }

    private fun Exception.toNetworkError(): NetworkResult.Error =
        NetworkResult.Error(toUserMessage(), userMessageRes = toUserMessageRes())
}

/**
 * Internal signal from [MediaRepository.httpGetRaw] that the server responded
 * with a non-2xx HTTP status. Carries no message of its own — the repository
 * formats the user-facing string via [MediaRepository.toUserMessage]. This is
 * NOT an [IOException]; the BLE failover gate must not fire on it because the
 * transport is fine and re-sending the request would only repeat the failure.
 */
private class HttpStatusException(val code: Int) : Exception("HTTP $code")

/**
 * Task 12 (L-6): Percent-encodes a `/`-separated relative path for safe use
 * as URL path segments (e.g. `/api/v1/folders/<route>/browse`).
 *
 * Each segment goes through [URLEncoder.encode] individually so characters
 * that are illegal or ambiguous inside a path segment (`#`, `?`, `&`, `+`,
 * spaces, CJK, …) are escaped, while the `/` separators stay literal so the
 * server wildcard route still resolves the same directory. `URLEncoder` is
 * query-string oriented and encodes a space as `+`; in a path segment `+` is
 * a literal character and the Go server (`url.PathUnescape`) would NOT turn
 * it back into a space, so `+` is re-encoded to `%20`.
 */
internal fun encodePathSegments(path: String): String =
    path.split("/").joinToString("/") { segment ->
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }
