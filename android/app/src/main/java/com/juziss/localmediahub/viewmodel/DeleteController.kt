package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.network.NetworkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * BrowseViewModel delegate responsible for deletion state and operations:
 * clearing delete UI state, synchronous path deletion, and asynchronous
 * path deletion with automatic directory refresh after success.
 *
 * Round 18 refactor: extracted verbatim from BrowseViewModel. `deletePath`
 * invokes `refreshCurrentDirectory` — to avoid coupling the delegate to
 * navigation logic, the refresh callback is passed as `onRefresh`.
 *
 * `deletePath` previously wrapped its body in `viewModelScope.launch` —
 * it now takes a `scope: CoroutineScope` parameter so the caller
 * (BrowseViewModel) supplies the coroutine scope.
 *
 * Internal to the viewmodel package — NOT exposed to UI. The ViewModel
 * re-exposes the relevant flows/functions for backward compat.
 */
internal class DeleteController(
    private val repository: MediaRepository,
    private val sharedState: BrowseSharedState,
) {

    // ── Delete state (private to this delegate) ─────────────────

    private val _deleteState = MutableStateFlow<DeleteState>(DeleteState.Idle)
    val deleteState: StateFlow<DeleteState> = _deleteState.asStateFlow()

    fun clearDeleteState() {
        _deleteState.value = DeleteState.Idle
    }

    suspend fun deletePathSync(path: String, recursive: Boolean): NetworkResult<String> {
        return repository.deletePath(path, recursive)
    }

    fun deletePath(
        path: String,
        recursive: Boolean,
        onRefresh: (suspend () -> Unit)?,
        scope: CoroutineScope
    ) {
        scope.launch {
            _deleteState.value = DeleteState.Loading
            when (val result = deletePathSync(path, recursive)) {
                is NetworkResult.Success -> {
                    _deleteState.value = DeleteState.Success(result.data)
                    onRefresh?.invoke()
                }
                is NetworkResult.Error -> {
                    _deleteState.value = DeleteState.Error(result.message)
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun deletePaths(
        paths: List<String>,
        recursive: Boolean,
        onRefresh: (suspend () -> Unit)?,
        scope: CoroutineScope
    ) {
        scope.launch {
            _deleteState.value = DeleteState.Loading
            var anySuccess = false
            var errorMessage: String? = null
            for (path in paths) {
                when (val result = deletePathSync(path, recursive)) {
                    is NetworkResult.Success -> {
                        anySuccess = true
                    }
                    is NetworkResult.Error -> {
                        errorMessage = result.message
                    }
                    else -> {}
                }
            }
            if (anySuccess) {
                _deleteState.value = DeleteState.Success("成功删除了选中的文件")
                onRefresh?.invoke()
            } else if (errorMessage != null) {
                _deleteState.value = DeleteState.Error(errorMessage)
            } else {
                _deleteState.value = DeleteState.Idle
            }
        }
    }
}
