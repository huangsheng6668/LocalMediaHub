package com.juziss.localmediahub.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Singleton Retrofit client with dynamic baseUrl support.
 */
object RetrofitClient {

    private const val DEFAULT_TIMEOUT = 30L

    private var _baseUrl: String = ""
    private var _retrofit: Retrofit? = null
    // Shared singleton OkHttpClient provided by Hilt OkHttpModule. Must be
    // set via [setSharedClient] before [initialize] so the Retrofit builder
    // reuses the shared client + connection pool. Round 17 C3.
    private var sharedClient: OkHttpClient? = null

    val instance: Retrofit
        get() = _retrofit ?: throw IllegalStateException(
            "RetrofitClient not initialized. Call initialize() first."
        )

    val api: MediaApi
        get() = instance.create(MediaApi::class.java)

    /**
     * Set the shared OkHttpClient (provided by Hilt OkHttpModule). Must be
     * called before [initialize] so the Retrofit builder reuses the shared
     * client + connection pool. Round 17 C3.
     */
    fun setSharedClient(client: OkHttpClient) {
        sharedClient = client
    }

    fun initialize(baseUrl: String) {
        val normalized = baseUrl.trimEnd('/')
        if (normalized == _baseUrl && _retrofit != null) return

        _baseUrl = normalized
        _retrofit = buildRetrofit(normalized)
    }

    fun isInitialized(): Boolean = _retrofit != null

    fun getBaseUrl(): String = _baseUrl

    private fun buildRetrofit(baseUrl: String): Retrofit {
        val client = sharedClient ?: throw IllegalStateException(
            "RetrofitClient.setSharedClient() must be called before initialize()"
        )

        return Retrofit.Builder()
            .baseUrl("$baseUrl/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
}
