package com.juziss.localmediahub.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Singleton Retrofit client with dynamic baseUrl support.
 */
object RetrofitClient {

    private const val DEFAULT_TIMEOUT = 30L

    private var _baseUrl: String = ""
    private var _retrofit: Retrofit? = null

    val instance: Retrofit
        get() = _retrofit ?: throw IllegalStateException(
            "RetrofitClient not initialized. Call initialize() first."
        )

    val api: MediaApi
        get() = instance.create(MediaApi::class.java)

    fun initialize(baseUrl: String) {
        val normalized = baseUrl.trimEnd('/')
        if (normalized == _baseUrl && _retrofit != null) return

        _baseUrl = normalized
        _retrofit = buildRetrofit(normalized)
    }

    fun isInitialized(): Boolean = _retrofit != null

    fun getBaseUrl(): String = _baseUrl

    private fun buildRetrofit(baseUrl: String): Retrofit {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl("$baseUrl/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
}
