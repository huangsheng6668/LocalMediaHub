package com.juziss.localmediahub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juziss.localmediahub.data.BrowseResult
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.network.NetworkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the file browser screen.
 */
class BrowseViewModel : ViewModel() {

    private val repository = MediaRepository()

    private val _browseState = MutableStateFlow<BrowseState>(BrowseState.Idle)
    val browseState: StateFlow<BrowseState> = _browseState.asStateFlow()

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _pathStack = MutableStateFlow<List<String>>(emptyList())
    val pathStack: StateFlow<List<String>> = _pathStack.asStateFlow()

    /** Load root folders. */
    fun loadRoots() {
        viewModelScope.launch {
            _browseState.value = BrowseState.Loading
            when (val result = repository.getFolders()) {
                is NetworkResult.Success -> {
                    _browseState.value = BrowseState.RootFolders(result.data)
                    _currentPath.value = ""
                    _pathStack.value = emptyList()
                }
                is NetworkResult.Error -> {
                    _browseState.value = BrowseState.Error(result.message)
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    /** Browse into a specific folder. */
    fun browseFolder(relativePath: String, folderName: String) {
        viewModelScope.launch {
            _browseState.value = BrowseState.Loading
            // Push current path onto stack for back navigation
            _pathStack.value = _pathStack.value + _currentPath.value
            _currentPath.value = relativePath

            when (val result = repository.browseFolder(relativePath)) {
                is NetworkResult.Success -> {
                    _browseState.value = BrowseState.Browsed(result.data)
                }
                is NetworkResult.Error -> {
                    _browseState.value = BrowseState.Error(result.message)
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    /** Go back to previous path level. */
    fun navigateBack() {
        val stack = _pathStack.value
        if (stack.isEmpty()) {
            loadRoots()
            return
        }
        val previousPath = stack.last()
        _pathStack.value = stack.dropLast(1)

        viewModelScope.launch {
            _browseState.value = BrowseState.Loading
            _currentPath.value = previousPath

            if (previousPath.isEmpty()) {
                loadRoots()
            } else {
                when (val result = repository.browseFolder(previousPath)) {
                    is NetworkResult.Success -> {
                        _browseState.value = BrowseState.Browsed(result.data)
                    }
                    is NetworkResult.Error -> {
                        _browseState.value = BrowseState.Error(result.message)
                    }
                    is NetworkResult.Loading -> {}
                }
            }
        }
    }

    fun canGoBack(): Boolean = _pathStack.value.isNotEmpty()

    fun getVideoStreamUrl(file: MediaFile): String {
        return repository.getVideoStreamUrl(file.relativePath)
    }

    fun getThumbnailUrl(file: MediaFile): String {
        return repository.getThumbnailUrl(file.relativePath)
    }

    fun getOriginalImageUrl(file: MediaFile): String {
        return repository.getOriginalImageUrl(file.relativePath)
    }
}

sealed class BrowseState {
    data object Idle : BrowseState()
    data object Loading : BrowseState()
    data class RootFolders(val folders: List<com.juziss.localmediahub.data.Folder>) : BrowseState()
    data class Browsed(val result: BrowseResult) : BrowseState()
    data class Error(val message: String) : BrowseState()
}
