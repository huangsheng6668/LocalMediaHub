package com.juziss.localmediahub.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.juziss.localmediahub.viewmodel.TextReaderViewModel
import kotlinx.coroutines.launch

/**
 * Compose UI for [com.juziss.localmediahub.TextReaderActivity].
 *
 * Layout:
 * - [ModalNavigationDrawer] for the table-of-contents sidebar (toggled from
 *   the TopAppBar actions slot).
 * - [Scaffold] with a [TopAppBar] (back + TOC) and a [BottomAppBar]
 *   showing `chapter / total` and prev/next buttons.
 * - Body is a [LazyColumn] of paragraphs obtained by splitting the current
 *   chapter text on `"\n\n"` and filtering blank paragraphs.
 *
 * Loading and error overlays are rendered centered on top of the body so the
 * previous chapter remains visible underneath (matches Read-it-later app UX).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextReaderScreen(viewModel: TextReaderViewModel, onBack: () -> Unit) {
    val book by viewModel.book.collectAsState()
    val text by viewModel.chapterText.collectAsState()
    val idx by viewModel.currentIndex.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "目录",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                LazyColumn {
                    items(book?.chapters ?: emptyList()) { ch ->
                        NavigationDrawerItem(
                            label = { Text(ch.title) },
                            selected = ch.index == idx,
                            onClick = {
                                viewModel.loadChapter(ch.index)
                                scope.launch { drawerState.close() }
                            },
                        )
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(book?.chapters?.getOrNull(idx)?.title ?: book?.title ?: "") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "目录")
                        }
                    },
                )
            },
            bottomBar = {
                BottomAppBar {
                    Text(
                        "第 ${idx + 1} / ${book?.chapters?.size ?: 0} 章",
                        modifier = Modifier.padding(16.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { viewModel.prevChapter() }) { Text("上一章") }
                    TextButton(onClick = { viewModel.nextChapter() }) { Text("下一章") }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                error?.let {
                    Text(
                        it,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (error == null && !isLoading) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(vertical = 16.dp),
                    ) {
                        items(text.split("\n\n").filter { it.isNotBlank() }) { para ->
                            Text(
                                para,
                                modifier = Modifier.padding(vertical = 6.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}
