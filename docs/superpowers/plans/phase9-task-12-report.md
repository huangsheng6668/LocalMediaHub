# Task 12 报告：Android 杂项加固（M-7 / L-6 / L-7）

**Commit**: `3a2bfd6a584c99e55cf82a3159807f56efd0bd66` — `fix(android): pip receiver export guard, path encoding and zip cap (Phase 9)`（master，brief Step 5 原文）

**状态**: DONE_WITH_CONCERNS（功能全部落地、测试全绿；两处对 brief 的消歧偏离需 reviewer 知悉，见「消歧决策」）

## 做了什么

按 brief Step 1-5 TDD 流程执行：先写失败测试 → 确认编译失败（`Unresolved reference 'encodePathSegments'` / `'shouldAbortUnzip'`，符合 Step 2 预期）→ 实现 → `testDebugUnitTest assembleDebug` 全绿 → 提交。

## 改动点清单

### 1. PiP 接收器导出面（M-7）

- `android/app/src/main/java/com/juziss/localmediahub/VideoPlayerActivity.kt`
  - 原 API≥33 用三参 `registerReceiver(..., RECEIVER_NOT_EXPORTED)`、API<33 走两参（默认导出）的分支删除，统一为：
    `ContextCompat.registerReceiver(this, pipReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)`
  - 新增 `androidx.core.content.ContextCompat` import；`Build` import 仍被 `parseMediaFileExtra` 使用，保留。
- 删除死代码 `android/app/src/main/java/com/juziss/localmediahub/pip/PipActionReceiver.kt`。
  - **注意**：brief 写的路径是 `ui/pip/PipActionReceiver.kt`，实际文件在 `pip/PipActionReceiver.kt`（全仓唯一同名文件）。
  - 删除前全仓 grep：无任何代码引用（不在 Manifest、无动态注册点）。仅剩 3 处注释/文档提及，全部同步清理：
    - `pip/PipControllerStore.kt` KDoc 去掉 `[PipActionReceiver]` 引用（类本身仍被 `VideoPlayerScreen` 的 bind/unbind 使用，保留）；
    - `ui/screen/VideoPlayerScreen.kt:291` 注释改为引用 `PipControllerStore`；
    - `docs/INDEX.md:221` PiP 章节移除 `PipActionReceiver` 并注明统一 `RECEIVER_NOT_EXPORTED`。
  - `PipControllerTest` 未引用被删类，无需适配（已核实）。

### 2. REST 路径段 URL 编码（L-6）

- `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt`
  - 新增顶层 `internal fun encodePathSegments(path: String): String`：按 `/` 切分逐段 `URLEncoder.encode(seg, "UTF-8").replace("+", "%20")` 后以 `/` 重新拼接；空串返回空串。
  - **接入全部 5 个把 `normalizeRoutePath(...)` 结果拼进 URL 路径段的调用点**：
    1. `browseFolder` — `/api/v1/folders/<route>/browse`（仅 URL 编码；传给 BLE failover 的 `path = route` 保持原始未编码，那是 BLE 线格式，不进 URL）
    2. `getFolderFilesRecursive` — `/api/v1/folders/<route>/files`
    3. `downloadFolderZip` — `/api/v1/folders/<route>/download`
    4. `tagFile` — `/api/v1/tags/<id>/files/<path>`
    5. `untagFile` — 同上（DELETE）
  - 查询参数场景（`?path=` / `?q=` / thumbnail mtime 等）已有的 URLEncoder 调用一律未动。
  - 服务端兼容性已核实：`server/internal/server/handler/path_suffix.go` 的 `decodeWildcardPath` 用 `url.PathUnescape` 解码路径段（`+` 不会被解成空格），故 `+`→`%20` 替换是必需且正确的；编码后服务端仍解析到同一目录。

### 3. ZIP 解压总量上限（L-7）

- `android/app/src/main/java/com/juziss/localmediahub/data/DownloadWorker.kt`
  - companion object 新增：
    - `const val MAX_UNCOMPRESSED_BYTES = 4L * 1024 * 1024 * 1024`
    - 纯函数 `fun shouldAbortUnzip(extracted: Long, declared: Long): Boolean`
  - 解压循环：逐 entry 写入时累计 `extractedBytes` 并**在拷贝循环内部**调用 `shouldAbortUnzip`（比 brief 的"每 entry 检查"更严——单个超大膨胀 entry 也无法绕过检查）；触发即停止解压、删除本次解压产生的所有文件（含写到一半的那个）、`throw SecurityException("unzip budget exceeded")` → 外层 catch 统一 toast/通知并 `Result.failure()`；`finally` 照旧清理临时 zip。
  - `incomingEntries` 只在解压全部完成后才 `addDownloads`，中止路径不会登记半成品。

### 测试

- 新建 `android/app/src/test/java/com/juziss/localmediahub/util/PathEncodingTest.kt`：`encodesEachSegmentButKeepsSlashes`（brief Step 1 原文三断言：`a b/c#d/e.mp4` → `a%20b/c%23d/e.mp4`、单段、空串）。
- `data/DownloadManagerTest.kt` 追加 `unzipAbortsBeyondDeclaredBudget`（brief Step 1 原文三断言，经 `DownloadWorker.shouldAbortUnzip` 调用）。

## 测试结果

- Step 2（红）：`./gradlew testDebugUnitTest --tests "...PathEncodingTest" --tests "...DownloadManagerTest"` → 编译失败，`Unresolved reference` × 7，符合预期。
- Step 4（绿）：`cd android && ./gradlew testDebugUnitTest assembleDebug` → **BUILD SUCCESSFUL**（1m 8s；仅存量无关测试的 deprecation warning）。
- 报告 XML 证据：
  - `PathEncodingTest`：tests=1 failures=0 errors=0
  - `DownloadManagerTest`：tests=6（5 存量 + 1 新增）failures=0 errors=0

## 消歧决策（对 brief 的偏离，reviewer 请知悉）

1. **`shouldAbortUnzip` 公式与 brief 测试期望冲突**：brief 文字描述为 `extracted > maxOf(declared * 2, 64MB)`，但其 Step 1 测试期望 `(30000, 10000) → true`（30000 < 64MB，`maxOf` 形式必然 false，自相矛盾）。以测试（brief 明示"代码按原文使用"且 coordinator 复述了同样三组期望）为准，实现为：
   `extracted > MAX_UNCOMPRESSED_BYTES || extracted > (if (declared > 0) declared * 2 else 64MB)`
   即 64MB 作为**未声明长度时的兜底额度**（declared=0），而非把 `declared*2` 抬到 64MB 的下限。三组测试期望全部满足，且保留了 brief 中"2×declared"与"64MB"两个语义要素。代码内已注释说明。
2. **`declared` 取值**：brief 说"取响应 contentLength（无则 0）"。经核实服务端 `DownloadFolderZip`（`server/internal/server/handler/folders.go:226-228`）`WriteHeader` 后直接 `zip.NewWriter(c.Response().Writer)` 流式输出——**无 Content-Length，恒为 chunked**，OkHttp `contentLength()` 返回 -1。若按字面"无则 0"，则每次真实下载都落到 64MB 兜底额度，>64MB 的媒体目录（本 App 主流场景：视频）会全部误杀。故实现为 `contentLength > 0 ?: tempFile.length()`——temp zip 是已完整落盘的响应体，其长度正是公式想要的"压缩尺寸"，与 contentLength 语义等价且恒可用。服务端 zip 用 Store（媒体不压缩），declared ≈ 解压后尺寸，2× 预算对合法媒体目录是宽松的，对 zip 炸弹仍有效。
3. **删除半成品的范围**：brief 写 `file.deleteRecursively()`（删"半成品目录"）。但解压目标 `destDirectory = .../Download/LocalMediaHub` 是**所有历史下载共用的目录**，整目录递归删除会把之前的下载全部抹掉。改为只删除**本次解压创建的文件**（逐个 `deleteRecursively()`，含中止时写到一半的文件），不动无关历史数据。
4. **PipActionReceiver 实际路径**为 `pip/` 而非 brief 写的 `ui/pip/`（见上）。

## Self-review

- `grep -rn PipActionReceiver` 全仓（排除 build 产物）：仅剩历史 spec/plan 文档中的审计记录（描述本次发现本身，保留合理），代码与 INDEX.md 已清零。
- `MediaRepository` 中 `normalizeRoutePath` 的 5 个 URL 路径段使用点全部走 `encodePathSegments`；BLE 线格式传参、查询参数编码均未动。
- `VideoPlayerActivity` 的 `Build` import 无孤儿；`onDestroy` 的 `unregisterReceiver` 与注册配对不变。
- 中止路径控制流：内层 `break` → 关闭流（use）→ 外层 `break` → `ZipFile.use` 关闭 → 删文件 → 抛 SecurityException → 外层 catch（通知+failure）→ finally 删 temp zip；`incomingEntries` 不会在中止时入库。
- 未触碰任何 BLE 相关文件（`bleFetchOrHttp`/`BleProtocol` 等仅原有代码）。
- 未提交工作区中两个与本任务无关的未跟踪项（`docs/superpowers/reviews/`、`tools/reformat_novels.py`）。
- 后续可选项（未做，超出 brief 范围）：`PipControllerStore` 的 `isPlaying()`/`togglePlayPause()` 在删除 PipActionReceiver 后已无读取方，bind/unbind 成为纯占用；可考虑下轮清理。
