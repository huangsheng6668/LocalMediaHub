package com.juziss.localmediahub.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.height
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
import com.juziss.localmediahub.data.ReadingMode
import com.juziss.localmediahub.data.ScrollModeChapter
import com.juziss.localmediahub.ui.component.reader.ReaderSettingsSheet
import com.juziss.localmediahub.ui.component.reader.ReaderThemeWrapper
import com.juziss.localmediahub.viewmodel.TextReaderViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Compose UI for the text reader screen.
 *
 * 分章模式（CHAPTER）：单章 LazyColumn，左右点击翻章，底栏显示章节进度。
 * 滚动模式（SCROLL）：多章 LazyColumn 连续滚动，接近末尾自动加载下一章，
 *   底栏显示全书进度。两种模式共用同一 listState，模式切换时重置。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TextReaderScreen(viewModel: TextReaderViewModel, onBack: () -> Unit) {
    val book by viewModel.book.collectAsState()
    val blocks by viewModel.chapterBlocks.collectAsState()
    val scrollChapters by viewModel.scrollChapters.collectAsState()
    val idx by viewModel.currentIndex.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isScrollLoadingMore by viewModel.isScrollLoadingMore.collectAsState()
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

    // 当前阅读模式
    val isScrollMode = settings.readingMode == ReadingMode.SCROLL

    // 书签列表随打开书变化时重新加载
    LaunchedEffect(book?.path) {
        book?.path?.let { viewModel.loadBookmarksFor(it) }
    }

    // 书签添加反馈 Toast，消费后清除
    LaunchedEffect(bookmarkToast) {
        bookmarkToast?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.consumeBookmarkToast()
        }
    }

    // 自动滚动循环（在 UI 层持有 LazyListState）
    LaunchedEffect(isAutoScrolling, settings.autoScrollSpeed) {
        if (isAutoScrolling) {
            val pxPerFrame = settings.autoScrollSpeed * 0.5f
            while (isActive) {
                listState.scrollBy(pxPerFrame)
                delay(16)
            }
        }
    }

    // 手动滑动时停止自动滚动
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling && viewModel.isAutoScrolling.value) {
                    viewModel.stopAutoScroll()
                }
            }
    }

    // 自动滚动时保持屏幕常亮
    DisposableEffect(isAutoScrolling) {
        val window = (context as? android.app.Activity)?.window
        if (isAutoScrolling && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 节流保存阅读进度（停止滚动 1s 后写入）
    LaunchedEffect(listState, idx) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .debounce(1000)
            .collect { (itemIdx, offset) ->
                viewModel.persistScrollProgress(itemIdx, offset)
            }
    }

    // ---------- 滚动模式：进入后同时向前和向后预加载 ----------
    // 向后预加载 3 章，向前预加载 2 章（支持向上滚动）
    LaunchedEffect(isScrollMode) {
        if (!isScrollMode) return@LaunchedEffect
        // 串行先加后续章
        viewModel.preloadScrollChapters(3)
        // 如果当前不是第一章，串行向前加载 2 章，加载完后补偿滚动位置
        val addedItems = viewModel.preloadPreviousScrollChapters(2)
        if (addedItems > 0) {
            // 向前插入了新 item，连同当前偏移一起向后跳以保持画面不跳动
            val current = listState.firstVisibleItemIndex
            listState.scrollToItem(
                index = (current + addedItems).coerceAtLeast(0),
                scrollOffset = listState.firstVisibleItemScrollOffset,
            )
        }
    }

    // ---------- 滚动模式：接近列表末尾时自动预加载下一章 ----------
    LaunchedEffect(listState, isScrollMode) {
        if (!isScrollMode) return@LaunchedEffect
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            // 距列表末尾还剩的 item 数
            totalItems - 1 - lastVisible
        }.collect { remaining ->
            if (remaining < 10) {
                viewModel.loadNextChapterForScroll()
            }
        }
    }

    // ---------- 滚动模式：接近列表顶部时动态加载前一章 ----------
    // 向前插入章节后用 scrollToItem 补偿位置，避免画面向上跳动
    LaunchedEffect(listState, isScrollMode) {
        if (!isScrollMode) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { firstIdx ->
                if (firstIdx < 10) {
                    val savedOffset = listState.firstVisibleItemScrollOffset
                    val addedItems = viewModel.loadPreviousChapterForScroll()
                    if (addedItems > 0) {
                        // 头部插入新 item，补偿滚动偏移
                        listState.scrollToItem(
                            index = firstIdx + addedItems,
                            scrollOffset = savedOffset,
                        )
                    }
                }
            }
    }

    // ---------- 滚动模式：根据当前可见项更新 currentIndex ----------
    // 每章在 LazyColumn 中占 (1 标题 + blocks.size 段落 + 1 分隔符) 个 item
    LaunchedEffect(listState, scrollChapters) {
        if (scrollChapters.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { firstIdx ->
                var offset = 0
                for (ch in scrollChapters) {
                    val itemsInChapter = ch.blocks.size + 2 // 标题 + blocks + 分隔符
                    if (firstIdx < offset + itemsInChapter) {
                        viewModel.updateCurrentIndex(ch.chapterIndex)
                        break
                    }
                    offset += itemsInChapter
                }
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
                                        scope.launch {
                                            drawerState.close()
                                            val targetIdx = ch.index
                                            if (isScrollMode) {
                                                val loadedCh = scrollChapters.find { it.chapterIndex == targetIdx }
                                                if (loadedCh != null) {
                                                    var itemOffset = 0
                                                    for (c in scrollChapters) {
                                                        if (c.chapterIndex == targetIdx) break
                                                        itemOffset += c.blocks.size + 2
                                                    }
                                                    viewModel.updateCurrentIndex(targetIdx)
                                                    listState.scrollToItem(itemOffset)
                                                } else {
                                                    val ok = viewModel.loadChapter(targetIdx, resetScroll = true)
                                                    if (ok) {
                                                        viewModel.preloadScrollChapters(3)
                                                        listState.scrollToItem(0)
                                                    }
                                                }
                                            } else {
                                                val ok = viewModel.loadChapter(targetIdx, resetScroll = true)
                                                if (ok) {
                                                    listState.scrollToItem(0)
                                                }
                                            }
                                        }
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
                                        scope.launch {
                                            drawerState.close()
                                            val targetIdx = bm.chapterIndex
                                            val paraIdx = bm.paragraphIndex.coerceAtLeast(0)
                                            if (isScrollMode) {
                                                val loadedCh = scrollChapters.find { it.chapterIndex == targetIdx }
                                                if (loadedCh != null) {
                                                    var itemOffset = 0
                                                    for (c in scrollChapters) {
                                                        if (c.chapterIndex == targetIdx) break
                                                        itemOffset += c.blocks.size + 2
                                                    }
                                                    viewModel.updateCurrentIndex(targetIdx)
                                                    listState.scrollToItem((itemOffset + 1 + paraIdx).coerceAtLeast(0))
                                                } else {
                                                    val ok = viewModel.loadChapter(targetIdx, resetScroll = true)
                                                    if (ok) {
                                                        viewModel.preloadScrollChapters(3)
                                                        listState.scrollToItem((1 + paraIdx).coerceAtLeast(0))
                                                    }
                                                }
                                            } else {
                                                if (idx != targetIdx) {
                                                    viewModel.loadChapter(targetIdx, resetScroll = true)
                                                }
                                                listState.scrollToItem((1 + paraIdx).coerceAtLeast(0))
                                            }
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
            // 沉浸模式下拦截后退按键，优先退出沉浸模式而非直接退出阅读界面
            val isImmersiveActive = settings.immersiveMode || !chromeVisible
            BackHandler(enabled = isImmersiveActive) {
                viewModel.exitImmersiveMode()
            }

            // 动态计算阅读进度
            val totalChaptersCount = book?.chapters?.size ?: 1
            // 分章模式：按本章 item 位置算章内进度
            val chapterPercent = remember(listState.firstVisibleItemIndex, listState.layoutInfo.totalItemsCount) {
                val totalItems = listState.layoutInfo.totalItemsCount
                if (totalItems <= 1) {
                    0
                } else {
                    ((listState.firstVisibleItemIndex.toFloat() / (totalItems - 1).coerceAtLeast(1)) * 100).toInt().coerceIn(0, 100)
                }
            }
            // 滚动模式：按全书 item 位置算全书进度
            val overallPercent = remember(idx, chapterPercent, totalChaptersCount) {
                (((idx.toFloat() + (chapterPercent / 100f)) / totalChaptersCount.coerceAtLeast(1)) * 100).toInt().coerceIn(0, 100)
            }

            Scaffold(
                topBar = {
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
                        Column {
                            LinearProgressIndicator(
                                progress = { if (isScrollMode) overallPercent / 100f else chapterPercent / 100f },
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            BottomAppBar {
                                if (isScrollMode) {
                                    Text(
                                        "全书进度 ${overallPercent}% · 第 ${idx + 1} / ${totalChaptersCount} 章" +
                                            if (isAutoScrolling) " · 速:${settings.autoScrollSpeed}" else "",
                                        modifier = Modifier.padding(16.dp),
                                    )
                                } else {
                                    Text(
                                        "第 ${idx + 1} / ${totalChaptersCount} 章 (${chapterPercent}%)" +
                                            if (isAutoScrolling) " · 速:${settings.autoScrollSpeed}" else "",
                                        modifier = Modifier.padding(16.dp),
                                    )
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = { viewModel.prevChapter(); scope.launch { listState.scrollToItem(0) } }) { Text("上一章") }
                                    TextButton(onClick = { viewModel.nextChapter(); scope.launch { listState.scrollToItem(0) } }) { Text("下一章") }
                                }
                            }
                        }
                    }
                },
            ) { padding ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .pointerInput(isScrollMode) {
                            detectTapGestures { offset ->
                                if (!isScrollMode) {
                                    val width = size.width.toFloat().coerceAtLeast(1f)
                                    val ratio = offset.x / width
                                    when {
                                        ratio < 0.20f -> viewModel.prevChapter()
                                        ratio > 0.80f -> viewModel.nextChapter()
                                        else -> viewModel.toggleChrome()
                                    }
                                } else {
                                    viewModel.toggleChrome()
                                }
                            }
                        }
                ) {
                    // 沉浸模式下底部微光进度条
                    if (!chromeVisible) {
                        LinearProgressIndicator(
                            progress = { if (isScrollMode) overallPercent / 100f else chapterPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .align(Alignment.BottomCenter),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            trackColor = Color.Transparent,
                        )
                    }
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
                        // 宽屏下限制内容列宽，避免行过长影响阅读体验
                        val configuration = LocalConfiguration.current
                        val maxContentDp = min(720, configuration.screenWidthDp - 32).dp
                        val contentDp = settings.contentWidthDp.dp.coerceAtMost(maxContentDp)

                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (isScrollMode) {
                                // ===== 全文滚动模式：多章连续 LazyColumn =====
                                ScrollModeContent(
                                    scrollChapters = scrollChapters,
                                    isScrollLoadingMore = isScrollLoadingMore,
                                    settings = settings,
                                    contentDp = contentDp,
                                    listState = listState,
                                    context = context,
                                    viewModel = viewModel,
                                )
                            } else {
                                // ===== 分章模式：单章，切换时淡入淡出 =====
                                val chapterKey = blocks.hashCode()
                                AnimatedContent(
                                    targetState = chapterKey,
                                    transitionSpec = {
                                        fadeIn(tween(120)) togetherWith fadeOut(tween(0))
                                    },
                                    label = "chapterTransition",
                                ) { _ ->
                                    ChapterModeContent(
                                        blocks = blocks,
                                        idx = idx,
                                        book = book,
                                        settings = settings,
                                        contentDp = contentDp,
                                        listState = listState,
                                        context = context,
                                        viewModel = viewModel,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 设置面板显示期间强制 chrome/systemBars 可见；面板关闭后若仍处于沉浸模式则重新隐藏
    LaunchedEffect(showSettings) {
        if (showSettings) viewModel.showChrome() else viewModel.hideChrome()
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
 * 分章模式内容区：渲染单章的标题、段落与末尾跳转提示。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterModeContent(
    blocks: List<com.juziss.localmediahub.data.Block>,
    idx: Int,
    book: com.juziss.localmediahub.data.Book?,
    settings: com.juziss.localmediahub.data.ReaderSettings,
    contentDp: androidx.compose.ui.unit.Dp,
    listState: androidx.compose.foundation.lazy.LazyListState,
    context: Context,
    viewModel: TextReaderViewModel,
) {
    val scope = rememberCoroutineScope()
    LazyColumn(
        state = listState,
        modifier = Modifier.width(contentDp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        // item 0: 章节大标题
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
            BlockItem(
                block = block,
                blockIdx = blockIdx,
                settings = settings,
                readingMode = com.juziss.localmediahub.data.ReadingMode.CHAPTER,
                chapterIndex = idx,
                context = context,
                viewModel = viewModel,
            )
        }

        // 末尾 ❖（点击跳下一章）
        item {
            Text(
                text = "❖",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp)
                    .clickable { viewModel.nextChapter(); scope.launch { listState.scrollToItem(0) } },
            )
        }
    }
}

/**
 * 滚动模式内容区：将所有已加载章节拼接成一个连续 LazyColumn，
 * 每章包含标题、段落与章间分隔符。列表末尾附加加载指示器（当仍在加载时）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScrollModeContent(
    scrollChapters: List<ScrollModeChapter>,
    isScrollLoadingMore: Boolean,
    settings: com.juziss.localmediahub.data.ReaderSettings,
    contentDp: androidx.compose.ui.unit.Dp,
    listState: androidx.compose.foundation.lazy.LazyListState,
    context: Context,
    viewModel: TextReaderViewModel,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.width(contentDp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        scrollChapters.forEach { chapter ->
            // 章节标题
            item(key = "title_${chapter.chapterIndex}") {
                Text(
                    text = chapter.title,
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

            // 章节内容块
            itemsIndexed(
                items = chapter.blocks,
                key = { blockIdx, _ -> "block_${chapter.chapterIndex}_$blockIdx" },
            ) { blockIdx, block ->
                BlockItem(
                    block = block,
                    blockIdx = blockIdx,
                    settings = settings,
                    readingMode = ReadingMode.SCROLL,
                    chapterIndex = chapter.chapterIndex,
                    context = context,
                    viewModel = viewModel,
                )
            }

            // 章间分隔符
            item(key = "sep_${chapter.chapterIndex}") {
                Text(
                    text = "— — —",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                )
            }
        }

        // 加载更多指示器
        if (isScrollLoadingMore) {
            item(key = "loading_more") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

/**
 * 通用内容块渲染：文本段落（长按书签/复制）或图片。
 * 分章模式下段落支持左右点击翻章；滚动模式下点击触发 chrome 切换。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BlockItem(
    block: com.juziss.localmediahub.data.Block,
    blockIdx: Int,
    settings: com.juziss.localmediahub.data.ReaderSettings,
    readingMode: ReadingMode,
    chapterIndex: Int,
    context: Context,
    viewModel: TextReaderViewModel,
) {
    when (block.type) {
        "text" -> ParagraphItem(
            text = block.value ?: "",
            fontSizeSp = settings.fontSizeSp.sp,
            lineHeightSp = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
            fontFamily = settings.fontFamily.toFontFamily(),
            firstLineIndent = settings.firstLineIndent,
            paragraphGapEm = if (settings.paragraphSpacing) 1.6f else 1.2f,
            readingMode = readingMode,
            onAddBookmark = {
                viewModel.addBookmarkFromParagraph(blockIdx, chapterIndex)
            },
            onCopy = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("paragraph", block.value ?: ""))
            },
            onPrevChapter = { viewModel.prevChapter() },
            onNextChapter = { viewModel.nextChapter() },
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
                // Auth headers 由 OkHttpClient（AuthInterceptor）自动注入
                AsyncImage(
                    model = block.src,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * 一段文字，长按弹出"添加书签 / 复制段落"菜单。
 * 分章模式下点击左/右区域（占屏幕宽度 20%）触发翻章；其余区域或滚动模式下触发 chrome 切换。
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
    readingMode: com.juziss.localmediahub.data.ReadingMode = com.juziss.localmediahub.data.ReadingMode.CHAPTER,
    onAddBookmark: () -> Unit,
    onCopy: () -> Unit,
    onPrevChapter: () -> Unit = {},
    onNextChapter: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }

    Column {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = (paragraphGapEm * 4).dp)
                .pointerInput(readingMode) {
                    detectTapGestures(
                        onTap = { offset ->
                            if (readingMode == com.juziss.localmediahub.data.ReadingMode.CHAPTER) {
                                val ratio = offset.x / screenWidthPx.coerceAtLeast(1f)
                                if (ratio < 0.20f) {
                                    onPrevChapter()
                                } else if (ratio > 0.80f) {
                                    onNextChapter()
                                }
                            }
                        },
                        onLongPress = { showMenu = true }
                    )
                },
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
 * 书签列表条目。点击加载对应章节并跳转至段落；末尾图标删除书签。
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
