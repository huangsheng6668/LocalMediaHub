package com.juziss.localmediahub.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt singleton holding the current server base URL + bearer token.
 *
 * Round 29 (Phase 1 Bearer Token auth) changes:
 * - Removed `httpClient: OkHttpClient` from the constructor. The shared
 *   OkHttpClient is now built by [OkHttpModule] with an [AuthInterceptor]
 *   that reads the token from this class via `getTokenSnapshot()`. Letting
 *   ServerConfig depend on OkHttpClient (its previous constructor param)
 *   formed a Hilt cycle: provideOkHttpClient -> ServerConfig -> OkHttpClient.
 *   Callers needing a derived client now inject `OkHttpClient` directly via
 *   Hilt (e.g. `ConnectionViewModel` already does this).
 * - Added `token: StateFlow<String>` + [setToken] / [getTokenSnapshot]. The
 *   token is NOT auto-seeded from DataStore here (mirrors the `_baseUrl`
 *   pattern); the connection form / settings screen calls [setToken] after
 *   the user enters or loads a token. Task 8 wires the DataStore -> StateFlow
 *   sync at app startup.
 *
 * The Retrofit dependency is removed entirely — all API calls use
 * OkHttp + Gson directly (see MediaRepository).
 */
@Singleton
class ServerConfig @Inject constructor() {
    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _token = MutableStateFlow("")
    val token: StateFlow<String> = _token.asStateFlow()

    fun setBaseUrl(url: String) {
        val normalized = url.trimEnd('/')
        if (normalized != _baseUrl.value) {
            _baseUrl.value = normalized
        }
    }

    fun setToken(token: String) {
        _token.value = token
    }

    fun getTokenSnapshot(): String = _token.value

    fun isInitialized(): Boolean = _baseUrl.value.isNotEmpty()

    fun getBaseUrl(): String = _baseUrl.value
}
