package com.juziss.localmediahub.data

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.FileSystem
import java.io.IOException
import java.net.URLDecoder

/**
 * Coil fetcher that collapses the grid's N+1 thumbnail requests into batched
 * POSTs to /api/v1/media/thumbnails. Each visible card previously fired its
 * own HTTP request; with this fetcher, requests that arrive within a short
 * debounce window are coalesced into ONE batch round-trip and each pending
 * image receives its bytes from the shared response.
 *
 * Only URLs pointing at the server's /api/v1/media/thumbnail endpoint are
 * intercepted — everything else falls through to the next factory in the
 * ImageLoader component chain (the OkHttp network fetcher).
 *
 * Failure behavior: a failed batch resolves every pending image to null, and
 * the fetcher returns [FetchResult.Error] so Coil's error fallbacks (the
 * neutral tint blocks) render exactly as they would for a single failed
 * request.
 */
class BatchThumbnailFetcherFactory(
    private val client: OkHttpClient,
    private val getBaseUrl: () -> String,
) : Fetcher.Factory<String> {

    override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? {
        val base = getBaseUrl()
        if (base.isEmpty()) return null
        val prefix = "$base/api/v1/media/thumbnail"
        if (!data.startsWith(prefix)) return null
        return BatchThumbnailFetcher(client, base, data)
    }
}

private val batchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val batchMutex = Mutex()
private val pendingBatch = LinkedHashMap<String, CompletableDeferred<ByteArray?>>()
private var drainScheduled = false

/** Debounce window: requests in the same scroll frame coalesce into one POST. */
private const val BATCH_DEBOUNCE_MS = 40L
private const val BATCH_TIMEOUT_MS = 10_000L

private class BatchThumbnailFetcher(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val url: String,
) : Fetcher {

    override suspend fun fetch(): coil3.fetch.FetchResult {
        val bytes = fetchBatchedThumbnail(baseUrl, client, url)
            ?: throw IOException("batch thumbnail fetch failed: $url")
        val source = ImageSource(Buffer().write(bytes), FileSystem.SYSTEM)
        return SourceFetchResult(source, "image/jpeg", DataSource.NETWORK)
    }
}

/** Extracts the decoded `path` query parameter from a thumbnail URL. */
private fun pathFromThumbnailUrl(url: String): String =
    URLDecoder.decode(url.substringAfter("path=").substringBefore('&'), "UTF-8")

private suspend fun fetchBatchedThumbnail(baseUrl: String, client: OkHttpClient, url: String): ByteArray? {
    val deferred = CompletableDeferred<ByteArray?>()
    var trigger = false
    batchMutex.withLock {
        if (pendingBatch.put(url, deferred) == null) {
            // Fresh entry: schedule the drain if none is pending.
            if (!drainScheduled) {
                drainScheduled = true
                trigger = true
            }
        }
    }
    if (trigger) {
        batchScope.launch {
            delay(BATCH_DEBOUNCE_MS)
            drainBatch(baseUrl, client)
        }
    }
    return withTimeoutOrNull(BATCH_TIMEOUT_MS) { deferred.await() }
}

private suspend fun drainBatch(baseUrl: String, client: OkHttpClient) {
    val batch: LinkedHashMap<String, CompletableDeferred<ByteArray?>> = batchMutex.withLock {
        drainScheduled = false
        val snapshot = LinkedHashMap(pendingBatch)
        pendingBatch.clear()
        snapshot
    }
    if (batch.isEmpty()) return

    val paths = batch.keys.map { pathFromThumbnailUrl(it) }
    val jsonMedia = "application/json; charset=utf-8".toMediaType()
    val body = Gson().toJson(mapOf("paths" to paths)).toRequestBody(jsonMedia)

    // Always settle every deferred — a crash mid-parse must not leave Coil
    // hanging (the 10s timeout would eventually rescue it, but settle-fast is
    // cheaper).
    val settle = { result: Map<String, ByteArray?> ->
        batch.forEach { (u, d) ->
            if (!d.isCompleted) d.complete(result[pathFromThumbnailUrl(u)])
        }
    }

    try {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/media/thumbnails")
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                settle(emptyMap())
                return
            }
            val text = resp.body?.string()
            if (text == null) {
                settle(emptyMap())
                return
            }
            val items = JsonParser.parseString(text).asJsonObject.getAsJsonArray("items")
            val result = HashMap<String, ByteArray?>(items.size())
            items.forEach { el ->
                val obj = el.asJsonObject
                val path = obj.get("path").asString
                val b64 = obj.get("thumbnail")?.takeIf { !it.isJsonNull }?.asString
                result[path] = b64?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
            }
            settle(result)
        }
    } catch (e: Exception) {
        settle(emptyMap())
    }
}
