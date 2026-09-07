package com.juziss.localmediahub.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.juziss.localmediahub.R
import com.juziss.localmediahub.TextReaderActivity
import com.juziss.localmediahub.viewmodel.BookshelfFilter
import com.juziss.localmediahub.viewmodel.BookshelfItem
import com.juziss.localmediahub.viewmodel.BookshelfViewModel
import com.juziss.localmediahub.viewmodel.formatReadDuration
import java.util.Locale

/**
 * 独立书架页（spec 2026-09-08-reading-stats-and-android-bookshelf）：
 * 全量最近书籍网格 + 在读/读完/收藏筛选 + 今日/本周阅读时长统计条。
 * 数据离线降级为纯本地进度书架。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    onBack: () -> Unit,
    viewModel: BookshelfViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_section_bookshelf), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (uiState.todaySeconds > 0 || uiState.weekSeconds > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.bookshelf_stats_today, formatReadDuration(uiState.todaySeconds))) },
                    )
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.bookshelf_stats_week, formatReadDuration(uiState.weekSeconds))) },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BookshelfFilter.entries.forEach { f ->
                    FilterChip(
                        selected = uiState.filter == f,
                        onClick = { viewModel.setFilter(f) },
                        label = { Text(filterLabel(f)) },
                    )
                }
            }
            if (uiState.items.isEmpty() && !uiState.isLoading) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(R.string.bookshelf_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.bookshelf_empty_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(uiState.items, key = { it.path }) { item ->
                        BookshelfGridTile(item = item, onClick = {
                            context.startActivity(TextReaderActivity.newIntent(context, item.path))
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun filterLabel(f: BookshelfFilter): String = when (f) {
    BookshelfFilter.ALL -> stringResource(R.string.bookshelf_filter_all)
    BookshelfFilter.READING -> stringResource(R.string.bookshelf_filter_reading)
    BookshelfFilter.FINISHED -> stringResource(R.string.bookshelf_filter_finished)
    BookshelfFilter.FAVORITES -> stringResource(R.string.bookshelf_filter_favorites)
}

@Composable
private fun BookshelfGridTile(item: BookshelfItem, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.format.uppercase(Locale.ROOT),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                if (item.status == "finished") {
                    Text(
                        stringResource(R.string.bookshelf_status_finished),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val dur = formatReadDuration(item.readSeconds)
            val chapterText = stringResource(R.string.home_bookshelf_chapter, item.chapterIndex + 1) +
                if (dur.isNotEmpty()) " · ${stringResource(R.string.bookshelf_read_prefix, dur)}" else ""
            Text(
                chapterText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
