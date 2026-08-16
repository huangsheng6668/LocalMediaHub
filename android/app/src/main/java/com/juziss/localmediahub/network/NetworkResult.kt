package com.juziss.localmediahub.network

import android.content.Context
import androidx.annotation.StringRes

/**
 * A generic sealed class that holds a value with its loading status.
 */
sealed class NetworkResult<out T> {
    data class Success<out T>(val data: T) : NetworkResult<T>()

    data class Error(
        val message: String,
        val code: Int? = null,
        /** Localized resource for known transport errors; null means the
         *  caller should surface [message] directly. */
        @StringRes val userMessageRes: Int? = null,
    ) : NetworkResult<Nothing>()

    data object Loading : NetworkResult<Nothing>()
}

/** Resolves the user-facing text: the localized resource when the repository
 *  attached one (transport errors such as connect/timeout/unknown-host),
 *  otherwise the server-provided message. */
fun NetworkResult.Error.userText(context: Context): String =
    userMessageRes?.let { context.getString(it) } ?: message
