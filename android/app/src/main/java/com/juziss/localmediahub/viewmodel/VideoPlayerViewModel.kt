package com.juziss.localmediahub.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Hilt ViewModel that exposes the shared singleton [OkHttpClient] to
 * [com.juziss.localmediahub.ui.screen.VideoPlayerScreen] for ExoPlayer
 * DataSource.Factory construction.
 *
 * Composables cannot receive Hilt constructor injection directly; this
 * ViewModel acts as the injection seam. Round 17 C3 replaces the per-screen
 * `OkHttpClient.Builder()` with the shared singleton.
 */
@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    private val httpClient: OkHttpClient,
) : ViewModel() {
    /** The shared singleton OkHttpClient (with 20MB cache + connection pool). */
    fun provideHttpClient(): OkHttpClient = httpClient
}
