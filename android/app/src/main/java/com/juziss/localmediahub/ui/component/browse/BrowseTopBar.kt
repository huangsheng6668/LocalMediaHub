package com.juziss.localmediahub.ui.component.browse

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.juziss.localmediahub.R
import com.juziss.localmediahub.viewmodel.SortOrder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowseTopBar(
    isSearchMode: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    title: String,
    onBack: (() -> Unit)?,
    showLibraryActions: Boolean,
    isSystemBrowse: Boolean,
    onToggleSystemMode: () -> Unit,
    onShowFavorites: () -> Unit,
    showSortAndSearch: Boolean,
    folderSort: SortOrder,
    fileSort: SortOrder,
    onFolderSortChange: (SortOrder) -> Unit,
    onFileSortChange: (SortOrder) -> Unit,
    showSearch: Boolean,
    onEnterSearch: () -> Unit,
) {
    if (isSearchMode) {
        TopAppBar(
            title = {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text(stringResource(R.string.browse_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                )
            },
            navigationIcon = {
                IconButton(onClick = onClearSearch) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
    } else {
        TopAppBar(
            title = {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold,
                )
            },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            },
            actions = {
                if (showLibraryActions) {
                    IconButton(onClick = onToggleSystemMode) {
                        Icon(
                            Icons.Filled.Storage,
                            contentDescription = if (isSystemBrowse) stringResource(R.string.browse_libraries) else stringResource(R.string.browse_title_drive),
                        )
                    }
                }
                if (showLibraryActions) {
                    IconButton(onClick = onShowFavorites) {
                        Icon(
                            Icons.Outlined.FavoriteBorder,
                            contentDescription = stringResource(R.string.browse_favorites),
                        )
                    }
                }
                if (showSortAndSearch) {
                    BrowseSortMenu(
                        folderSort = folderSort,
                        fileSort = fileSort,
                        onFolderSortChange = onFolderSortChange,
                        onFileSortChange = onFileSortChange,
                    )
                    if (showSearch) {
                        IconButton(onClick = onEnterSearch) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                titleContentColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}
