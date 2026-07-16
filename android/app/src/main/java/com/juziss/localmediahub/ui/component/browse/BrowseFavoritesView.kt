package com.juziss.localmediahub.ui.component.browse

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juziss.localmediahub.R
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.ui.component.FavoritesContent

@Composable
internal fun BrowseFavoritesView(
    favoriteFiles: List<MediaFile>,
    onVideoClick: (MediaFile) -> Unit,
    onImageClick: (MediaFile, List<MediaFile>) -> Unit,
    onTextClick: (MediaFile) -> Unit,
    onToggleFavorite: (MediaFile) -> Unit,
    isFavorite: (String) -> Boolean,
    getFavoriteThumbnailUrl: (MediaFile) -> String,
    onFileLongClick: (MediaFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        BrowseSummaryCard(
            icon = painterResource(R.drawable.ic_favorite_border_outline),
            title = stringResource(R.string.browse_favorites),
            message = stringResource(R.string.browse_fav_card_desc),
            meta = "共 ${favoriteFiles.size} 个收藏",
            badge = null,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        FavoritesContent(
            favoriteFiles = favoriteFiles,
            onVideoClick = onVideoClick,
            onImageClick = onImageClick,
            onTextClick = onTextClick,
            onToggleFavorite = onToggleFavorite,
            isFavorite = isFavorite,
            getThumbnailUrl = getFavoriteThumbnailUrl,
            onFileLongClick = onFileLongClick,
            modifier = Modifier.weight(1f),
        )
    }
}
