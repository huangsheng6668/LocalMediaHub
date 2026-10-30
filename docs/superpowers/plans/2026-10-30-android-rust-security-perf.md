# Batch 2：Android & Rust Native 安全与性能加固实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Android 客户端（Kotlin/Compose）与 Rust 原生解码层（`localmedia_native`）的 10 项安全与性能隐患（R1–R3, S1, D1–D2, L1–L2, T1–T2），并补齐单元回归测试。

**Architecture:**
- **Rust 原生层 (`android/app/src/main/rust/`)**：开启 release unwind 使 JNI `catch_unwind` 生效；在 `bitmap.rs` 统一提供 `validate_image_dimensions`（单边 ≤ 16384、总像素 ≤ 64MP）并在 JPEG/PNG/WebP 解码分配前拦截解压炸弹；移除 `png.rs` 的未初始化 `set_len`；将 `bitmap.rs` 的 90°/270°/transverse EXIF 旋转优化为单次分配单遍拷贝。
- **Android 客户端 (`android/app/src/main/java/com/juziss/localmediahub/`)**：将 `AuthInterceptor` 限制为仅向匹配的 `ServerConfig` 主机发送 Bearer Token，并对局域网探测响应执行 4KB 有界读取；为 `DownloadWorker` 引入 500ms 通知节流器、协程取消重抛、HTTP 416 响应体关闭与 $O(1)$ 子路径元数据索引；修复 `FavoritesStore` + `LibrarySyncManager` 因 `StateFlow` 初始空列表抹除离线收藏及阅读时长补传冲掉 `FINISHED` 状态的问题；修复 `ReadingSessionTimer` 亚秒截断与 `TextReaderViewModel.loadChapter` 覆盖恢复段落偏移的问题。

**Tech Stack:** Rust (`cargo test`), Kotlin / Jetpack Compose / Coroutines / OkHttp / DataStore / JUnit4 (`./gradlew testDebugUnitTest assembleDebug`).

---

## Task 1：Rust 原生解码器安全与性能加固（R1 + R2 + R3）

**Files:**
- Modify: `android/app/src/main/rust/Cargo.toml`
- Modify: `android/app/src/main/rust/src/bitmap.rs`
- Modify: `android/app/src/main/rust/src/jpeg.rs`
- Modify: `android/app/src/main/rust/src/png.rs`
- Modify: `android/app/src/main/rust/src/webp.rs`

- [x] **Step 1: 在 `bitmap.rs` 中编写 `validate_image_dimensions` 与 `apply_exif_orientation` 的失败测试**
  - 测试 0 尺寸、单边 > 16384、总像素 > 64MP 返回 `Err`。
  - 测试 Orientation 1..=8 在非对称矩阵（如 3×2 图像）上的精确像素映射，以及异常短缓冲区不 panic。
- [x] **Step 2: 实现 R1 (`Cargo.toml` `panic = "unwind"`)、R2 (`validate_image_dimensions` + `jpeg.rs`/`png.rs`/`webp.rs` 头部校验与安全缓冲初始化) 与 R3 (`bitmap.rs` 边界守卫与单次分配旋转)**
- [x] **Step 3: 运行 `cd android/app/src/main/rust && cargo test` 验证全部通过**
- [x] **Step 4: Git Commit (`security(rust): guard image decode dimensions, enable release unwind, and optimize EXIF rotations`)**

---

## Task 2：Android 局域网扫描凭据隔离与有界探测读取（S1）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/di/OkHttpModule.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/ConnectionViewModel.kt`
- Create: `android/app/src/test/java/com/juziss/localmediahub/di/AuthInterceptorTest.kt`

- [x] **Step 1: 编写 `AuthInterceptorTest.kt` 单元测试**
  - 验证请求匹配 `baseUrlProvider`（同 scheme/host/port）时附加 `Authorization: Bearer <token>`。
  - 验证局域网扫描异源 IP（如 `http://192.168.1.55:8000/`，而配置为 `http://192.168.1.10:8000`）**绝不附加** `Authorization` 头。
  - 验证请求已自带 `Authorization` 头时不覆盖；token 为空时不附加。
- [x] **Step 2: 修改 `OkHttpModule.kt` 的 `AuthInterceptor` 与 `ConnectionViewModel.kt` 的有界探测响应读取（4096 bytes 上限）**
- [x] **Step 3: 运行 `./gradlew testDebugUnitTest --tests com.juziss.localmediahub.di.AuthInterceptorTest`**
- [x] **Step 4: Git Commit (`security(android): scope AuthInterceptor to configured server origin and bound LAN scan reads`)**

---

## Task 3：Android 离线下载节流、取消处理、连接释放与 $O(1)$ 元数据匹配（D1 + D2）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/DownloadWorker.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/DownloadsStore.kt`
- Create: `android/app/src/test/java/com/juziss/localmediahub/data/DownloadWorkerHelpersTest.kt`

- [x] **Step 1: 编写 `DownloadWorkerHelpersTest.kt` 单元测试**
  - 测试 `NotificationThrottle`：500ms 内重复进度更新（含未知总大小 `percent = -1` 及已知百分比）被节流，跨 500ms 窗口或 `percent == 100` 放行。
  - 测试 `MediaMetadataLookup`：子目录同名文件（如 `sub1/001.jpg` 与 `sub2/001.jpg`）按相对路径后缀精确匹配，单文件名回退匹配。
- [x] **Step 2: 实现 `DownloadWorker.kt` 与 `DownloadsStore.kt` 加固**
  - 在 `DownloadWorker.kt` 中集成 `NotificationThrottle` 与 `ongoing` 标志；
  - `catch (e: Exception)` 首行重抛 `CancellationException`；
  - `dl.code == 416` 分支关闭 `dl.body.close()`；
  - ZIP 解压保留子目录相对路径结构（防同名文件互相覆盖）并使用 `MediaMetadataLookup` $O(1)$ 匹配；
  - `DownloadsStore.downloadedFiles` 增加 `.flowOn(Dispatchers.IO)`。
- [x] **Step 3: 运行 `./gradlew testDebugUnitTest --tests com.juziss.localmediahub.data.DownloadWorkerHelpersTest`**
- [x] **Step 4: Git Commit (`fix(android): throttle download notifications, preserve zip subpaths, and fix cancellation/416 leaks`)**

---

## Task 4：Android 收藏原子合并与阅读进度/时长同步正确性（L1 + L2）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/FavoritesStore.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/LibrarySyncManager.kt`
- Create: `android/app/src/test/java/com/juziss/localmediahub/data/LibrarySyncHelperTest.kt`

- [x] **Step 1: 编写 `LibrarySyncHelperTest.kt` 单元测试**
  - 测试本地收藏与服务端收藏合并纯函数 `mergeFavoriteEntries(local, server)`：保留服务端条目 + 追加本地独有（离线新增）条目，不丢失任何本地离线收藏。
  - 测试阅读时长补传载荷推导纯函数 `buildReadingTimeFlushPayload(serverState, localProgress, nowMs)`：当服务端已是 `FINISHED` 时保持 `finished = true`；当服务端无状态但本地有 `BookProgress(chapterIndex = 5, blockIndex = 12)` 时保留章节与段落进度；保留真实 `lastReadAt`。
- [x] **Step 2: 修改 `FavoritesStore.kt` 与 `LibrarySyncManager.kt`**
  - `FavoritesStore` 新增 `readFavoriteEntriesSnapshot()`（直接读取 `decoded.first()` 而非 `favoriteEntries` StateFlow 初始空值）与 `mergeServerFavorites(server: List<ServerFavorite>)`（在 `favoritesDataStore.edit` 内原子合并）。
  - `LibrarySyncManager.migrateLocalProgressOnce()` 改为全部成功（`allSuccess`）才标记 `setProgressMigrationDone()`。
  - `LibrarySyncManager.syncOnConnected()` 的阅读时长补传循环遇到单本书查询失败时 `continue` 而非 `return`，并使用 `buildReadingTimeFlushPayload` 保护 `finished` 与章节进度。
- [x] **Step 3: 运行 `./gradlew testDebugUnitTest --tests com.juziss.localmediahub.data.LibrarySyncHelperTest`**
- [x] **Step 4: Git Commit (`fix(android): prevent offline favorites data loss on sync and preserve finished state during reading time flush`)**

---

## Task 5：阅读计时器亚秒精度与阅读器恢复偏移保持（T1 + T2）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/ReadingSessionTimer.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt`
- Modify: `android/app/src/test/java/com/juziss/localmediahub/data/ReadingSessionTimerTest.kt`

- [x] **Step 1: 在 `ReadingSessionTimerTest.kt` 中补充亚秒余数保留与时钟回拨测试，并测试 `resolveInitialChapterProgress` 纯函数**
  - 验证连续 3 次间隔 900ms 调用 `takeDelta()` 累计返回 `2` 秒（`0 + 1 + 1`，总耗时 2700ms），余数不丢失。
  - 验证时钟回拨（`now` 变小）不会产生负数增量。
  - 验证 `resolveInitialChapterProgress(chapterIndex, pendingResume)` 在章节匹配 `_pendingResume` 时保留 `blockIndex` 与 `scrollOffsetPx`，切换到其他章节时归零。
- [x] **Step 2: 修改 `ReadingSessionTimer.kt` 与 `TextReaderViewModel.kt`**
  - `ReadingSessionTimer` 方法加 `@Synchronized`，`takeDelta()` 按整秒推进 `startTs = it + wholeSec * 1000L` 并夹紧非负耗时。
  - `TextReaderViewModel`：
    - `loadChapter` 使用 `resolveInitialChapterProgress(index, _pendingResume.value)` 避免把恢复中的 `(blockIndex, scrollOffsetPx)` 覆盖成 `(0, 0)`；
    - `loadBookmarksFor` 保存并取消上一个 `bookmarksJob`。
- [x] **Step 3: 运行 `./gradlew testDebugUnitTest --tests com.juziss.localmediahub.data.ReadingSessionTimerTest`**
- [x] **Step 4: Git Commit (`fix(android): preserve sub-second reading timer remainder and keep pending resume offset on chapter load`)**

---

## Task 6：Android 版本号递增、Rust 原生库重编与文档同步

**Files:**
- Modify: `android/app/build.gradle.kts`
- Modify: `AGENTS.md`

- [x] **Step 1: 递增 `android/app/build.gradle.kts` 中的 `versionCode` (+1) 与 `versionName` (+0.0.1)**
- [x] **Step 2: 更新 `AGENTS.md` 记录 Batch 2 的 Android 与 Rust 安全/性能加固要点**
- [x] **Step 3: 运行全量验证**
  - `cd android/app/src/main/rust && cargo test`
  - `cd android && ./gradlew testDebugUnitTest assembleDebug`（自动触发 `buildRustNative` 重新编译 `liblocalmedia_native.so`）
- [x] **Step 4: Git Commit (`chore(android): bump version for batch 2 security/perf hardening and update AGENTS.md`)**
