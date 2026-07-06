package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.network.NetworkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * BrowseViewModel delegate responsible for search state and operations:
 * query tracking, executing searches scoped to the current browse path,
 * and clearing search results.
 *
 * Round 18 refactor: extracted verbatim from BrowseViewModel. `search()`
 * reads `sharedState.currentPath` so it always searches within the
 * currently-browsed directory. The search state and query are private
 * to this delegate because no other delegate consumes them.
 *
 * `search()` previously wrapped its body in `viewModelScope.launch` —
 * it now takes a `scope: CoroutineScope` parameter so the caller
 * (BrowseViewModel) supplies the coroutine scope.
 *
 * Internal to the viewmodel package — NOT exposed to UI. The ViewModel
 * re-exposes the relevant flows/functions for backward compat.
 */
internal class SearchController(
    private val repository: MediaRepository,
    private val sharedState: BrowseSharedState,
) {

    // ── Search state (private to this delegate) ────────────────

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun search(scope: CoroutineScope) {
        val query = _searchQuery.value.trim()
        if (query.isEmpty()) return
        scope.launch {
            _searchState.value = SearchState.Loading
            when (val result = repository.search(query, sharedState.currentPath.value)) {
                is NetworkResult.Success -> {
                    _searchState.value = SearchState.Results(result.data)
                }
                is NetworkResult.Error -> {
                    _searchState.value = SearchState.Error(result.message)
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchState.value = SearchState.Idle
    }
}
