package com.juziss.localmediahub.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalDensity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import com.juziss.localmediahub.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Button
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.juziss.localmediahub.data.PageTurnStyle
import com.juziss.localmediahub.data.ReaderListLayout
import com.juziss.localmediahub.data.ReadingMode
import com.juziss.localmediahub.data.ScrollModeChapter
import com.juziss.localmediahub.ui.component.reader.ReaderScrollbar
import com.juziss.localmediahub.ui.component.ScrollFabGroup
import com.juziss.localmediahub.ui.component.calculateScrollFabVisibility
import com.juziss.localmediahub.ui.component.reader.ReaderSettingsSheet
import com.juziss.localmediahub.ui.component.reader.PageTurnController
import com.juziss.localmediahub.ui.component.reader.PageTurnDirection
import com.juziss.localmediahub.ui.component.reader.PageTurnSimulator
import com.juziss.localmediahub.ui.component.reader.ReaderThemeWrapper
import com.juziss.localmediahub.ui.component.reader.DragOutcome
import com.juziss.localmediahub.ui.component.reader.shouldDragTakeOver
import com.juziss.localmediahub.ui.component.reader.resolveDragOutcome
import com.juziss.localmediahub.ui.component.reader.toCustomReaderColors
import com.juziss.localmediahub.ble.BleConnState
import com.juziss.localmediahub.viewmodel.BleSettingsViewModel
import com.juziss.localmediahub.viewmodel.TextReaderViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Compose UI for the text reader screen.
 *
 * 分章模式（CHAPTER）：单章 LazyColumn，左右点击翻章，底栏显示章节进度。
 * 滚动模式（SCROLL）：多章 LazyColumn 连续滚动，接近末尾自动加载下一章，
 *   底栏显示全书进度。两种模式共用同一 listState，模式切换时重置。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TextReaderScreen(
    viewModel: TextReaderViewModel,
    onBack: () -> Unit,
    bleViewModel: BleSettingsViewModel? = null,
) {
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
    @Suppress("unused")
    val isBleDegraded by viewModel.isBleDegraded.collectAsState()

    val context = LocalContext.current
    val isHiltAvailable = remember(context) {
        context is dagger.hilt.internal.GeneratedComponentManager<*> ||
        context is dagger.hilt.internal.GeneratedComponent ||
        (context as? android.app.Activity) is dagger.hilt.internal.GeneratedComponentManager<*> ||
        (context as? android.app.Activity) is dagger.hilt.internal.GeneratedComponent
    }

    val bleVm: BleSettingsViewModel? = when {
        bleViewModel != null -> bleViewModel
        isHiltAvailable -> androidx.hilt.navigation.compose.hiltViewModel()
        else -> null
    }

    val bleEnabled by bleVm?.bleEnabled?.collectAsState() ?: remember { mutableStateOf(false) }
    val bleConnState by bleVm?.connectionState?.collectAsState() ?: remember { mutableStateOf(BleConnState.DISABLED) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // ===== 分章模式翻页控制器（Task 10/11） =====
    val totalChaptersCount = book?.chapters?.size ?: 1
    val pageTurnController = remember(settings.readingMode) {
        PageTurnController(
            currentIdx = { idx },
            // 必须经委托属性 `book` 读取章节数：直接捕获上面的普通 val
            // totalChaptersCount 会把"控制器首次创建时"的值冻结在闭包里
            // （此时 book 通常尚未加载 → 恒为 1 → NEXT 边界检查永远拒绝，
            // 表现为"下一章按钮没反应"，而目录跳转绕过控制器所以正常）。
            chapterCount = { book?.chapters?.size ?: 1 },
        )
    }

    // 动画层描述：顶层是旧章快照，随 progress 移出/卷走
    data class IncomingPage(
        val topBlocks: List<com.juziss.localmediahub.data.Block>,
        val topIdx: Int,
        val targetIdx: Int,
        val direction: PageTurnDirection,
    )
    val progress = remember { Animatable(1f) }
    var incoming by remember { mutableStateOf<IncomingPage?>(null) }

    fun turn(direction: PageTurnDirection) {
        scope.launch {
            val style = settings.pageTurnStyle
            val oldBlocks = blocks
            val oldIdx = idx // 必须在 loadChapter 前捕获，避免 overlay 标题显示为新章标题
            val target = pageTurnController.turnTo(direction) { t ->
                val ok = viewModel.loadChapter(t, resetScroll = true)
                if (ok) {
                    listState.scrollToItem(0, 0)
                }
                ok
            }
            if (target == null) return@launch
            listState.scrollToItem(0, 0)
            when (style) {
                PageTurnStyle.NONE -> Unit // blocks 已刷新，直接显示新章
                PageTurnStyle.COVER, PageTurnStyle.DRAG -> {
                    // DRAG 样式下点击热区/按钮/❖ 走 COVER 式滑动
                    incoming = IncomingPage(oldBlocks, oldIdx, target, direction)
                    progress.snapTo(0f)
                    progress.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
                    incoming = null
                }
                PageTurnStyle.SIMULATION -> {
                    incoming = IncomingPage(oldBlocks, oldIdx, target, direction)
                    progress.snapTo(0f)
                    progress.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
                    incoming = null
                }
            }
        }
    }

    var showSettings by remember { mutableStateOf(false) }
    var tocTab by remember { mutableStateOf(0) } // 0 = 目录, 1 = 书签

    // ===== Task 12: DRAG 手势状态 =====
    // 使用 remember 对象避免 drag 位移更新触发无意义 recomposition；
    // incoming/progress 由 Animatable 驱动顶层渲染。
    val density = LocalDensity.current
    val dragSlopPx = remember(density) { with(density) { 8.dp.toPx() } }
    val drag = remember {
        object {
            var takenOver = false
            var preloaded = false
            var totalDx = 0f
            var totalDy = 0f
            var direction: PageTurnDirection? = null
            var oldIdx = 0
            var oldBlocks: List<com.juziss.localmediahub.data.Block> = emptyList()
        }
    }

    // Task 3 / I2: BLE 降级徽标。驱动源是仓库的 bleDegradedEvents
    // SharedFlow —— 它在 *每个* BLE 兜底成功的章节上都发射一次（而非只在
    // 布尔翻转时），所以长时间 BLE 降级期间徽标会在每次新章节送达时重新
    // 显示并重新启动 3 秒自动消失计时器（spec §1.2 step 4 要求 per-delivery
    // 反馈）。旧的 LaunchedEffect(isBleDegraded) 只在布尔值变化时重跑，导致
    // 连续 BLE 章节期间徽标只在第一次出现，之后再也不显示 —— 已弃用。
    var showBleBadge by remember { mutableStateOf(false) }
    // token 守护：每次发射递增；只有最近这次发射的 token 仍有效时才隐藏
    // 徽标。这避免了"第一次发射的 3s 计时器到期后清掉第二次发射刚刚显示
    // 的徽标"的竞态（连续 BLE 章节间隔 < 3s 时会发生）。
    var badgeToken by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        viewModel.bleDegradedEvents.collect {
            val myToken = ++badgeToken
            showBleBadge = true
            delay(3000)
            // 只有当这次发射仍是最近的一次时才隐藏 —— 否则更新的发射已
            // 接管徽标生命周期，让它继续显示。
            if (myToken == badgeToken) {
                showBleBadge = false
            }
        }
    }

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

    // 节流保存阅读进度（停止滚动 1s 后写入）。把 listState 的全局 item 索引映射为
    // (章, 章内 block)，使记录与已加载章节数解耦、跨会话可恢复。
    // key 含 isScrollMode：手动切模式后（idx 不变）重启以捕获新分支，防陈旧闭包写错章/段。
    LaunchedEffect(listState, idx, isScrollMode) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .debounce(1000)
            .collect { (itemIdx, offset) ->
                if (isScrollMode) {
                    val (chIdx, blockIdx) = ReaderListLayout.scrollChapterBlock(
                        viewModel.scrollChapters.value, itemIdx,
                    )
                    if (chIdx >= 0) viewModel.persistScrollProgress(chIdx, blockIdx, offset)
                } else {
                    // 分章模式：item 0 = 章标题；滚到底首可见为 ❖ 时 coerce 到末段
                    val lastBlock = (blocks.size - 1).coerceAtLeast(0)
                    val blockIdx = (itemIdx - ReaderListLayout.CHAPTER_MODE_HEADER_ITEMS)
                        .coerceIn(0, lastBlock)
                    viewModel.persistScrollProgress(idx, blockIdx, offset)
                }
            }
    }

    // ---------- 滚动模式：进入后同时向前和向后预加载 ----------
    LaunchedEffect(isScrollMode) {
        if (!isScrollMode) return@LaunchedEffect
        // 开书就绪门闩：持久化 SCROLL 模式经 DataStore 先于书籍加载到达是常态
        // （设置读取 ~ms vs getBookInfo 网络往返）。此时 book 未加载、preload 全部
        // no-op、pendingResume 未写——必须先等首章就绪（loadChapter 会写入
        // _scrollChapters 单章列表），再预载与恢复；否则恢复被静默丢弃。
        viewModel.scrollChapters.first { it.isNotEmpty() }
        viewModel.preloadScrollChapters(3)
        val addedItems = viewModel.preloadPreviousScrollChapters(2)
        if (addedItems > 0) {
            val current = listState.firstVisibleItemIndex
            listState.scrollToItem(
                index = (current + addedItems).coerceAtLeast(0),
                scrollOffset = listState.firstVisibleItemScrollOffset,
            )
        }
        // 开书恢复：预载完成后定位到上次阅读段落（覆盖上方的补偿滚动）。
        // 必须读 viewModel.scrollChapters.value（StateFlow 即时值）——collectAsState
        // 的 State 更新可能滞后于挂起函数返回，读委托属性会拿到预载前的旧列表。
        val saved = viewModel.pendingResume.value
        if (saved != null) {
            val target = ReaderListLayout.scrollItemIndex(
                viewModel.scrollChapters.value, saved.chapterIndex, saved.blockIndex,
            )
            if (target >= 0 && (saved.blockIndex > 0 || saved.scrollOffsetPx > 0)) {
                listState.scrollToItem(target, saved.scrollOffsetPx.coerceAtLeast(0))
            }
            viewModel.consumePendingResume()
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

    ReaderThemeWrapper(
        theme = settings.theme,
        bgImageUri = settings.bgImageUri,
        customColors = settings.toCustomReaderColors(),
    ) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    PrimaryTabRow(selectedTabIndex = tocTab) {
                        Tab(
                            selected = tocTab == 0,
                            onClick = { tocTab = 0 },
                            text = { Text(stringResource(R.string.reader_tab_toc)) },
                        )
                        Tab(
                            selected = tocTab == 1,
                            onClick = { tocTab = 1 },
                            text = { Text(stringResource(R.string.reader_tab_bookmarks, bookmarks.size)) },
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
            // 分章模式：章内连续进度(亚 item 级)。
            // 用当前首个可见 item 的真实高度把 scrollOffset 归一化在 [0, 1] 范围内,
            // 再与 firstVisibleItemIndex 拼接得到连续位置。与 onSeek 中的
            // targetFloat ↔ scrollToItem(targetItem, offset) 构成完美的双向可逆映射。
            val chapterPercent = remember {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val totalItems = info.totalItemsCount
                    if (totalItems <= 1) {
                        0
                    } else {
                        val visItems = info.visibleItemsInfo
                        val firstItemH = visItems.firstOrNull()?.size?.toFloat() ?: 1f
                        val offsetFrac = (listState.firstVisibleItemScrollOffset.toFloat() / firstItemH.coerceAtLeast(1f)).coerceIn(0f, 1f)
                        val continuous = listState.firstVisibleItemIndex.toFloat() + offsetFrac
                        ((continuous / (totalItems - 1).coerceAtLeast(1)) * 100).toInt().coerceIn(0, 100)
                    }
                }
            }.value

            // 滚动模式：全书连续进度。按 listState.firstVisibleItemIndex 匹配当前所在的章节与章内位置。
            val overallPercent = remember(idx, chapterPercent, totalChaptersCount, scrollChapters, isScrollMode) {
                if (isScrollMode) {
                    if (scrollChapters.isEmpty() || totalChaptersCount <= 0) {
                        0
                    } else {
                        val info = listState.layoutInfo
                        val visItems = info.visibleItemsInfo
                        val firstIdx = listState.firstVisibleItemIndex

                        var accumulatedItems = 0
                        var currentChIdx = idx
                        var chFraction = 0f

                        for (ch in scrollChapters) {
                            val itemsInCh = ch.blocks.size + 2
                            if (firstIdx < accumulatedItems + itemsInCh) {
                                currentChIdx = ch.chapterIndex
                                val itemInCh = firstIdx - accumulatedItems
                                // 用当前可见 item 的实际高度计算亚 item 偏移
                                val firstItemH = visItems.firstOrNull()?.size?.toFloat() ?: 1f
                                val subItemFrac = (listState.firstVisibleItemScrollOffset.toFloat() / firstItemH.coerceAtLeast(1f)).coerceIn(0f, 1f)
                                chFraction = (itemInCh.toFloat() + subItemFrac) / itemsInCh.coerceAtLeast(1)
                                break
                            }
                            accumulatedItems += itemsInCh
                        }
                        val continuousBookPos = currentChIdx.toFloat() + chFraction.coerceIn(0f, 1f)
                        ((continuousBookPos / totalChaptersCount.coerceAtLeast(1)) * 100).toInt().coerceIn(0, 100)
                    }
                } else {
                    0
                }
            }

            val readerFabVisibility by remember(listState) {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val visibleItems = info.visibleItemsInfo
                    val totalItems = info.totalItemsCount
                    val firstIndex = listState.firstVisibleItemIndex
                    val firstOffset = listState.firstVisibleItemScrollOffset
                    val lastIndex = visibleItems.lastOrNull()?.index ?: 0
                    calculateScrollFabVisibility(firstIndex, firstOffset, lastIndex, totalItems, visibleItems.size)
                }
            }

            // 分章模式：章节变更时自动滚动到顶部,
            // 解决翻页与切章时停在旧滚动位置的问题。
            // 开书首帧改为执行 pendingResume 恢复定位（含 blockIndex=0 的章顶情形），
            // 而非无条件置顶——两个独立 effect 在同帧重启时后执行者胜出，
            // 无条件置顶会覆盖段内恢复，合并进同一 effect 从根上消除该竞争。
            LaunchedEffect(idx, isScrollMode) {
                if (!isScrollMode) {
                    val saved = viewModel.pendingResume.value
                    if (saved != null && idx == saved.chapterIndex && blocks.isNotEmpty()) {
                        val lastBlock = (blocks.size - 1).coerceAtLeast(0)
                        val blk = saved.blockIndex.coerceIn(0, lastBlock)
                        if (blk > 0 || saved.scrollOffsetPx > 0) {
                            listState.scrollToItem(
                                ReaderListLayout.CHAPTER_MODE_HEADER_ITEMS + blk,
                                saved.scrollOffsetPx.coerceAtLeast(0),
                            )
                        }
                        viewModel.consumePendingResume()
                    } else {
                        if (saved != null) viewModel.consumePendingResume()
                        listState.scrollToItem(0, 0)
                    }
                }
            }

            Scaffold(
                containerColor = if (!settings.bgImageUri.isNullOrBlank()) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background,
                topBar = {
                    AnimatedVisibility(visible = chromeVisible, enter = fadeIn(), exit = fadeOut()) {
                        TopAppBar(
                            title = { Text(book?.chapters?.getOrNull(idx)?.title ?: book?.title ?: "") },
                            navigationIcon = {
                                IconButton(onClick = onBack) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.reader_back))
                                }
                            },
                            actions = {
                                IconButton(onClick = { showSettings = true }) {
                                    Text(stringResource(R.string.reader_font_size))
                                }
                                IconButton(onClick = { viewModel.toggleAutoScroll() }) {
                                    if (isAutoScrolling) {
                                        Text("‖")
                                    } else {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.reader_autoscroll))
                                    }
                                }
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.reader_tab_toc))
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
                                        stringResource(
                                            R.string.reader_progress_scroll,
                                            overallPercent,
                                            idx + 1,
                                            totalChaptersCount,
                                        ) + if (isAutoScrolling) stringResource(R.string.reader_speed_suffix, settings.autoScrollSpeed) else "",
                                        modifier = Modifier.padding(16.dp),
                                    )
                                } else {
                                    Text(
                                        stringResource(
                                            R.string.reader_progress_chapter,
                                            idx + 1,
                                            totalChaptersCount,
                                            chapterPercent,
                                        ) + if (isAutoScrolling) stringResource(R.string.reader_speed_suffix, settings.autoScrollSpeed) else "",
                                        modifier = Modifier.padding(16.dp),
                                    )
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = { turn(PageTurnDirection.PREV) }) { Text(stringResource(R.string.reader_prev_chapter)) }
                                    TextButton(onClick = { turn(PageTurnDirection.NEXT) }) { Text(stringResource(R.string.reader_next_chapter)) }
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
                                        ratio < 0.20f -> turn(PageTurnDirection.PREV)
                                        ratio > 0.80f -> turn(PageTurnDirection.NEXT)
                                        else -> viewModel.toggleChrome()
                                    }
                                } else {
                                    viewModel.toggleChrome()
                                }
                            }
                        }
                        // Task 12 fix-round-2: DRAG 翻页手势。使用 detectHorizontalDragGestures
                        // 替代 detectDragGestures——此检测器仅响应水平拖动，垂直滑动穿透至子
                        // LazyColumn 正常滚动。shouldDragTakeOver 的水平主导检查由框架内置的
                        // 水平拖拽检测等效替代；resolveDragOutcome 仍用于松手 COMMIT/REVERT。
                        .pointerInput(settings.pageTurnStyle) {
                            if (settings.pageTurnStyle != PageTurnStyle.DRAG || isScrollMode) return@pointerInput
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    // 快照当前章状态（惰性：仅在水平拖拽确认后才记录，垂直滚动不触发）
                                    drag.takenOver = false
                                    drag.preloaded = false
                                    drag.totalDx = 0f
                                    drag.totalDy = 0f
                                    drag.direction = null
                                    drag.oldIdx = idx
                                    drag.oldBlocks = blocks
                                },
                                onHorizontalDrag = { _, dragAmount ->
                                    drag.totalDx += dragAmount
                                    if (!drag.takenOver) {
                                        drag.takenOver = true
                                        drag.direction = if (drag.totalDx < 0) PageTurnDirection.NEXT else PageTurnDirection.PREV
                                        // 异步预加载目标章
                                        scope.launch {
                                            val d = drag.direction ?: return@launch
                                            val target = pageTurnController.turnTo(d) { t ->
                                                viewModel.loadChapter(t, resetScroll = true)
                                            }
                                            if (target == null) {
                                                drag.takenOver = false
                                                return@launch
                                            }
                                            drag.preloaded = true
                                            incoming = IncomingPage(drag.oldBlocks, drag.oldIdx, target, d)
                                            val w = size.width.toFloat().coerceAtLeast(1f)
                                            progress.snapTo((abs(drag.totalDx) / w).coerceIn(0f, 1f))
                                        }
                                    }
                                    // 预加载完成后每帧更新 overlay 位置
                                    if (drag.preloaded && incoming != null) {
                                        scope.launch {
                                            val w = size.width.toFloat().coerceAtLeast(1f)
                                            progress.snapTo((abs(drag.totalDx) / w).coerceIn(0f, 1f))
                                        }
                                    }
                                },
                                onDragEnd = {
                                    if (drag.takenOver && drag.preloaded && incoming != null) {
                                        val w = size.width.toFloat().coerceAtLeast(1f)
                                        val outcome = resolveDragOutcome(drag.totalDx / w)
                                        when (outcome) {
                                            DragOutcome.COMMIT -> scope.launch {
                                                progress.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
                                                listState.scrollToItem(0, 0)
                                                incoming = null
                                                drag.takenOver = false
                                                drag.preloaded = false
                                            }
                                            DragOutcome.REVERT -> scope.launch {
                                                progress.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
                                                viewModel.loadChapter(drag.oldIdx, resetScroll = true)
                                                incoming = null
                                                drag.takenOver = false
                                                drag.preloaded = false
                                            }
                                        }
                                    }
                                },
                                onDragCancel = {
                                    // LOW fix: 所有清理包在同一个协程内，与 onDragEnd 一致
                                    if (drag.takenOver) {
                                        scope.launch {
                                            incoming = null
                                            viewModel.loadChapter(drag.oldIdx, resetScroll = true)
                                            drag.takenOver = false
                                            drag.preloaded = false
                                        }
                                    }
                                },
                            )
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
                    error?.let { errText ->
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                text = errText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            viewModel.loadChapter(idx, resetScroll = true)
                                        }
                                    },
                                ) {
                                    Text(stringResource(R.string.retry))
                                }

                                if (bleEnabled && bleConnState != BleConnState.CONNECTED) {
                                    OutlinedButton(
                                        onClick = {
                                            bleVm?.autoConnect()
                                            scope.launch {
                                                viewModel.loadChapter(idx, resetScroll = true)
                                            }
                                        },
                                    ) {
                                        Text(stringResource(R.string.ble_connect_and_retry))
                                    }
                                }
                            }
                        }
                    }
                    // Task 3: BLE 降级传输徽标（3 秒自动消失，文本不可改动 —— spec §1.2 step 4）
                    AnimatedVisibility(
                        visible = showBleBadge,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            shape = RoundedCornerShape(50),
                            tonalElevation = 3.dp,
                        ) {
                            Text(
                                text = stringResource(R.string.reader_ble_degraded_badge),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
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
                                // ===== 分章模式：COVER/SIMULATION/NONE 动画支持 =====
                                Box(Modifier.fillMaxSize()) {
                                    // 底层：当前 blocks（loadChapter 后的新章）
                                    ChapterModeContent(
                                        blocks = blocks,
                                        idx = idx,
                                        book = book,
                                        settings = settings,
                                        contentDp = contentDp,
                                        listState = listState,
                                        context = context,
                                        viewModel = viewModel,
                                        onTurnPrev = { turn(PageTurnDirection.PREV) },
                                        onTurnNext = { turn(PageTurnDirection.NEXT) },
                                    )
                                    // 顶层：动画层（旧章快照）
                                    incoming?.let { inc ->
                                        val topTx by remember(inc, progress) {
                                            derivedStateOf {
                                                val sign = if (inc.direction == PageTurnDirection.NEXT) -1f else 1f
                                                progress.value * sign
                                            }
                                        }
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .graphicsLayer { translationX = topTx * size.width }
                                        ) {
                                            // 顶层用静态 Column 渲染旧章（动画期间无需滚动）
                                            StaticChapterOverlay(
                                                blocks = inc.topBlocks,
                                                idx = inc.topIdx,
                                                book = book,
                                                settings = settings,
                                                contentDp = contentDp,
                                                context = context,
                                                viewModel = viewModel,
                                                onTurnPrev = { turn(PageTurnDirection.PREV) },
                                                onTurnNext = { turn(PageTurnDirection.NEXT) },
                                            )
                                            // SIMULATION：卷曲阴影覆盖层
                                            if (settings.pageTurnStyle == PageTurnStyle.SIMULATION) {
                                                PageTurnSimulator(
                                                    progress = progress.value,
                                                    reverse = inc.direction == PageTurnDirection.PREV,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // ===== 右侧进度拖动条 =====
                    //   分章模式:thumb = 章内进度,拖动实时滚动本章(不跳章)。
                    //   滚动模式:thumb = 全书进度,松手才跳章。
                    ReaderScrollbar(
                        progress = ((if (isScrollMode) overallPercent else chapterPercent) / 100f).coerceIn(0f, 1f),
                        onSeekStart = {
                            if (viewModel.isAutoScrolling.value) viewModel.stopAutoScroll()
                        },
                        onSeek = { p ->
                            // 分章模式:拖动实时滚动本章内容(亚 item 级,连续)。
                            // 双向精确寻址：将 p 映射为 (targetItem, offset)，offset 严格约束在 [0, itemH - 1]，确保 scrollToItem 秒级即时响应
                            if (!isScrollMode) {
                                val info = listState.layoutInfo
                                val totalItems = info.totalItemsCount
                                if (totalItems > 1) {
                                    val targetFloat = p * (totalItems - 1)
                                    val targetItem = targetFloat.toInt().coerceIn(0, totalItems - 1)
                                    val frac = targetFloat - targetItem
                                    val visItems = info.visibleItemsInfo
                                    val itemH = visItems.find { it.index == targetItem }?.size?.toFloat()
                                        ?: visItems.firstOrNull()?.size?.toFloat()
                                        ?: 300f
                                    val maxOffset = (itemH - 1f).coerceAtLeast(0f)
                                    val offset = (frac * itemH).toInt().coerceIn(0, maxOffset.toInt())
                                    scope.launch { listState.scrollToItem(targetItem, offset) }
                                }
                            }
                            // 滚动模式:纯本地预览,组件内部已更新 thumb
                        },
                        onSeekEnd = { p ->
                            // 分章模式已在 onSeek 实时滚动,无需跳章;滚动模式松手跳章
                            if (!isScrollMode) return@ReaderScrollbar
                            val total = (book?.chapters?.size ?: 1).coerceAtLeast(1)
                            val targetIdx = (p * (total - 1)).roundToInt().coerceIn(0, total - 1)
                            scope.launch {
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
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )

                    // 快速置顶/置底悬浮按钮组：与上下工具栏联动，全屏沉浸阅读时自动淡出防遮挡正文
                    AnimatedVisibility(
                        visible = chromeVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 36.dp, bottom = 80.dp),
                    ) {
                        ScrollFabGroup(
                            canScrollToTop = readerFabVisibility.canScrollToTop,
                            canScrollToBottom = readerFabVisibility.canScrollToBottom,
                            onScrollToTop = {
                                scope.launch { listState.animateScrollToItem(0) }
                            },
                            onScrollToBottom = {
                                val total = listState.layoutInfo.totalItemsCount
                                if (total > 0) {
                                    scope.launch { listState.animateScrollToItem(total - 1) }
                                }
                            },
                        )
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
            bleEnabled = bleEnabled,
            bleConnState = bleConnState,
            onBleConnect = { bleVm?.autoConnect() },
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
    onTurnPrev: () -> Unit = {},
    onTurnNext: () -> Unit = {},
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.width(contentDp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        // item 0: 章节大标题
        item(key = "title_$idx") {
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

        itemsIndexed(
            items = blocks,
            key = { blockIdx, _ -> "ch_${idx}_$blockIdx" },
        ) { blockIdx, block ->
            BlockItem(
                block = block,
                blockIdx = blockIdx,
                settings = settings,
                readingMode = com.juziss.localmediahub.data.ReadingMode.CHAPTER,
                chapterIndex = idx,
                context = context,
                viewModel = viewModel,
                onTurnPrev = onTurnPrev,
                onTurnNext = onTurnNext,
            )
        }

        // 末尾 ❖（点击跳下一章）
        item(key = "end_$idx") {
            Text(
                text = "❖",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp)
                    .clickable { onTurnNext() },
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
    onTurnPrev: () -> Unit = {},
    onTurnNext: () -> Unit = {},
) {
    when (block.type) {
        "text" -> ParagraphItem(
            text = block.value ?: "",
            fontSizeSp = settings.fontSizeSp.sp,
            lineHeightSp = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
            letterSpacingSp = (settings.fontSizeSp * settings.letterSpacing).sp,
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
            onPrevChapter = onTurnPrev,
            onNextChapter = onTurnNext,
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
    letterSpacingSp: TextUnit = 0.sp,
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
                letterSpacing = letterSpacingSp,
                textIndent = if (firstLineIndent) TextIndent(firstLine = 2.em) else TextIndent.None,
            ),
        )
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.reader_add_bookmark)) },
                onClick = { onAddBookmark(); showMenu = false },
                leadingIcon = { Icon(Icons.Filled.Favorite, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.reader_copy_paragraph)) },
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
                stringResource(R.string.reader_bookmark_paragraph, bookmark.paragraphIndex),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.reader_delete_bookmark))
        }
    }
}

/**
 * 动画顶层：用静态 Column 渲染旧章内容快照（[ParagraphItem] 列表），
 * 供 COVER/SIMULATION 动画期间使用。动画 280-400ms 内不需要滚动交互，
 * 避免与底层 LazyColumn 共享 listState 的复杂化。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StaticChapterOverlay(
    blocks: List<com.juziss.localmediahub.data.Block>,
    idx: Int,
    book: com.juziss.localmediahub.data.Book?,
    settings: com.juziss.localmediahub.data.ReaderSettings,
    contentDp: androidx.compose.ui.unit.Dp,
    context: Context,
    viewModel: TextReaderViewModel,
    onTurnPrev: () -> Unit = {},
    onTurnNext: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .width(contentDp)
            .padding(vertical = 16.dp)
    ) {
        // 章节大标题（镜像 ChapterModeContent item 0）
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

        // 内容块（仅文本；图片在动画期间跳过）
        blocks.forEachIndexed { blockIdx, block ->
            when (block.type) {
                "text" -> ParagraphItem(
                    text = block.value ?: "",
                    fontSizeSp = settings.fontSizeSp.sp,
                    lineHeightSp = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
                    letterSpacingSp = (settings.fontSizeSp * settings.letterSpacing).sp,
                    fontFamily = settings.fontFamily.toFontFamily(),
                    firstLineIndent = settings.firstLineIndent,
                    paragraphGapEm = if (settings.paragraphSpacing) 1.6f else 1.2f,
                    readingMode = ReadingMode.CHAPTER,
                    onAddBookmark = {},
                    onCopy = {},
                    onPrevChapter = {},
                    onNextChapter = {},
                )
                "image" -> {
                    // 动画 280-400ms 内不渲染图片（顶层快照无需请求网络）
                }
            }
        }
    }
}
