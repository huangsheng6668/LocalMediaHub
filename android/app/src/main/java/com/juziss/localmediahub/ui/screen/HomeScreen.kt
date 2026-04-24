package com.juziss.localmediahub.ui.screen

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.juziss.localmediahub.data.LastBrowseLocation
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.PlaybackProgressEntry
import com.juziss.localmediahub.data.RecentMediaEntry
import com.juziss.localmediahub.data.Tag
import com.juziss.localmediahub.viewmodel.CollectionSummary
import com.juziss.localmediahub.viewmodel.HomeUiState
import com.juziss.localmediahub.viewmodel.HomeViewModel
import com.juziss.localmediahub.viewmodel.LibrarySummary

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    viewModel: HomeViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("LocalMediaHub")
                        Text(
                            text = "Your private media hub",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onDisconnect) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Disconnect",
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
                        text = "Refreshing your media hub",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Pulling libraries, collections, and recent activity.",
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
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item {
                HeroCard(
                    uiState = uiState,
                    onResumeBrowse = onResumeBrowse,
                    onOpenFavorites = onOpenFavorites,
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
                        title = "Libraries",
                        subtitle = "${uiState.libraries.size} server roots ready to browse",
                    )
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(uiState.libraries, key = { it.path }) { library ->
                            LibraryCard(library = library, onClick = { onOpenLibrary(library) })
                        }
                    }
                }
            }

            if (uiState.collections.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Collections",
                        subtitle = "Jump into your tagged groups without drilling through folders",
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
                        title = "Continue Watching",
                        subtitle = "Pick up unfinished videos from the exact timestamp you left",
                    )
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(uiState.continueWatching, key = { "${it.file.relativePath}-${it.updatedAt}" }) { entry ->
                            ContinueWatchingCard(
                                entry = entry,
                                onClick = { onContinueWatching(entry) },
                            )
                        }
                    }
                }
            }

            if (uiState.recentMedia.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Recent",
                        subtitle = "Fast re-entry to the media you opened most recently",
                    )
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        title = "Favorites",
                        subtitle = "Pinned files that deserve one-tap access",
                    )
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(uiState.favoriteFiles, key = { it.relativePath }) { file ->
                            FavoritePreviewCard(
                                file = file,
                                onClick = { onFavoriteClick(file) },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroCard(
    uiState: HomeUiState,
    onResumeBrowse: (LastBrowseLocation) -> Unit,
    onOpenFavorites: () -> Unit,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Back to your media, without the drive-letter hunt.",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (uiState.serverLabel.isNotBlank()) {
                    "Connected to ${condenseServerLabel(uiState.serverLabel)}. Everything below is ready for a quick jump."
                } else {
                    "Your phone is ready to reconnect with LocalMediaHub and resume where you left off."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HeroMetric(label = "Libraries", value = uiState.libraries.size.toString())
                HeroMetric(label = "Collections", value = uiState.collections.size.toString())
                HeroMetric(label = "Continue", value = uiState.continueWatching.size.toString())
                HeroMetric(label = "Recent", value = uiState.recentMedia.size.toString())
            }

            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                shape = MaterialTheme.shapes.large,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Current connection",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = uiState.serverLabel.ifBlank { "Waiting for your next connection" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            uiState.lastBrowseLocation?.let { location ->
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Filled.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Last location",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = location.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (location.isSystemBrowse) "Device path" else "Library browse",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                uiState.lastBrowseLocation?.let { location ->
                    FilledTonalButton(onClick = { onResumeBrowse(location) }) {
                        Icon(Icons.Filled.History, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Resume ${location.title}")
                    }
                }
                OutlinedButton(onClick = onOpenFavorites) {
                    Icon(Icons.Filled.Favorite, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Favorites")
                }
            }
        }
    }
}

@Composable
private fun HeroMetric(
    label: String,
    value: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LibraryCard(
    library: LibrarySummary,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.width(232.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Library root",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = library.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = library.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Open library",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun CollectionChip(
    collection: CollectionSummary,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = { Text("${collection.tag.name} · ${collection.itemCount}") },
        leadingIcon = {
            Icon(
                Icons.Filled.Bookmarks,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

@Composable
private fun ContinueWatchingCard(
    entry: PlaybackProgressEntry,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.width(232.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = "Continue watching",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Text(
                text = entry.file.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatProgressLabel(entry.positionMs, entry.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progressFraction(entry.positionMs, entry.durationMs) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = if (entry.isSystemBrowse) "Resume from device path" else "Resume from library",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecentMediaCard(
    entry: RecentMediaEntry,
    getThumbnailUrl: (RecentMediaEntry) -> String,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.width(184.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column {
            if (entry.file.mediaType == "image") {
                AsyncImage(
                    model = getThumbnailUrl(entry),
                    contentDescription = entry.file.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(124.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(124.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = if (entry.file.mediaType == "image") "Image" else "Video",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = entry.file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (entry.isSystemBrowse) "From device path" else "From library",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FavoritePreviewCard(
    file: MediaFile,
    onClick: () -> Unit = {},
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.width(184.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Favorited",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = file.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = file.extension.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyHomeStateCard() {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "No libraries are available yet",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Expose at least one media root from the server, then refresh this page to start browsing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusNoticeCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Server sync needs attention",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun progressFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

private fun formatProgressLabel(positionMs: Long, durationMs: Long): String {
    return "${formatTime(positionMs)} / ${formatTime(durationMs)}"
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private fun condenseServerLabel(serverLabel: String): String {
    return serverLabel.removePrefix("http://").removePrefix("https://")
}
