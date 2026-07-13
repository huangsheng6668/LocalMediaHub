# Round 30 — 死代码清理 B 阶段（cleanup）设计

- **创建日期**: 2026-07-13
- **分支**: `round-30-deadcode-cleanup`（从 master `15e6f19` 切出）
- **关联**: A 阶段审计报告 `docs/2026-07-13-deadcode-audit-report.md`
- **决策来源**：用户在 B 阶段启动会议上的 3 项选择

---

## 1. 目标

按审计报告执行清理，覆盖：

- 2 条直接删除候选（强证据，非导出）
- 41 条待人工确认符号（用户选 **b 全删**——按报告建议动作执行）
- 22 条冗余建议中的部分项（涉及死代码相关的，纯"重构建议"仍跳过）
- 68 个 Android UnusedResources（用户选 **包含**）
- 1 条 Rust 依赖冗余（`log = "0.4"`）
- 1 项附带 bug 修复：`enableR8.fullMode` 配置迁移（用户选 **a 修复**）

不在本轮范围：21 条 Deprecated API 现代化（仅记录）、纯重构类冗余（如 normalizePath 两版合并需要语义判断）。

---

## 2. 用户决策（启动会议）

| # | 问题 | 决策 | 含义 |
|---|---|---|---|
| 1 | 待人工确认 41 条 | **b 全删** | 批次 2-4 各自的"待人工确认"按报告建议动作执行 |
| 2 | `enableR8.fullMode` 修复 | **a 修复** | 批次 4 顺便把配置从根 `gradle.properties` 迁到 `android/gradle.properties` |
| 3 | 68 个 UnusedResources | **包含** | 批次 2 批量删除 |

---

## 3. 批次计划与待删清单

### 批次 1：Go server（1 commit）

**目标符号**：

| 符号 | 位置 | 类型 | 动作 |
|---|---|---|---|
| `(*Scanner).Search` | `server/internal/service/scanner.go:404` | 直接删 | 删 |
| `ServerStatus` | `server/internal/models/models.go:59` | 待确认 | 删 |
| `DecodeImage` | `server/internal/service/thumbnail.go:461` | 待确认 | 删 |

**验证**：
- `cd server && go build ./...`
- `cd server && go vet ./...`
- `cd server && go test ./...`

**回滚条件**：build/vet/test 任一失败 → `git revert`。

---

### 批次 2：Android Kotlin（1 commit）

**目标符号（20 条 + 68 资源）**：

§3.3 直接删：
| 符号 | 位置 |
|---|---|
| `BrowseViewModel.emitBrowseError` | `viewmodel/BrowseViewModel.kt:356` |

§3.4 待确认（全删）：
| 符号 | 位置 |
|---|---|
| `MediaRepository.getVideos` | `data/MediaRepository.kt:158` |
| `MediaRepository.getImages` | `data/MediaRepository.kt:162` |
| `MediaRepository.getTaggedFiles` | `data/MediaRepository.kt:226` |
| `MediaRepository.getTaggedMedia` | `data/MediaRepository.kt:229` |
| `MediaRepository.downloadFolderZip` | `data/MediaRepository.kt:150` |
| `MediaRepository.getFolderFilesRecursive` | `data/MediaRepository.kt:146` |
| `MediaRepository.getSystemVideoStreamUrl` | `data/MediaRepository.kt:274` |
| `MediaRepository.getSystemThumbnailUrl` | `data/MediaRepository.kt:277` |
| `MediaRepository.getSystemOriginalImageUrl` | `data/MediaRepository.kt:280` |
| `MediaRepository.getVideoStreamUrl(relativePath)` | `data/MediaRepository.kt:256` |
| `MediaRepository.getThumbnailUrl(relativePath)` | `data/MediaRepository.kt:259` |
| `MediaRepository.getOriginalImageUrl(relativePath)` | `data/MediaRepository.kt:262` |
| `RoutePath.normalizeRoutePath` | `data/RoutePath.kt:3` |
| `BrowseViewModel.filterFilesByFavorites` | `viewmodel/BrowseViewModel.kt:214` |
| `BrowseViewModel.currentCollectionTag` | `viewmodel/BrowseViewModel.kt:304` |
| `BrowseViewModel.deletePathSync` | `viewmodel/BrowseViewModel.kt:334` |
| `BrowseViewModel.loadAllFileTags` | `viewmodel/BrowseViewModel.kt:288` |
| `BrowseViewModel.loadFileTagsForFile` | `viewmodel/BrowseViewModel.kt:284` |
| `BrowseViewModel.setActiveTagFilter` | `viewmodel/BrowseViewModel.kt:296` |
| `PaginatedMediaFiles` (data class) | `data/Models.kt:51` |

§3.6 UnusedResources（68 条）：
- `colors.xml`: `purple_200` / `purple_700` / `teal_200` / `teal_700` / `black` / `white`（6 个）
- `strings.xml`: 61 个未用字符串（lint 报告完整清单）
- `xml/ic_launcher.xml`: 1 个（保留 `mipmap-anydpi-v26/ic_launcher.xml`）

**验证**：
- `cd android && ./gradlew :app:assembleDebug`
- `cd android && ./gradlew :app:testDebugUnitTest`
- `cd android && ./gradlew :app:lintDebug`（确认 68 UnusedResources 归零；既有 11 UnsafeOptInUsageError 不在本次范围）

**回滚条件**：assemble/test 任一失败 → `git revert`。

---

### 批次 3：Web JS（1 commit）

§4.4 待确认（全删——这里是"去掉 export"，函数体保留）：

| 符号 | 位置 | 动作 |
|---|---|---|
| `onDashboardRecentClick` | `dashboard.js:10` | 去 `export` |
| `renderLightboxImage` | `lightbox.js:33` | 去 `export` |
| `navigateLightbox` | `lightbox.js:94` | 去 `export` |
| `toggleFileTagAssociation` | `tagsView.js:57` | 去 `export` |
| `onTagsManagerListClick` | `tagsView.js:98` | 去 `export` |
| `onTagSelectorChange` | `tagsView.js:107` | 去 `export` |
| `deleteTag` | `tagsView.js:136` | 去 `export` |

**验证**：
- 启动 server，浏览器手动跑 4 个主路径（dashboard / browse / tags / settings），控制台无 `Uncaught Error` / `404 module`
- 若需更严格：可用 chrome-devtools MCP 自动跑

**回滚条件**：任一页面控制台有 module 错误 → `git revert`。

---

### 批次 4：Rust JNI + Build（1 commit）

§5.3 待确认（全删）：
| 符号 | 位置 |
|---|---|
| `jpeg::dimensions` | `rust/src/jpeg.rs:21` |
| `jpeg::pick_jpeg_scale` | `rust/src/jpeg.rs:40` |
| `webp::dimensions` | `rust/src/webp.rs:13` |
| `png::decode` | `rust/src/png.rs`（行号待 grep） |
| `bitmap::create_android_bitmap` 非 Android stub | `rust/src/bitmap.rs:171` |

**注意**：
- `jpeg::dimensions` 删除时同时删私有 `parse_jpeg_sof_dimensions`（仅被 dimensions 调用）
- `jpeg::pick_jpeg_scale` 删除时同时删 4 个测试（`pick_jpeg_scale_*`）
- `bitmap::create_android_bitmap` 的 `#[allow(dead_code)]` 非 Android stub：**保留**——它是 `#[cfg(not(target_os = "android"))]` 的占位 stub，删除会破坏非 Android 编译路径。这一条改为"保留 stub、仅清理其 doc 注释中的过时引用"。
- `png::decode`：报告中标记为待确认但描述偏弱（仅说"非 Android stub 类似"）。先 grep 确认零引用再删；若 png 路径有在线 caller 则保留。

§6 根目录孤儿 gradle 文件（6 条，全删）：
- `build.gradle.kts`（根）
- `settings.gradle.kts`（根）
- `gradle.properties`（根）
- `gradlew`（根）
- `gradlew.bat`（根）
- `gradle/wrapper/gradle-wrapper.properties`（根）

**R8 fullMode 修复（用户决策 2）**：
- 把 `android.enableR8.fullMode=true` 从根 `gradle.properties` 复制到 `android/gradle.properties`
- 然后删除根 `gradle.properties`（包含上一步）

**验证**：
- `cd android/app/src/main/rust && cargo check`
- `cd android && ./gradlew :app:assembleDebug`
- **release 构建烟测（关键）**：`cd android && ./gradlew :app:assembleRelease` —— R8 full mode 首次启用，必须验证 release APK 能产出且无明显 R8 错误。若 R8 full mode 暴露新错误（如 missing rules），按情况：
  - 单个 missing rule → 加 keep rule，不回滚
  - 大量错误 → 回滚 R8 修复（保留根 gradle.properties），其他清理仍合并

**回滚条件**：cargo check 失败、assembleDebug 失败 → `git revert`。R8 release 错误按上述分级处理。

---

### 批次 5：依赖冗余（1 commit）

§7.3 Rust 依赖：
| 依赖 | 位置 | 动作 |
|---|---|---|
| `log = "0.4"` | `android/app/src/main/rust/Cargo.toml:14` | 删除 require |

**验证**：
- `cd android/app/src/main/rust && cargo check`

**回滚条件**：cargo check 失败 → `git revert`。

---

## 4. 执行流程

每个批次：
1. 我列出本批次清单（已在本 spec 中），最后确认
2. 改代码
3. 跑验证
4. 通过 → 提交；失败 → `git revert` + 报告
5. 更新 progress ledger

**每批次完成后暂停**，给你 checkpoint——避免连续多个批次累积风险。

---

## 5. 护栏

- 单个 commit 只动一个批次范围
- 不动 `*_test.go` / `*Test.kt`（除非该 test 本身是死代码，如 `pick_jpeg_scale_*` 4 个测试）
- 不动 vendored / 构建产物
- 跨批次无依赖（每批独立 commit、独立 revert）
- 不动 `android/keystore.properties.example`
- R8 full mode 修复必须先单独跑一次 release 构建确认无新错误，否则不合并该变更

---

## 6. 工作流

```
B 阶段：清理（每批次循环）
  for batch in [1, 2, 3, 4, 5]:
    1. 列出本批次待删清单（已在 spec §3）
    2. 用户最后确认
    3. 改代码
    4. 跑该批次验证
    5. 通过 → commit；失败 → git revert + 报告
    6. 更新 ledger
    7. 暂停给 checkpoint（除非用户要求连续）
```

---

## 7. 完成标准

- 5 个批次 commit 全部成功
- 所有验证通过（含 R8 release 烟测）
- 报告 §1.1 中"待人工确认 41 条"清零
- 报告 §3.6 UnusedResources 68 条清零
- 报告 §5.3 Rust pub fn 4 条删除（保留 bitmap stub）
- 报告 §7.3 Rust log=0.4 删除
- 报告 §6.3 R8 fullMode 配置正确迁移且生效
