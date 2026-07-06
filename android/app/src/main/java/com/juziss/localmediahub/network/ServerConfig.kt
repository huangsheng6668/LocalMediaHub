package com.juziss.localmediahub.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt singleton holding the current server base URL + shared OkHttpClient.
 *
 * Round 19 refactor: replaces the Kotlin `object RetrofitClient` which
 * required a `setSharedClient()` bridge to receive the Hilt-provided
 * OkHttpClient. ServerConfig receives [httpClient] via constructor
 * injection (proper Hilt pattern) and exposes baseUrl as a StateFlow
 * so ViewModels can reactively observe changes.
 *
 * The Retrofit dependency is removed entirely — all API calls use
 * OkHttp + Gson directly (see MediaRepository).
 *
 * [httpClient] is exposed as a public val so ViewModels that need a
 * derived client (e.g. ConnectionViewModel LAN scan with 250ms timeout)
 * can call `serverConfig.httpClient.newBuilder()` to share the connection
 * pool without a separate Hilt injection.
 */
@Singleton
class ServerConfig @Inject constructor(
    val httpClient: OkHttpClient,
) {
    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    fun setBaseUrl(url: String) {
        val normalized = url.trimEnd('/')
        if (normalized != _baseUrl.value) {
            _baseUrl.value = normalized
        }
    }

    fun isInitialized(): Boolean = _baseUrl.value.isNotEmpty()

    fun getBaseUrl(): String = _baseUrl.value
}
