package com.juziss.localmediahub.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.juziss.localmediahub.network.NetworkResult
import com.juziss.localmediahub.network.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A discovered BLE peripheral reported by the server's Central scanner.
 *
 * `id` is the device address; `name` is the advertised local name (may be
 * empty); `rssi` is the signal strength in dBm (negative, closer to 0 is
 * stronger).
 */
data class BleDevice(val id: String, val name: String, val rssi: Int)

/**
 * Talks to the PC server's BLE Central over the existing Wi-Fi/HTTP channel.
 *
 * The server exposes three endpoints under /api/v1/ble/ (added in Task 4)
 * that proxy scan/connect/send to its BLE Central. This class wraps those
 * endpoints with OkHttp + Gson, returning [NetworkResult] for each call.
 *
 * Used by Task 9's `BleSettingsViewModel` to coordinate the PC's BLE Central
 * from the Android app. Zero-regression: any failure (network, HTTP, parse)
 * collapses to [NetworkResult.Error]; success returns [NetworkResult.Success].
 *
 * (Note: the three endpoints are scan / connect / send under /api/v1/ble/ .)
 *
 * JSON shapes (server-side):
 *  - scan:    `{"devices":[{"id":"...","name":"...","rssi":-45}]}`
 *  - connect: `{"connected":true}`
 *  - send:    `{"echo":"..."}` (echo may be null on server-side error)
 */
@Singleton
class BleApi @Inject constructor(
    private val client: OkHttpClient,
    private val serverConfig: ServerConfig,
) {
    private val gson = Gson()
    private val json = "application/json; charset=utf-8".toMediaType()

    private fun authHeader(): String = "Bearer ${serverConfig.getTokenSnapshot()}"

    /** GET `/api/v1/ble/scan` → list of discovered devices. Empty on server-side "unavailable". */
    suspend fun scan(): NetworkResult<List<BleDevice>> = withContext(Dispatchers.IO) {
        val url = "${serverConfig.getBaseUrl()}/api/v1/ble/scan"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", authHeader())
            .get()
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext NetworkResult.Error("Server returned ${resp.code}", resp.code)
                }
                val body = resp.body?.string()
                    ?: return@withContext NetworkResult.Error("Empty response body")
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val map: Map<String, Any> = gson.fromJson(body, type) ?: emptyMap()
                @Suppress("UNCHECKED_CAST")
                val devices = (map["devices"] as? List<Any>).orEmpty()
                val result = devices.mapNotNull { entry ->
                    @Suppress("UNCHECKED_CAST")
                    val d = entry as? Map<String, Any> ?: return@mapNotNull null
                    val id = d["id"] as? String ?: return@mapNotNull null
                    BleDevice(
                        id = id,
                        name = d["name"] as? String ?: "",
                        rssi = (d["rssi"] as? Number)?.toInt() ?: 0,
                    )
                }
                NetworkResult.Success(result)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "scan failed")
        }
    }

    /** POST `/api/v1/ble/connect` → `{"connected":true}`. Returns the boolean. */
    suspend fun connect(id: String): NetworkResult<Boolean> = withContext(Dispatchers.IO) {
        val url = "${serverConfig.getBaseUrl()}/api/v1/ble/connect"
        val body = """{"id":${gson.toJson(id)}}""".toRequestBody(json)
        val req = Request.Builder()
            .url(url)
            .header("Authorization", authHeader())
            .post(body)
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext NetworkResult.Error("Server returned ${resp.code}", resp.code)
                }
                val text = resp.body?.string()
                    ?: return@withContext NetworkResult.Error("Empty response body")
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val map: Map<String, Any> = gson.fromJson(text, type) ?: emptyMap()
                val connected = (map["connected"] as? Boolean) == true
                NetworkResult.Success(connected)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "connect failed")
        }
    }

    /** POST `/api/v1/ble/send` → `{"echo":"..."}`. Returns the echo payload (may be null). */
    suspend fun send(payload: String): NetworkResult<String?> = withContext(Dispatchers.IO) {
        val url = "${serverConfig.getBaseUrl()}/api/v1/ble/send"
        val body = """{"payload":${gson.toJson(payload)}}""".toRequestBody(json)
        val req = Request.Builder()
            .url(url)
            .header("Authorization", authHeader())
            .post(body)
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext NetworkResult.Error("Server returned ${resp.code}", resp.code)
                }
                val text = resp.body?.string()
                    ?: return@withContext NetworkResult.Error("Empty response body")
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val map: Map<String, Any> = gson.fromJson(text, type) ?: emptyMap()
                @Suppress("UNCHECKED_CAST")
                val echo = (map["echo"] as? String)
                NetworkResult.Success(echo)
            }
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "send failed")
        }
    }
}
