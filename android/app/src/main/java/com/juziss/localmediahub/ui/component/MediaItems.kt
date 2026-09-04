package com.juziss.localmediahub.ui.component
 
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.filled.PlayArrow
import coil3.compose.AsyncImage
import com.juziss.localmediahub.R
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.ReadingStatus
import kotlin.math.roundToInt
 
@Composable
internal fun FavoriteToggleIcon(
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(32.dp),
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (isFavorite) stringResource(R.string.media_unfavorite) else stringResource(R.string.media_favorite),
            tint = if (isFavorite) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
    }
}
 
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderCard(
    folder: Folder,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = 2.dp,
            pressedElevation = 6.dp,
            hoveredElevation = 4.dp
        )
    ) {
        Box {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_folder),
                            contentDescription = stringResource(R.string.folder),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.folder),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.media_open_folder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onToggleFavorite != null) {
                FavoriteToggleIcon(
                    isFavorite = isFavorite,
                    onClick = onToggleFavorite,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                )
            }
        }
    }
}
 
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VideoCard(
    file: MediaFile,
    thumbnailUrl: String,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    isSelected: Boolean = false,
    /**
     * Task 5 搂1.3: when false (BLE degraded mode is active), the card renders
     * greyed (alpha 0.4) and its click is routed to [onDisabledClick] instead
     * of [onClick]. The screen wires [onDisabledClick] to a Snackbar showing
     * [R.string.ble_video_disabled_message]. Defaults to true so all existing
     * call sites keep the pre-Task-5 behavior.
     */
    enabled: Boolean = true,
    onDisabledClick: () -> Unit = {},
) {
    // Spec message is read unconditionally so the string lookup is stable
    // across recompositions; it's only attached to the semantics tree when
    // the card is actually disabled (avoids noise on enabled cards).
    val disabledMessage = stringResource(R.string.ble_video_disabled_message)
    val cardClick: () -> Unit = if (enabled) onClick else onDisabledClick
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            // Grey-out the entire card (thumbnail + text + favorite toggle)
            // when BLE degraded mode is active 鈥?spec 搂1.3.
            .alpha(if (enabled) 1f else 0.4f)
            .combinedClickable(
                onClick = cardClick,
                onLongClick = onLongClick,
            )
            .then(
                if (enabled) Modifier
                else Modifier.semantics {
                    // Doubles as the screen-reader announcement AND the
                    // Compose-test hook (the degraded test finds the card by
                    // this contentDescription and performs click on it).
                    contentDescription = disabledMessage
                }
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = 2.dp,
            pressedElevation = 6.dp
        ),
    ) {
        Box {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(124.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.ic_movie),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    AsyncImage(
                        model = thumbnailUrl,
                        contentDescription = file.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = CircleShape,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(R.string.play),
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.media_video_ext, file.extension.uppercase()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FavoriteToggleIcon(
                isFavorite = isFavorite,
                onClick = onToggleFavorite,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            )
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                )
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(24.dp)
                        .align(Alignment.TopStart)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Done,
                            contentDescription = stringResource(R.string.a11y_selected),
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
 
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ImageCard(
    file: MediaFile,
    thumbnailUrl: String,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    isSelected: Boolean = false,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = 2.dp,
            pressedElevation = 6.dp
        ),
    ) {
        Box {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(124.dp)
                        // Neutral fallback block: a failed thumbnail shows
                        // this tint instead of a blank card area.
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                ) {
                    AsyncImage(
                        model = thumbnailUrl,
                        contentDescription = file.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.media_image_ext, file.extension.uppercase()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FavoriteToggleIcon(
                isFavorite = isFavorite,
                onClick = onToggleFavorite,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            )
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                )
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(24.dp)
                        .align(Alignment.TopStart)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Done,
                            contentDescription = stringResource(R.string.a11y_selected),
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Card for mediaType="text" files. Renders a document icon plus the file name
 * and an optional "鏆備笉鏀寔" badge when the format is one the reader cannot
 * open (e.g. .mobi, .azw3). The favorite toggle and selection overlay mirror
 * VideoCard / ImageCard so existing multi-select flows work unchanged.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TextCard(
    file: MediaFile,
    isUnsupported: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    isSelected: Boolean = false,
    readingStatus: ReadingStatus? = null,
    percent: Double = 0.0,
) {
    val containerColor = if (isUnsupported) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.surface
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = 2.dp,
            pressedElevation = 6.dp,
        ),
    ) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_text_file),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = if (isUnsupported) MaterialTheme.colorScheme.outline
                           else MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = file.extension.uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (readingStatus == ReadingStatus.FINISHED) {
                    StatusBadge(
                        text = stringResource(R.string.reading_badge_finished),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    )
                } else if (readingStatus == ReadingStatus.READING) {
                    StatusBadge(
                        text = if (percent > 0) stringResource(R.string.reading_badge_reading, percent.roundToInt())
                               else stringResource(R.string.reading_badge_reading_plain),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    )
                }
                if (isUnsupported) {
                    Surface(
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = "暂不支持",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.surface,
                        )
                    }
                }
            }
            FavoriteToggleIcon(
                isFavorite = isFavorite,
                onClick = onToggleFavorite,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            )
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                )
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(24.dp)
                        .align(Alignment.TopStart),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Done,
                            contentDescription = stringResource(R.string.a11y_selected),
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, containerColor: Color) {
    Surface(color = containerColor, shape = RoundedCornerShape(999.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
