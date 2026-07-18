package com.juziss.localmediahub.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.juziss.localmediahub.data.Bookmark
import com.juziss.localmediahub.ui.component.reader.ReaderSettingsSheet
import com.juziss.localmediahub.ui.component.reader.ReaderThemeWrapper
import com.juziss.localmediahub.viewmodel.TextReaderViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Compose UI for [com.juziss.localmediahub.TextReaderActivity].
 *
 * Task 6 (text-reader C-phase) wires together:
 * - TopAppBar actions: Aa (settings sheet), Play/Pause (auto-scroll), Menu (TOC).
 * - [ReaderThemeWrapper] around the body so the reading area honors the user's
 *   day/night/eye-care preference without affecting the App-level theme.
 * - Paragraph font size + line-height from [ReaderSettings].
 * - Long-press paragraph → [DropdownMenu] with "添加书签" / "复制段落".
 * - TOC drawer with two tabs: 目录 (chapters) and 书签 (bookmarks).
 * - Auto-scroll loop + FLAG_KEEP_SCREEN_ON while auto-scrolling.
 * - Throttled (1s) scroll-position persistence via [snapshotFlow] + [debounce].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TextReaderScreen(viewModel: TextReaderViewModel, onBack: () -> Unit) {
    val book by viewModel.book.collectAsState()
    val blocks by viewModel.chapterBlocks.collectAsState()
    val idx by viewModel.currentIndex.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val settings by viewModel.readerSettings.collectAsState()
    val isAutoScrolling by viewModel.isAutoScrolling.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val bookmarkToast by viewModel.bookmarkToast.collectAsState()
    val chromeVisible by viewModel.chromeVisible.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var tocTab by remember { mutableStateOf(0) } // 0 = 目录, 1 = 书签

    // Reload bookmarks whenever the open book changes.
    LaunchedEffect(book?.path) {
        book?.path?.let { viewModel.loadBookmarksFor(it) }
    }

    // Surface bookmark-add feedback (e.g. "已存在书签" on duplicate) as a
    // Toast, then clear the one-shot state so recomposition doesn't re-fire.
    LaunchedEffect(bookmarkToast) {
        bookmarkToast?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.consumeBookmarkToast()
        }
    }

    // Auto-scroll loop — runs in the UI layer so LazyListState stays here.
    LaunchedEffect(isAutoScrolling, settings.autoScrollSpeed) {
        if (isAutoScrolling) {
            val pxPerFrame = settings.autoScrollSpeed * 0.5f
            while (isActive) {
                listState.scrollBy(pxPerFrame)
                delay(16)
            }
        }
    }

    // Stop auto-scroll when the user manually scrolls.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling && viewModel.isAutoScrolling.value) {
                    viewModel.stopAutoScroll()
                }
            }
    }

    // Keep the screen on while auto-scrolling so the reading isn't interrupted.
    DisposableEffect(isAutoScrolling) {
        val window = (context as? android.app.Activity)?.window
        if (isAutoScrolling && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Throttled progress save: fires ~1s after the reading position stops moving.
    LaunchedEffect(listState, idx) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .debounce(1000)
            .collect { (itemIdx, offset) ->
                viewModel.persistScrollProgress(itemIdx, offset)
            }
    }

    ReaderThemeWrapper(theme = settings.theme) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    PrimaryTabRow(selectedTabIndex = tocTab) {
                        Tab(
                            selected = tocTab == 0,
                            onClick = { tocTab = 0 },
                            text = { Text("目录") },
                        )
                        Tab(
                            selected = tocTab == 1,
                            onClick = { tocTab = 1 },
                            text = { Text("书签 (${bookmarks.size})") },
                        )
                    }
                    when (tocTab) {
                        0 -> LazyColumn {
                            itemsIndexed(book?.chapters ?: emptyList()) { _, ch ->
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
                        1 -> LazyColumn {
                            itemsIndexed(bookmarks) { _, bm ->
                                BookmarkRow(
                                    bookmark = bm,
                                    chapterTitle = book?.chapters?.getOrNull(bm.chapterIndex)?.title ?: "—",
                                    onClick = {
                                        viewModel.loadChapter(bm.chapterIndex)
                                        scope.launch {
                                            drawerState.close()
                                            // Wait for the new chapter to render, then jump.
                                            delay(200)
                                            listState.scrollToItem(bm.paragraphIndex.coerceAtLeast(0))
                                        }
                                    },
                                    onDelete = { viewModel.deleteBookmark(bm) },
                                )
                            }
                            if (bookmarks.isEmpty()) {
                                item {
                                    Text(
                                        "暂无书签，长按段落添加",
                                        modifier = Modifier.padding(16.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) {
            Scaffold(
                topBar = {
                    // Phase 5: 沉浸模式 — fadeIn/fadeOut 让栏平滑显隐；不显时
                    // AnimatedVisibility 从组合中移除栏（含其点击区），让正文
                    // 上下区域可被读取。
                    AnimatedVisibility(visible = chromeVisible, enter = fadeIn(), exit = fadeOut()) {
                        TopAppBar(
                            title = { Text(book?.chapters?.getOrNull(idx)?.title ?: book?.title ?: "") },
                            navigationIcon = {
                                IconButton(onClick = onBack) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                }
                            },
                            actions = {
                                IconButton(onClick = { showSettings = true }) {
                                    Text("Aa")
                                }
                                IconButton(onClick = { viewModel.toggleAutoScroll() }) {
                                    if (isAutoScrolling) {
                                        Text("‖")
                                    } else {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = "自动滚动")
                                    }
                                }
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Filled.Menu, contentDescription = "目录")
                                }
                            },
                        )
                    }
                },
                bottomBar = {
                    AnimatedVisibility(visible = chromeVisible, enter = fadeIn(), exit = fadeOut()) {
                        BottomAppBar {
                            Text(
                                "第 ${idx + 1} / ${book?.chapters?.size ?: 0} 章" +
                                    if (isAutoScrolling) " · 速:${settings.autoScrollSpeed}" else "",
                                modifier = Modifier.padding(16.dp),
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { viewModel.prevChapter() }) { Text("上一章") }
                            TextButton(onClick = { viewModel.nextChapter() }) { Text("下一章") }
                        }
                    }
                },
            ) { padding ->
                // Phase 5: 中区域点击切换沉浸栏；左/右 20% 翻章。pointerInput +
                // detectTapGestures(onTap) 只消费单击事件，不消费拖动 —— LazyColumn
                // 在此 Box 内部的滚动继续工作（手势分发先到 LazyColumn 的滚动
                // pointerInput，onTap 在没有 drag 时才触发）。
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val width = size.width.toFloat().coerceAtLeast(1f)
                                val ratio = offset.x / width
                                when {
                                    ratio < 0.20f -> viewModel.prevChapter()
                                    ratio > 0.80f -> viewModel.nextChapter()
                                    else -> viewModel.toggleChrome()
                                }
                            }
                        }
                ) {
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
                        // Phase 3: clamp content width so novel text stays in a
                        // readable column even on wide/tablet screens. We honor
                        // settings.contentWidthDp but never exceed screenWidthDp-32.
                        val configuration = LocalConfiguration.current
                        val maxContentDp = min(720, configuration.screenWidthDp - 32).dp
                        val contentDp = settings.contentWidthDp.dp.coerceAtMost(maxContentDp)

                        // Phase 6: chapter content wrapped in AnimatedContent so
                        // switching chapters fades the body in (120ms) over a
                        // 0ms fade-out. chapterKey changes whenever the block
                        // list identity changes, which retriggers the animation.
                        val chapterKey = blocks.hashCode()
                        AnimatedContent(
                            targetState = chapterKey,
                            transitionSpec = {
                                fadeIn(tween(120)) togetherWith fadeOut(tween(0))
                            },
                            label = "chapterTransition",
                        ) { _ ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.width(contentDp),
                                    contentPadding = PaddingValues(vertical = 16.dp),
                                ) {
                                    // item 0: 章节大标题（居中 serif + 40dp 装饰线）
                                    item {
                                        val chapterTitle = book?.chapters?.getOrNull(idx)?.title ?: ""
                                        Text(
                                            text = chapterTitle,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = (settings.fontSizeSp + 6).sp,
                                                fontFamily = FontFamily.Serif,
                                                textAlign = TextAlign.Center,
                                            ),
                                            color = MaterialTheme.colorScheme.onBackground,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 32.dp, bottom = 24.dp),
                                        )
                                        HorizontalDivider(
                                            modifier = Modifier
                                                .width(40.dp)
                                                .padding(bottom = 16.dp),
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }

                                    itemsIndexed(blocks) { blockIdx, block ->
                                        when (block.type) {
                                            "text" -> ParagraphItem(
                                                text = block.value ?: "",
                                                fontSizeSp = settings.fontSizeSp.sp,
                                                lineHeightSp = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
                                                fontFamily = settings.fontFamily.toFontFamily(),
                                                firstLineIndent = settings.firstLineIndent,
                                                paragraphGapEm = if (settings.paragraphSpacing) 1.6f else 1.2f,
                                                onAddBookmark = {
                                                    // Returns false for image/out-of-range blocks;
                                                    // duplicate feedback is delivered via bookmarkToast.
                                                    viewModel.addBookmarkFromParagraph(blockIdx)
                                                },
                                                onCopy = {
                                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    cm.setPrimaryClip(ClipData.newPlainText("paragraph", block.value ?: ""))
                                                },
                                            )
                                            "image" -> {
                                                if (block.src.isNullOrEmpty()) {
                                                    Text(
                                                        text = "[本图片无法显示]",
                                                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                                        style = MaterialTheme.typography.bodyMedium.copy(
                                                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        ),
                                                    )
                                                } else {
                                                    // Auth headers (Bearer token) are injected by
                                                    // LocalMediaHubApplication's Hilt-provided OkHttpClient
                                                    // via AuthInterceptor — no per-call setup needed.
                                                    AsyncImage(
                                                        model = block.src,
                                                        contentDescription = null,
                                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // item N: 章节末尾 ❖（点击下一章）
                                    item {
                                        Text(
                                            text = "❖",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 24.dp)
                                                .clickable { viewModel.nextChapter() },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        ReaderSettingsSheet(
            settings = settings,
            onChange = { viewModel.updateSettings(it) },
            onDismiss = { showSettings = false },
        )
    }
}

/**
 * One paragraph row with long-press → context [DropdownMenu] offering
 * "添加书签" / "复制段落". Phase 3 applies V2 typography (font family,
 * font size, line height, first-line indent, paragraph gap) driven by the
 * current [com.juziss.localmediahub.data.ReaderSettings].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ParagraphItem(
    text: String,
    fontSizeSp: TextUnit,
    lineHeightSp: TextUnit,
    fontFamily: FontFamily,
    firstLineIndent: Boolean,
    paragraphGapEm: Float,  // 1.2f or 1.6f
    onAddBookmark: () -> Unit,
    onCopy: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Column {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = (paragraphGapEm * 4).dp)  // 粗略：1em ≈ 4dp 段间距视觉
                .combinedClickable(
                    onClick = {},
                    onLongClick = { showMenu = true },
                ),
            style = LocalTextStyle.current.copy(
                fontSize = fontSizeSp,
                lineHeight = lineHeightSp,
                fontFamily = fontFamily,
                textIndent = if (firstLineIndent) TextIndent(firstLine = 2.em) else TextIndent.None,
            ),
        )
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("添加书签") },
                onClick = { onAddBookmark(); showMenu = false },
                leadingIcon = { Icon(Icons.Filled.Favorite, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("复制段落") },
                onClick = { onCopy(); showMenu = false },
            )
        }
    }
}

/**
 * One bookmark entry inside the 书签 tab. Tapping loads the chapter and
 * scrolls to the paragraph; the trailing icon deletes the bookmark.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    chapterTitle: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .combinedClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "$chapterTitle · ${bookmark.preview}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(
                "段落 #${bookmark.paragraphIndex}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "删除书签")
        }
    }
}
