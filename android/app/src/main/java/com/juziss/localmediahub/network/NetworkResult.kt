package com.juziss.localmediahub.network

/**
 * A generic sealed class that holds a value with its loading status.
 */
sealed class NetworkResult<out T> {
    data class Success<out T>(val data: T) : NetworkResult<T>()
    data class Error(val message: String, val code: Int? = null) : NetworkResult<Nothing>()
    data object Loading : NetworkResult<Nothing>()
}
