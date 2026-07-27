package com.juziss.localmediahub.ui.screen

import com.juziss.localmediahub.ui.component.BleChannelSection
import com.juziss.localmediahub.ui.component.home.HeroCard
import com.juziss.localmediahub.ui.component.home.EmptyHomeStateCard
import com.juziss.localmediahub.ui.component.home.LibraryCard
import com.juziss.localmediahub.ui.component.home.CollectionChip
import com.juziss.localmediahub.ui.component.home.ContinueWatchingCard
import com.juziss.localmediahub.ui.component.home.RecentMediaCard
import com.juziss.localmediahub.ui.component.home.FavoritePreviewCard
import com.juziss.localmediahub.ui.component.home.DownloadedPreviewCard
import com.juziss.localmediahub.ui.component.home.StatusNoticeCard
import com.juziss.localmediahub.ui.component.home.SectionHeader
import com.juziss.localmediahub.ui.component.home.BookshelfCard
import com.juziss.localmediahub.TextReaderActivity
 
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.juziss.localmediahub.data.DownloadEntry
import com.juziss.localmediahub.data.LastBrowseLocation
import androidx.compose.ui.res.stringResource
import com.juziss.localmediahub.R
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.PlaybackProgressEntry
import com.juziss.localmediahub.data.RecentMediaEntry
import com.juziss.localmediahub.data.Tag
import com.juziss.localmediahub.viewmodel.CollectionSummary
import com.juziss.localmediahub.viewmodel.HomeUiState
import com.juziss.localmediahub.viewmodel.HomeViewModel
import com.juziss.localmediahub.viewmodel.LibrarySummary
 
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun HomeScreen(
    onOpenLibrary: (LibrarySummary) -> Unit,
    onResumeBrowse: (LastBrowseLocation) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenCollection: (Tag) -> Unit,
    onContinueWatching: (PlaybackProgressEntry) -> Unit,
    onOpenRecentMedia: (RecentMediaEntry) -> Unit,
    onFavoriteClick: (MediaFile) -> Unit = {},
    onDisconnect: () -> Unit = {},
    downloadedEntries: List<DownloadEntry> = emptyList(),
    onOpenDownloads: () -> Unit = {},
    onDownloadClick: (DownloadEntry) -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val recentBooks by viewModel.recentBooks.collectAsState()
    val context = LocalContext.current

    // Task 7: responsive horizontal padding via WindowSizeClass.
    // DOWNSCOPED: only `horizontalPadding` is consumed; `columns` is computed
    // (and reserved for a future multi-column LazyVerticalGrid enhancement) but
    // the LazyColumn below still renders a single column regardless of value.
    //
    // `calculateWindowSizeClass` is a @Composable that requires a real Activity
    // window. Because the Compose compiler forbids try/catch around composable
    // invocations, we guard the call with a null-check on the cast Activity and
    // only invoke it when we actually have one. When null (e.g. the composable
    // is hosted in a non-Activity Context), we fall back to Compact-width
    // defaults: columns = 1 → 20.dp.
    val activity = context as? android.app.Activity
    val windowClass = activity?.let { calculateWindowSizeClass(it) }
    val columns = when (windowClass?.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> 3
        WindowWidthSizeClass.Medium -> 2
        else -> 1
    }
    val horizontalPadding = when (columns) {
        3 -> 32.dp
        2 -> 24.dp
        else -> 20.dp
    }
 
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("LocalMediaHub", fontWeight = FontWeight.Bold)
                        Text(
                            text = stringResource(R.string.home_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    val url = uiState.serverLabel
                    if (url.isNotBlank()) {
                        IconButton(onClick = {
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }) {
                            Icon(
                                painterResource(R.drawable.ic_language),
                                contentDescription = stringResource(R.string.home_web_btn),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    IconButton(onClick = onDisconnect) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.home_disconnect),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        }
    ) { innerPadding ->
        if (uiState.isLoading && uiState.libraries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.home_loading_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.home_loading_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Scaffold
        }
 
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding),
            contentPadding = PaddingValues(start = horizontalPadding, end = horizontalPadding, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            item {
                BleChannelSection()
            }

            item {
                HeroCard(
                    uiState = uiState,
                    onResumeBrowse = onResumeBrowse,
                    onOpenFavorites = onOpenFavorites,
                    downloadCount = downloadedEntries.size,
                    onOpenDownloads = onOpenDownloads,
                    onOpenWeb = {
                        try {
                            val url = uiState.serverLabel
                            if (url.isNotEmpty()) {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                context.startActivity(intent)
                            }
                        } catch (_: Exception) {}
                    }
                )
            }
 
            if (uiState.libraries.isEmpty() && uiState.errorMessage == null) {
                item {
                    EmptyHomeStateCard()
                }
            }
 
            if (uiState.libraries.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = stringResource(R.string.home_section_libraries),
                        subtitle = stringResource(R.string.home_section_libraries_desc, uiState.libraries.size),
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(uiState.libraries, key = { it.path }) { library ->
                            LibraryCard(library = library, onClick = { onOpenLibrary(library) })
                        }
                    }
                }
            }
 
            if (uiState.collections.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = stringResource(R.string.home_section_collections),
                        subtitle = stringResource(R.string.home_section_collections_desc),
                    )
                }
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        uiState.collections.take(8).forEach { collection ->
                            CollectionChip(
                                collection = collection,
                                onClick = { onOpenCollection(collection.tag) },
                            )
                        }
                    }
                }
            }
 
            if (uiState.continueWatching.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = stringResource(R.string.home_section_continue),
                        subtitle = stringResource(R.string.home_section_continue_desc),
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(uiState.continueWatching, key = { "${it.file.relativePath}-${it.updatedAt}" }) { entry ->
                            ContinueWatchingCard(
                                entry = entry,
                                onClick = { onContinueWatching(entry) },
                            )
                        }
                    }
                }
            }

            if (recentBooks.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = stringResource(R.string.home_section_bookshelf),
                        subtitle = stringResource(R.string.home_section_bookshelf_desc),
                    )
                }
                item {
                    BookshelfCard(
                        books = recentBooks,
                        onOpen = { entry ->
                            context.startActivity(
                                TextReaderActivity.newIntent(context, entry.path)
                            )
                        },
                    )
                }
            }
 
            if (uiState.recentMedia.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = stringResource(R.string.home_section_recent),
                        subtitle = stringResource(R.string.home_section_recent_desc),
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(uiState.recentMedia, key = { "${it.file.relativePath}-${it.openedAt}" }) { entry ->
                            RecentMediaCard(
                                entry = entry,
                                getThumbnailUrl = viewModel::getThumbnailUrl,
                                onClick = { onOpenRecentMedia(entry) },
                            )
                        }
                    }
                }
            }
 
            if (uiState.favoriteFiles.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = stringResource(R.string.home_section_favorites),
                        subtitle = stringResource(R.string.home_section_favorites_desc),
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(uiState.favoriteFiles, key = { it.relativePath }) { file ->
                            FavoritePreviewCard(
                                file = file,
                                onClick = { onFavoriteClick(file) },
                            )
                        }
                    }
                }
            }
 
            if (downloadedEntries.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = stringResource(R.string.home_section_downloads),
                        subtitle = stringResource(R.string.home_section_downloads_desc),
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(downloadedEntries.take(10), key = { "home-download-${it.file.relativePath}" }) { entry ->
                            DownloadedPreviewCard(
                                entry = entry,
                                onClick = { onDownloadClick(entry) },
                            )
                        }
                    }
                }
            }
 
            uiState.errorMessage?.let { message ->
                item {
                    StatusNoticeCard(message = message)
                }
            }
        }
    }
}
 
