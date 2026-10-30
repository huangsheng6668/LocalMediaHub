# Batch 2：Android (Kotlin/Compose) 与 Rust Native (`localmedia_native`) 安全与性能加固设计规格

- **日期**：2026-10-30
- **状态**：已批准（Approved）
- **分支**：`feat/batch-2-android-rust-hardening`
- **前置批次**：`docs/superpowers/specs/2026-09-30-security-perf-hardening-design.md`（Batch 1 Server Go + Web SPA，已合入 `master`）

---

## 1. 背景与目标

在完成 Batch 1（Server Go + Web SPA）的安全与性能加固后，本批次聚焦 Android 客户端（Kotlin / Jetpack Compose）与 Rust 原生图像解码层（`android/app/src/main/rust/`，`localmedia_native`）的深度审计问题。共识别并验证 **4 个领域、10 项高价值修复点**，涵盖：

1. **Rust FFI 安全、解压炸弹防护与旋转性能（R1–R3）**
2. **Android 局域网扫描凭据隔离与响应体边界防护（S1）**
3. **Android 离线下载通知 IPC 节流、取消语义与解压正确性（D1–D2）**
4. **Android 跨端书架同步数据完整性、阅读计时器精度与阅读器恢复状态保持（L1–L2, T1–T2）**

---

## 2. 详细问题与技术方案

### Part 1：Rust 原生解码器（`android/app/src/main/rust/`）

#### R1（Critical / FFI Crash）：`Cargo.toml` Release 配置 `panic = "abort"` 导致 JNI `catch_unwind` 失效
- **现状证据**：
  - `android/app/src/main/rust/Cargo.toml:77` 在 `[profile.release]` 下配置了 `panic = "abort"`。
  - 所有 JNI 入口函数（`jni_bridge/decoders.rs:105,149`、`jni_bridge/exif_jni.rs:39`、`jni_bridge/natural_sort_jni.rs:33`、`bitmap.rs:62`）均使用 `std::panic::catch_unwind` 捕获 Rust panic 并降级返回 `null` / `0`。
  - 当 `panic = "abort"` 开启时，`catch_unwind` 在 release 构建中完全失效，任何底层解码库触发的内部 panic 都会直接调用 `abort()` 杀死整个 Android 应用进程（`SIGABRT`）。
- **修复设计**：
  - 将 `Cargo.toml` 的 `[profile.release]` 中 `panic = "abort"` 改为 `panic = "unwind"`，确保 FFI 边界上的 `std::panic::catch_unwind` 在 Release 构建中真正生效，避免单张损坏图片导致 App 闪退。

#### R2（High Security / OOM & UB）：JPEG / PNG / WebP 解压炸弹（Decompression Bomb）与 PNG 未初始化内存暴露
- **现状证据**：
  - `jpeg.rs:24-27`：未调用 `decoder.read_info()` 校验宽高，直接调用 `decoder.decode()`。恶意构造的 `65535×65535` SOF0 头部即可触发数 GB 内存分配导致 OOM 崩溃。
  - `png.rs:31-54`：
    1. 直接将 `u32` 的 `info.width` / `info.height` 强转为 `i32`（`> i32::MAX` 时溢出为负数，且无总像素上限校验）。
    2. `png.rs:50-54` 使用 `let mut buf = Vec::with_capacity(buf_size); unsafe { buf.set_len(buf_size); }` 后调用 `reader.next_frame(&mut buf)`。若 APNG 子帧尺寸小于画布或 `output_buffer_size()` 大于实际写入字节，未初始化的堆内存将直接进入后续像素转换通道（UB / 内存信息泄露）。
  - `webp.rs:14-35`：在第 27 行先执行 `vec![0u8; (w as usize) * (h as usize) * bytes_per_pixel]` 分配完整像素缓冲区，第 31 行才调用 `decoder.set_memory_limit(256 * 1024 * 1024)`，导致内存上限对第 27 行的预分配形同虚设。
- **修复设计**：
  - 在 `bitmap.rs`（或公共辅助函数 `validate_image_dimensions(width: u32, height: u32) -> Result<(i32, i32, usize), String>`）中统一强制图像尺寸边界：
    - `MAX_IMAGE_DIMENSION: u32 = 16_384`（单边最大 16384 px）
    - `MAX_IMAGE_PIXELS: u64 = 64 * 1024 * 1024`（总像素上限 64 MP，对应 RGBA 缓冲区最大 256 MiB）
    - 校验 `width > 0 && height > 0 && width <= MAX_IMAGE_DIMENSION && height <= MAX_IMAGE_DIMENSION && (width as u64) * (height as u64) <= MAX_IMAGE_PIXELS`。
  - `jpeg.rs`：先调用 `decoder.read_info()` 获取 `metadata()`，通过 `validate_image_dimensions` 校验后再调用 `decoder.decode()`。
  - `png.rs`：对 `reader.info()` 及 `next_frame` 返回的 `output_info` 均执行 `validate_image_dimensions`；移除 `unsafe { buf.set_len(buf_size) }`，改用安全的 `vec![0u8; buf_size]`。
  - `webp.rs`：在读取头部后立即调用 `validate_image_dimensions(w, h)`，通过后再分配 `raw` 缓冲区并解码。

#### R3（Medium Perf & Safety）：`bitmap.rs` 像素拷贝边界校验与 EXIF 旋转单次分配优化
- **现状证据**：
  - `bitmap.rs:139-160`（`create_android_bitmap_inner`）：未校验 `info.width == width as u32`、`info.height == height as u32` 以及 `stride_bytes >= row_bytes` 即进行 `unsafe` 指针偏移拷贝。
  - `bitmap.rs:208-308`（`apply_exif_orientation`）：
    1. 未校验 `w <= 0 || h <= 0` 或 `rgba.len() < (w as usize).saturating_mul(h as usize).saturating_mul(4)`。
    2. 最常见的手机竖拍照片（Orientation 6 = `rotate_90_cw`，Orientation 8 = `rotate_90_ccw`，以及 Orientation 7 = `transverse`）目前先调 `transpose()` 分配一份 `w*h*4` Vec，再调 `flip_horizontal` / `flip_vertical` 分配或全量二次遍历（Orientation 6/8 峰值或遍历开销翻倍）。
- **修复设计**：
  - `create_android_bitmap_inner` 增加 `stride_bytes >= row_bytes` 与 `rgba.len() >= row_bytes * (height as usize)` 防御校验。
  - `apply_exif_orientation` 增加非正宽高与缓冲区长度不足时安全原样返回的守卫；将 `rotate_90_cw`（Orientation 6）、`rotate_90_ccw`（Orientation 8）和 `transverse`（Orientation 7）改写为**单次分配、单次遍历**的直接坐标映射（例如 `rotate_90_cw`：`dst[x * h + (h - 1 - y)] = src[y * w + x]`），减少 50% 中间遍历开销。

---

### Part 2：Android 网络与凭据安全（`OkHttpModule.kt`, `ConnectionViewModel.kt`）

#### S1（High Security）：局域网子网扫描向全网段 254 台主机广播 Bearer Token 及无界响应读取
- **现状证据**：
  - `ConnectionViewModel.kt:356-359` 使用注入的单例 `httpClient.newBuilder()...build()` 创建 `scanClient`，并发探测 `http://$subnet.$i:$port/`（`i` 从 1 到 254）。
  - 单例 `httpClient` 挂载了 `AuthInterceptor`（`OkHttpModule.kt:29-40, 131`），只要本地保存过 `authToken`，就会无条件对**所有经过该 Client 的请求**注入 `Authorization: Bearer <token>`。这意味着用户在公共/家庭 Wi-Fi 下点击“扫描局域网服务器”时，会把自己的 Bearer Token 明文广播给局域网内全部 254 个 IP！
  - 此外，`ConnectionViewModel.kt:421` 调用 `response.body?.string()` 无上限读取探测响应体，若局域网某端口返回超大流（如大文件或无限流），会导致扫描协程 OOM。
- **修复设计**：
  1. **Host 作用域化 `AuthInterceptor`（`OkHttpModule.kt`）**：
     - 为 `AuthInterceptor` 注入目标基准 URL 提供者 `baseUrlProvider: () -> String = { ServerConfig.getBaseUrl() }`。
     - 仅当请求满足以下全部条件时才注入 `Authorization: Bearer <token>`：
       - `token` 非空；
       - 请求尚未携带 `Authorization` 头；
       - 请求的 `url.scheme`、`url.host`（大小写不敏感）与 `url.port` 与当前配置的 `baseUrlProvider()` 解析出的 `HttpUrl` 完全匹配。
     - 使用 `.header("Authorization", "Bearer $token")` 替代 `.addHeader(...)` 防止重复头。
  2. **局域网探测响应体有界读取（`ConnectionViewModel.kt`）**：
     - 在 `probeHostPorts` 中仅读取前 4096 字节（`source.request(4096L)` + `buffer.clone().readString(minOf(buffer.size, 4096L), Charsets.UTF_8)`），严禁无界 `body.string()`。

---

### Part 3：Android 离线下载可靠性与主线程 I/O 治理（`DownloadWorker.kt`, `DownloadsStore.kt`）

#### D1（High Perf / ANR）：流式下载与解压期间 Notification 跨进程 IPC 风暴
- **现状证据**：
  - `DownloadWorker.kt:152-155` 与 `273-276`：当服务端未返回 `Content-Length`（`totalBytes <= 0`，例如文件夹打包下载 `/api/v1/folders/*/download` 的流式 ZIP）时，`else` 分支在**每次 `inputStream.read(buffer)`（8KB）循环**中都会调用 `updateNotification`，每秒触发数百次 `NotificationManager.notify` Binder IPC，导致系统通知栏卡顿甚至抛出 TransactionTooLarge / 丢弃通知。
  - `DownloadWorker.kt:334-338`：ZIP 解压循环对每个文件条目都无节流调用 `updateNotification`。
  - `DownloadWorker.kt:408-418`：`updateNotification` 将 `.setOngoing(false)` 硬编码，导致下载进行中的通知没有 `ongoing` 属性。
- **修复设计**：
  - 提取纯逻辑节流器 `NotificationThrottle(minIntervalMs: Long = 500L, clock: () -> Long = SystemClock::elapsedRealtime)`：
    - 进度更新必须满足 `(pct != lastPercent || totalBytes <= 0) && now - lastUpdateMs >= minIntervalMs`（或 `pct == 100`）才放行。
  - `updateNotification` 增加 `ongoing: Boolean = false` 参数：下载/解压进行中传 `ongoing = true`，完成或失败传 `ongoing = false`。

#### D2（Medium Reliability）：协程取消误报失败、HTTP 416 连接泄漏、ZIP 子目录同名冲突与 `DownloadsStore` 主线程磁盘 I/O
- **现状证据**：
  - `DownloadWorker.kt:201, 378`：`catch (e: Exception)` 捕获了 `kotlinx.coroutines.CancellationException`，当用户或系统取消任务时错误弹出“下载失败”通知并返回 `Result.failure()`。
  - `DownloadWorker.kt:113-125`：当断点续传遇到 `dl.code == 416` 时，直接递归重试而未关闭 `dl.body.close()`，导致 OkHttp 连接泄漏。
  - `DownloadWorker.kt:307-353`：
    - `filesMetadata.find { it.name.equals(entryFileName, ignoreCase = true) }` 在每个 ZIP entry 上做 $O(N)$ 线性扫描（整体 $O(N^2)$），且当文件夹包含子目录同名文件（如 `disc1/01.mp3` 与 `disc2/01.mp3`）时会错误匹配同一个元数据。
  - `DownloadsStore.kt:41-44`：`downloadedFiles` Flow 在 `map` 中同步执行 `File(entry.localPath).exists() && file.isFile`，未指定 `.flowOn(Dispatchers.IO)`，在 UI 收集该 Flow 时会在主线程触发磁盘 `stat` 系统调用。
- **修复设计**：
  - 在 `DownloadWorker` 的 `catch (e: Exception)` 首行显式判断 `if (e is CancellationException) throw e`。
  - 在 `dl.code == 416` 分支首行调用 `dl.body.close()`。
  - 提取纯函数 `buildMetadataLookupIndex(filesMetadata: List<MediaFile>)`：按归一化相对路径后缀与文件名建立 $O(1)$ 索引表，优先精确匹配相对路径后缀，回退匹配唯一文件名。
  - 在 `DownloadsStore.downloadedFiles` 链尾追加 `.flowOn(Dispatchers.IO)`。

---

### Part 4：书架同步、阅读计时器与阅读器状态正确性（`FavoritesStore.kt`, `LibrarySyncManager.kt`, `ReadingSessionTimer.kt`, `TextReaderViewModel.kt`）

#### L1（Critical Data Loss）：`LibrarySyncManager` 读取未初始化的 `StateFlow` 导致本地离线收藏被清空
- **现状证据**：
  - `FavoritesStore.kt:106-107`：`val favoriteEntries: StateFlow<List<FavoriteEntry>> = decoded.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())`。
  - `LibrarySyncManager.kt:66`：`val local = favoritesStore.favoriteEntries.first()`。因为 `favoriteEntries` 是 `StateFlow`，其初值为 `emptyList()`；当 App 刚启动触发 `syncOnConnected()` 且尚未有界面长期订阅 `favoriteEntries` 时，`.first()` 会**立即返回初始值 `emptyList()`** 而不会等待 DataStore 从磁盘读取！随后第 73 行 `favoritesStore.replaceAll(merged)` 将仅含服务端数据的列表写回磁盘，**彻底抹掉用户在离线期间新增的本地收藏**！
- **修复设计**：
  - 在 `FavoritesStore` 中新增直接读取底层 `decoded.first()` 的挂起方法 `suspend fun readFavoriteEntriesSnapshot(): List<FavoriteEntry>`，以及在 `favoritesDataStore.edit` 事务内原子合并服务端收藏的 `suspend fun mergeServerFavorites(server: List<ServerFavorite>): List<FavoriteEntry>`（同时保留合并时本地独有的新增项，避免并发 UI 切换收藏丢失）。
  - `LibrarySyncManager.syncFavorites()` 改用 `favoritesStore.readFavoriteEntriesSnapshot()` 获取待上传快照，再用 `favoritesStore.mergeServerFavorites(server)` 原子落盘。

#### L2（High Correctness）：`LibrarySyncManager` 进度迁移半途标记完成与阅读时长补传冲掉已读完状态/进度
- **现状证据**：
  - `LibrarySyncManager.kt:50-62`：只要任意一本书上传成功（`anySuccess = true`），即便其余书籍因网络抖动失败，也会调用 `setProgressMigrationDone()` 永久跳过未成功迁移的书籍。
  - `LibrarySyncManager.kt:80-98`：补传离线累计阅读时长（`readingTimeStore`）时：
    1. 第 86 行 `else -> return`：单本书查询状态失败会直接 `return` 中断整个 `for` 循环，导致后续书籍的时长全部无法补传（应为 `continue`）。
    2. 第 95 行将 `finished = false` 硬编码，且第 96 行将 `lastReadAt = System.currentTimeMillis()` 硬编码；当服务端尚未有该书记录（`st == null`）时还将 `ch = 0, para = 0, pct = 0.0` 硬编码。若该书在服务端已是 `FINISHED` 状态，补传一段离线阅读时长会直接把 `finished` 重置为 `false`；若服务端无记录但本地有 `BookProgress`，则会把章节重置为第 0 章！
- **修复设计**：
  - `migrateLocalProgressOnce()` 改为仅当所有待迁移书籍均成功（`allSuccess`）时才调用 `setProgressMigrationDone()`。
  - `flushPendingReadingTime()` 中：
    - 查询状态失败时 `continue` 处理下一本书，不中断整体循环；
    - 结合本地 `recentActivityStore.getBookProgress(bookPath)` 与服务端 `st`：
      - `ch = st?.chapterIndex ?: localProg?.chapterIndex ?: 0`
      - `para = st?.paragraphIndex ?: localProg?.blockIndex ?: 0`
      - `pct = st?.percent ?: 0.0`
      - `finished = (st?.status == ReadingStatus.FINISHED) || (localProg != null && st == null && pct >= 0.999)`（保留服务端已读完状态）
      - `lastReadAt = maxOf(st?.lastReadAt ?: 0L, localProg?.lastReadAt ?: 0L).takeIf { it > 0L } ?: System.currentTimeMillis()`（避免用当前系统时间伪造最后阅读时间而压过其他设备的真实阅读位置）。

#### T1（Medium Correctness）：`ReadingSessionTimer` 亚秒截断累积误差与时钟回拨防护
- **现状证据**：
  - `ReadingSessionTimer.kt:18, 27-28`：
    1. `(now() - it) / 1000` 未做非负保护，若系统 NTP 校时产生时钟回拨会计算出负数秒并污染累计值。
    2. `takeDelta()` 每次调用都将 `startTs = now()`，直接丢弃 `(now() - it) % 1000` 的亚秒余数（例如每 2.9 秒触发一次章节切换/保存，每次丢 0.9 秒，误差达 31%）。
    3. 多处方法读写 `startTs` 与 `accumulatedSec` 未加 `@Synchronized`。
- **修复设计**：
  - 为 `ReadingSessionTimer` 所有公开方法添加 `@Synchronized`。
  - 计算经过毫秒时强制 `(now() - it).coerceAtLeast(0L)`。
  - 在 `takeDelta()` 中，按整秒推进起点：`val elapsedMs = (now() - it).coerceAtLeast(0L); val wholeSec = elapsedMs / 1000L; startTs = it + wholeSec * 1000L`，完美保留亚秒余数。

#### T2（High Correctness & Perf）：`TextReaderViewModel.loadChapter` 冲掉恢复段落偏移、主线程 `File.exists()` 与重复书签收集协程
- **现状证据**：
  - `TextReaderViewModel.kt:232-255` & `351-359`：打开书籍时，`processBookLoaded` 先将上次阅读的 `BookProgress(chapterIndex, blockIndex, scrollOffsetPx)` 写入 `_pendingResume`，紧接着调用 `loadChapter(resumeChapter)`。然而 `loadChapter` 在章节加载成功后（第 351–359 行）无条件向 `RecentActivityStore` 保存 `BookProgress(..., blockIndex = 0, scrollOffsetPx = 0)`！如果用户在 UI 完成首帧滚动恢复前切后台/退出，或者服务端同步稍后触发，原本精确到段落和像素偏移的阅读进度就会被 `(0, 0)` 覆盖丢失！
  - `TextReaderViewModel.kt:178-184`：`loadBook` 在主线程直接调用 `File(path).exists()`。
  - `TextReaderViewModel.kt:658-662`：`loadBookmarksFor(path)` 每次调用都启动新的 `viewModelScope.launch { recentActivityStore.bookmarksFlow.collect { ... } }` 而未取消上一次的 Job，多次切书会导致重复收集器泄漏。
- **修复设计**：
  - 在 `TextReaderViewModel.loadChapter` 保存初始章节进度时，检查 `_pendingResume.value`：若 `pending?.chapterIndex == index`，则沿用 `pending.blockIndex` 与 `pending.scrollOffsetPx`，而非硬编码 `0`。
  - 将 `loadBook` 中的本地文件存在性检查移入 `withContext(Dispatchers.IO)`。
  - 新增 `private var bookmarksJob: Job? = null`，在 `loadBookmarksFor` 中先 `bookmarksJob?.cancel()` 再启动新收集协程。

---

## 3. 测试与验证计划

1. **Rust 单元测试（`cd android/app/src/main/rust && cargo test`）**：
   - `bitmap.rs`：新增 `validate_image_dimensions` 边界测试（0 尺寸、负数溢出、单边超 16384、总像素超 64MP 拒绝，正常尺寸通过）；新增 Orientation 1~8（含单次分配 `rotate_90_cw` / `rotate_90_ccw` / `transverse`）像素精确性测试与异常尺寸防崩溃测试。
   - `jpeg.rs` / `png.rs` / `webp.rs`：验证超大尺寸头部拦截与正常图像解码通过。
2. **Android 单元测试（`cd android && ./gradlew testDebugUnitTest`）**：
   - `AuthInterceptorTest.kt`：验证同源请求注入 Bearer Token、异源 IP（模拟局域网扫描 `192.168.1.x`）**不注入** Token、已有 `Authorization` 头不覆盖、开放模式（空 Token）不注入。
   - `NotificationThrottleTest.kt`：验证 500ms 窗口内高频进度更新被抑制、跨窗口放行、100% 完成态放行。
   - `DownloadMetadataIndexTest.kt`：验证 ZIP 子目录同名文件精确按相对路径后缀匹配，无 $O(N^2)$ 退化。
   - `FavoritesStoreMergeTest.kt` & `LibrarySyncManagerTest.kt`：验证 `mergeServerFavorites` 不会因 `StateFlow` 初始空列表抹除离线收藏，验证 `flushPendingReadingTime` 保持 `FINISHED` 状态与已有章节进度。
   - `ReadingSessionTimerTest.kt`：验证亚秒余数跨 `takeDelta()` 保留、时钟回拨不产生负数秒。
3. **全量构建验证（`cd android && ./gradlew testDebugUnitTest assembleDebug`）**：
   - 按 `AGENTS.md` 规范递增 `android/app/build.gradle.kts` 的 `versionCode` (+1) 与 `versionName` (+0.0.1)，触发 `buildRustNative` 重新编译 `liblocalmedia_native.so` 并完成 Debug APK 构建。
