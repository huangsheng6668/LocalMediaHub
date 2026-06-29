# 安全加固（Security Hardening）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 封堵 LocalMediaHub 三端的三个已确认安全漏洞（服务端系统媒体端点越权读、Android ZIP 解压路径穿越、Web 存储型 XSS / inline onclick 注入），并统一 system.go 错误响应。

**Architecture:** 三端互不耦合，分三个独立阶段（Server / Android / Web），各自带测试与回归、各自提交。Server 与 Android 走 TDD（逻辑层单测）；Web 无自动化测试，走事件委托重构 + 手工浏览器回归（含恶意文件名 XSS 专项验证）。

**Tech Stack:** Go 1.22+ / Echo v4 / slog（Server）；Kotlin / JUnit4（Android 单测）；原生 ES Module JS（Web，无构建）。

## Global Constraints

- **提交策略**：本项目约定任何本地改动自动同步推送至 GitHub `master` 分支（见 `AGENTS.md` 同步政策）。所有提交直接在 `master` 上进行，**不开 feature 分支**。
- **Go 编码规则**（来自 `AGENTS.md`）：handler 只做参数解析与响应，业务逻辑放 `internal/service/`；所有文件访问必须经路径校验函数；列表返回用 `make([]T, 0)` 初始化避免 JSON `null`；5xx 错误不得向客户端回显 `err.Error()`（用 `respondError`/`respondInternalError`）。
- **Go 测试**：沿用 `server/internal/service/path_test.go` 现有风格——平铺 `func TestXxx(t *testing.T)`、`t.TempDir()`、`filepath.Join`、`os.WriteFile` 造真实文件。
- **Kotlin 测试**：JUnit4（`org.junit.Test` + `org.junit.Assert.*`），backtick 测试名，纯 JVM 单测放在 `app/src/test/java/...`，不依赖 Android 框架。
- **Go 代理**（中国大陆网络）：如 `go test`/`go build` 拉依赖失败，用 `GOPROXY=https://goproxy.cn,direct`。
- **Web 无构建**：直接编辑 `server/internal/web/*.js`，无打包/转译；验证靠浏览器。
- **行为保持**：Web 事件委托重构是行为保持的——所有现有交互（浏览/打开/打标签/删除/面包屑/仪表盘）必须与重构前一致，只是实现方式从 inline onclick 改为委托监听器。

## File Structure

**Server（Go）**
- 修改 `server/internal/service/path.go` — 新增 `ValidateSystemMediaAccess`（组合现有 `ValidateSystemBrowseAllowed` + `checkBlocked` + `validateMediaFilePath`）。
- 修改 `server/internal/service/path_test.go` — 新增 4 个 `ValidateSystemMediaAccess` 测试。
- 修改 `server/internal/server/handler/system.go` — 3 个系统媒体 handler 改用新校验；`SystemBrowse` 错误响应统一为 helper。

**Android（Kotlin）**
- 修改 `android/app/src/main/java/com/juziss/localmediahub/data/DownloadManager.kt` — 新增文件级 `internal fun isInside` + 私有 `safeResolveChild`；`downloadFolder`/`downloadFile` 接入。
- 新增 `android/app/src/test/java/com/juziss/localmediahub/data/DownloadManagerTest.kt` — `isInside` 单测。

**Web（JS）**
- 修改 `server/internal/web/app.js` — 移除全部 inline `onclick`/`onchange`/`onerror`，改为 `data-action` + 委托监听器；统一 `escapeHtml`；移除 9 个 `window.xxx` 全局挂载。
- 修改 `server/internal/web/state.js` — 新增 `dashboardRecentFiles: []`。

---

## Phase 1 — Server（Go）

### Task 1: 新增 `ValidateSystemMediaAccess` 路径校验（TDD）

**Files:**
- Test: `server/internal/service/path_test.go`
- Modify: `server/internal/service/path.go`（在 `ValidateSystemPath` 之后插入新函数）

**Interfaces:**
- Consumes: `ValidateSystemBrowseAllowed(pathStr, allowedRoots)`、`NormalizePath(pathStr)`、`checkBlocked(absPath)`、`validateMediaFilePath(absPath, allowedExtensions)` —— 均已存在于 `path.go`。
- Produces: `ValidateSystemMediaAccess(pathStr string, allowedRoots []string, allowedExtensions []string) error` —— Task 2 的 3 个 handler 依赖此签名。

- [ ] **Step 1: 写失败测试**

在 `server/internal/service/path_test.go` 末尾追加：

```go
func TestValidateSystemMediaAccessRequiresConfiguredRoots(t *testing.T) {
	root := t.TempDir()
	filePath := filepath.Join(root, "clip.mp4")
	if err := os.WriteFile(filePath, []byte("video"), 0o644); err != nil {
		t.Fatalf("failed to create media file: %v", err)
	}

	err := ValidateSystemMediaAccess(filePath, nil, []string{".mp4"})
	if err == nil {
		t.Fatal("expected access to be denied when no system roots are configured")
	}
}

func TestValidateSystemMediaAccessAllowsFileWithinRoots(t *testing.T) {
	root := t.TempDir()
	filePath := filepath.Join(root, "clip.mp4")
	if err := os.WriteFile(filePath, []byte("video"), 0o644); err != nil {
		t.Fatalf("failed to create media file: %v", err)
	}

	err := ValidateSystemMediaAccess(filePath, []string{root}, []string{".mp4"})
	if err != nil {
		t.Fatalf("expected media file within roots to be accessible, got %v", err)
	}
}

func TestValidateSystemMediaAccessRejectsPathOutsideRoots(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	filePath := filepath.Join(outside, "secret.jpg")
	if err := os.WriteFile(filePath, []byte("img"), 0o644); err != nil {
		t.Fatalf("failed to create media file: %v", err)
	}

	err := ValidateSystemMediaAccess(filePath, []string{root}, []string{".jpg"})
	if err == nil {
		t.Fatal("expected media file outside roots to be denied")
	}
}

func TestValidateSystemMediaAccessRejectsDisallowedExtension(t *testing.T) {
	root := t.TempDir()
	filePath := filepath.Join(root, "notes.txt")
	if err := os.WriteFile(filePath, []byte("txt"), 0o644); err != nil {
		t.Fatalf("failed to create file: %v", err)
	}

	err := ValidateSystemMediaAccess(filePath, []string{root}, []string{".mp4", ".jpg"})
	if err == nil {
		t.Fatal("expected non-media extension to be denied")
	}
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd server && go test ./internal/service/ -run TestValidateSystemMediaAccess -v`
Expected: 编译失败 / FAIL，提示 `undefined: ValidateSystemMediaAccess`。

- [ ] **Step 3: 写最小实现**

在 `server/internal/service/path.go` 的 `ValidateSystemPath` 函数（结尾 `}` 在第 98 行）之后插入：

```go
// ValidateSystemMediaAccess validates a media file path for the system
// thumbnail/original/stream endpoints. It enforces that the path is under one
// of the configured system allowed roots, is not inside a blocked directory,
// and is an existing file whose extension is in the allowed list.
//
// Unlike ValidateSystemPath, this also enforces the allowed-roots boundary,
// preventing the system media endpoints from serving files outside the
// directories the operator explicitly opened via system.allowed_roots.
func ValidateSystemMediaAccess(pathStr string, allowedRoots []string, allowedExtensions []string) error {
	if err := ValidateSystemBrowseAllowed(pathStr, allowedRoots); err != nil {
		return err
	}
	absPath, err := NormalizePath(pathStr)
	if err != nil {
		return err
	}
	if err := checkBlocked(absPath); err != nil {
		return err
	}
	return validateMediaFilePath(absPath, allowedExtensions)
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd server && go test ./internal/service/ -run TestValidateSystemMediaAccess -v`
Expected: PASS（4 个测试全过）。

- [ ] **Step 5: 运行整个 service 包测试，确认无回归**

Run: `cd server && go test ./internal/service/ -v`
Expected: PASS（含既有 `TestValidateSystemBrowseAllowed*`、`TestIsPathWithinRoots`、`TestValidateAccessibleMediaPath*` 等全部通过）。

- [ ] **Step 6: 提交**

```bash
git add server/internal/service/path.go server/internal/service/path_test.go
git commit -m "feat(server): add ValidateSystemMediaAccess for system media endpoints

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: 系统媒体 handler 接入新校验 + SystemBrowse 错误一致性

**Files:**
- Modify: `server/internal/server/handler/system.go`（`SystemThumbnail` 130-149、`SystemOriginal` 151-162、`SystemStream` 164-181、`SystemBrowse` 53-69）

**Interfaces:**
- Consumes: Task 1 的 `ValidateSystemMediaAccess`；既有 `respondError`/`respondInternalError`/`respondNotFound`（`handler.go`）；`h.cfg.GetSystemAllowedRoots()`、`h.mediaExtensions()`。
- Produces: 三个系统媒体端点现在强制 `allowed_roots` 边界；`SystemBrowse` 错误响应不再回显内部信息。

> 安全逻辑（边界判定）已在 Task 1 单测覆盖；本任务是机械接线，靠 `go build` + 手工 curl 验证。

- [ ] **Step 1: 改 `SystemThumbnail`**

将 `server/internal/server/handler/system.go` 的 `SystemThumbnail`（130-149）整体替换为：

```go
func (h *Handler) SystemThumbnail(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	if err := service.ValidateSystemMediaAccess(pathStr, h.cfg.GetSystemAllowedRoots(), h.mediaExtensions()); err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	thumbPath, err := h.thumbnail.GenerateSystemThumbnail(pathStr)
	if err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "file not found")
		}
		return respondInternalError(c, err)
	}

	return c.File(thumbPath)
}
```

- [ ] **Step 2: 改 `SystemOriginal`**

将 `SystemOriginal`（151-162）整体替换为：

```go
func (h *Handler) SystemOriginal(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	if err := service.ValidateSystemMediaAccess(pathStr, h.cfg.GetSystemAllowedRoots(), h.mediaExtensions()); err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	return c.File(pathStr)
}
```

- [ ] **Step 3: 改 `SystemStream`**

将 `SystemStream`（164-181）整体替换为：

```go
func (h *Handler) SystemStream(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	if err := service.ValidateSystemMediaAccess(pathStr, h.cfg.GetSystemAllowedRoots(), h.mediaExtensions()); err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	if err := h.streaming.ServeFile(c.Response().Writer, c.Request(), pathStr); err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "file not found")
		}
		return respondInternalError(c, err)
	}
	return nil
}
```

- [ ] **Step 4: 统一 `SystemBrowse` 错误响应（漏洞 #4 清理）**

在 `SystemBrowse`（24-128）内，把以下 5 处裸 `c.JSON` 改为 helper（其余逻辑不动）：

第 53-54 行：
```go
	if err := service.ValidateSystemBrowseAllowed(pathStr, h.cfg.GetSystemAllowedRoots()); err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}
```

第 56-57 行：
```go
	if err := service.ValidateSystemBrowsePath(pathStr); err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}
```

第 62-63 行：
```go
		if os.IsNotExist(err) {
			return respondNotFound(c, "path not found")
		}
```

第 64-65 行：
```go
		return respondInternalError(c, err)
```

第 68-69 行：
```go
	if !fi.IsDir() {
		return respondError(c, http.StatusBadRequest, "not a directory")
	}
```

> `GetDrives`（16-22）与 `DeletePath`/`isAllowedToDelete`（183-289）**不在本轮范围**，保持不动——`DeletePath` 已有正确的 roots 边界校验。

- [ ] **Step 5: 编译 + 全量测试**

Run: `cd server && go build ./... && go test ./...`
Expected: 编译通过；全部测试 PASS（若有依赖拉取失败，加 `GOPROXY=https://goproxy.cn,direct`）。

- [ ] **Step 6: 手工验证越权读已被封堵**

启动服务（按 `README.md`，确保 `config.yaml` 配了 `system.allowed_roots`，例如 `D:/Media`）：

```bash
cd server && go build -o LocalMediaHub.exe ./cmd/server && ./LocalMediaHub.exe --headless
```

在另一终端，用一个**不在 allowed_roots 内**的媒体文件（如 `C:/Users/<you>/Pictures/test.jpg`，需真实存在）发请求：

```bash
curl -i "http://localhost:8000/api/v1/system/original?path=C:/Users/<you>/Pictures/test.jpg"
curl -i "http://localhost:8000/api/v1/system/thumbnail?path=C:/Users/<you>/Pictures/test.jpg"
curl -i "http://localhost:8000/api/v1/system/stream?path=C:/Users/<you>/Pictures/test.jpg"
```
Expected: 三者均返回 **HTTP 403** `{"error":"access denied"}`（修复前会返回 200 + 文件内容/流）。

再用一个**在 allowed_roots 内**的真实媒体文件验证正常访问仍 200：
```bash
curl -i "http://localhost:8000/api/v1/system/original?path=D:/Media/test.jpg"
```
Expected: 200。

退出服务（Ctrl+C）。

- [ ] **Step 7: 提交**

```bash
git add server/internal/server/handler/system.go
git commit -m "fix(server): enforce allowed_roots on system media endpoints

SystemThumbnail/Original/Stream previously only checked the blocked-list and
extension via ValidateSystemPath, letting any client read/stream media files
outside system.allowed_roots. Switch to ValidateSystemMediaAccess. Also unify
SystemBrowse error responses to respondError helpers.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Phase 2 — Android（Kotlin）

### Task 3: 新增 `isInside` 边界校验（TDD）

**Files:**
- Test: `android/app/src/test/java/com/juziss/localmediahub/data/DownloadManagerTest.kt`（新建）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/DownloadManager.kt`（文件末尾、最后一个 `}` 之后追加文件级函数）

**Interfaces:**
- Produces: 文件级 `internal fun isInside(destDir: File, candidate: File): Boolean` —— Task 4 的 `safeResolveChild` 依赖它；`internal` 可见性使其对 app 模块 JVM 单测可见。

- [ ] **Step 1: 写失败测试**

新建 `android/app/src/test/java/com/juziss/localmediahub/data/DownloadManagerTest.kt`：

```kotlin
package com.juziss.localmediahub.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadManagerTest {

    private fun newTempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "dlm-test-${System.nanoTime()}")
        require(dir.mkdirs()) { "failed to create temp dir" }
        return dir
    }

    @Test
    fun `isInside accepts a simple file inside dest dir`() {
        val dest = newTempDir()
        assertTrue(isInside(dest, File(dest, "video.mp4")))
    }

    @Test
    fun `isInside accepts a nested file inside dest dir`() {
        val dest = newTempDir()
        assertTrue(isInside(dest, File(dest, "sub/dir/video.mp4")))
    }

    @Test
    fun `isInside rejects parent traversal entry`() {
        val dest = newTempDir()
        assertFalse(isInside(dest, File(dest, "../escape.mp4")))
    }

    @Test
    fun `isInside rejects absolute path outside dest dir`() {
        val dest = newTempDir()
        assertFalse(isInside(dest, File("/etc/evil.mp4")))
    }

    @Test
    fun `isInside rejects the dest dir itself`() {
        val dest = newTempDir()
        assertFalse(isInside(dest, dest))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd android && ./gradlew testDebugUnitTest --tests "*.DownloadManagerTest"`
Expected: 编译失败，提示 `isInside` 未定义 / 无法解析。

- [ ] **Step 3: 写最小实现**

在 `android/app/src/main/java/com/juziss/localmediahub/data/DownloadManager.kt` 的文件末尾（`DownloadManager` 类最后一个 `}` 之后）追加文件级函数：

```kotlin
/**
 * Returns true only when [candidate] resolves to a path strictly inside [destDir].
 * Guards ZIP extraction against Zip Slip: entries whose canonical path escapes
 * the destination directory (e.g. `../escape.mp4` or absolute paths) return false.
 */
internal fun isInside(destDir: File, candidate: File): Boolean {
    val destCanonical = destDir.canonicalPath + File.separator
    return candidate.canonicalPath.startsWith(destCanonical)
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd android && ./gradlew testDebugUnitTest --tests "*.DownloadManagerTest"`
Expected: BUILD SUCCESSFUL，5 个测试全过。

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/DownloadManager.kt android/app/src/test/java/com/juziss/localmediahub/data/DownloadManagerTest.kt
git commit -m "feat(android): add isInside guard for download extraction

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: 接入 `downloadFolder` 与 `downloadFile`

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/DownloadManager.kt`（`downloadFolder` 解压循环 125-159、`downloadFile` 写入处 53）

**Interfaces:**
- Consumes: Task 3 的 `isInside`。
- Produces: 私有成员 `safeResolveChild(destDir, name): File?`；两处解压/写入前做边界校验。

- [ ] **Step 1: 在 `DownloadManager` 类内新增 `safeResolveChild`**

在 `DownloadManager` 类内（例如 `downloadFolder` 方法之前）新增私有成员函数：

```kotlin
    private fun safeResolveChild(destDir: File, name: String): File? {
        val candidate = File(destDir, name)
        return if (isInside(destDir, candidate)) candidate else null
    }
```

- [ ] **Step 2: 改 `downloadFolder` 解压处（Zip Slip 防护）**

将 `downloadFolder` 内（当前 130-141 行附近）的：

```kotlin
                            if (!zipEntry.isDirectory) {
                                val extractedFile = File(destDirectory, zipEntry.name)
                                extractedFile.parentFile?.mkdirs()
```

替换为：

```kotlin
                            if (!zipEntry.isDirectory) {
                                val extractedFile = safeResolveChild(destDirectory, zipEntry.name)
                                if (extractedFile == null) {
                                    android.util.Log.w("DownloadManager", "Skipping zip entry outside dest dir: ${zipEntry.name}")
                                    continue
                                }
                                extractedFile.parentFile?.mkdirs()
```

- [ ] **Step 3: 改 `downloadFile` 写入处（同名防护）**

将 `downloadFile` 内（当前 53 行附近）的：

```kotlin
                val localFile = File(destDirectory, file.name)
```

替换为：

```kotlin
                val localFile = safeResolveChild(destDirectory, file.name)
                    ?: throw SecurityException("非法文件名，已拒绝下载")
```

（外层 `catch (e: Exception) { onMessage("下载失败: ${e.message}") }` 会捕获并提示用户，无需额外处理。）

- [ ] **Step 4: 编译 + 单测 + Debug 构建**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL；既有单测 + `DownloadManagerTest` 全过；`assembleDebug` 产出 APK。

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/DownloadManager.kt
git commit -m "fix(android): guard Zip Slip in folder download and file name in file download

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Phase 3 — Web（JS，事件委托重构 + 手工回归）

> Web 无自动化测试。每个 Task 都是"改渲染 + 加委托监听器"，结束时必须保证 App 仍可正常工作（同一 commit 内同时完成渲染改动与监听器注册）。最终 Task 10 做完整回归 + XSS 专项验证。
>
> 共性原则：所有用户/网络可控文本（文件名、文件夹名、路径、tag 名/颜色）渲染进 HTML 时一律用 `escapeHtml()`；对象引用用数组索引（`data-index`），不再 `JSON.stringify` 进属性。

### Task 5: browserList 委托（文件夹卡 / 文件卡 / 根目录卡 / 磁盘卡 + 缩略图 onerror 回退）

**Files:**
- Modify: `server/internal/web/app.js`（`setupEventListeners` 内加监听器；`renderBrowserList` 698-796、`loadRoots` 622-637、`loadSystemDrives` 650-664）

**Interfaces:**
- Produces: `onBrowserListClick(e)`、捕获态 `error` 监听器；卡片用 `data-action`（`browse`/`open`/`tag`/`delete-folder`/`delete-file`）+ `data-path`/`data-index`。

- [ ] **Step 1: 在 `setupEventListeners` 注册委托监听器**

在 `setupEventListeners` 内"Search Box Listener"（约 181-185 行）之后、"Close Video Modal"（约 187 行）之前插入：

```js
    // Delegated click handling for browser list (folders, files, roots, drives)
    elements.browserList.addEventListener('click', onBrowserListClick);

    // Delegated thumbnail error fallback (img 'error' does not bubble -> capture phase)
    elements.browserList.addEventListener('error', (e) => {
        const img = e.target;
        if (img instanceof HTMLImageElement && img.classList.contains('card-thumb')) {
            const wrapper = img.closest('.card-preview');
            if (wrapper) {
                img.style.display = 'none';
                const fallback = document.createElement('span');
                fallback.className = 'card-preview-icon';
                fallback.textContent = wrapper.dataset.fallbackIcon || '🖼️';
                wrapper.appendChild(fallback);
            }
        }
    }, true);
```

- [ ] **Step 2: 新增 `onBrowserListClick` 分发函数**

在 `renderBrowserList` 函数（698 行）之前插入：

```js
// Delegated click dispatcher for the browser grid
function onBrowserListClick(e) {
    const actionEl = e.target.closest('[data-action]');
    if (!actionEl) return;
    const action = actionEl.dataset.action;
    const idx = Number(actionEl.dataset.index);

    if (action === 'browse') {
        browsePath(actionEl.dataset.path || '');
    } else if (action === 'open') {
        if (state.currentFiles[idx]) openMedia(state.currentFiles[idx]);
    } else if (action === 'tag') {
        if (state.currentFiles[idx]) openTaggingDialog(state.currentFiles[idx]);
    } else if (action === 'delete-folder') {
        if (state.currentFolders[idx]) deleteFolder(state.currentFolders[idx]);
    } else if (action === 'delete-file') {
        if (state.currentFiles[idx]) deleteMediaFile(state.currentFiles[idx]);
    }
}
```

- [ ] **Step 3: 重写 `renderBrowserList` 的文件夹区段**

将 `renderBrowserList` 内文件夹 `forEach`（708-728）替换为：

```js
    // 1. Folders
    state.currentFolders.forEach((folder, index) => {
        const safePath = escapeHtml(folder.path.replace(/\\/g, '/'));
        const safeName = escapeHtml(folder.name);
        html += `
            <div class="media-card" data-action="browse" data-path="${safePath}">
                <div class="card-preview">
                    <span class="card-preview-icon">📁</span>
                </div>
                <div class="card-actions-overlay">
                    ${state.enableDelete && !folder.is_root ? `<button class="card-action-btn delete-btn" title="删除文件夹" data-action="delete-folder" data-index="${index}">🗑️</button>` : ''}
                </div>
                <div class="card-details">
                    <div class="card-title" title="${safeName}">${safeName}</div>
                    <div class="card-meta">
                        <span>文件夹</span>
                    </div>
                </div>
            </div>
        `;
    });
```

- [ ] **Step 4: 重写 `renderBrowserList` 的文件区段**

将 `renderBrowserList` 内文件 `forEach`（730-793）替换为：

```js
    // 2. Media Files
    state.currentFiles.forEach((file, index) => {
        const isVideo = file.media_type === 'video';
        const fallbackIcon = isVideo ? '🎬' : '🖼️';
        let previewHtml = '';
        let playOverlay = '';

        let thumbUrl = `${state.apiBase}/api/v1/images/${encodeRoutePath(file.relative_path)}/thumbnail`;
        if (state.isSystemBrowse) {
            thumbUrl = `${state.apiBase}/api/v1/system/thumbnail?path=${encodeURIComponent(file.path)}`;
        }

        if (isVideo) {
            const videoThumbUrl = `${state.apiBase}/api/v1/videos/${encodeRoutePath(file.relative_path)}/thumbnail`;
            const videoUrl = state.isSystemBrowse ? thumbUrl : videoThumbUrl;
            previewHtml = `<img src="${escapeHtml(videoUrl)}" class="card-thumb" alt="${escapeHtml(file.name)}">`;
            playOverlay = `
                <div class="play-overlay">
                    <div class="play-button-circle">▶</div>
                </div>
            `;
        } else {
            previewHtml = `<img src="${escapeHtml(thumbUrl)}" class="card-thumb" alt="${escapeHtml(file.name)}">`;
        }

        const fileTags = state.fileTagsMap[file.path] || [];
        const isTagged = fileTags.length > 0;
        const tagDotHtml = fileTags.map(tag => `
            <span style="display:inline-block; width:8px; height:8px; border-radius:50%; background-color:${escapeHtml(tag.color)};" title="${escapeHtml(tag.name)}"></span>
        `).join('');

        const cardClass = `media-card ${isTagged ? 'tagged' : ''}`;
        const safeName = escapeHtml(file.name);
        const safeExt = escapeHtml(file.extension);

        html += `
            <div class="${escapeHtml(cardClass)}" id="file-card-${safeBtoa(file.path).replace(/=/g, '')}" data-action="open" data-index="${index}">
                <div class="card-preview" data-fallback-icon="${fallbackIcon}">
                    ${previewHtml}
                    ${playOverlay}
                </div>
                <div class="card-actions-overlay">
                    <button class="card-action-btn" title="分类标签" data-action="tag" data-index="${index}">🏷️</button>
                    ${state.enableDelete ? `<button class="card-action-btn delete-btn" title="删除文件" data-action="delete-file" data-index="${index}">🗑️</button>` : ''}
                </div>
                <div class="card-details">
                    <div class="card-title" title="${safeName}">${safeName}</div>
                    <div class="card-meta">
                        <span class="card-badge">${safeExt.toUpperCase()}</span>
                        <div style="display:flex; gap:3px; align-items:center;">
                            ${tagDotHtml}
                            <span>${formatSize(file.size)}</span>
                        </div>
                    </div>
                </div>
            </div>
        `;
    });
```

- [ ] **Step 5: 重写 `loadRoots` 的根目录卡**

将 `loadRoots` 内卡片渲染（622-637）替换为：

```js
    elements.browserList.innerHTML = state.folders.map(path => {
        const name = path.replace(/\\/g, '/').split('/').filter(Boolean).pop() || path;
        const safePath = escapeHtml(path.replace(/\\/g, '/'));
        const safeName = escapeHtml(name);
        return `
            <div class="media-card" data-action="browse" data-path="${safePath}">
                <div class="card-preview">
                    <span class="card-preview-icon">📁</span>
                </div>
                <div class="card-details">
                    <div class="card-title" title="${safeName}">${safeName}</div>
                    <div class="card-meta">
                        <span>共享库</span>
                    </div>
                </div>
            </div>
        `;
    }).join('');
```

- [ ] **Step 6: 重写 `loadSystemDrives` 的磁盘卡**

将 `loadSystemDrives` 内卡片渲染（650-664）替换为：

```js
            elements.browserList.innerHTML = drives.map(drive => {
                const safePath = escapeHtml(drive.replace(/\\/g, '/'));
                return `
                    <div class="media-card" data-action="browse" data-path="${safePath}">
                        <div class="card-preview">
                            <span class="card-preview-icon">💾</span>
                        </div>
                        <div class="card-details">
                            <div class="card-title">${escapeHtml(drive)}</div>
                            <div class="card-meta">
                                <span>本地磁盘</span>
                            </div>
                        </div>
                    </div>
                `;
            }).join('');
```

- [ ] **Step 7: 手工冒烟验证（App 仍可用）**

启动服务后用浏览器打开 Web 管理器（`http://localhost:8000`），进入"媒体浏览"：
- 点根目录卡 / 磁盘卡 / 子文件夹卡 → 正常进入浏览。
- 点文件卡 → 视频弹播放器 / 图片弹灯箱。
- 文件卡上点 🏷️ → 弹标签对话框；点 🗑️ → 弹删除确认（若启用删除）。
- 缩略图加载失败时（可临时把某 thumbUrl 改错验证）→ 显示 🖼️/🎬 占位图标，不报错。

- [ ] **Step 8: 提交**

```bash
git add server/internal/web/app.js
git commit -m "refactor(web): delegate browser list clicks, escape names, fix thumb fallback

Removes inline onclick/onerror from folder/file/root/drive cards; replaces
JSON.stringify attribute injection with index-based delegation; escapes all
names/paths/URLs to close stored-XSS via filenames.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 6: 面包屑委托

**Files:**
- Modify: `server/internal/web/app.js`（`setupEventListeners` 加监听器；`renderBreadcrumbs` 798-824、`loadSystemDrives` 面包屑 645、`triggerBrowserSearch` 面包屑 845-848）

**Interfaces:**
- Produces: `onBreadcrumbsClick(e)`；面包屑用 `data-action="load-roots"` / `"crumb"` + `data-path`。

- [ ] **Step 1: 注册面包屑委托监听器**

在 `setupEventListeners` 内 Task 5 插入的 `browserList` 监听器之后追加：

```js
    // Delegated click handling for breadcrumbs
    elements.browserBreadcrumbs.addEventListener('click', onBreadcrumbsClick);
```

- [ ] **Step 2: 新增 `onBreadcrumbsClick`**

在 `renderBreadcrumbs` 函数（798 行）之前插入：

```js
// Delegated click dispatcher for breadcrumbs
function onBreadcrumbsClick(e) {
    const actionEl = e.target.closest('[data-action]');
    if (!actionEl) return;
    const action = actionEl.dataset.action;
    if (action === 'load-roots') {
        loadRoots();
    } else if (action === 'crumb') {
        browsePath(actionEl.dataset.path || '');
    }
}
```

- [ ] **Step 3: 重写 `renderBreadcrumbs`**

将 `renderBreadcrumbs`（798-824）整体替换为：

```js
// Render Breadcrumbs
function renderBreadcrumbs(path) {
    const isWin = path.includes(':');
    const segments = path.split(/[/\\]+/).filter(Boolean);

    let html = `<span class="crumb" data-action="load-roots">根目录</span>`;

    let currentAccumulated = '';
    segments.forEach((seg, index) => {
        if (index === 0 && isWin) {
            currentAccumulated = seg + '/';
        } else {
            currentAccumulated += (index === 0 ? '' : '/') + seg;
        }

        const isLast = index === segments.length - 1;
        if (isLast) {
            html += `<span class="crumb active">${escapeHtml(seg)}</span>`;
        } else {
            html += `<span class="crumb" data-action="crumb" data-path="${escapeHtml(currentAccumulated)}">${escapeHtml(seg)}</span>`;
        }
    });

    elements.browserBreadcrumbs.innerHTML = html;
}
```

- [ ] **Step 4: 改 `loadSystemDrives` 面包屑**

将 `loadSystemDrives` 内（645 行）的：

```js
    elements.browserBreadcrumbs.innerHTML = '<span class="crumb" onclick="loadRoots()">根目录</span><span class="crumb active">磁盘盘符</span>';
```

替换为：

```js
    elements.browserBreadcrumbs.innerHTML = '<span class="crumb" data-action="load-roots">根目录</span><span class="crumb active">磁盘盘符</span>';
```

- [ ] **Step 5: 改 `triggerBrowserSearch` 面包屑**

将 `triggerBrowserSearch` 内（845-848）的：

```js
        elements.browserBreadcrumbs.innerHTML = `
            <span class="crumb" onclick="browsePath('${state.currentPath}')">返回上级目录</span>
            <span class="crumb active">关于 "${escapeHtml(query)}" 的结果</span>
        `;
```

替换为：

```js
        elements.browserBreadcrumbs.innerHTML = `
            <span class="crumb" data-action="crumb" data-path="${escapeHtml(state.currentPath)}">返回上级目录</span>
            <span class="crumb active">关于 "${escapeHtml(query)}" 的结果</span>
        `;
```

- [ ] **Step 6: 手工冒烟 + 提交**

浏览器验证：逐级点面包屑能正确跳转、点"根目录"回根、搜索后点"返回上级目录"回到原目录。

```bash
git add server/internal/web/app.js
git commit -m "refactor(web): delegate breadcrumb clicks and escape segments

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 7: 仪表盘"最近"委托

**Files:**
- Modify: `server/internal/web/state.js`（加 `dashboardRecentFiles`）
- Modify: `server/internal/web/app.js`（`setupEventListeners` 加监听器；`renderDashboard` 最近区段 575-591）

**Interfaces:**
- Produces: `state.dashboardRecentFiles`；`onDashboardRecentClick(e)`；最近项用 `data-action="open-video"` + `data-index`。

- [ ] **Step 1: 在 `state.js` 加字段**

在 `server/internal/web/state.js` 的 `// Selected file for tag mapping` 注释块附近（`taggingFile: null,` 之后）插入：

```js
    // Dashboard recent media (backing array for index-based click delegation)
    dashboardRecentFiles: [],
```

- [ ] **Step 2: 注册监听器**

在 `setupEventListeners` 内 Task 6 的面包屑监听器之后追加：

```js
    // Delegated click handling for dashboard recent items
    elements.dashboardRecent.addEventListener('click', onDashboardRecentClick);
```

- [ ] **Step 3: 新增 `onDashboardRecentClick`**

在 `renderDashboard` 函数（548 行）之前插入：

```js
// Delegated click dispatcher for dashboard recent items
function onDashboardRecentClick(e) {
    const actionEl = e.target.closest('[data-action]');
    if (!actionEl) return;
    if (actionEl.dataset.action === 'open-video') {
        const idx = Number(actionEl.dataset.index);
        if (state.dashboardRecentFiles[idx]) openVideoPlayer(state.dashboardRecentFiles[idx]);
    }
}
```

- [ ] **Step 4: 重写 `renderDashboard` 最近区段**

将 `renderDashboard` 内（575-591）的：

```js
        try {
            const data = await apiRequest(`${state.apiBase}/api/v1/videos?page=1&page_size=3`);
            const items = data.items || [];
            
            if (items.length === 0) {
                elements.dashboardRecent.innerHTML = '<div class="empty-state">暂无最近媒体数据</div>';
                elements.dashboardRecent.classList.add('empty-state');
            } else {
                elements.dashboardRecent.classList.remove('empty-state');
                elements.dashboardRecent.innerHTML = items.map(file => {
                    return `
                        <div class="info-item" style="cursor:pointer;" onclick="openVideoPlayer(${JSON.stringify(file).replace(/"/g, '&quot;')})">
                            <span class="info-label">🎬 ${escapeHtml(file.name)}</span>
                            <span class="info-value" style="font-size:11px;">${formatSize(file.size)}</span>
                        </div>
                    `;
                }).join('');
            }
        } catch (err) {
```

替换为：

```js
        try {
            const data = await apiRequest(`${state.apiBase}/api/v1/videos?page=1&page_size=3`);
            const items = data.items || [];
            state.dashboardRecentFiles = items;

            if (items.length === 0) {
                elements.dashboardRecent.classList.add('empty-state');
                elements.dashboardRecent.innerHTML = '<div class="empty-state">暂无最近媒体数据</div>';
            } else {
                elements.dashboardRecent.classList.remove('empty-state');
                elements.dashboardRecent.innerHTML = items.map((file, index) => {
                    return `
                        <div class="info-item" style="cursor:pointer;" data-action="open-video" data-index="${index}">
                            <span class="info-label">🎬 ${escapeHtml(file.name)}</span>
                            <span class="info-value" style="font-size:11px;">${formatSize(file.size)}</span>
                        </div>
                    `;
                }).join('');
            }
        } catch (err) {
```

- [ ] **Step 5: 手工冒烟 + 提交**

浏览器验证：仪表盘"最近"项点击 → 打开视频播放器。

```bash
git add server/internal/web/state.js server/internal/web/app.js
git commit -m "refactor(web): delegate dashboard recent clicks via index

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 8: 标签管理 + 标签选择器委托

**Files:**
- Modify: `server/internal/web/app.js`（`setupEventListeners` 加两个监听器；`renderTagsManager` 1084-1092、`openTaggingDialog` 1016-1027）

**Interfaces:**
- Produces: `onTagsManagerListClick(e)`、`onTagSelectorChange(e)`；标签删除用 `data-action="delete-tag"` + `data-id`/`data-name`；选择器复选框用 `data-tag-id`。

- [ ] **Step 1: 注册两个监听器**

在 `setupEventListeners` 内 Task 7 的仪表盘监听器之后追加：

```js
    // Delegated click for tag manager (delete tag)
    elements.tagsManagerList.addEventListener('click', onTagsManagerListClick);

    // Delegated change for per-file tag selector checkboxes
    elements.tagSelectorCheckboxes.addEventListener('change', onTagSelectorChange);
```

- [ ] **Step 2: 新增两个分发函数**

在 `renderTagsManager` 函数（1077 行）之前插入：

```js
// Delegated click dispatcher for the tags manager list
function onTagsManagerListClick(e) {
    const actionEl = e.target.closest('[data-action]');
    if (!actionEl) return;
    if (actionEl.dataset.action === 'delete-tag') {
        deleteTag(actionEl.dataset.id || '', actionEl.dataset.name || '');
    }
}

// Delegated change dispatcher for the file-tag selector dialog
function onTagSelectorChange(e) {
    const checkbox = e.target;
    if (checkbox.matches('input[type="checkbox"][data-tag-id]') && state.taggingFile) {
        toggleFileTagAssociation(checkbox, checkbox.dataset.tagId || '', state.taggingFile.path);
    }
}
```

- [ ] **Step 3: 重写 `renderTagsManager`**

将 `renderTagsManager`（1084-1092）替换为：

```js
    elements.tagsManagerList.innerHTML = state.tags.map(tag => {
        const safeColor = escapeHtml(tag.color);
        const safeName = escapeHtml(tag.name);
        const safeId = escapeHtml(tag.id);
        return `
            <div class="tag-chip" style="background-color: ${safeColor}33; border-color: ${safeColor};">
                <span style="display:inline-block; width:10px; height:10px; border-radius:50%; background-color:${safeColor};"></span>
                <span>${safeName}</span>
                <button class="btn-tag-delete" title="删除分类标签" data-action="delete-tag" data-id="${safeId}" data-name="${safeName}">✕</button>
            </div>
        `;
    }).join('');
```

- [ ] **Step 4: 重写 `openTaggingDialog` 的复选框渲染**

将 `openTaggingDialog` 内（1016-1027）的：

```js
    elements.tagSelectorCheckboxes.innerHTML = state.tags.map(tag => {
        const checked = mappedIds.includes(tag.id) ? 'checked' : '';
        return `
            <label class="tag-selector-item">
                <span style="display:flex; align-items:center; gap:8px;">
                    <span style="width:12px; height:12px; border-radius:50%; background-color:${escapeHtml(tag.color)};"></span>
                    <span>${escapeHtml(tag.name)}</span>
                </span>
                <input type="checkbox" data-tag-id="${escapeHtml(tag.id)}" ${checked} onchange="toggleFileTagAssociation(this, '${escapeHtml(tag.id)}', '${escapeHtml(file.path.replace(/\\/g, '\\\\'))}')">
            </label>
        `;
    }).join('');
```

替换为：

```js
    elements.tagSelectorCheckboxes.innerHTML = state.tags.map(tag => {
        const checked = mappedIds.includes(tag.id) ? 'checked' : '';
        return `
            <label class="tag-selector-item">
                <span style="display:flex; align-items:center; gap:8px;">
                    <span style="width:12px; height:12px; border-radius:50%; background-color:${escapeHtml(tag.color)};"></span>
                    <span>${escapeHtml(tag.name)}</span>
                </span>
                <input type="checkbox" data-tag-id="${escapeHtml(tag.id)}" ${checked}>
            </label>
        `;
    }).join('');
```

- [ ] **Step 5: 手工冒烟 + 提交**

浏览器验证：标签管理删标签、文件标签对话框勾选/取消勾选关联。

```bash
git add server/internal/web/app.js
git commit -m "refactor(web): delegate tag manager clicks and tag-selector changes

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 9: 移除 9 个 `window.xxx` 全局挂载

**Files:**
- Modify: `server/internal/web/app.js`（删除 1235-1244 的全局挂载块）

**Interfaces:**
- 无新产出。前置条件：Task 5-8 已将所有 inline handler 迁移到委托监听器，这些全局函数不再被 HTML 属性引用。

- [ ] **Step 1: 删除全局挂载块**

删除 `app.js` 末尾（1235-1244 行）整块：

```js
// Expose module-scoped functions to global window object for legacy inline event handlers
window.browsePath = browsePath;
window.openMedia = openMedia;
window.openTaggingDialog = openTaggingDialog;
window.deleteMediaFile = deleteMediaFile;
window.deleteFolder = deleteFolder;
window.deleteTag = deleteTag;
window.openVideoPlayer = openVideoPlayer;
window.loadRoots = loadRoots;
window.toggleFileTagAssociation = toggleFileTagAssociation;
```

- [ ] **Step 2: 静态确认无残留 inline handler / 全局引用**

Run: `grep -nE 'onclick=|onchange=|onerror=|window\.(browsePath|openMedia|openTaggingDialog|deleteMediaFile|deleteFolder|deleteTag|openVideoPlayer|loadRoots|toggleFileTagAssociation)' server/internal/web/app.js`
Expected: 无输出（所有 inline handler 与全局挂载均已移除）。

- [ ] **Step 3: 手工全功能冒烟 + 提交**

浏览器快速过一遍所有交互（浏览/打开/标签/删除/面包屑/仪表盘/标签管理），确认无 `Uncaught ReferenceError`。

```bash
git add server/internal/web/app.js
git commit -m "refactor(web): remove legacy window.* globals now that clicks are delegated

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 10: 完整回归 + XSS 专项验证

**Files:** 无改动（纯验证）。

- [ ] **Step 1: 准备恶意文件名测试文件**

在服务端某个**已配置为 scan root 或 allowed_root** 的目录下，创建以下测试文件（文件名含 HTML/引号/空格/中文）：
- `<img src=x onerror=alert(1)>.jpg`
- `it's a "weird" name.jpg`
- `中文 测试 目录`（建一个含 `'` 的子目录名，如 `it's folder`）

- [ ] **Step 2: 浏览器全功能回归清单**

打开 Web 管理器，逐项验证：
- 仪表盘：最近项点击打开视频；统计数字显示正常。
- 媒体浏览：根目录卡 → 进入；磁盘卡 → 进入；子目录卡 → 进入；面包屑逐级回跳、点"根目录"回根。
- 搜索：输入关键词回车 → 结果显示；点"返回上级目录"回到原目录。
- 文件卡：点卡 → 视频/图片打开；视频内转码切换、进度条、关闭；图片灯箱单张/拼接切换、左右导航、Esc 关闭。
- 标签：文件卡 🏷️ 打开对话框 → 勾选/取消关联生效、卡片色点更新；标签管理 → 新建/删除标签。
- 删除（若启用）：文件 🗑️、文件夹 🗑️、视频播放器内删除均生效。
- 缩略图失败回退显示占位图标。

- [ ] **Step 3: XSS 专项验证（核心）**

浏览到含 Step 1 恶意文件名的目录：
- **确认浏览器未弹出 `alert(1)`**（修复前会执行）。
- 确认 `<img src=x onerror=alert(1)>.jpg` 作为**纯文本**显示在卡片标题里。
- 点击含 `'` 的文件/文件夹 → 正常打开/进入（修复前 inline onclick 会语法错误导致点击失效）。
- 打开 DevTools Console → 确认无 `ReferenceError` / 无未捕获异常。

- [ ] **Step 4: 记录验证结果**

在交付说明中记录：三端安全加固已完成并通过回归；XSS 专项验证通过（恶意文件名不再执行脚本）。

---

## Self-Review（作者已执行）

**1. Spec 覆盖**：
- 漏洞一（Server 越权读）→ Task 1（逻辑+测试）+ Task 2（接线+错误一致性）。✅
- 漏洞二（Android Zip Slip）→ Task 3（`isInside`+测试）+ Task 4（接入两处）。✅
- 漏洞三（Web XSS/路径注入）→ Task 5-9（事件委托重构 + 统一 escape + 移除全局 + onerror 回退）。✅
- 漏洞 #4 清理（system.go 错误一致性）→ Task 2 Step 4。✅
- 验证策略（spec 第 7 节）→ 各 Task 的 `go test`/`gradlew`/浏览器手工 + Task 10 XSS 专项。✅

**2. 占位符扫描**：无 TBD/TODO；每个代码步骤均含完整代码；每条命令含期望输出。✅

**3. 类型/签名一致性**：
- `ValidateSystemMediaAccess(pathStr, allowedRoots, allowedExtensions)` —— Task 1 定义、Task 2 调用，签名一致。✅
- `isInside(destDir: File, candidate: File): Boolean`（文件级 `internal`）—— Task 3 定义、Task 4 经 `safeResolveChild` 调用。✅
- Web 委托函数名（`onBrowserListClick`/`onBreadcrumbsClick`/`onDashboardRecentClick`/`onTagsManagerListClick`/`onTagSelectorChange`）—— 注册与定义一致；`data-action` 值（`browse`/`open`/`tag`/`delete-folder`/`delete-file`/`load-roots`/`crumb`/`open-video`/`delete-tag`）在渲染与分发处逐一对应。✅
- `toggleFileTagAssociation(checkbox, tagId, filePath)` 签名未变，Task 8 调用匹配。✅
