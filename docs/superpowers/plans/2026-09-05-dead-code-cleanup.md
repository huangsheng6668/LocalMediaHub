# 死代码清理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除四个子系统（Go server / Web / Android / Rust）中经静态分析与全仓交叉引用验证的死代码，不改变任何运行行为。

**Architecture:** 纯删除型清理。候选清单来自 `golang.org/x/tools/cmd/deadcode`（Go，RTA 全程序可达性分析）+ 两个只读探查 agent 对 Android 297 个顶层声明与 Web 33 个模块的全量交叉引用。每一项都经过"生产引用 + 测试引用 + DI/反射/Manifest/JNI 保活"三重核实。

**Tech Stack:** Go / Kotlin (Compose+Hilt) / 原生 ES Module JS / Rust（本轮无改动）

## Global Constraints

- 只删"零引用"或"仅被测试其自身逻辑的引用"的代码；**保留**以下被判定为非垃圾的类别：
  - `service.NewScanner`（AGENTS.md 明确"保留给测试"，10 个测试文件在用）
  - BLE `EncodeApiReqPayload` / `EncodeApiReqSegmentPayload` / `DecodeJsonChunkPayload` / `ChunkJsonBytes`（协议对称 codec，是活代码 `DecodeApiReqPayload` / `ChunkJsonBytesSized` round-trip 测试的 fixture，见 protocol_test.go:72-80/139-249）
  - Web test-only 导出（`clearChapterCache` / `off` / `setDecorationsForTest` / `chapterIndexToProgress` / `nextSpeed`）与 `_snapshot-helpers.mjs`（测试基础设施）
  - `NativeExif`（JNI 桥，Rust 侧 exif_jni.rs:33 有实现）
  - Hilt `@Module` 类（DI 图保活）
  - `vendor/hls.min.js.sha256`（供应链校验资产）
- 每个子系统删除后必须跑该子系统的既有测试套件（见各 Task），全部绿才算完成。
- Commit 风格：Conventional Commits，`refactor` type，每子系统一个 commit。
- Rust 本轮无改动：`cargo check` 零警告，无死代码。

---

### Task 1: Server (Go) 死代码删除

**Files:**
- Modify: `server/internal/ble/central_adapter.go:452-460`（`mustUUID` 及其 doc 注释）
- Modify: `server/internal/service/thumbnail.go:153`（`getFFprobeCmd`）、`server/internal/service/thumbnail.go:682-690`（`ffprobeSibling` 及注释）
- Modify: `server/internal/service/thumbnail_test.go`（删除仅测 `ffprobeSibling` 的用例）
- Modify: `server/internal/mdns/mdns.go:53`（`Service.Stop`）
- Modify: `server/internal/systray/systray.go:66`（`Tray.SetStatus`）
- Modify: `server/internal/service/bookparser/txt_cache.go:93-99,126-128`（`GetChapterBlocksFromText` / `GetRuneCount` 薄包装）
- Modify: `server/internal/service/bookparser/txt_cache_test.go`（改调 `GetChapterBlocksFromRunes([]rune(text), …)`；`GetRuneCount` 断言直接删除——测 stdlib 无意义）

**Interfaces:** 无新增接口；删除均为零生产引用符号（deadcode RTA + grep 三重确认）。

- [ ] **Step 1: 预检** — `grep -rn "\.Stop(\|\.SetStatus(" server/cmd server/internal --include="*.go" | grep -v _test` 确认 mdns `Service.Stop` / systray `Tray.SetStatus` 零调用点（防 deadcode 误报）
- [ ] **Step 2: 逐符号删除**（上述 Files 列表，删前 Read 精确行界）
- [ ] **Step 3: 验证** — `cd server && go build ./... && go test ./...` 全绿
- [ ] **Step 4: `go mod tidy` 检查** — 若 go.mod/go.sum 出现 diff 且仅为未用依赖则保留，否则 `git checkout` 还原
- [ ] **Step 5: Commit** — `refactor(server): remove dead code unreachable from main`

### Task 2: Web 死代码删除

**Files:**
- Delete: `server/internal/web/escape.js`（单行 re-export 兼容壳，全仓零 import；唯一提及是历史 plan 文档）
- Modify: `server/internal/web/library.js:364`（删除 `initLibrary` 导出，生产+测试零引用）
- Modify: `server/internal/web/videoPlayer.js:26`（删除私有 `PLAYBACK_SPEEDS` const，倍速档位实际来自 index.html `data-speed`）
- Modify: `server/internal/web/package.json`（删除零引用 devDependency `open-props`；jsdom 保留——`_snapshot-helpers.mjs` 在用）

**Interfaces:** 删除项均无任何 import 方；web.go embed 为 `*.js` glob，escape.js 删除后 embed 自动收缩，无需改 web.go。

- [ ] **Step 1: 删除文件与符号**
- [ ] **Step 2: 验证** — `cd server/internal/web && node --test` 全绿；`cd tools/xsscheck && go run . ../../server/internal/web` 通过；`cd server && go build ./...`（embed 完整性）
- [ ] **Step 3: Commit** — `refactor(web): drop unused escape.js shim, initLibrary and PLAYBACK_SPEEDS`

### Task 3: Android 死代码删除

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/BrowseContent.kt:28`（删除孤儿 `FavoritesContent` @Composable）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt:56`（删除未用 ctor 参数 `serverConfig`）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/DeleteController.kt:29`（删除未用 ctor 参数 `sharedState`）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/DownloadController.kt:33,35`（删除未用 ctor 参数 `repository`、`sharedState`）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BrowseViewModel.kt:62-63`（同步收缩两个构造调用）
- Modify: `android/app/src/main/res/values/strings.xml:126,167,371`（删除 `browse_fav_card_desc` / `media_unsupported_badge` / `browse_filter_status`；如存在 values-* 本地化目录同步删除）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/FavoritesStore.kt:80-103`（删除 `FavoriteMediaEntry` + `decodeFavoriteEntry` + "旧模型保留"注释块——`decodeFavoriteEntryV2:45` 已覆盖全部三代格式含第一代裸 MediaFile）
- Modify: `android/app/src/test/.../data/FavoritesStoreTest.kt`（删除/改写仅测 `decodeFavoriteEntry` 的用例，勿动 V2 解码器用例）

**Interfaces:** ctor 参数删除后调用方只剩 BrowseViewModel 两处 + Hilt（BleSettingsViewModel 由 DI 注入，直接删参即可）。

- [ ] **Step 1: 删除符号与参数、修调用点**
- [ ] **Step 2: 验证** — `cd android && ./gradlew testDebugUnitTest assembleDebug` 全绿
- [ ] **Step 3: Commit** — `refactor(android): remove orphan composable, unused ctor params and legacy v1 favorite decoder`

### Task 4: 终验与报告

- [ ] **Step 1: 全量复跑** — `go test ./...` / `node --test` + xsscheck / `gradlew testDebugUnitTest assembleDebug`
- [ ] **Step 2: 汇总报告**（保留项及理由、`mdns.Service.Stop` 删除附注：进程退出未注销 mDNS 属既有行为，未改变）

## Self-Review

- 覆盖：deadcode 13 项 → 删 6 项（mustUUID/getFFprobeCmd/ffprobeSibling/GetChapterBlocksFromText/GetRuneCount + mdns.Stop、systray.SetStatus 归并为 7 个符号）、留 7 项（NewScanner 1 + BLE codec 5 + api_provider 链无需动）；Android A 类 5 项 + B 类 2 项 + D 类 3 string 全处理；Web A 1 + B 2 + open-props 全处理。无遗漏。
- 占位符：无——每项均给出精确文件与符号。
- 一致性：FavoritesStore 删除与测试改写、ctor 参数与 BrowseViewModel 调用点均已对齐。
