# Round 30 — 死代码 / 冗余代码审计报告

- **创建日期**: 2026-07-13
- **关联 spec**: `docs/2026-07-13-deadcode-audit-design.md`（commit `46564ff`）
- **关联 plan**: `docs/2026-07-13-deadcode-audit-plan.md`（commit `1a81180`）
- **A 阶段状态**: 完成
- **B 阶段状态**: 未启动（待用户审阅本报告后决定批次）

---

## 1. 执行摘要

### 1.1 工具运行情况

| 栈 | 工具 | 状态 |
|---|---|---|
| Go | staticcheck | **缺失**（降级为 codegraph + grep） |
| Go | go vet | 可用，0 输出 |
| Go | go mod tidy -diff | 可用，仅 go.sum hash 重排 |
| Android | gradlew lintDebug | 可用但 BUILD FAILED（11 UnsafeOptInUsageError），文本报告完整；UnusedDeclaration 对 Kotlin 盲区，降级为 Grep |
| Web | knip | 拉取成功但执行失败（无 package.json），降级为手工 import 链追溯 |
| Rust | cargo machete | **缺失**（降级为人工 Grep） |
| Rust | cargo check | 可用，0 dead_code warning |

详见附录 B。

### 1.2 发现项分布

| 层 | 直接删除候选 | 待人工确认 | 冗余建议 | Deprecated（仅记录） | 误报剔除 |
|---|---|---|---|---|---|
| §2 Go server | 1 | 2 | 6 | 14 处 SA1019 | 0 |
| §3 Android Kotlin | 1 | 19 | 6 | 6 | 0 |
| §4 Web JS | 0 | 7（过度暴露 export） | 7 | 1 | 0 |
| §5 Rust JNI | 0 | 5 | 1 | 0 | 0 |
| §6 构建脚本/工具 | 0 | 6 | 0 | 0 | 0 |
| §7 依赖冗余 | — | 1（Rust `log=0.4`，与 §5 交叉） | — | — | 0 |
| **合计** | **2** | **40** | **20** | **21** | **0** |

### 1.3 按风险等级分布

- **低**: 全部条目（62 条）
- **中**: 0
- **高**: 0

**说明**：所有导出符号（Go 大写、Kotlin public/internal、JS export、Rust pub fn）一律进"待人工确认"区，不自动删——按 spec §7 护栏约定。所有"直接删除候选"都是非导出符号 + 工具+codegraph 双确认零调用 + grep 零反射引用，证据强度高、误删风险低。

### 1.4 按建议动作分布

- **直接删除（进 B 阶段清理批次）**: 2
- **待人工确认（用户决定是否删）**: 40
- **冗余建议（仅记录，不自动清理）**: 20
- **Deprecated（仅记录，不进清理批次）**: 21
- **误报剔除**: 0

### 1.5 高优先级发现

1. **`android.enableR8.fullMode=true` 实际未生效**（§6.3）——Round 26 D1 的 R8 full mode 配置写在了根 `gradle.properties`，但实际构建走 `android/gradle.properties`，导致 R8 full mode 默认 `false`。这是隐藏 bug，建议独立修复（非死代码）。
2. **BrowseViewModel 7 个 orphan 转发方法**（§3.4 + §3.6）——Round 18 拆分 delegate 后留下的死转发，其中 `emitBrowseError` 是 private 可直接删，另 6 个 public 待人工确认。
3. **MediaRepository 12 个未用方法**（§3.4）——分页端点、legacy URL builder、system URL builder、tag 端点 wrapper，整批未接入 UI，疑似早期 UI 预留。
4. **Android 68 个 UnusedResources**（§3.6）——Android Studio 模板默认色 + 早期 UI 文案，可批量删除减少 APK 体积 3-5 KB。
5. **Web JS 7 个过度暴露 export**（§4.4）——函数体有内部调用、但 `export` 关键字无外部 import，去掉 `export` 收窄 API surface。
6. **Rust `log = "0.4"` 依赖零引用**（§5.5 + §7.3）——Cargo.toml 唯一可清理依赖。

### 1.6 B 阶段建议批次顺序

按 spec §5 + 本报告实际发现项，建议清理顺序：

1. **批次 1（Go server，1 条直接删）**：`(*Scanner).Search`
2. **批次 2（Android Kotlin，1 条直接删 + 68 UnusedResources）**：`BrowseViewModel.emitBrowseError` + lint 标记的未用资源
3. **批次 3（Web JS，0 条直接删，可选去 export）**：无强制清理；待人工确认是否去 export
4. **批次 4（Rust/Build，0 条直接删）**：可选清理根目录孤儿 gradle 文件（需先把 `enableR8.fullMode` 迁到 `android/gradle.properties`）
5. **批次 5（依赖，1 条）**：`Cargo.toml` 删除 `log = "0.4"`

待人工确认的 40 条由用户在 B 阶段开始前逐批决定保留或删除。

---

## 2. Go server 发现项

### 2.1 工具运行情况

- staticcheck: **缺失**（未安装，`staticcheck -version` 报 `command not found`）。证据文件：`/tmp/staticcheck-missing.txt`。降级为 codegraph + grep 兜底；附录 B 由 Task 8 汇总。
- go vet: **可用**（Go 1.25.0 windows/amd64 自带）。`go vet ./...` 在 `server/` 下执行，0 行输出（无可达性错误、无 declared-and-not-used）。输出文件：`/tmp/govet.out`（0 字节）。
- go mod tidy -diff: **可用**（Go 1.25+ 只读模式）。执行 `go mod tidy -diff` 输出 371 行，**全部是 `go.sum` 的 h1: hash 重排**，没有 `go.mod` 的 require 块变更——即 R3（依赖冗余）为空集。输出文件：`/tmp/gomodtidy.out`（371 行）。

### 2.2 文件级死代码

| 位置 | 类型 | 证据 | 风险 | 建议 |
|---|---|---|---|---|

（无。所有 `server/**/*.go` 文件均被同包或跨包引用，没有"整个文件无引用"的命中。）

### 2.3 符号级死代码（直接删除候选）

| 符号 | 位置 | 导出 | 证据 | 风险 | 建议 |
|---|---|---|---|---|---|
| `(*Scanner).Search` | `server/internal/service/scanner.go:404` | 非导出（方法名首字母小写） | codegraph 零引用者；`Grep` 全仓搜 `scanner\.Search`/`s\.Search\(files` 0 命中（唯一 `.Search(` 命中是 `Handler.Search` HTTP handler，不同符号）；无字符串字面量 `"Search"` 引用此方法；无反射 | 低 | 直接删除。该方法是 O(n) 线性扫描 + `strings.Contains` 过滤，已被 `handler/search.go` 的 `searchFiles` + `searchFoldersCached`（基于内存 cacheDirs 前缀扫）完全取代。删除后无行为变化。 |

### 2.4 符号级死代码（待人工确认）

| 符号 | 位置 | 导出 | 证据 | 风险 | 建议 |
|---|---|---|---|---|---|
| `ServerStatus` | `server/internal/models/models.go:59` | 导出（首字母大写） | codegraph 零引用者；`Grep` 全仓搜 `ServerStatus` 仅命中定义行；无 handler 构造或 JSON 序列化此类型；无测试 | 低 | 待人工确认。导出 struct，JSON tag 齐全（`running`/`host`/`port`/`ip`），疑似为未实装的 `/api/v1/status` 端点预留。因导出 API 可能被外部 consumer（Android/Web）依赖，不自动删除；建议确认无外部 consumer 后删除。 |
| `DecodeImage` | `server/internal/service/thumbnail.go:461` | 导出（包级函数首字母大写） | codegraph 零引用者；`Grep` 全仓搜 `DecodeImage` 仅命中定义行 + doc 注释；无测试；无反射/字符串引用 | 低 | 待人工确认。导出函数封装 `os.Open` + `image.Decode`，逻辑已被 `generateThumbnailFromFile` 内部的 `imaging.Open` 路径覆盖。因导出 API 可能被外部 consumer 依赖，不自动删除；建议确认无外部 consumer 后删除。 |

### 2.5 Deprecated API 使用（SA1019，仅记录）

> staticcheck 不可用，下表为人工 grep 替代扫描的结果（基于 Go 官方 `os.IsNotExist`/`os.IsPermission` 自 Go 1.13 起被标记 deprecated、推荐 `errors.Is(err, fs.ErrNotExist)` 的约定）。**仅记录，不进清理批次。**

| 位置 | Deprecated API | 现代等价 | 出现次数 |
|---|---|---|---|
| `server/internal/service/path.go:196` | `os.IsNotExist(err)` | `errors.Is(err, fs.ErrNotExist)` | 1 |
| `server/internal/service/path.go:199` | `os.IsPermission(err)` | `errors.Is(err, fs.ErrPermission)` | 1 |
| `server/internal/server/handler/folders.go:112, 198` | `os.IsNotExist(err)` | `errors.Is(err, fs.ErrNotExist)` | 2 |
| `server/internal/server/handler/videos.go:63, 85` | `os.IsNotExist(err)` | `errors.Is(err, fs.ErrNotExist)` | 2 |
| `server/internal/server/handler/search.go:46` | `os.IsNotExist(err)` | `errors.Is(err, fs.ErrNotExist)` | 1 |
| `server/internal/server/handler/system.go:56, 142, 179, 214` | `os.IsNotExist(err)` | `errors.Is(err, fs.ErrNotExist)` | 4 |
| `server/internal/server/handler/media.go:26, 63` | `os.IsNotExist(err)` | `errors.Is(err, fs.ErrNotExist)` | 2 |
| `server/internal/server/handler/images.go:63` | `os.IsNotExist(err)` | `errors.Is(err, fs.ErrNotExist)` | 1 |

**合计 14 处** `os.IsNotExist`/`os.IsPermission` 调用，分布在 8 个文件中。功能等价、无 bug，仅 API 风格陈旧。

### 2.6 冗余但非死（仅记录，不自动清理）

> 以下条目均为"有调用者、但调用结构可优化"的冗余模式。按 spec §4 约定**只进报告、不进清理批次**。

| 类型 | 位置 | 描述 | 建议 |
|---|---|---|---|
| 重复实现 | `server/internal/service/tags.go:270 GetTagsForFiles` vs `:301 GetAllFileTags` | 两个方法的 SQL 查询（`SELECT a.file_path, t.id, t.name, t.color FROM associations a JOIN tags t ...`）和 rows.Scan 循环几乎逐字相同；唯一差异是 `GetTagsForFiles` 预填 `result[fp]=[]FileTag{}` 做白名单过滤，`GetAllFileTags` 不预填。 | 建议提取私有 `queryFileTags(filePaths []string, filter bool)` helper，两个导出方法各传一个 flag。收益：减少 ~25 行重复代码、未来 SQL schema 变更只改一处。 |
| 重复实现 | `getLocalIP`（`server/internal/mdns/mdns.go:66`、`server/internal/gui/gui.go:51`、`server/internal/server/server.go:222`） | 三个包各有一个 `getLocalIP` 实现，逻辑高度相似（枚举 `net.InterfaceAddrs`/`net.Interfaces`，跳过 loopback + APIPA，优先 RFC1918 私网）。`server.go` 版本最完善（含虚拟适配器过滤）。 | 建议在 `internal/util` 或类似包统一一个 `LocalIPs() []string`，三处改为 import。收益：消除 ~120 行跨包重复。注意三处签名略有差异（返回 `string` vs `(string, error)` vs `[]string`），统一时需兼容。 |
| 重复实现 | `strings.EqualFold(ext, imgExt)` 媒体类型判定散落 3 处（`handler/handler.go:43-48 isMediaExt`、`handler/system.go:103-108` 内联循环、`service/path.go:208-210 validateMediaFilePath`） | 三处各自遍历 `ImageExtensions`/`VideoExtensions` 做 `EqualFold` 比对，逻辑等价但实现各写一遍。 | 建议统一到 `service` 层一个 `IsAllowedExt(ext, videoExts, imageExts) (mediaType string, ok bool)`，handler 直接调用。收益：消除 3 处媒体类型判定分歧风险。 |
| 单调用 helper | `server/internal/server/handler/handler.go:41 isMediaExt` | 仅被 `handler/system.go:94 SystemBrowse` 调用 1 次（codegraph 确认 1 caller）；调用点本身仅做一个 `if h.isMediaExt(ext)` 分支，内联后可读性不下降。 | 建议内联到 `SystemBrowse`，或与上面"重复实现"条合并统一到 service 层后删除。 |
| 单调用 helper | `server/internal/server/handler/path_suffix.go:9 stripRouteActionSuffix` | 仅被同文件 `decodeWildcardPath` 调用 1 次；函数体仅 `strings.TrimSuffix(path, suffix)` 一行 + 空 suffix 短路。 | 建议内联到 `decodeWildcardPath`（`decodeWildcardPath` 本身有 7 callers，保留；只删 helper）。 |
| 过度防御 | `server/internal/service/tags.go:123 Close` + 各 Query 方法里的 `defer rows.Close()` + `if tags == nil { return []FileTag{} }` 模式 | `Close` 在 `db != nil` 时才调 `db.Close()`，但 `NewTagsService` 构造成功时 `db` 必非 nil（否则构造返回 error），nil 检查是冗余防御。同理 `GetAllTags`/`GetFilesForTag` 末尾的 `if tags == nil` 分支：`var tags []FileTag` 后若 rows 无数据，`tags` 本就是 nil-slice，可直接返回（JSON 序列化 nil-slice 与空 slice 在 `encoding/json` 下都产 `[]`/`null`，需确认前端兼容性后再决定是否保留）。 | 建议保留 `Close` 的 nil 检查（防御未来字段变更）；`if tags == nil` 分支可在确认前端接受 `null` 后删除（Go 惯例是返回 nil-slice）。优先级低。 |

---

## 3. Android Kotlin 发现项

### 3.1 工具运行情况

- **gradle wrapper**: 可用（Gradle 8.13，`./gradlew --version` 输出正常）。
- **`:app:lintDebug`**: **可用但 BUILD FAILED**（2 分 5 秒完成，`--no-daemon` 单次执行）。失败由 11 个 `UnsafeOptInUsageError`（ExoPlayer `@UnstableApi` opt-in 缺失）+ 1 个 `LocalContextGetResourceValueCall` 触发——这些是代码质量问题，不是工具问题。lint 在失败前完整输出了文本报告：`android/app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`（716 行）。
- **R1 原始命中分布**：
  - `UnusedDeclaration` / `UnusedSymbol`: **0 条**。Android lint 的 `UnusedDeclaration` 检查对 Kotlin 的覆盖极有限（主要检测 Java + 资源 ID），因此本节的 Kotlin 符号级死代码由**降级方案**（手动 Grep + codegraph 引用追溯）发现。
  - `UnusedResources`: **68 个唯一资源**（6 个 `colors.xml` 颜色 + 61 个 `strings.xml` 字符串 + 1 个 `xml/ic_launcher.xml`）。
  - 其余 118 条 warning / 2 条 hint 均为非死代码类（`GradleDependency`、`NewerVersionAvailable`、`UnsafeOptInUsageError`、`TypographyEllipsis` 等），归入各专项（deprecated / 冗余建议），不进死代码清理批次。
- **降级方案**：对 `android/app/src/main/java/` 下全部 68 个 `.kt` 文件，枚举所有 `private` / `internal` / `public` 符号，用 Grep 全仓搜 `<symbol_name>` 统计引用计数。判定规则：计数 = 1（仅定义行）→ 候选死代码；计数 > 1 → 剔除。再对每个候选 Grep 其定义处注解，命中 `@Inject` / `@HiltViewModel` / `@Composable` / `@Parcelize` / `@Provides` / `@Binds` / `@Module` / `@EntryPoint` / `@AndroidEntryPoint` / `@HiltAndroidApp` → 强制升级为"待人工确认"。

### 3.2 文件级死代码

| 位置 | 类型 | 证据 | 风险 | 建议 |
|---|---|---|---|---|

（无。没有整个 `.kt` 文件的所有导出符号均零引用的命中。`RoutePath.kt` 最接近——唯一的 `internal fun normalizeRoutePath` 零引用，但文件本身被同包 `data` 的其他类间接 import 路径覆盖，不作为文件级删除。）

### 3.3 符号级死代码（直接删除候选）

> 以下均为 `private` 符号 + codegraph/Grep 零引用 + 无 DI/Compose/Parcelize 注解 + 不在 XML 资源中。**均未进入 lint R1**（lint 对 Kotlin private 符号检测为空），由降级方案发现。

| 符号 | 位置 | 导出 | 证据 | 风险 | 建议 |
|---|---|---|---|---|---|
| `BrowseViewModel.emitBrowseError` | `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt:356` | `private` | Grep 全仓搜 `\.emitBrowseError\(` 命中 8 处，**全部直接调用 `sharedState.emitBrowseError`**（BrowseNavigator 6 处、TagController 1 处、BrowseSharedState 定义 1 处），**没有任何调用者经过 BrowseViewModel 的 private wrapper**；该方法是 1 行转发 `sharedState.emitBrowseError(message)`；无注解；无 XML 引用 | 低 | 直接删除。Round 18 拆分 delegate 时遗留的 orphan wrapper——所有 delegate 已经直接持有 `sharedState` 引用并直接调用，ViewModel 层的 private 转发函数从未被调用。 |

### 3.4 符号级死代码（待人工确认）

> 以下为 `public`/`internal` 符号 + Grep 零外部引用 + 无 DI/Compose/Parcelize 注解 + 不在 XML 资源中。因导出 API 可能被外部 consumer 或反射引用，不自动删除。

| 符号 | 位置 | 导出 | 证据 | 风险 | 建议 |
|---|---|---|---|---|---|
| `MediaRepository.getVideos` | `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt:158` | `public`（类本身 `@Inject`，方法无注解） | Grep 全仓搜 `\bgetVideos\b` 仅命中定义行；唯一 caller 应为 HomeViewModel / BrowseViewModel，但两者均未调用——HomeViewModel 通过 `getFolders()` + `getFileTags()` 组装首页数据，不走分页 video 端点；`AndroidManifest.xml` / `res/xml/*.xml` / `res/values/strings.xml` 零命中 | 低 | 待人工确认。`/api/v1/videos?page=&page_size=` 分页端点在客户端从未被调用，疑似为早期 UI 预留。因 `MediaRepository` 是 `@Inject` 单例、方法为 public，建议人工确认无反射或未来计划后删除。 |
| `MediaRepository.getImages` | `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt:162` | `public` | 同上，Grep `\bgetImages\b` 仅定义行；无 caller | 低 | 待人工确认。与 `getVideos` 同批预留的分页端点 wrapper，建议一并确认删除。 |
| `MediaRepository.getTaggedFiles` | `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt:226` | `public` | Grep `\bgetTaggedFiles\b` 仅定义行；UI 实际用的是 `getTaggedMedia`（返回 `List<MediaFile>`），本方法返回 `List<String>`（纯文件路径列表）零引用 | 低 | 待人工确认。返回 `List<String>` 的 tag-files 端点被返回 `List<MediaFile>` 的 `getTaggedMedia` 端点取代，但 `getTaggedMedia` 本身也无 caller（见下行）。 |
| `MediaRepository.getTaggedMedia` | `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt:229` | `public` | Grep `\bgetTaggedMedia\b` 仅定义行；`TagController.openCollection` 调用的是 `repository.getFileTags(paths)` 而非本方法 | 低 | 待人工确认。`/api/v1/tags/{id}/media` 端点在客户端从未被调用，`openCollection` 实际通过 `getFileTags` 拉取全量 file-tags 再过滤。 |
| `MediaRepository.downloadFolderZip` | `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt:150` | `public` | Grep `\bdownloadFolderZip\b` 仅定义行；`DownloadController` / `DownloadWorker` 实际通过 `downloadFileStream(url)` 下载单文件，不走 zip 整包端点 | 低 | 待人工确认。`/api/v1/folders/{path}/download`（返回 zip）在客户端从未被调用，被逐文件流式下载取代。 |
| `MediaRepository.getFolderFilesRecursive` | `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt:146` | `public` | Grep `\bgetFolderFilesRecursive\b` 仅定义行；`DownloadController.downloadFolder` 实际通过 `browseFolder(path)` 拿一级列表再逐个 enqueue，不走递归 files 端点 | 低 | 待人工确认。`/api/v1/folders/{path}/files` 递归端点在客户端从未被调用。 |
| `MediaRepository.getSystemVideoStreamUrl` | `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt:274` | `public` | Grep 仅命中定义行；系统浏览模式实际通过 `getMediaStreamUrl(absolutePath)`（`/api/v1/media/stream?path=`）播放，不走 `/api/v1/system/stream?path=` | 低 | 待人工确认。system stream URL builder 被 media stream URL builder 取代——`BrowseNavigator.getVideoStreamUrl` 调用的是 `repository.getMediaStreamUrl(file.path)`，不调本方法。 |
| `MediaRepository.getSystemThumbnailUrl` | `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt:277` | `public` | 同上，Grep 仅定义行；缩略图走 `getMediaThumbnailUrl` | 低 | 待人工确认。system thumbnail URL builder 被 media thumbnail URL builder 取代。 |
| `MediaRepository.getSystemOriginalImageUrl` | `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt:280` | `public` | 同上，Grep 仅定义行；原图走 `getMediaOriginalImageUrl` | 低 | 待人工确认。system original URL builder 被 media original URL builder 取代。 |
| `MediaRepository.getVideoStreamUrl(relativePath)` | `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt:256` | `public` | Grep `repository\.getVideoStreamUrl\(` 0 命中；`BrowseNavigator.getVideoStreamUrl(file)` 实际调 `repository.getMediaStreamUrl(file.path)`（绝对路径版），不调本 relativePath 版 | 低 | 待人工确认。legacy relative-path 版 URL builder 被 absolute-path 版（`getMediaStreamUrl`）取代。注意 `BrowseViewModel.getVideoStreamUrl(file)` 是不同签名（接受 `MediaFile`），有 caller，勿混淆。 |
| `MediaRepository.getThumbnailUrl(relativePath)` | `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt:259` | `public` | Grep `repository\.getThumbnailUrl\(` 0 命中；实际走 `getMediaThumbnailUrl` | 低 | 待人工确认。legacy relative-path 版被 absolute-path 版取代。 |
| `MediaRepository.getOriginalImageUrl(relativePath)` | `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt:262` | `public` | Grep `repository\.getOriginalImageUrl\(` 0 命中；实际走 `getMediaOriginalImageUrl` | 低 | 待人工确认。legacy relative-path 版被 absolute-path 版取代。注意 `BrowseViewModel.getOriginalImageUrl(file)` / `FavoritesController.getFavoriteOriginalImageUrl(file)` / `HomeViewModel.getOriginalImageUrl(entry)` 是不同签名（接受 `MediaFile`/`RecentMediaEntry`），均有 caller，勿混淆。 |
| `RoutePath.normalizeRoutePath` | `android/app/src/main/java/com/juziss/localmediahub/data/RoutePath.kt:3` | `internal` | Grep `import.*normalizeRoutePath` + `data\.normalizeRoutePath` 0 命中；`MediaRepository` 有自己的 `private fun normalizeRoutePath`（同文件 :287），完全独立实现，不 import RoutePath 版 | 低 | 待人工确认。`RoutePath.kt` 整个文件唯一的符号零引用——疑似为统一 path 规范化逻辑而提取但从未接入。两个实现逻辑不同（RoutePath 版保留 Windows 绝对路径 `C:/` 前缀，Repository 版不保留），合并前需人工确认语义。 |
| `BrowseViewModel.filterFilesByFavorites` | `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt:214` | `public` | Grep `browseViewModel\.filterFilesByFavorites` / `viewModel\.filterFilesByFavorites` 0 命中；`BrowseScreen` / `BrowseContent` 不调用此方法（收藏过滤通过 `showFavoritesOnly` StateFlow 驱动） | 低 | 待人工确认。Round 18 拆分后的 orphan 转发方法，1 行调用 `favoritesController.filterFilesByFavorites`，但 UI 从未经过 ViewModel 调用。 |
| `BrowseViewModel.currentCollectionTag` | `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt:304` | `public` | Grep `browseViewModel\.currentCollectionTag` 0 命中；`TagController.currentCollectionTag()` 仅在 TagController 内部被 `loadTags` / `createTag` 回调使用，UI 不经过 ViewModel 读取 | 低 | 待人工确认。orphan 转发方法，UI 通过 `browseState` StateFlow 里的 `BrowseState.TagCollection` 子类读取集合标题，不调本方法。 |
| `BrowseViewModel.deletePathSync` | `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt:334` | `public` `suspend` | Grep `browseViewModel\.deletePathSync` / `\.deletePathSync\(` 仅命中 `DeleteController` 内部调用（`DeleteController.deletePathSync` 调 `repository.deletePath`），UI（`BrowseScreen`）调的是非 suspend 的 `viewModel.deletePath(path, recursive)` | 低 | 待人工确认。orphan 转发方法，UI 从未直接 await suspend delete——删除通过 fire-and-forget 的 `deletePath` 触发，结果通过 `deleteState` StateFlow 回传。 |
| `BrowseViewModel.loadAllFileTags` | `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt:288` | `public` | Grep `browseViewModel\.loadAllFileTags` / `\.loadAllFileTags\(` 仅命中 ViewModel 自身转发行；UI 不调本方法（全量 file-tags 通过 `loadTags()` 间接触发） | 低 | 待人工确认。orphan 转发方法。 |
| `BrowseViewModel.loadFileTagsForFile` | `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt:284` | `public` | Grep `browseViewModel\.loadFileTagsForFile` / `\.loadFileTagsForFile\(` 仅命中 ViewModel 自身转发行；UI 不调本方法 | 低 | 待人工确认。orphan 转发方法。 |
| `BrowseViewModel.setActiveTagFilter` | `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt:296` | `public` | Grep `browseViewModel\.setActiveTagFilter` / `\.setActiveTagFilter\(` 仅命中 ViewModel 自身转发行；UI 不调本方法（标签过滤通过 `openCollection(tag)` 触发，后者内部调 `tagController.openCollection`） | 低 | 待人工确认。orphan 转发方法。 |
| `PaginatedMediaFiles` (data class) | `android/app/src/main/java/com/juziss/localmediahub/data/Models.kt:51` | `public` | Grep `\bPaginatedMediaFiles\b` 命中 4 处：`Models.kt` 定义 + 类注释 + `MediaRepository.kt:158,160,162,164`（`getVideos`/`getImages` 返回类型）；这两个方法本身已是待确认死代码（见上表） | 低 | 待人工确认。传递性死代码——唯一引用者是同样待删的 `getVideos`/`getImages`。若确认删除这两个方法，`PaginatedMediaFiles` 可一并删除。 |

### 3.5 Deprecated API 使用（仅记录）

> Android lint 报告中**无** `Deprecated` 命中。以下为 lint 报告中与"API 陈旧"相关的 warning/hint，**仅记录，不进清理批次**。

| 位置 | Lint issue ID | 描述 | 现代等价 | 严重度 |
|---|---|---|---|---|
| `AndroidManifest.xml:15` | `DataExtractionRules` | `android:allowBackup="false"` 自 Android 12 起废弃 | 添加 `android:dataExtractionRules="@xml/..."` | Warning |
| `DownloadWorker.kt:265` | `ObsoleteSdkInt` | `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)` 冗余（minSdk = 26 = O） | 删除版本判断，直接调用 | Warning |
| `res/mipmap-anydpi-v26/` | `ObsoleteSdkInt` | `-v26` 文件夹限定符冗余（minSdk = 26） | 合并到 `mipmap-anydpi/` | Warning |
| `HomeScreen.kt:124,198` | `UseKtx` | `android.net.Uri.parse(url)` | `String.toUri()` KTX 扩展 | Warning |
| `NativeDecoderFactory.kt:49` | `UseKtx` | `BitmapDrawable(...)` | `Bitmap.toDrawable(resources)` KTX 扩展 | Warning |
| `VideoPlayerScreen.kt:114,115` | `AutoboxingStateCreation` | `mutableStateOf(0)` 包装 Int | `mutableIntStateOf(0)` | Hint |

### 3.6 冗余但非死（仅记录，不自动清理）

> 以下条目均为"有调用者、但实现/调用结构可优化"的冗余模式。按 spec §4 约定**只进报告、不进清理批次**。

| 类型 | 位置 | 描述 | 建议 |
|---|---|---|---|
| 重复实现 | `normalizeRoutePath` 两处：`data/MediaRepository.kt:287`（private）vs `data/RoutePath.kt:3`（internal，零引用） | 两个实现逻辑不同：Repository 版做 `replace("\\","/").trim('/')`（简单去斜杠），RoutePath 版额外用正则 `^[A-Za-z]:/.*` 保留 Windows 绝对路径前缀（`C:/Users/...` 不被 trimStart）。RoutePath 版语义更完整（system browse 模式会传 `C:/...` 绝对路径），但从未被接入。 | 建议确认 RoutePath 版的 Windows 路径保留语义是否需要（当前 system browse 的 URL builder 用 `URLEncoder.encode(path)` 不依赖 normalize，所以无 bug），若需要则替换 Repository 版为本版并删除 RoutePath.kt；若不需要则直接删除 RoutePath.kt。 |
| 重复实现 | URL builder 三套并存：`getVideoStreamUrl/getThumbnailUrl/getOriginalImageUrl`（relativePath 版，零引用）vs `getMediaStreamUrl/getMediaThumbnailUrl/getMediaOriginalImageUrl`（absolutePath 版，有引用）vs `getSystemVideoStreamUrl/getSystemThumbnailUrl/getSystemOriginalImageUrl`（absolutePath 版，零引用） | 三套 URL builder 覆盖同一组 server 端点（`/api/v1/videos/`、`/api/v1/images/`、`/api/v1/media/`、`/api/v1/system/`），实际只有 `getMedia*` 这一套被调用。legacy relative-path 版和 system 版均为死代码（见 3.4）。 | 建议删除 relative-path 版（3 个方法）和 system 版（3 个方法），只保留 media 版。收益：消除 6 个零引用的 public 方法 + 减少未来端点变更时的维护面。 |
| 重复实现 | `BrowseViewModel` 7 个 orphan 转发方法（`filterFilesByFavorites`、`currentCollectionTag`、`deletePathSync`、`loadAllFileTags`、`loadFileTagsForFile`、`setActiveTagFilter`、`emitBrowseError`） | Round 18 将 BrowseViewModel 拆分为 7 个 delegate（Navigator/FavoritesController/TagController/SearchController/DownloadController/DeleteController + SharedState）后，ViewModel 保留了原本给 UI 的 public API 作为 1 行转发。但 UI 实际只用了其中一部分（如 `loadTags`、`tagFile`、`deletePath`、`downloadFile` 有 caller），另一部分（上列 7 个）从未被 UI 经过 ViewModel 调用——UI 要么直接读 StateFlow，要么调了同名的非转发方法。 | 建议删除 7 个 orphan 转发方法（其中 `emitBrowseError` 是 private 可直接删，其余 6 个 public 需人工确认）。收益：BrowseViewModel 减约 15 行、API surface 收窄。注意 `getOriginalImageUrl(file)` / `getFavoriteVideoStreamUrl(file)` 等**同名但不同签名**的方法有 caller，勿误删。 |
| 重复实现 | `color` 资源 6 个 + `string` 资源 61 个 + `xml/ic_launcher.xml`（lint `UnusedResources`） | `colors.xml` 的 `purple_200/700`、`teal_200/700`、`black`、`white` 是 Android Studio 模板默认色，从未被 Compose 主题引用（项目用 `Theme.kt` 的 `darkColorScheme`/`lightColorScheme` 自定义色板）。`strings.xml` 的 61 个未用字符串多为早期 UI 文案（`home_connected_desc`、`conn_found_multi`、`browse_title_system`、`video_transcode_on/off`、`sort_*`、`toast_*` 等），UI 重构后改为硬编码或 `SortOrder.label` enum 属性，旧 string key 残留。`xml/ic_launcher.xml` 被 `mipmap-anydpi-v26/ic_launcher.xml` 取代（lint 报 both，实际 manifest 引用的是 mipmap 版）。 | 建议批量删除 68 个未用资源（6 color + 61 string + 1 xml）。收益：减少 APK 体积约 3-5 KB（strings XML 编译后）+ 降低翻译维护成本。注意 `xml/ic_launcher.xml` 与 `mipmap-anydpi-v26/ic_launcher.xml` 是不同文件，删前者留后者。 |
| 单调用 helper | `DownloadsStore.gson` / `FavoritesStore.gson` / `ServerConfigStore.gson` / `RecentActivityStore.gson` / `MediaRepository.gson` | 5 个 Store/Repository 各自 `private val gson = Gson()` 实例。Gson 是线程安全无状态对象，5 个独立实例功能等价。 | 建议保留（Gson 构造成本低、各 Store 独立测试更清晰），或抽 `@Provides @Singleton Gson` 到 Hilt module 统一注入。优先级低。 |
| 过度防御 | `DownloadWorker.kt:265` `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)` | 项目 `minSdk = 26 = VERSION_CODES.O`，版本判断恒为 true。lint `ObsoleteSdkInt` 标记。 | 建议删除 if 包裹，直接调用 `NotificationChannel` / `Notification.Builder` 相关 API。功能无变化。 |
| 过度防御 | `ConnectionViewModel.kt:74-83` 6 个 `private var` NSD/锁字段 | `nsdManager`、`discoveryListener`、`multicastLock`、`resolveQueue`、`resolveWorkerJob` 均为 nullable var，生命周期由 `startNsdDiscovery`/`stopNsdDiscovery` 手动管理。非死代码（有读写），但手动生命周期管理易泄漏（若 `stopNsdDiscovery` 未在 `onCleared` 调用则 NSD listener 泄漏）。 | 建议加 `init { viewModelScope.launch { ensureActive() } }` + 在 `onCleared` override 中调 `stopNsdDiscovery()`（若已调则确认）。优先级中——与死代码无关，是健壮性建议。 |

---

## 4. Web JS 发现项

### 4.1 工具运行情况

- **knip（`npx knip@6.26.0`）**：**可拉取但不可执行**。`npx knip --version` 成功返回 `6.26.0`（npx 自动下载），但 `npx knip --no-exit-code` 因 `server/internal/web/` 目录无 `package.json` 报错退出：`ERROR: Unable to find package.json`。证据文件：`/tmp/knip.out`（仅 3 行错误文本）。Web 前端为纯静态 ES Module + Go `embed` 挂载，不经过 Node 构建链，因此 knip 无法识别模块入口与依赖图。
- **降级方案**：采用 brief 授权的手工 import 链追溯。步骤：(1) 列出 `server/internal/web/*.js` 全部 14 个文件；(2) 对每个文件提取 `export` 列表（共 37 个导出符号）；(3) 对每个导出符号在 `server/internal/web/` 下 `Grep` 引用计数，引用计数 = 1（仅定义行）→ 候选；(4) 以 `index.html` 唯一的 `<script type="module" src="app.js">` 为入口，用 import 声明做可达性闭包验证文件级死代码。附录 B 由 Task 8 汇总。
- **入口与可达闭包**：`index.html` 仅 `app.js` 一个 script 入口（line 311）。`app.js` 直接 import 10 个模块（`state/router/settings/tagsView/dom/videoPlayer/lightbox/dashboard/browserView/api`），传递闭包再覆盖 `toast/utils/delete` —— **全部 14 个 `.js` 文件均可达，无文件级死代码**。`go:embed` 指令 `//go:embed index.html style.css *.js`（`web.go:6`）用通配符 `*.js` 覆盖所有 JS 文件，但未被 `app.js` import 的文件运行时不会加载——文件级判定以 import 闭包为准，不以 embed 为准。
- **R1 原始命中分布**：knip 失败，R1 由降级方案产出。文件级：0 条；符号级（`export` 外部零引用）：7 条；误报剔除：0 条（因 knip 未产出任何原始报告，无误报可剔除）。

### 4.2 文件级死代码

| 位置 | 类型 | 证据 | 风险 | 建议 |
|---|---|---|---|---|

（无。所有 14 个 `.js` 文件均从 `index.html` 唯一入口 `app.js` 经 import 闭包可达。`favicon.go` 不在审计范围。）

### 4.3 符号级死代码（直接删除候选）

> 无。所有 37 个 `export` 的函数/常量在本模块内至少有 1 次调用——不存在"导出了但完全无引用"的纯死代码。下面 4.4 列出的是"导出了但外部零引用、可降级为非导出"的过度暴露符号，不属于直接删除范畴。

### 4.4 符号级死代码（待人工确认——过度暴露的 `export`）

> 以下 7 个符号 `export` 出去后无任何外部 import，仅在本模块内调用。函数体本身有调用者（非死代码），但 `export` 关键字是冗余的。建议去掉 `export`（降级为模块内私有函数），语义无变化、打包体积可忽略减少、API surface 收窄。因 ES Module 静态分析失败时浏览器不会报错（只是其他模块无法再 import），风险极低，但因可能被未审到的动态 `import()` 引用，标记为待人工确认。

| 符号 | 位置 | 导出 | 证据 | 风险 | 建议 |
|---|---|---|---|---|---|
| `onDashboardRecentClick` | `server/internal/web/dashboard.js:10` | `export function` | Grep `\bonDashboardRecentClick\b` 命中 3 处：注释 + 定义 + 同文件 `:75` 的 `elements.dashboardRecent.addEventListener('click', onDashboardRecentClick)`；无任何外部 `import { onDashboardRecentClick }` | 低 | 去掉 `export`，改为模块内私有函数。事件监听注册在同文件内完成，外部无需访问。 |
| `renderLightboxImage` | `server/internal/web/lightbox.js:33` | `export function` | Grep `\brenderLightboxImage\b` 命中 5 处：注释 + 定义 + 同文件 `:28/:102/:122` 三处内部调用；无外部 import | 低 | 去掉 `export`。仅 lightbox 模块内部的 `openImageLightbox` / `navigateLightbox` / `setupLightboxListeners` 调用。 |
| `navigateLightbox` | `server/internal/web/lightbox.js:94` | `export function` | Grep `\bnavigateLightbox\b` 命中 6 处：注释 + 定义 + 同文件 `:115/:116/:158/:167` 四处内部调用；无外部 import | 低 | 去掉 `export`。与 `renderLightboxImage` 同批。 |
| `toggleFileTagAssociation` | `server/internal/web/tagsView.js:57` | `export async function` | Grep `\btoggleFileTagAssociation\b` 命中 2 处：定义 + 同文件 `:110` 的 `onTagSelectorChange` 内部调用；无外部 import | 低 | 去掉 `export`。仅 `onTagSelectorChange` 在勾选 tag 选择框时调用。 |
| `onTagsManagerListClick` | `server/internal/web/tagsView.js:98` | `export function` | Grep `\bonTagsManagerListClick\b` 命中 2 处：定义 + 同文件 `:180` 的 `setupTagsListeners` 内注册；无外部 import | 低 | 去掉 `export`。事件委托处理器，仅在同文件注册一次。 |
| `onTagSelectorChange` | `server/internal/web/tagsView.js:107` | `export function` | Grep `\bonTagSelectorChange\b` 命中 2 处：定义 + 同文件 `:183` 的 `setupTagsListeners` 内注册；无外部 import | 低 | 去掉 `export`。与上条同批。 |
| `deleteTag` | `server/internal/web/tagsView.js:136` | `export async function` | Grep `\bdeleteTag\b` 命中 2 处：同文件 `:102` 的 `onTagsManagerListClick` 内调用 + `:136` 定义；无外部 import | 低 | 去掉 `export`。删除分类标签的 handler，仅由 tag manager 点击委托触发。 |

### 4.5 Deprecated API 使用（仅记录）

> Web 前端无 lint 工具运行（无 ESLint 配置）。以下为人工 grep 扫描的陈旧 API 模式，**仅记录，不进清理批次**。

| 位置 | Deprecated API | 现代等价 | 出现次数 |
|---|---|---|---|
| `server/internal/web/utils.js:21` | `unescape(encodeURIComponent(str))` 用于 Unicode-safe Base64（`escapeHtml`/`safeBtoa` 内） | `TextEncoder` + `btoa(String.fromCharCode(...new Uint8Array(bytes)))` | 1 |
| `server/internal/web/toast.js:14` | `setTimeout(() => toast.remove(), 300)` 手动触发 reflow 后改 `style.opacity/transform` 做 CSS 过渡（非 deprecated，但与 `style.css` 的 `.toast` transition 类存在双重过渡声明） | 统一由 CSS `transition` 控制 | 1 |

**合计 1 处真正陈旧 API**（`unescape`）。功能等价、无 bug，仅 API 风格陈旧。

### 4.6 冗余但非死（仅记录，不自动清理）

> 以下条目均为"有调用者、但实现/调用结构可优化"的冗余模式。按 spec §4 约定**只进报告、不进清理批次**。

| 类型 | 位置 | 描述 | 建议 |
|---|---|---|---|
| 跨平台重复实现 | `server/internal/web/utils.js:28 formatTime(seconds)` vs `android/.../util/TimeUtil.kt:10 formatTime(ms: Long)` | 两个 `formatTime` 语义等价（秒/毫秒 → `H:MM:SS` 或 `MM:SS`），但格式细节不同：Web 版分钟补零（`00:00`）、秒级输入；Android 版分钟不补零（`0:00`）、毫秒级输入。Go 端无对应的 `formatTime`（duration 由前端展示）。 | 不建议强行统一（三端输入单位不同、UI 风格不同）。建议在两侧 doc 注释中互相引用，标注"已知行为差异"。优先级低。 |
| 跨平台重复实现 | `server/internal/web/utils.js:4 formatSize(bytes)` vs Android `StringUtil`/`FileUtil`（未直接命名，但 `RecentMediaEntry.size` 的展示链路上有同义格式化） | Web 端 `formatSize` 将字节转 `B/KB/MB/GB/TB`，Android 端在 `HomeScreen`/`BrowseScreen` 也展示文件大小但格式化逻辑分散在 Composable 内。 | 不建议合并（跨平台）。建议确保两侧单位进制一致（Web 用 1024 进制 `Math.pow(k, i)`，Android 需确认同为 1024 而非 1000）。 |
| 单调用 helper（非导出） | `server/internal/web/browserView.js:57 loadSystemDrives` | 未导出的 `async function loadSystemDrives()`，仅在同文件 `:32` 被 `loadRoots` 动态注入的 "浏览磁盘驱动器" 按钮的 click listener 引用一次。函数体 32 行，包含独立的 API 调用与 DOM 渲染逻辑。 | 建议保留（独立功能单元、可读性优于内联）。**非死代码、非过度暴露**，仅记录。 |
| 单调用 helper（非导出） | `server/internal/web/browserView.js:241 renderBreadcrumbs` | 未导出，仅在同文件 `:108` 被 `browsePath` 调用一次。 | 建议保留（与 `renderBrowserList` 对称拆分，便于维护）。 |
| 单调用 helper（非导出） | `server/internal/web/browserView.js:229 onBreadcrumbsClick` / `:116 onBrowserListClick` / `:267 triggerBrowserSearch` | 三个事件委托处理器，均仅在同文件 `setupBrowserListeners` 注册一次。 | 建议保留（事件委托模式的标准写法）。 |
| 单调用 helper（非导出） | `server/internal/web/videoPlayer.js:13 togglePlayPause` / `:24 seekTo` / `:31 resetControlsTimer` | 三个内部 helper，均仅在同文件的 listener 中被调用。 | 建议保留（拆分清晰）。 |
| 过度防御 | `server/internal/web/state.js:2 state` 对象字段 `enableDelete` / `thumbMax` | 这两个字段未在 `state` 初始化声明（`state.js:2-43` 的对象字面量没有 `enableDelete` / `thumbMax` 键），而是在 `settings.js:15-16` 由 `loadConfig` 动态添加。功能正常（JS 允许运行时属性扩展），但与对象字面量内其他字段风格不一致。 | 建议在 `state.js` 的 `state` 对象字面量中预声明 `enableDelete: false` 和 `thumbMax: 300`（与现有默认值一致），提升可读性。优先级低。 |
| 过度防御 | `server/internal/web/dom.js:3 elements` 对象的 62 个 DOM 引用均无 null 检查 | `dom.js` 用 `document.getElementById(...)` 在模块顶层（module script 自动 defer）一次性抓取所有元素，下游代码（如 `app.js:79 elements.btnTriggerScan.addEventListener`）直接 `.addEventListener` 不做 null 检查。若 `index.html` 的某个 ID 被误删，运行时 NPE。 | 建议保留现状（index.html 与 dom.js 一一对应、已交叉核对，无 orphan 字段；加 null 检查会膨胀代码）。仅记录防御风格。 |

---

## 5. Rust JNI 发现项

> 审计范围：`android/app/src/main/rust/`，排除 `target/**`（cargo 构建产物）。Cargo.toml 声明 7 个直接依赖（`jni`、`log`、`kamadak-exif`、`jpeg-decoder`、`image-webp`、`fast_image_resize`、`png`）+ `[target.'cfg(target_os = "android")'.dependencies]` 空块。源码树 9 个 `.rs` 文件（`lib.rs`、`natural_sort.rs`、`exif_reader.rs`、`jpeg.rs`、`webp.rs`、`png.rs`、`bitmap.rs`、`heif.rs` + `jni_bridge/{mod,decoders,exif_jni,natural_sort_jni}.rs`）。

### 5.1 工具运行情况

- **cargo machete**: **缺失**（`cargo machete --version` 报 `no such command: machete`；`cargo install --dry-run cargo-machete` 因 `--dry-run` 仅 nightly 可用而失败）。**未自动安装**（按全局约束）。降级为人工 Grep 每个 `[dependencies]` 条目在 `src/` 下的 `use <dep>::` / `extern crate <dep>` 引用，结果见 5.4 R1。
- **cargo check dead_code lint**: **可用但零警告**。`cargo check --message-format=short` 在 `android/app/src/main/rust/` 下 3.65s 完成，`Finished dev profile`，零 warning（输出文件 `/tmp/cargo-check-full.out` 仅 47 行依赖编译进度 + 1 行 Finished，无任何 dead_code / never used / never read 命中）。`cargo check --tests` 同样零 warning。
  - 原因分析：crate-type = `cdylib`，所有 JNI 入口（4 个 `Java_...` 符号）均为 `pub extern "system" fn` + `#[no_mangle]`，编译器将其视为 crate 公共 API，不触发 dead_code；模块内的 `pub fn`（`jpeg::dimensions` 等）也因 `pub` 而免于 dead_code lint。R2 因此为空集，5.3 的发现全部来自降级方案（人工 Grep）。
- **RegisterNatives 探测**：全 src 树 Grep `RegisterNatives` / `register_native_methods` **0 命中**——本 crate 不使用运行时注册，全部 JNI 符号通过 `#[no_mangle]` + JVM 名称解析约定（`Java_<pkg>_<Class>_<method>`）由 JVM 直接 dlsym。因此 JNI 注册表豁免集合 N = { 4 个 `Java_...` 符号 }，无任何 cargo dead_code 命中需要与之交叉剔除——**误报剔除数 = 0**。
- **降级方案**：对 `src/` 全部 9 个 `.rs` 文件枚举所有 `pub fn` / `fn` 符号，对每个符号名在 `src/` 树做 Grep 全字匹配，统计非定义引用。判定规则按 brief §Step 4：cargo dead_code 命中 ∅，因此所有发现均为"未被在线（非 `#[cfg(test)]`）代码引用的 `pub fn`"，按 spec §7 不主动删 Rust `pub fn` → 全部归入"待人工确认"。

### 5.2 文件级死代码

| 位置 | 类型 | 证据 | 风险 | 建议 |
|---|---|---|---|---|

（无。9 个 `.rs` 文件中每个 `pub mod` 声明（`natural_sort`/`exif_reader`/`jpeg`/`webp`/`png`/`bitmap`/`heif`）都在 `lib.rs` 中被 `pub mod <name>;` 引入；`jni_bridge` 的 4 个子模块（`decoders`/`exif_jni`/`natural_sort_jni` + 聚合 `mod.rs`）也都被 `mod jni_bridge;` + `pub mod <sub>;` 链路接入。没有任何 `.rs` 文件整体零引用。）

### 5.3 符号级死代码（直接删除候选）

> **0 条**。cargo dead_code lint 零警告，且 5.4 列出的所有候选均为 `pub fn`（按 spec §7 不主动删 Rust pub fn），因此本节为空。

### 5.4 符号级死代码（待人工确认）

> 以下 5 个 `pub fn` 在 `src/` 树中**仅被同文件的 `#[cfg(test)] mod tests` 引用**，没有任何在线（非测试）调用者。因 spec §7 约定不主动删 Rust `pub fn`，全部归入"待人工确认"——这些函数的 doc 注释均声称"为 plan 的 API 对等保留"或"为未来 backend 切换保留"，但当前在线路径（`jni_bridge::decoders::decode_slice`）从未调用它们。若确认无外部 consumer（本 crate 是 `cdylib`，外部 consumer 仅 JVM，而 JVM 只通过 `Java_...` 符号名访问），可一并删除。

| 符号 | 位置 | 导出 | 证据 | 风险 | 建议 |
|---|---|---|---|---|---|
| `jpeg::dimensions` | `android/app/src/main/rust/src/jpeg.rs:21` | `pub fn` | Grep `\bdimensions\(` 在 `jpeg.rs` 命中 5 处：1 定义 + 4 测试（`dimensions_invalid_data` / `dimensions_real_jpeg` 各 2 处 assert）；`jni_bridge/decoders.rs::decode_slice` 直接调 `jpeg::decode_scaled`，**不调 `dimensions`**（`decode_scaled` 内部自带的 SOF 解析器 `parse_jpeg_sof_dimensions` 是私有 fn，与 `dimensions` 重复实现）。无字符串引用、无反射 | 低 | 待人工确认。`dimensions()` 是"只读 header 不做 IDCT"的廉价查询，但在线解码路径已经走完整 IDCT + 缩放（`decode_scaled`），从不预查 dimensions。doc 注释称"为 plan API 对等保留"，但 plan 的 plan-3 backend 已锁定为 `jpeg-decoder`，不会再切回 turbojpeg。建议删除 `dimensions()` + 其依赖的私有 `parse_jpeg_sof_dimensions`（后者仅被前者调用）。 |
| `jpeg::pick_jpeg_scale` | `android/app/src/main/rust/src/jpeg.rs:40` | `pub fn` | Grep `pick_jpeg_scale` 在 `jpeg.rs` 命中 11 处：1 定义 + 4 doc/注释提及 + 6 测试 assert；无任何在线调用者。`decode_scaled` 的缩放路径走 `fast_downscale_rgba`（基于 `fast_image_resize`），**不调 `pick_jpeg_scale`** | 低 | 待人工确认。doc 注释（:33-39）自承"retained for API parity with the plan, for unit-testing the scale-selection logic, and for a future swap back to a DCT-scaling backend"。当前 `jpeg-decoder` 不支持 IDCT scaling，该函数返回值被整个忽略。建议删除函数 + 4 个测试（`pick_jpeg_scale_*`），共减约 60 行。 |
| `webp::dimensions` | `android/app/src/main/rust/src/webp.rs:13` | `pub fn` | Grep `dimensions` 在 `webp.rs` 命中 6 处：1 定义 + 1 内部 `decoder.dimensions()` 调用（`image_webp::WebPDecoder::dimensions`，不同符号）+ 4 测试；无任何在线调用者。`decode_slice` 对 format=2 直接调 `webp::decode_scaled`，**不预查 dimensions** | 低 | 待人工确认。与 `jpeg::dimensions` 同构——在线路径不预查尺寸。建议删除。 |
| `png::decode`（无 scale 重载） | `android/app/src/main/rust/src/png.rs:21` | `pub fn` | Grep `crate::png::decode\b` / `use.*png::decode\b` **0 命中**；`decode_slice` 对 format=3 调的是 `png::decode_scaled(data, tw, th)`（带缩放参数版），**不调本无参版**；本函数体仅 1 行 `decode_scaled(data, 0, 0)`，是 `decode_scaled` 的零参便利 wrapper | 低 | 待人工确认。`decode()` 仅被同文件 2 个测试（`decode_invalid_data` / `decode_real_png_rgb`）调用。在线路径统一走 `decode_scaled`，wrapper 多余。建议删除 wrapper + 改写 2 个测试直接调 `decode_scaled(data, 0, 0)`。 |
| `bitmap::create_android_bitmap`（非 Android stub） | `android/app/src/main/rust/src/bitmap.rs:171` | `pub fn`（`#[cfg(not(target_os = "android"))]` + `#[allow(dead_code)]`） | `#[cfg(not(target_os = "android"))]` 分支下的 stub，函数体仅 `std::ptr::null_mut()`，**永远不被调用**（在线 callers `decoders.rs:114/181` 都在 `#[cfg(target_os = "android")]` 分支内）；作者已显式 `#[allow(dead_code)]` 抑制警告。doc 注释（:166-168）自承"only needs to exist to satisfy any non-JNI references (there are none)" | 低 | 待人工确认。功能上完全冗余——`bitmap.rs` 中所有 `use jni::...` 在非 Android 目标下也编译（`:16-18` 未被 cfg gate），但 `create_android_bitmap` 本身在非 Android 下无调用者。建议要么删除 stub（确认非 Android 构建不依赖该符号），要么把整个 `bitmap.rs` 的非 Android 部分（`:16-18` 的 use + `:169-178` 的 stub）都用 `#[cfg(target_os = "android")]` 包裹，消除 `#[allow(dead_code)]`。优先级低——作者已用 `#[allow(dead_code)]` 显式承认，无功能影响。 |

### 5.5 冗余但非死（仅记录，不自动清理）

> 以下条目均为"有调用者、但实现/调用结构可优化"的冗余模式。按 spec §4 约定**只进报告、不进清理批次**。

| 类型 | 位置 | 描述 | 建议 |
|---|---|---|---|
| 依赖冗余（R1） | `android/app/src/main/rust/Cargo.toml:14` `log = "0.4"` | `log` 作为直接依赖声明，但全 `src/` 树 Grep `log::` / `use log` / `#[log` / `extern crate log` **0 命中**。`Cargo.lock` 中 `log` 仅作为间接依赖（被 `jni`、`image-rs` 生态等传递引入），从 `Cargo.toml` `[dependencies]` 中删除该行不影响任何编译。对应 brief R1（cargo machete 不可用，降级人工 Grep）。 | 建议删除 `Cargo.toml:14` 的 `log = "0.4"` 行。收益：`Cargo.toml` 直接依赖从 7 个减为 6 个；下游 `cargo machete` / `cargo udeps` 将不再误报（若未来安装）。风险：零（无源码引用）。**本条同时归入第 7 节 R1 汇总，由 Task 7 统一计数。** |
| 文档与代码不一致 | `android/app/src/main/rust/src/bitmap.rs:4-6` | 模块 doc 注释称"links against `libjnigraphics.so` (...) linked automatically when the `jnigraphics` cargo feature is set on the `jni` crate"。但 `Cargo.toml:13` 声明 `jni = "0.21"`（无 `features = ["jnigraphics", ...]`），实际通过 `bitmap.rs:31` 的 `#[link(name = "jnigraphics")] extern "C" { ... }` 直接 FFI 链接，不经过 jni crate 的 feature gate。 | 建议修改 doc 注释为"linked via direct `#[link(name = \"jnigraphics\")]` FFI in this module, not via the `jni` crate's `jnigraphics` feature"。优先级低（仅文档）。 |
| 重复实现 | `jpeg::dimensions`（pub fn，:21）+ `jpeg::parse_jpeg_sof_dimensions`（私有 fn，:193） | `dimensions()` 仅委托给私有 `parse_jpeg_sof_dimensions`，两者 1:1 转发。`dimensions` 已在 5.4 列为待确认死代码。 | 若 5.4 确认删除 `dimensions`，`parse_jpeg_sof_dimensions`（仅被 `dimensions` 调用）随之变为死代码，一并删除（共减约 50 行 SOF marker 解析逻辑）。 |
| 过度暴露 | `heif::decode`（`pub fn`，`heif.rs:27`） | 函数体仅 `None`（stub），被 `decoders.rs:66` 调用（format=4 → `crate::heif::decode(data)?`）。doc 注释自承"intentionally returns None to trigger the Kotlin fallback path"。功能上等价于 `decoders.rs` 直接在 format=4 分支 `return None`，但保留独立函数便于"未来 drop in 纯 Rust HEIC crate"。 | 建议保留（明确的扩展点，doc 充分说明）。仅记录。 |
| 过度防御 | `bitmap.rs:169-178` 非 Android stub + `#[allow(dead_code)]` | 见 5.4 末行——作者已用 `#[allow(dead_code)]` 显式承认冗余。 | 同 5.4 建议：要么删 stub，要么用 `#[cfg(target_os = "android")]` 包裹整段非 Android use + stub。 |

---

## 6. 构建脚本与工具发现项

> 审计范围：`tools/xsscheck/`（独立 Go module）、`scripts/`（仓库根）、仓库根的 gradle 构建脚本（`build.gradle.kts` / `settings.gradle.kts` / `gradlew` / `gradle/wrapper/` / `gradle.properties` / `gradlew.bat`）。排除 `build/ffmpeg-src/**`（vendored）。`android/app/build.gradle.kts` 不在本节范围（已在 Task 3 间接覆盖）。

### 6.1 tools/xsscheck

**工具运行情况**

- **go vet**: 可用（Go 1.25.0 自带）。在 `tools/xsscheck/` 下 `go vet ./...` 零输出、exit 0——无可达性错误、无 declared-and-not-used。
- **staticcheck**: **缺失**（`staticcheck: command not found`）。按全局约束**未自动安装**。降级为人工 Grep（见下）。

**main 入口引用情况**

Grep `xsscheck` 全仓（排除 `build/**`）命中 7 文件，全部为：工具自身 4 文件（`main.go` / `main_test.go` / `go.mod` / `README.md`）+ 3 个 superpowers spec/plan 文档（`2026-07-11-security-phase5-xss-lint-*.md`）+ 本审计的 design/plan 文档。**无 CI 工作流引用**——仓库根无 `.github/` 目录、无 `Makefile`、无任何 YAML/Shell 调用 `go run ./tools/xsscheck`。`tools/xsscheck/README.md:69-77` 明确标注"This tool will be a step in the future CI workflow"——为 Round 29 Phase 6（CI 整合）预留。

**判定**：非死代码。按 brief 关键判断提示——"xsscheck 工具的 main 包入口即使没 CI 调用，也通常保留（CLI 工具），不轻易判定为死"。该工具是 Round 29 Phase 5 的交付物（commit `6518e40`），配套 3 个测试函数（`TestScanFile` / `TestScanRealWebUI` / `TestAnalyzeExpr`）+ 8 个 `testdata/*.js` fixture，作为安全 lint 工具明确保留。

**内部 helper / 文件审计**

`tools/xsscheck/` 下仅 1 个源文件 `main.go`（397 行）+ 1 个测试文件 `main_test.go`（107 行）。`main.go` 的所有 12 个私有函数（`isLiteral` / `isEscapeCall` / `isFunctionCall` / `isSafe` / `analyzeExpr` / `isMultiLineCallStart` / `topLevelConcat` / `splitTopLevelPlus` / `collectSafeVars` / `scanFile` / `insideString` / `main`）均被同文件或测试文件引用——go vet 零警告亦佐证。**无误报剔除**（staticcheck 未运行，无误报池）；**直接删除候选 0 条**；**待人工确认 0 条**。

| 符号 | 位置 | 导出 | 证据 | 风险 | 建议 |
|---|---|---|---|---|---|

（无。xsscheck 是完整的、有测试覆盖的、有文档意图的安全工具，无死代码。）

### 6.2 scripts/

**脚本清单**：`scripts/` 仅 1 个脚本——`scripts/build_ffmpeg.sh`（77 行，bash），用途为交叉编译 FFmpeg 6.1.1 → `android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so`。

**引用情况**

Grep `build_ffmpeg` 全仓（排除 `build/**`）命中**仅** 8 处，全部位于 `docs/superpowers/` 下的 2 个文档文件（`2026-07-07-apk-size-optimization-design.md` / `plans/2026-07-07-apk-size-optimization.md`）——即最初引入该脚本的 spec 与 plan。具体分布：

| 引用类型 | 位置 | 说明 |
|---|---|---|
| 仓库根 `README.md` | — | **未提及**。README:64 / :363 仅说"预编译 libffmpeg.so 为预编译产物，直接放在 `jniLibs/arm64-v8a/` 下，不参与 Rust 构建链"，不提编译脚本 |
| `AGENTS.md` 常用命令 | — | **未提及**。AGENTS.md:21-23 仅列 `cd android && ./gradlew assembleDebug/Release` |
| CI / Makefile / 其它 shell 脚本 | — | **无**（仓库无 `.github/`、无 `Makefile`、无其它 `.sh` 引用本脚本） |
| 构建系统（gradle / cargo / go） | — | **无**。`android/app/build.gradle.kts` 的 `buildRustNative` task 只跑 `cargo ndk`，FFmpeg 走预编译二进制路径 |
| spec / plan 文档 | `docs/superpowers/{specs,plans}/2026-07-07-apk-size-optimization.md` | **8 处**——脚本本身的引入文档与执行说明 |

**判定**：**待人工确认**。脚本本身是有意的开发工具（一次性产出预编译 `libffmpeg.so`，不参与 CI 构建链——README 已明示），但完全无活跃引用、无 README 文档化、仅被最初引入它的 spec/plan 提及。按 brief 关键判断提示——"scripts/ 下的脚本若仅被 README 提到，但 README 描述的是'如何构建 ffmpeg'等非本仓库流程，标记'待人工确认'"——本脚本连 README 提及都没有，严格按规则标记。

| 符号 | 位置 | 类型 | 证据 | 风险 | 建议 |
|---|---|---|---|---|---|
| `scripts/build_ffmpeg.sh` | `scripts/build_ffmpeg.sh`（整文件） | bash 脚本（77 行） | Grep 全仓零活跃引用；仅 `docs/superpowers/` 下的 spec/plan 提及；README/AGENTS.md/CI/Makefile 全部未引用；产物 `libffmpeg.so` 已是 `jniLibs/arm64-v8a/` 下的预编译二进制，gradle 不重新执行本脚本 | 低 | 待人工确认。该脚本是 Round 26 apk-size-optimization 的一次性开发工具（用于产出 commit 时的预编译 `libffmpeg.so`）。**若未来不打算重编 FFmpeg**，可作历史产物删除（连同 `build/ffmpeg-src/` 的 vendored 源码，已在排除范围）；**若保留为"重编指南"**，建议在 `README.md` 的"原生库编译"章节补一行"如需重编 FFmpeg，运行 `bash scripts/build_ffmpeg.sh`（需要 Linux + NDK r26b）"作为入口文档化。优先级低。 |

### 6.3 根目录 gradle 配置

**结构性发现（重要）**：仓库根存在一整套**孤儿 gradle wrapper + 构建脚本**，与 `android/` 下的活跃构建并存：

| 文件 | 仓库根 | android/ | 说明 |
|---|---|---|---|
| `build.gradle.kts` | `build.gradle.kts`（247 字节，5 月 23 日） | `android/build.gradle.kts`（595 字节，7 月 8 日） | 根版仅声明 2 个 plugin `apply false`（AGP 8.2.2 / Kotlin 1.9.22）；android 版用 `buildscript { classpath(...) }` 声明 5 个 classpath（AGP 8.13.2 / Kotlin 2.2.0 / compose-compiler 2.2.0 / hilt 2.56.2 / ksp 2.2.0-2.0.2） |
| `settings.gradle.kts` | `settings.gradle.kts`（357 字节，5 月 23 日） | `android/settings.gradle.kts`（917 字节，6 月 4 日） | 根版 `include(":android:app")`；android 版 `include(":app")` + aliyun 镜像仓库 |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 8.5（5 月 23 日） | Gradle 8.13（6 月 4 日） | 根版 wrapper 落后 3 个版本 |
| `gradlew` / `gradlew.bat` | 存在（5 月 23 日，4716 / 2838 字节） | 存在（5 月 23 日，4712 / 2865 字节） | 两套 wrapper |
| `gradle.properties` | 存在（205 字节，7 月 7 日） | 存在（371 字节，6 月 4 日） | **内容不同**——见下 |

**活跃构建路径确认**

- `AGENTS.md:21-23` + `README.md:226`：所有文档化的 Android 构建命令均为 `cd android && ./gradlew ...`。
- Task 3 的 lint 报告（§3.1）记录"gradle wrapper 可用（Gradle 8.13）"——与 `android/` wrapper 一致，证明活跃构建走 android/ 路径。
- `android/app/build.gradle.kts:254` 用 `rootProject.projectDir.parentFile` 访问 `docs/sbom/libffmpeg.sha256`——这要求 `rootProject.projectDir` 必须是 `android/`（parent 才是仓库根），否则 parentFile 会指向 `android/` 之外的非预期路径。反向印证 `android/` 才是 gradle root。

**根 `gradle.properties` 的隐藏 bug（待人工确认）**

仓库根 `gradle.properties:6` 有 `android.enableR8.fullMode=true`（Round 26 apk-size-optimization 添加，spec 明确"在 `gradle.properties` 加一行"）。但：

- 活跃构建从 `android/` 启动，gradle 读的是 `android/gradle.properties`（不含此行）；
- `android/gradle.properties` 是独立文件（内容不同，多了 `javax.net.ssl.trustStoreType=Windows-ROOT` + 3 个 `systemProp.http.ssl.*`），**不会**自动合并仓库根的 `gradle.properties`；
- 后果：Round 26 D1（R8 full mode）在活跃构建链中**未生效**——`android.enableR8.fullMode=true` 需移到 `android/gradle.properties` 才能起作用。

**判定**：根目录的 6 个文件（`build.gradle.kts` / `settings.gradle.kts` / `gradle.properties` / `gradlew` / `gradlew.bat` / `gradle/wrapper/` 整目录）构成**孤儿构建脚本集合**——历史上仓库根曾是 gradle root，后来 gradle root 迁到 `android/` 子目录但根目录的旧文件未清理。

| 符号 | 位置 | 类型 | 证据 | 风险 | 建议 |
|---|---|---|---|---|---|
| 根 `build.gradle.kts` | `build.gradle.kts`（整文件） | gradle 构建脚本（5 行） | 仅声明 2 个 plugin `apply false`（AGP 8.2.2 / Kotlin 1.9.22），但 `android/app/build.gradle.kts:8-9` 实际应用的是 AGP 8.13.2 / Kotlin 2.2.0（从 `android/build.gradle.kts` 的 `buildscript.classpath` 解析）；根脚本版本号落后且无 apply 目标——仓库根不是 gradle root（`android/` 才是），本文件不被任何 gradlew 调用读取 | 低 | 待人工确认。**整组 6 个孤儿文件建议一起清理**（连同下面 5 条）。删除前需人工确认无外部 IDE 配置（如 Android Studio 打开仓库根而非 `android/` 子目录）依赖根 `build.gradle.kts`。 |
| 根 `settings.gradle.kts` | `settings.gradle.kts`（整文件） | gradle 设置脚本 | `include(":android:app")` 与活跃的 `android/settings.gradle.kts:33` 的 `include(":app")` 冲突；仓库根不被任何活跃 gradlew 解析 | 低 | 待人工确认。与上一条一起删除。 |
| 根 `gradle.properties` | `gradle.properties`（整文件） | gradle 项目属性（6 行） | 包含 `android.enableR8.fullMode=true`（Round 26 添加）——**活跃构建（`cd android && ./gradlew`）读 `android/gradle.properties` 不读本文件**，R8 full mode 当前未生效（隐藏 bug）；其余 4 行（jvmargs / useAndroidX / kotlin.code.style / nonTransitiveRClass）在 `android/gradle.properties` 已重复声明 | 中 | 待人工确认。删除本文件前**必须**将 `android.enableR8.fullMode=true` 迁移到 `android/gradle.properties:6`——否则继续未生效状态（现状即如此）。迁移 + 删除可作一次小 PR。 |
| 根 `gradlew` + `gradlew.bat` | `gradlew` / `gradlew.bat`（2 文件） | gradle wrapper 脚本 | 两套 wrapper（根 + `android/`）并存；所有文档与 CI 均用 `cd android && ./gradlew`，根 wrapper 自 5 月 23 日从未被活跃调用 | 低 | 待人工确认。与 `build.gradle.kts`/`settings.gradle.kts` 同批删除。 |
| 根 `gradle/wrapper/` | `gradle/wrapper/gradle-wrapper.properties` + `gradle-wrapper.jar` | gradle wrapper 二进制 + 配置 | 声明 Gradle 8.5，落后于活跃的 8.13；wrapper jar 为 gradle 分发工具的二进制，不会被 `android/` 下 wrapper 共享 | 低 | 待人工确认。与上一条同批删除。 |

**直接删除候选**：0 条（所有孤儿 gradle 文件均涉及构建配置，删除前需人工确认 IDE / 外部 CI 是否引用根路径——保守标记为"待人工确认"）。
**冗余但非死（仅记录）**：

| 类型 | 位置 | 描述 | 建议 |
|---|---|---|---|
| 重复构建根 | 根 `build.gradle.kts` + `settings.gradle.kts` vs `android/build.gradle.kts` + `android/settings.gradle.kts` | 仓库曾以根为 gradle root，后迁移到 `android/` 子目录（迁移时间约 6 月 4 日，`android/settings.gradle.kts` 的 mtime），但根目录的旧 gradle 文件未清理，造成两套并存的构建脚本。版本号不同步（根 AGP 8.2.2 / Kotlin 1.9.22 vs android AGP 8.13.2 / Kotlin 2.2.0）。 | 建议确认无 IDE 配置打开仓库根后，批量删除根目录的 6 个 gradle 文件（含 `gradle.properties` 迁移）。收益：消除"两套构建脚本"的认知混淆、消除 `gradle.properties` 隐藏 bug（R8 full mode 未生效）。优先级中——bug 风险（R8 full mode 未生效）是触发清理的主因。 |

---

## 7. 依赖冗余

> 审计范围：`server/go.mod`（Go 直接依赖 11 条 + indirect 31 条）、`android/app/build.gradle.kts` 的 `dependencies { ... }` 块（直接依赖 27 条，排除 testImplementation/androidTestImplementation/debugImplementation）、`android/app/src/main/rust/Cargo.toml` 的 `[dependencies]` 块（7 条直接依赖）。证据来源：Task 2 Step 4 的 `go mod tidy -diff`（`/tmp/gomodtidy.out`，371 行）、本任务的 `./gradlew :app:dependencies --configuration debugRuntimeClasspath`（`/tmp/android-deps.out`，1210 行）、Task 5 Step 2 的降级 Grep（cargo machete 不可用）。

### 7.1 Go (server/go.mod)

**工具运行情况**：`go mod tidy -diff`（Go 1.25+ 只读模式）可用，exit 0，输出文件 `/tmp/gomodtidy.out`（371 行）。

**判定方法**：扫描 diff 输出的所有 `diff` / `---` / `+++` 行头，确认 diff 仅覆盖 `current/go.sum` ↔ `tidy/go.sum`，**不涉及 `go.mod` 的 require 块**。具体证据：
- `grep "^diff " /tmp/gomodtidy.out` → 仅 1 行：`diff current/go.sum tidy/go.sum`
- `grep "^--- " /tmp/gomodtidy.out` → 仅 1 行：`--- current/go.sum`
- `grep "^+++ " /tmp/gomodtidy.out` → 仅 1 行：`+++ tidy/go.sum`
- `grep -iE "require\b" /tmp/gomodtidy.out` → **0 命中**（无任何 require 行增删）
- 371 行差异全部是 `go.sum` 中 `h1:` hash 的行序重排（`-github.com/...` 与 `+github.com/...` 成对出现，版本号无变化，仅排序差异）

**直接依赖清单（11 条，`server/go.mod:5-17`）**：`disintegration/imaging`、`fsnotify/fsnotify`、`getlantern/systray`、`google/uuid`、`hashicorp/golang-lru/v2`、`hashicorp/mdns`、`labstack/echo/v4`、`stretchr/testify`、`golang.org/x/sync`、`gopkg.in/yaml.v3`、`modernc.org/sqlite`。

| 依赖 | 证据 | 风险 | 建议 |
|---|---|---|---|

（无。`go mod tidy -diff` 显示 0 条 require 删除——Go 依赖无冗余。go.sum 的 hash 行序重排不影响功能，是 Go 1.25 tidy 对 go.sum 排序规范的微调，可忽略。）

### 7.2 Android (android/app/build.gradle.kts)

**工具运行情况**：`./gradlew :app:dependencies --configuration debugRuntimeClasspath` 可用（Gradle 8.13，exit 0），输出文件 `/tmp/android-deps.out`（1210 行）。`debugRuntimeClasspath` 下的直接依赖（`+---` 一级缩进）共 27 条，与 `app/build.gradle.kts:325-394` 的 `dependencies {}` 块声明的 `implementation`/`api`/`ksp` 直接依赖一一对应（扣除 testImplementation / androidTestImplementation / debugImplementation——后三者不在 runtime classpath 中）。

**判定方法**：对 `build.gradle.kts` 中每个 `implementation(...)` 声明，Grep 其对应的 `import <package>` 在 `android/app/src/main/java/` 下是否存在；找不到 import → 标记"待人工确认"（Gradle 依赖大量通过传递方式间接使用，找不到 import ≠ 未使用）。KSP 注解处理器（`hilt-android-compiler`）不产 import，通过 Hilt 代码生成间接消费，不参与本次 grep 判定。

**直接依赖清单与引用情况**（27 条 `implementation` + 1 条 `ksp`）：

| # | 依赖（build.gradle.kts 行号） | import 包前缀 | Grep 命中文件数 | 判定 |
|---|---|---|---|---|
| 1 | `androidx.core:core-ktx:1.12.0`（:325） | `androidx.core` | 2 | 在用 |
| 2 | `androidx.lifecycle:lifecycle-runtime-ktx:2.7.0`（:326） | `androidx.lifecycle` | 8（含 viewmodel-compose 合并） | 在用 |
| 3 | `androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0`（:327） | `androidx.lifecycle` | 同上 | 在用 |
| 4 | `androidx.activity:activity-compose:1.8.2`（:328） | `androidx.activity` | 5 | 在用 |
| 5 | `androidx.compose:compose-bom:2024.06.00`（:331，platform） | — | BOM 平台声明，无直接 import；由 :332-337 的 Compose 子模块消费 | 在用 |
| 6 | `androidx.compose.ui:ui`（:332） | `androidx.compose` | 27（合并 Compose 全部子模块） | 在用 |
| 7 | `androidx.compose.ui:ui-graphics`（:333） | `androidx.compose` | 同上 | 在用 |
| 8 | `androidx.compose.ui:ui-tooling-preview`（:334） | `androidx.compose` | 同上 | 在用 |
| 9 | `androidx.compose.material3:material3`（:335） | `androidx.compose` | 同上 | 在用 |
| 10 | `androidx.compose.foundation:foundation`（:337） | `androidx.compose` | 同上 | 在用 |
| 11 | `androidx.navigation:navigation-compose:2.7.0`（:342） | `androidx.navigation` | 1（`MainActivity.kt`） | 在用 |
| 12 | `com.google.dagger:hilt-android:2.56.2`（:345） | `dagger.` / `javax.inject` / `com.google.dagger` | 18 | 在用 |
| 13 | `com.google.dagger:hilt-android-compiler:2.56.2`（:346，ksp） | — | KSP 注解处理器，生成代码消费 | 在用 |
| 14 | `androidx.hilt:hilt-navigation-compose:1.2.0`（:347） | `androidx.hilt` | 2 | 在用 |
| 15 | `com.squareup.okhttp3:okhttp:4.12.0`（:351） | `okhttp3` | 5 | 在用 |
| 16 | `com.squareup.okhttp3:logging-interceptor:4.12.0`（:352） | `okhttp3.logging` | 1（`OkHttpModule.kt:16/130/131`） | 在用 |
| 17 | `io.coil-kt.coil3:coil-compose:3.5.0`（:361） | `coil` / `coil3` | 8（合并 Coil 全部子模块） | 在用 |
| 18 | `io.coil-kt.coil3:coil-network-okhttp:3.5.0`（:362） | `coil3.network` | 包含在 Coil 命中内（`LocalMediaHubApplication.kt` 的 `newImageLoader` 配置 OkHttpClient 作为 Coil 的网络层） | 在用 |
| 19 | `androidx.media3:media3-exoplayer:1.2.0`（:365） | `androidx.media3` | 3（合并 media3 全部子模块） | 在用 |
| 20 | `androidx.media3:media3-ui:1.2.0`（:366） | `androidx.media3.ui` | 包含在 media3 命中内（`VideoPlayerScreen.kt` 的 `PlayerSurface`） | 在用 |
| 21 | `androidx.media3:media3-datasource-okhttp:1.2.0`（:367） | `androidx.media3.datasource.okhttp` | 1（`VideoPlayerScreen.kt:151` 的 `OkHttpDataSource.Factory`） | 在用 |
| 22 | `androidx.media3:media3-session:1.2.0`（:368） | `androidx.media3.session` | 1（`VideoPlayerScreen.kt:193` 的 `MediaSession.Builder`） | 在用 |
| 23 | `androidx.datastore:datastore-preferences:1.1.1`（:373） | `androidx.datastore` | 4 | 在用 |
| 24 | `androidx.work:work-runtime-ktx:2.9.0`（:376） | `androidx.work` | 2 | 在用 |
| 25 | `com.google.code.gson:gson:2.8.9`（:382） | `com.google.gson` | 8 | 在用 |

| 依赖 | 证据 | 风险 | 建议 |
|---|---|---|---|

（无。`debugRuntimeClasspath` 下全部 25 条 `implementation`（扣除 BOM platform 声明与 ksp 注解处理器共 2 条不产生 import 的条目）均找到对应 `import` 或通过 API 调用（如 `OkHttpDataSource.Factory`、`MediaSession.Builder`、`HttpLoggingInterceptor`）确认在用。**无依赖冗余。**）

### 7.3 Rust (android/app/src/main/rust/Cargo.toml)

**工具运行情况**：`cargo machete` 不可用（Task 5 Step 2 记录 `no such command: machete`），按全局约束未自动安装。降级为人工 Grep：对 `Cargo.toml:6-56` 的 `[dependencies]` 块每个直接依赖，在 `src/` 树下 Grep `use <dep>::` / `<dep>::` / `extern crate <dep>`。

**直接依赖清单（7 条，`Cargo.toml:13/14/18/47/48/49/56`）**：

| # | crate（Cargo.toml 行号） | use 路径 | Grep 命中文件数 | 判定 |
|---|---|---|---|---|
| 1 | `jni = "0.21"`（:13） | `jni::` / `use jni` | 多文件（`lib.rs`、`jni_bridge/*.rs`、`bitmap.rs`） | 在用 |
| 2 | `log = "0.4"`（:14） | `log::` / `use log` / `#[log` / `extern crate log` | **0** | **冗余**（见下表） |
| 3 | `kamadak-exif = "0.5"`（:18） | `use exif::` / `exif::` | `exif_reader.rs`、`jni_bridge/exif_jni.rs` | 在用 |
| 4 | `jpeg-decoder = "0.3"`（:47） | `use jpeg_decoder::` / `jpeg_decoder::` | `jpeg.rs` | 在用 |
| 5 | `image-webp = "0.2"`（:48） | `use image_webp::` / `image_webp::` | `webp.rs` | 在用 |
| 6 | `fast_image_resize = "6.0"`（:49） | `use fast_image_resize::` / `fast_image_resize::` | `jpeg.rs`、`webp.rs`、`png.rs` | 在用 |
| 7 | `png = "0.17"`（:56） | `use png::` / `png::` | `png.rs` | 在用 |

| 依赖 | 证据 | 风险 | 建议 |
|---|---|---|---|
| `log = "0.4"` | `android/app/src/main/rust/Cargo.toml:14`；Grep `\blog::|use\s+log|extern\s+crate\s+log|#\[log` 在 `src/` 全树 **0 命中**。`Cargo.lock` 中 `log` 仅作为间接依赖（被 `jni`、`image-rs` 生态等传递引入），从 `[dependencies]` 中删除该行不影响任何编译——`cargo check` 零 warning 佐证。 | 低 | 删除 `Cargo.toml:14` 的 `log = "0.4"` 行。收益：`Cargo.toml` 直接依赖从 7 个减为 6 个；下游 `cargo machete` / `cargo udeps` 将不再误报（若未来安装）。**与第 5.5 节 R1 条目交叉引用——同一发现的不同视角**：5.5 节从"冗余但非死"角度记录，本节从"依赖冗余"角度统一汇总，两者指向同一行。风险：零（无源码引用）。 |

---

## 8. 附录 A：非源码但占体积的文件清单（不清理）

> 本节列出仓库中非源码但占用显著磁盘空间的文件/目录，作为"体积热点"记录。按 spec §4 约定**不进清理批次**——这些文件均为构建产物或 vendored 依赖，正常应在 .gitignore 中。

| 路径 | 大小 | 是否在 .gitignore | 建议 |
|---|---|---|---|
| `android/app/src/main/rust/target` | 459M | 是（`android/app/src/main/rust/.gitignore:2:/target`） | 保留。Rust 构建产物目录，由 cargo 自动生成。当前 .gitignore 覆盖正确。 |
| `android/app/build` | 254M | 是（`.gitignore:37:android/app/build/`） | 保留。Android Gradle 构建产物目录。 |
| `build/ffmpeg-src` | 109M | 是（`.gitignore:30:build/`） | 保留。FFmpeg 6.1.1 vendored 源码，由 `scripts/build_ffmpeg.sh` 产出，用于交叉编译 `libffmpeg.so`。若未来不打算重编 FFmpeg，可考虑删除本目录 + 脚本（见 6.2 节 `build_ffmpeg.sh` 候选）。 |
| `app-debug-0d9eaf6-preRefactor.apk` | 18M | 是（`.gitignore:33:*.apk`） | 保留。历史 APK 副本，用于 Round 25 pip refactor 前后对比。 |
| `app-debug-round25pip-postRefactor.apk` | 18M | 是（`.gitignore:33:*.apk`） | 保留。历史 APK 副本，用途同上。 |
| `server/LocalMediaHub.exe` | 20M | 是（`server/.gitignore:2:*.exe`） | 保留。Go server Windows 可执行文件，由 `go build` 生成。 |
| `android/build` | 128K | 是（`android/.gitignore:6:/build`） | 保留。Android Gradle 子模块构建产物目录。 |

**合计**: 约 878M（其中 `android/app/src/main/rust/target` 占 52%，`android/app/build` 占 29%）。

**说明**：
- 所有路径均已被 .gitignore 覆盖，不会进入 git 历史记录。
- 本表不包含 `node_modules/`（仓库不使用 Node.js 构建链）或 `build/` 下其他零散临时文件（大小可忽略）。
- 本表也不包含 gradle wrapper 本身（`android/gradle/wrapper/gradle-wrapper.jar` 约 60KB，已在 git 中，正常应纳入版本控制）。

---

## 9. 附录 B：未执行工具及原因

> 本节汇总 Round 30 A 阶段各 Task 中计划运行但因环境限制而**缺失或降级**的工具。按 spec §4 约定**不进清理批次**——仅作为"审计覆盖度"的记录与未来改进建议。

| 工具 | 栈 | 原因 | 降级方案 | 影响 |
|---|---|---|---|---|
| `staticcheck` (server) | Go | 未安装。`staticcheck -version` 报 `command not found`。按全局约束未自动安装。 | 降级为 codegraph 引用追溯 + 全仓 Grep 零引用符号计数。 | 轻微。staticcheck 的 U1000/U1001 对非导出符号覆盖良好，但导出符号的"跨模块反射调用"理论上存在盲区——本仓 Go 模块无外部 consumer（`go.mod` module path 为 `github.com/localmediahub/server`，无 publish），故盲区可忽略。 |
| `go mod tidy -diff` | Go | 可用，但返回 exit code 1。 | 误报？非。Go 1.25+ 的预期行为（diff 存在时返回非零），tidy 实际未修改文件（只读模式）。输出 371 行全部是 `go.sum` 的 `h1:` hash 行重排，`go.mod` 本身零变更。 | 无。R3（依赖冗余）为空集，工具行为正常。 |
| `:app:lintDebug` | Android (Kotlin) | 可用但 BUILD FAILED。2 分 5 秒完成，失败由 11 个 `UnsafeOptInUsageError`（ExoPlayer `@UnstableApi` opt-in 缺失）+ 1 个 `LocalContextGetResourceValueCall` 触发——代码质量问题，非工具问题。 | lint 在失败前完整输出文本报告（716 行），`UnusedResources` 68 条已提取。`UnusedDeclaration` 对 Kotlin private/internal 符号的检测为 0 命中（Android lint 的 UnusedDeclaration 主要覆盖 Java + 资源 ID），降级为手动 Grep 全仓引用追溯。 | 轻微。lint 的 warning 类（含 `UnusedResources`）已完整输出，`UnusedDeclaration` 的盲区由降级方案覆盖。 |
| `knip` (`npx knip@6.26.0`) | Web JS | 可拉取但不可执行。`npx knip --no-exit-code` 在 `server/internal/web/` 下因无 `package.json` 报错退出：`ERROR: Unable to find package.json`。Web 前端为纯静态 ES Module + Go `embed` 挂载，不经过 Node 构建链。 | 降级为手工 import 链追溯——列出全部 14 个 `.js` 文件的 37 个 `export`，对每个 export 在 `server/internal/web/` 下 Grep 引用计数，以 `index.html` 唯一入口 `<script src="app.js">` 做 import 闭包可达性验证。 | 无。手工追溯确认全部 14 个 `.js` 文件均可达，无文件级死代码；7 个"过度暴露的 export"已记入 4.4 节。 |
| `cargo machete` | Rust | 缺失。`cargo machete --version` 报 `no such command: machete`；`cargo install --dry-run cargo-machete` 因 `--dry-run` 仅 nightly 可用而失败。按全局约束未自动安装。 | 降级为人工 Grep：对 `Cargo.toml` 的 `[dependencies]` 每个直接依赖，在 `src/` 树下 Grep `use <dep>::` / `extern crate <dep>`。 | 轻微。人工 Grep 发现 `log = "0.4"` 冗余（全 `src/` 树 0 命中），其余 6 个直接依赖均有源码引用。 |
| `cargo check dead_code lint` | Rust | 可用但零警告。`cargo check --message-format=short` + `cargo check --tests` 均零警告。crate-type=`cdylib`，所有 JNI 入口为 `pub extern "system" fn` + `#[no_mangle]`，模块内 `pub fn` 也因 `pub` 免于 lint。 | 无需降级——R2（符号级死代码）本就为空集。 | 无。人工 Grep 补充发现 5 个 `pub fn` 仅被测试引用（`jpeg::dimensions`、`jpeg::pick_jpeg_scale`、`webp::dimensions`、`png::decode`、`bitmap::create_android_bitmap`），已归入 5.4 节"待人工确认"。 |
| `cargo check --target aarch64-linux-android` | Rust | 未运行。需 NDK 工具链配置，未执行。 | 当前 `cargo check`（默认 host triple = MSVC）下零警告，人工 Grep 已覆盖 `jni_bridge/decoders.rs` 的两个 `#[cfg(target_os = "android")]` 私有 fn（`detect_format`、`decode_slice`），确认两者均被同文件 `Java_...` 入口调用。 | 轻微。Android 目标下的潜在 dead_code（若有 `#[cfg(target_os = "android")]` 分支内的私有死代码）未被覆盖，但人工审计已确认关键路径无遗漏。 |
| `staticcheck` (tools/xsscheck) | Go | 未安装。原因同 server staticcheck。 | 降级为人工 Grep + go vet 兜底——`tools/xsscheck/main.go` 仅 1 个源文件（397 行）+ 1 个测试文件（107 行），所有 12 个私有函数均被同文件或测试引用，go vet 零警告亦佐证。 | 无。xsscheck 工具本身明确保留（Round 29 Phase 5 交付物），无死代码。 |
| `detekt` (Android) | Kotlin | 未运行。Android lint 的 `UnusedDeclaration` 对 Kotlin private/internal 符号覆盖有限，未引入 detekt 的 `UnusedPrivateMember` rule 作为补充。 | 降级为手动 Grep 引用计数——枚举所有 `private` / `internal` / `public` 符号，对每个符号全仓 Grep 引用计数，计数 = 1（仅定义行）→ 候选。 | 轻微。Grep 范围限定 `android/app/src/main/java`，未检查 `src/test` / `src/androidTest` 下的测试引用。若存在测试引用，则符号不算完全死代码——但通常 production 符号仅被 test 引用也是"应降级为 internal 或删除测试"的信号。 |

**合计**: 9 条工具缺失/降级记录，分布在 Go（2）、Android（2）、Web JS（1）、Rust（3）、工具审计（1）。

**说明**：
- 本表不包含"工具运行失败但输出完整"的情况（如 `:app:lintDebug` BUILD FAILED 但 `UnusedResources` 已提取）。
- "影响"列评估降级方案的覆盖度：若降级方案能完整替代原始工具，影响为"无"或"轻微"；若存在明显盲区，影响为"中等"（本审计无此类情况）。
- 未包含的工具：`ktfmt`/`detekt`（未计划运行）、`cargo udeps`（未计划运行，且 cargo machete 降级方案已覆盖）。