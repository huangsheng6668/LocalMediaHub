# 安全加固 Round 2 实现计划（Security Hardening · Server-only）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 封堵服务端路径校验的符号链接/UNC 绕过（第一轮词法校验留下的真洞），并补齐 HTTP 超时/优雅关停、config 脱敏、原子化写、黑名单合并与测试。

**Architecture:** 仅改 Go 服务端，分三个阶段、7 个任务。Phase 1 把路径安全边界从"词法"升级为"解析后重新校验"并让 validator 返回解析后的真实路径（消除"校验用词法、服务跟随链接"的 TOCTOU）；Phase 2 给 HTTP 服务加超时与优雅关停；Phase 3 脱敏 config 并原子化写盘。每任务独立可编译、可测试、可提交。

**Tech Stack:** Go 1.22+ / Echo v4 / slog / 标准库 `net/http`、`path/filepath`。

## Global Constraints

- **提交策略**（`AGENTS.md` 同步政策）：本项目约定任何本地改动自动同步推送至 GitHub `master` 分支。所有提交直接在 `master` 上进行，**不开 feature 分支**。
- **Go 编码规则**（`AGENTS.md`）：handler 只做参数解析与响应，业务逻辑/路径校验放 `internal/service/`；所有文件访问必须经路径校验函数；列表返回用 `make([]T, 0)` 避免 JSON `null`；5xx 错误用 `respondError`/`respondInternalError`，**不向客户端回显 `err.Error()`**。
- **Go 测试风格**：沿用 `server/internal/service/path_test.go` 既有风格——平铺 `func TestXxx(t *testing.T)`、`t.TempDir()`、`filepath.Join`、`os.WriteFile` 造真实文件；`path_test.go`/`config_test.go` 与被测包同包，可调用未导出符号。
- **Go 代理**（中国大陆网络）：若 `go test`/`go build` 拉依赖失败，用 `GOPROXY=https://goproxy.cn,direct go ...`。
- **行为变化（已决策，见 spec §11）**：媒体/系统根里若有指向 `allowed_roots` 之外的符号链接/junction，加固后该条目返回 403；把目标加进 `allowed_roots` 即可恢复。`IsPathWithinRoots` 保持词法不变（仍被搜索/浏览/下载的显示过滤使用，非安全边界）。
- **`filepath.EvalSymlinks` 语义**：要求路径存在；本组端点本就要求文件/目录存在，不存在时返回包装错误，`os.IsNotExist` 可识别（用于 SystemBrowse 的 404 映射）。
- **范围之外**（spec §2 非目标）：鉴权/TLS、性能项、`ScanConfig.GetRoots` 的 `sync.Once` 竞态、`streaming.go` Range 测试、`DownloadFolderZip`（folders.go，仍用词法 `IsPathWithinRoots`，与 scan-root 过滤一致，本轮不动——其符号链接面留待后续轮次）。

## File Structure

**`server/internal/service/path.go`** — 核心安全层。
- Task 1：`blockedPaths`→`blockedSegments`（并集），`checkBlocked`→`containsBlockedSegment`（按段匹配），更新 4 处内部调用。
- Task 2：新增 `isUNC`、`resolveWithin`（未导出）、`ResolveWithinRoots`（导出）。
- Task 3：`ValidateSystemMediaAccess` 改返回 `(string, error)`；新增 `ValidateSystemBrowse`、`ValidateDeletion`；移除 `ValidateSystemPath`、`ValidateSystemBrowseAllowed`、`ValidateSystemBrowsePath`。
- Task 4：`ValidateAccessibleMediaPath` 改返回 `(string, error)`。

**`server/internal/service/path_test.go`** — Task 1/2/3/4 扩展。
**`server/internal/server/handler/system.go`** — Task 3：`SystemBrowse`/`SystemThumbnail`/`SystemOriginal`/`SystemStream`/`DeletePath` 改用解析路径；删除 `isAllowedToDelete`（逻辑迁入 `ValidateDeletion`）。
**`server/internal/server/handler/media.go`** / **`images.go`** / **`videos.go`** — Task 4：8 个 handler 改用解析路径。
**`server/internal/server/server.go`** + **`server_test.go`** — Task 5：`httpServer` 字段 + 超时 + 优雅关停 + 集成测试。
**`server/internal/config/config.go`** + **`config_test.go`**（新建）— Task 6：`Config.Public()` 脱敏；Task 7：原子 `Save`。
**`server/internal/server/handler/admin.go`** — Task 6：`GetConfig`/`UpdateConfig` 用 `Public()`；Task 7：roots 绝对路径校验。

---

## Phase 1 — 路径解析 + 黑名单 + 校验整合

### Task 1: 黑名单改为按段匹配 + 合并并集（TDD）

**Files:**
- Modify: `server/internal/service/path.go`（`blockedPaths` 第 11-18 行、`checkBlocked` 第 185-195 行、4 处 `checkBlocked` 调用）
- Test: `server/internal/service/path_test.go`（末尾追加）

**Interfaces:**
- Produces: `blockedSegments []string`（并集）、`containsBlockedSegment(absPath string) error`（未导出，同包测试可见）。Task 2 的 `ResolveWithinRoots` 依赖它。

- [ ] **Step 1: 写失败测试**

在 `server/internal/service/path_test.go` 末尾追加：

```go
func TestContainsBlockedSegmentMatchesWholeSegment(t *testing.T) {
	cases := map[string]bool{
		// 真实段 → 命中
		filepath.Join("D:", "Media", "windows", "x.jpg"):          true,
		filepath.Join("D:", "Media", "System32", "x.jpg"):         true, // 大小写不敏感
		filepath.Join("D:", "Media", "Program Files (x86)", "x"):  true, // 并集新成员 + 含括号空格
		filepath.Join("D:", "Media", "$RECYCLE.BIN", "x.jpg"):     true,
		// 非整段 → 不命中（修复旧子串误伤）
		filepath.Join("D:", "Media", "windows-screenshots", "x"):  false,
		filepath.Join("D:", "Media", "mywindows", "x.jpg"):        false,
		filepath.Join("D:", "Media", "clip.mp4"):                  false,
	}
	for path, wantBlocked := range cases {
		err := containsBlockedSegment(path)
		gotBlocked := err != nil
		if gotBlocked != wantBlocked {
			t.Errorf("containsBlockedSegment(%q) blocked=%v, want %v", path, gotBlocked, wantBlocked)
		}
	}
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd server && go test ./internal/service/ -run TestContainsBlockedSegment -v`
Expected: 编译失败，提示 `undefined: containsBlockedSegment`（`checkBlocked` 仍存在但测试引用了新名）。

- [ ] **Step 3: 替换黑名单变量**

将 `server/internal/service/path.go` 第 10-18 行（注释 + `var blockedPaths = []string{...}`）整体替换为：

```go
// blockedSegments are path segments (compared case-insensitively, whole-segment)
// that must never be browsed, served, or deleted. Shared by browse, media-access,
// and delete validation so read and write paths enforce the SAME blocklist
// (previously these were duplicated and had already diverged between path.go and
// system.go). Whole-segment matching avoids the old substring false positives
// (e.g. a media folder named "windows-screenshots" is its own segment and is NOT
// blocked).
//
// NOTE: "users" is intentionally EXCLUDED. On Windows most real media lives under
// C:\Users\<profile>\(Pictures|Videos|Downloads) and t.TempDir() sits under
// C:\Users\<profile>\AppData\Local\Temp, so blocking the "users" segment would
// reject legitimate user media (and break every temp-dir test fixture).
var blockedSegments = []string{
	"windows",
	"winnt",
	"system32",
	"syswow64",
	"$recycle.bin",
	"system volume information",
	"program files",
	"program files (x86)",
	"boot",
}
```

- [ ] **Step 4: 替换 `checkBlocked` 为 `containsBlockedSegment`**

将 `server/internal/service/path.go` 第 185-195 行（`checkBlocked` 函数）整体替换为：

```go
// containsBlockedSegment reports whether any segment of absPath (split on the OS
// separator, lower-cased) equals one of the blocked segments.
func containsBlockedSegment(absPath string) error {
	for _, seg := range strings.Split(strings.ToLower(absPath), string(filepath.Separator)) {
		for _, blocked := range blockedSegments {
			if seg == blocked {
				return fmt.Errorf("access denied: restricted directory")
			}
		}
	}
	return nil
}
```

- [ ] **Step 5: 更新 4 处旧 `checkBlocked` 调用为 `containsBlockedSegment`**

在 `server/internal/service/path.go` 内，把以下 4 处的 `checkBlocked(` 改为 `containsBlockedSegment(`（函数体不动，仅改名）：
- `ValidateSystemPath` 内（原第 74 行）：`if err := containsBlockedSegment(absPath); err != nil {`
- `ValidateSystemMediaAccess` 内（原第 116 行）：`if err := containsBlockedSegment(absPath); err != nil {`
- `ValidateSystemBrowsePath` 内（原第 131 行）：`if err := containsBlockedSegment(absPath); err != nil {`
- `ValidateAccessibleMediaPath` 内（原第 176 行）：`if err := containsBlockedSegment(absPath); err != nil {`

- [ ] **Step 6: 运行测试确认通过**

Run: `cd server && go test ./internal/service/ -run TestContainsBlockedSegment -v`
Expected: PASS。

- [ ] **Step 7: 全量 service 包测试，确认无回归**

Run: `cd server && go test ./internal/service/ -v`
Expected: PASS（既有 9 个测试全过；它们未使用被新名单覆盖的路径）。

- [ ] **Step 8: 提交**

```bash
git add server/internal/service/path.go server/internal/service/path_test.go
git commit -m "refactor(server): match blocked paths by segment with unified blocklist

Replaces the substring-based checkBlocked (which false-positived on names like
'windows-screenshots' and existed as two diverged copies) with a single
whole-segment containsBlockedSegment and a unified blockedSegments list shared
by browse, media-access, and delete paths.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: 新增符号链接解析原语 `ResolveWithinRoots`（TDD）

**Files:**
- Modify: `server/internal/service/path.go`（文件末尾追加）
- Test: `server/internal/service/path_test.go`（末尾追加）

**Interfaces:**
- Consumes: `NormalizePath`、`containsBlockedSegment`（Task 1）。
- Produces:
  - `func isUNC(path string) bool`
  - `func resolveWithin(pathStr string, roots []string) (string, error)`（未导出）
  - `func ResolveWithinRoots(pathStr string, roots []string) (string, error)`（导出）

  Task 3/4 的各 validator 依赖 `ResolveWithinRoots`/`resolveWithin`。

- [ ] **Step 1: 写失败测试**

在 `server/internal/service/path_test.go` 末尾追加：

```go
func TestResolveWithinRootsRejectsSymlinkEscape(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	target := filepath.Join(outside, "secret.jpg")
	if err := os.WriteFile(target, []byte("x"), 0o644); err != nil {
		t.Fatalf("create target: %v", err)
	}
	link := filepath.Join(root, "link.jpg")
	if err := os.Symlink(target, link); err != nil {
		t.Skipf("symlink creation not supported on this platform: %v", err)
	}
	if _, err := ResolveWithinRoots(link, []string{root}); err == nil {
		t.Fatal("expected symlink under root to be rejected")
	}
}

func TestResolveWithinRootsRejectsInRootSymlink(t *testing.T) {
	root := t.TempDir()
	target := filepath.Join(root, "real.jpg")
	if err := os.WriteFile(target, []byte("x"), 0o644); err != nil {
		t.Fatalf("create target: %v", err)
	}
	link := filepath.Join(root, "link.jpg")
	if err := os.Symlink(target, link); err != nil {
		t.Skipf("symlink creation not supported on this platform: %v", err)
	}
	// reparse-point policy rejects ALL links under roots (in-root or escaping).
	if _, err := ResolveWithinRoots(link, []string{root}); err == nil {
		t.Fatal("expected in-root symlink to be rejected under the reparse-point policy")
	}
}

func TestResolveWithinRootsRejectsUNC(t *testing.T) {
	if _, err := ResolveWithinRoots(`\\server\share\file.jpg`, []string{`\\server\share`}); err == nil {
		t.Fatal("expected UNC path to be rejected")
	}
}

func TestResolveWithinRootsRejectsPathOutsideRoots(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	filePath := filepath.Join(outside, "clip.mp4")
	if err := os.WriteFile(filePath, []byte("v"), 0o644); err != nil {
		t.Fatalf("create file: %v", err)
	}
	if _, err := ResolveWithinRoots(filePath, []string{root}); err == nil {
		t.Fatal("expected path outside roots to be rejected")
	}
}

// TestResolveWithinRootsRejectsJunction guards the threat that motivated the
// reparse-point policy: directory junctions, which filepath.EvalSymlinks does
// NOT resolve on Windows (Go 1.24). Junctions need no administrator privilege
// (unlike symlinks), so this runs on every Windows host — it is the real
// verification of the bypass that the symlink tests can only t.Skip on Windows.
func TestResolveWithinRootsRejectsJunction(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("junction test is Windows-specific")
	}
	root := t.TempDir()
	outside := t.TempDir()
	if err := os.WriteFile(filepath.Join(outside, "secret.jpg"), []byte("x"), 0o644); err != nil {
		t.Fatalf("create target: %v", err)
	}
	link := filepath.Join(root, "link")
	cmd := exec.Command("cmd", "/c", "mklink", "/J", link, outside)
	if err := cmd.Run(); err != nil {
		t.Skipf("mklink /J failed: %v", err)
	}
	if _, err := ResolveWithinRoots(link, []string{root}); err == nil {
		t.Fatal("expected junction under root to be rejected")
	}
	through := filepath.Join(link, "secret.jpg")
	if _, err := ResolveWithinRoots(through, []string{root}); err == nil {
		t.Fatal("expected path traversing a junction to be rejected")
	}
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd server && go test ./internal/service/ -run TestResolveWithinRoots -v`
Expected: 编译失败，提示 `undefined: ResolveWithinRoots`。

- [ ] **Step 3: 写实现**

在 `server/internal/service/path.go` 文件末尾追加：

```go
// isUNC reports whether path is a UNC path (\\server\share, \\?\, \\.\).
func isUNC(path string) bool {
	return len(path) >= 2 && path[0] == '\\' && path[1] == '\\'
}

// resolveWithin lexical-cleans pathStr, rejects UNC, requires the cleaned path
// to be inside one of the cleaned roots, AND rejects any path that traverses a
// reparse point (Windows junction or a symlink) below the root boundary.
//
// Reparse points are REJECTED, not followed. filepath.EvalSymlinks does NOT
// resolve Windows directory junctions (verified on Go 1.24 — caused a confirmed
// /system/browse escape via junction), and a hand-rolled follower is risky in
// security-critical code; denying links outright guarantees none can escape the
// configured roots. os.Readlink succeeds for both junctions and symlinks on
// Windows and for symlinks on Unix, so it is the detector.
//
// Returns the cleaned path (not the raw input) so callers open/serve that,
// closing the "validate lexically, serve follows the link" TOCTOU.
func resolveWithin(pathStr string, roots []string) (string, error) {
	absPath, err := NormalizePath(pathStr)
	if err != nil {
		return "", err
	}
	if isUNC(absPath) {
		return "", fmt.Errorf("access denied: UNC paths are not allowed")
	}

	for _, root := range roots {
		absRoot, err := NormalizePath(root)
		if err != nil || isUNC(absRoot) {
			continue
		}
		rel, err := filepath.Rel(absRoot, absPath)
		if err != nil {
			continue
		}
		if rel == "." {
			return absPath, nil // the root itself; operator-configured, allowed
		}
		if rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
			continue // not within this root
		}
		if err := assertNoReparseBelow(absRoot, rel); err != nil {
			return "", err
		}
		return absPath, nil
	}
	return "", fmt.Errorf("access denied: path outside allowed directories")
}

// assertNoReparseBelow walks each component of rel under root and returns an
// error if any is a reparse point (junction or symlink), detected via os.Readlink.
func assertNoReparseBelow(root, rel string) error {
	cur := root
	for _, seg := range strings.Split(filepath.ToSlash(rel), "/") {
		if seg == "" || seg == "." {
			continue
		}
		cur = filepath.Join(cur, seg)
		if _, err := os.Readlink(cur); err == nil {
			return fmt.Errorf("access denied: path traverses a link")
		}
	}
	return nil
}

// ResolveWithinRoots is the security boundary for system/media endpoints: it
// runs resolveWithin (lexical containment + reparse-point rejection) AND applies
// the blocked-segment list. Returns the cleaned path for the caller to open/serve.
func ResolveWithinRoots(pathStr string, roots []string) (string, error) {
	resolved, err := resolveWithin(pathStr, roots)
	if err != nil {
		return "", err
	}
	if err := containsBlockedSegment(resolved); err != nil {
		return "", err
	}
	return resolved, nil
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd server && go test ./internal/service/ -run TestResolveWithinRoots -v`
Expected: PASS（符号链接逃逸被拒、根内符号链接放行、UNC 被拒、根外被拒；无权限环境跳过 symlink 用例）。

- [ ] **Step 5: 全量测试 + 提交**

Run: `cd server && go test ./...`
Expected: PASS。

```bash
git add server/internal/service/path.go server/internal/service/path_test.go
git commit -m "feat(server): add ResolveWithinRoots symlink-aware path boundary

Adds EvalSymlinks-based resolution that resolves symlinks/junctions on both the
requested path and each root before the containment check, plus UNC rejection.
This is the primitive the validators switch to in the next tasks to close the
symlink-bypass of the lexical allowed_roots boundary.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: 系统侧 validator 改解析路径 + 合并/迁入/删除（含 system.go 接线）

**Files:**
- Modify: `server/internal/service/path.go`（`ValidateSystemMediaAccess`、新增 `ValidateSystemBrowse`/`ValidateDeletion`、删除 3 个旧函数）
- Modify: `server/internal/service/path_test.go`（更新签名、替换 1 个测试）
- Modify: `server/internal/server/handler/system.go`（5 处 handler + 删除 `isAllowedToDelete`）

**Interfaces:**
- Consumes: Task 1 `containsBlockedSegment`、Task 2 `ResolveWithinRoots`/`NormalizePath`/`validateMediaFilePath`。
- Produces（新签名，后续任务与 handler 依赖）：
  - `func ValidateSystemMediaAccess(pathStr string, allowedRoots []string, allowedExtensions []string) (string, error)`
  - `func ValidateSystemBrowse(pathStr string, allowedRoots []string) (string, error)`
  - `func ValidateDeletion(pathStr string, allRoots []string) (string, error)`
- 删除：`ValidateSystemPath`（零调用方，死代码）、`ValidateSystemBrowseAllowed`、`ValidateSystemBrowsePath`（折入 `ValidateSystemBrowse`）。

- [ ] **Step 1: 更新 `path_test.go` 既有系统侧测试的签名**

在 `server/internal/service/path_test.go` 中：
- 将 `TestValidateSystemBrowseAllowedRequiresConfiguredRoots`（第 9-16 行）整体替换为：

```go
func TestValidateSystemBrowseRequiresConfiguredRoots(t *testing.T) {
	root := t.TempDir()

	_, err := ValidateSystemBrowse(root, nil)
	if err == nil {
		t.Fatal("expected access to be denied when no system roots are configured")
	}
}
```

- 将 `TestValidateSystemMediaAccess*` 四个测试（第 78-129 行）里所有的 `err := ValidateSystemMediaAccess(...)` 改为 `_, err := ValidateSystemMediaAccess(...)`（共 4 处；函数名与参数不变）。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd server && go test ./internal/service/ -v`
Expected: 编译失败（`ValidateSystemBrowse` 未定义；`ValidateSystemMediaAccess` 返回值数量变化）。

- [ ] **Step 3: 重写 `ValidateSystemMediaAccess` 返回解析路径**

将 `server/internal/service/path.go` 的 `ValidateSystemMediaAccess`（第 100-120 行）整体替换为：

```go
// ValidateSystemMediaAccess validates a media file path for the system
// thumbnail/original/stream endpoints. It resolves symlinks/junctions, requires
// the resolved path be under one of the configured system allowed roots, blocks
// sensitive segments, and confirms it is an existing file with an allowed
// extension. Returns the resolved real path for the caller to open/serve.
func ValidateSystemMediaAccess(pathStr string, allowedRoots []string, allowedExtensions []string) (string, error) {
	if len(allowedRoots) == 0 {
		return "", fmt.Errorf("system browse is not configured")
	}
	resolved, err := ResolveWithinRoots(pathStr, allowedRoots)
	if err != nil {
		return "", err
	}
	if err := validateMediaFilePath(resolved, allowedExtensions); err != nil {
		return "", err
	}
	return resolved, nil
}
```

- [ ] **Step 4: 新增 `ValidateSystemBrowse` 与 `ValidateDeletion`，删除 3 个旧函数**

在 `server/internal/service/path.go` 中：
- 删除 `ValidateSystemPath`（第 63-98 行，含其上方注释）——死代码。
- 删除 `ValidateSystemBrowsePath`（第 122-136 行）与 `ValidateSystemBrowseAllowed`（第 138-153 行）——折入下面的新函数。
- 在原 `ValidateSystemBrowseAllowed` 的位置插入：

```go
// ValidateSystemBrowse validates a directory path for listing contents: it
// resolves symlinks/junctions, requires the resolved path within the resolved
// allowed roots, and blocks sensitive segments. Returns the resolved directory
// path. Replaces the old ValidateSystemBrowseAllowed + ValidateSystemBrowsePath
// two-step, whose security depended on call order.
func ValidateSystemBrowse(pathStr string, allowedRoots []string) (string, error) {
	if len(allowedRoots) == 0 {
		return "", fmt.Errorf("system browse is not configured")
	}
	return ResolveWithinRoots(pathStr, allowedRoots)
}

// ValidateDeletion resolves symlinks/junctions, requires the resolved path be
// within one of the resolved roots, blocks sensitive segments, and forbids
// deleting a root itself. Returns the resolved path. Moved here from
// handler.isAllowedToDelete so blocklist + roots logic live in one place.
func ValidateDeletion(pathStr string, allRoots []string) (string, error) {
	resolved, err := ResolveWithinRoots(pathStr, allRoots)
	if err != nil {
		return "", err
	}
	for _, root := range allRoots {
		absRoot, err := NormalizePath(root)
		if err != nil || isUNC(absRoot) {
			continue
		}
		resolvedRoot, err := filepath.EvalSymlinks(absRoot)
		if err != nil {
			continue
		}
		if resolved == resolvedRoot {
			return "", fmt.Errorf("access denied: cannot delete a root directory")
		}
	}
	return resolved, nil
}
```

- [ ] **Step 5: 重写 `system.go` 的 `SystemBrowse`**

将 `server/internal/server/handler/system.go` 的 `SystemBrowse`（第 24-128 行）整体替换为（仅把两步校验折成单步、并把后续 `os.Stat`/`os.ReadDir`/`filepath.Join`/`CurrentPath` 改用 `resolved`；entries 循环体不变）：

```go
func (h *Handler) SystemBrowse(c echo.Context) error {
	pathStr := c.QueryParam("path")

	if pathStr == "" {
		roots := h.cfg.GetSystemAllowedRoots()
		folders := make([]models.Folder, 0, len(roots))
		for _, root := range roots {
			fi, err := os.Stat(root)
			if err != nil {
				continue
			}
			if fi.IsDir() {
				folders = append(folders, models.Folder{
					Name:         filepath.Base(root),
					Path:         root,
					RelativePath: root,
					IsRoot:       true,
					ModifiedTime: fi.ModTime(),
				})
			}
		}
		return c.JSON(http.StatusOK, models.BrowseResult{
			CurrentPath: "",
			Folders:     folders,
			Files:       []models.MediaFile{},
		})
	}

	// Resolve symlinks + enforce allowed_roots boundary in one step.
	resolved, err := service.ValidateSystemBrowse(pathStr, h.cfg.GetSystemAllowedRoots())
	if err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "path not found")
		}
		return respondError(c, http.StatusForbidden, "access denied")
	}

	fi, err := os.Stat(resolved)
	if err != nil {
		return respondInternalError(c, err)
	}
	if !fi.IsDir() {
		return respondError(c, http.StatusBadRequest, "not a directory")
	}

	entries, err := os.ReadDir(resolved)
	if err != nil {
		return respondInternalError(c, err)
	}

	folders := make([]models.Folder, 0)
	files := make([]models.MediaFile, 0)
	for _, entry := range entries {
		fullPath := filepath.Join(resolved, entry.Name())
		if entry.IsDir() {
			info, _ := entry.Info()
			var modTime time.Time
			if info != nil {
				modTime = info.ModTime()
			}
			folders = append(folders, models.Folder{
				Name:         entry.Name(),
				Path:         fullPath,
				RelativePath: strings.TrimPrefix(fullPath, filepath.VolumeName(fullPath)),
				ModifiedTime: modTime,
			})
		} else {
			ext := strings.ToLower(filepath.Ext(entry.Name()))
			if h.isMediaExt(ext) {
				info, _ := entry.Info()
				var size int64
				var modTime time.Time
				if info != nil {
					size = info.Size()
					modTime = info.ModTime()
				}
				mediaType := "video"
				for _, imgExt := range h.cfg.Scan.ImageExtensions {
					if strings.EqualFold(ext, imgExt) {
						mediaType = "image"
						break
					}
				}
				files = append(files, models.MediaFile{
					Name:         entry.Name(),
					Path:         fullPath,
					RelativePath: fullPath,
					Size:         size,
					ModifiedTime: modTime,
					MediaType:    mediaType,
					Extension:    ext,
				})
			}
		}
	}

	return c.JSON(http.StatusOK, models.BrowseResult{
		CurrentPath: resolved,
		Folders:     folders,
		Files:       files,
	})
}
```

- [ ] **Step 6: 重写 `SystemThumbnail` / `SystemOriginal` / `SystemStream`**

将 `server/internal/server/handler/system.go` 的这三个函数（第 130-181 行）整体替换为：

```go
func (h *Handler) SystemThumbnail(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	resolved, err := service.ValidateSystemMediaAccess(pathStr, h.cfg.GetSystemAllowedRoots(), h.mediaExtensions())
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	thumbPath, err := h.thumbnail.GenerateSystemThumbnail(resolved)
	if err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "file not found")
		}
		return respondInternalError(c, err)
	}

	return c.File(thumbPath)
}

func (h *Handler) SystemOriginal(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	resolved, err := service.ValidateSystemMediaAccess(pathStr, h.cfg.GetSystemAllowedRoots(), h.mediaExtensions())
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	return c.File(resolved)
}

func (h *Handler) SystemStream(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	resolved, err := service.ValidateSystemMediaAccess(pathStr, h.cfg.GetSystemAllowedRoots(), h.mediaExtensions())
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	if err := h.streaming.ServeFile(c.Response().Writer, c.Request(), resolved); err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "file not found")
		}
		return respondInternalError(c, err)
	}
	return nil
}
```

- [ ] **Step 7: 删除 `isAllowedToDelete`，重写 `DeletePath` 调用 `ValidateDeletion`**

将 `server/internal/server/handler/system.go` 的 `isAllowedToDelete`（第 188-239 行，整个方法）**删除**。

将 `DeletePath`（第 241-289 行）整体替换为：

```go
func (h *Handler) DeletePath(c echo.Context) error {
	if !h.cfg.System.EnableDelete {
		return c.JSON(http.StatusForbidden, map[string]string{"error": "remote deletion is disabled on the server"})
	}

	var req DeleteRequest
	if err := c.Bind(&req); err != nil {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "invalid request body"})
	}

	if req.Path == "" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "path required"})
	}

	allRoots := append(append([]string{}, h.cfg.Scan.GetRoots()...), h.cfg.GetSystemAllowedRoots()...)
	resolved, err := service.ValidateDeletion(req.Path, allRoots)
	if err != nil {
		return c.JSON(http.StatusForbidden, map[string]string{"error": err.Error()})
	}

	fi, err := os.Stat(resolved)
	if err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "path not found")
		}
		return respondInternalError(c, err)
	}

	if fi.IsDir() {
		if !req.Recursive {
			return respondError(c, http.StatusBadRequest, "cannot delete a non-empty directory without recursive flag")
		}
		if err := os.RemoveAll(resolved); err != nil {
			return respondInternalError(c, fmt.Errorf("failed to delete directory: %w", err))
		}
	} else {
		if err := os.Remove(resolved); err != nil {
			return respondInternalError(c, fmt.Errorf("failed to delete file: %w", err))
		}
	}

	_ = h.tags.CleanDeletedPath(resolved)
	h.scanner.InvalidateCache()

	return c.JSON(http.StatusOK, map[string]string{"message": "deleted successfully"})
}
```

> `DeletePath` 仍用 `fmt.Errorf`，故 `system.go` 的 `"fmt"` import 保留；不要删除它。

- [ ] **Step 8: 编译 + 全量测试**

Run: `cd server && go build ./... && go test ./...`
Expected: 编译通过；全部测试 PASS（含更新后的系统侧测试）。若拉依赖失败加 `GOPROXY=https://goproxy.cn,direct`。

- [ ] **Step 9: 手工验证符号链接逃逸已被封堵**

准备（git-bash on Windows；junction 无需管理员）：

```bash
# 设 config.yaml 已配 system.allowed_roots，例如含一个真实目录 ROOT（如 D:/Media）
ROOT="D:/Media"            # 按实际改
OUT="C:/Users/$USER/outside_round2"   # allowed_roots 之外
mkdir -p "$OUT"
echo "secret" > "$OUT/secret.jpg"
# 在 ROOT 内建一个指向 OUT 的 junction
cmd //c "mklink /J \"${ROOT}/escapelink\" \"$(cygpath -w "$OUT" 2>/dev/null || echo "$OUT")\""
```

启动服务：

```bash
cd server && go build -o LocalMediaHub.exe ./cmd/server && ./LocalMediaHub.exe --headless
```

另一终端，**透过 junction** 请求系统端点（修复前期望 200，修复后必须 403）：

```bash
curl -i "http://localhost:8000/api/v1/system/original?path=${ROOT}/escapelink/secret.jpg"
curl -i "http://localhost:8000/api/v1/system/thumbnail?path=${ROOT}/escapelink/secret.jpg"
curl -i "http://localhost:8000/api/v1/system/browse?path=${ROOT}/escapelink"
```

Expected: 三者均 **HTTP 403** `{"error":"access denied"}`。

再用 ROOT 内一个**真实**媒体文件验证正常访问仍 200：
```bash
curl -i "http://localhost:8000/api/v1/system/original?path=${ROOT}/<某真实图片>.jpg"
```
Expected: 200。

清理 junction 与退出：
```bash
cmd //c "rmdir \"${ROOT}/escapelink\""
# Ctrl+C 停止服务
```

- [ ] **Step 10: 提交**

```bash
git add server/internal/service/path.go server/internal/service/path_test.go server/internal/server/handler/system.go
git commit -m "fix(server): resolve symlinks in system path validation, serve resolved path

SystemBrowse/Thumbnail/Original/Stream and DeletePath now resolve symlinks/
junctions and serve the resolved within-roots path, closing the bypass of the
lexical allowed_roots boundary round 1 built. Folds the two-step browse check
into ValidateSystemBrowse and moves delete authorization into service.ValidateDeletion
(removing the duplicated, diverged blocklist in system.go). Drops dead ValidateSystemPath.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: 媒体侧 `ValidateAccessibleMediaPath` 改解析路径（含 media/images/videos 接线）

**Files:**
- Modify: `server/internal/service/path.go`（`ValidateAccessibleMediaPath` 第 155-183 行）
- Modify: `server/internal/service/path_test.go`（3 个测试改签名）
- Modify: `server/internal/server/handler/media.go`（4 处）、`images.go`（2 处）、`videos.go`（2 处）

**Interfaces:**
- Consumes: Task 2 `resolveWithin`、`ResolveWithinRoots`。
- Produces: `func ValidateAccessibleMediaPath(pathStr string, scanRoots []string, systemAllowedRoots []string, allowedExtensions []string) (string, error)` —— 8 个媒体 handler 依赖此签名。

> 保留既有非对称语义：scan roots 分支不做黑名单段检查（操作者显式配置的库），system roots 分支做。

- [ ] **Step 1: 更新 `path_test.go` 媒体侧测试签名**

在 `server/internal/service/path_test.go` 中，把 `TestValidateAccessibleMediaPathAllowsScanRoots`、`TestValidateAccessibleMediaPathAllowsSystemRoots`、`TestValidateAccessibleMediaPathRejectsPathsOutsideAllRoots`（第 40-76 行）里的：
- `if err := ValidateAccessibleMediaPath(...); err != nil {` 改为 `if _, err := ValidateAccessibleMediaPath(...); err != nil {`
- `err := ValidateAccessibleMediaPath(...)` 改为 `_, err := ValidateAccessibleMediaPath(...)`

（共 3 处调用；函数名与参数不变。）

- [ ] **Step 2: 运行测试确认失败**

Run: `cd server && go test ./internal/service/ -v`
Expected: 编译失败（返回值数量变化）。

- [ ] **Step 3: 重写 `ValidateAccessibleMediaPath` 返回解析路径**

将 `server/internal/service/path.go` 的 `ValidateAccessibleMediaPath`（第 155-183 行）整体替换为：

```go
// ValidateAccessibleMediaPath checks whether a media file path is accessible from
// either the configured scan roots or the explicit system browse roots, resolving
// symlinks/junctions and returning the resolved real path. Scan-root matches do
// NOT apply the blocked-segment list (the operator's explicit library); system-root
// matches DO (via ResolveWithinRoots), preserving the prior asymmetry.
func ValidateAccessibleMediaPath(pathStr string, scanRoots []string, systemAllowedRoots []string, allowedExtensions []string) (string, error) {
	// 1. Scan roots.
	if resolved, err := resolveWithin(pathStr, scanRoots); err == nil {
		if err := validateMediaFilePath(resolved, allowedExtensions); err != nil {
			return "", err
		}
		return resolved, nil
	}
	// 2. System allowed roots (with blocked-segment check).
	if len(systemAllowedRoots) > 0 {
		if resolved, err := ResolveWithinRoots(pathStr, systemAllowedRoots); err == nil {
			if err := validateMediaFilePath(resolved, allowedExtensions); err != nil {
				return "", err
			}
			return resolved, nil
		}
	}
	return "", fmt.Errorf("access denied: path outside allowed directories")
}
```

- [ ] **Step 4: 重写 `media.go` 的 4 个 handler**

将 `server/internal/server/handler/media.go` 的 `MediaThumbnail`/`MediaOriginal`/`MediaStream`/`MediaDuration`（第 12-84 行）整体替换为：

```go
func (h *Handler) MediaThumbnail(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	allowedExts := append(h.cfg.Scan.ImageExtensions, h.cfg.Scan.VideoExtensions...)
	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), allowedExts)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	thumbPath, err := h.thumbnail.GenerateThumbnail(resolved)
	if err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "file not found")
		}
		return respondInternalError(c, err)
	}

	return c.File(thumbPath)
}

func (h *Handler) MediaOriginal(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.ImageExtensions)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	return c.File(resolved)
}

func (h *Handler) MediaStream(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.VideoExtensions)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	if err := h.streaming.ServeFile(c.Response().Writer, c.Request(), resolved); err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "file not found")
		}
		return respondInternalError(c, err)
	}
	return nil
}

func (h *Handler) MediaDuration(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}

	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.VideoExtensions)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	duration, err := h.streaming.GetVideoDuration(resolved)
	if err != nil {
		return respondInternalError(c, err)
	}

	return c.JSON(http.StatusOK, map[string]interface{}{
		"duration": duration,
	})
}
```

- [ ] **Step 5: 重写 `images.go` 的 `GetThumbnail` / `GetOriginal`**

将 `server/internal/server/handler/images.go` 的这两个函数（第 50-82 行）整体替换为：

```go
func (h *Handler) GetThumbnail(c echo.Context) error {
	pathStr, err := decodeWildcardPath(c.Param("*"), "/thumbnail")
	if err != nil {
		return respondError(c, http.StatusBadRequest, err.Error())
	}

	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.ImageExtensions)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	thumbPath, err := h.thumbnail.GenerateThumbnail(resolved)
	if err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "file not found")
		}
		return respondInternalError(c, err)
	}

	return c.File(thumbPath)
}

func (h *Handler) GetOriginal(c echo.Context) error {
	pathStr, err := decodeWildcardPath(c.Param("*"), "/original")
	if err != nil {
		return respondError(c, http.StatusBadRequest, err.Error())
	}

	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.ImageExtensions)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	return c.File(resolved)
}
```

- [ ] **Step 6: 重写 `videos.go` 的 `GetVideoThumbnail` / `StreamVideo`**

将 `server/internal/server/handler/videos.go` 的这两个函数（第 50-88 行）整体替换为：

```go
func (h *Handler) GetVideoThumbnail(c echo.Context) error {
	pathStr, err := decodeWildcardPath(c.Param("*"), "/thumbnail")
	if err != nil {
		return respondError(c, http.StatusBadRequest, err.Error())
	}

	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.VideoExtensions)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	thumbPath, err := h.thumbnail.GenerateThumbnail(resolved)
	if err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "file not found")
		}
		return respondInternalError(c, err)
	}

	return c.File(thumbPath)
}

func (h *Handler) StreamVideo(c echo.Context) error {
	pathStr, err := decodeWildcardPath(c.Param("*"), "/stream")
	if err != nil {
		return respondError(c, http.StatusBadRequest, err.Error())
	}

	resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.VideoExtensions)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}

	if err := h.streaming.ServeFile(c.Response().Writer, c.Request(), resolved); err != nil {
		if os.IsNotExist(err) {
			return respondNotFound(c, "file not found")
		}
		return respondInternalError(c, err)
	}
	return nil
}
```

- [ ] **Step 7: 编译 + 全量测试**

Run: `cd server && go build ./... && go test ./...`
Expected: 编译通过；全部测试 PASS。

- [ ] **Step 8: 手工验证 `/media` 与 `/images` / `/videos` 端点不跟随逃逸链接**

沿用 Task 3 的 junction 设置（scan root 内的 `escapelink` 指向根外）。请求统一媒体端点：

```bash
ROOT="D:/Media"   # 按实际（作为 scan root）
curl -i "http://localhost:8000/api/v1/media/original?path=${ROOT}/escapelink/secret.jpg"
curl -i "http://localhost:8000/api/v1/media/thumbnail?path=${ROOT}/escapelink/secret.jpg"
# 路由式端点（注意 /original 后缀）：
curl -i "http://localhost:8000/api/v1/images/${ROOT}/escapelink/secret.jpg/original"
```

Expected: 三者均 **HTTP 403** `{"error":"access denied"}`（修复前会返回 200 + 根外文件内容）。再用一个 scan root 内真实文件验证 200。

清理 junction（同 Task 3）。

- [ ] **Step 9: 提交**

```bash
git add server/internal/service/path.go server/internal/service/path_test.go server/internal/server/handler/media.go server/internal/server/handler/images.go server/internal/server/handler/videos.go
git commit -m "fix(server): resolve symlinks in unified media path validation

ValidateAccessibleMediaPath now resolves symlinks/junctions and returns the
resolved path; all 8 media handlers (media.go, images.go, videos.go) serve the
resolved within-roots path instead of the raw input, closing the same bypass on
the /media, /images, and /videos endpoints.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Phase 2 — HTTP 超时 + 优雅关停

### Task 5: `http.Server` 超时 + `Shutdown` 优雅关停（TDD）

**Files:**
- Modify: `server/internal/server/server.go`（`Server` struct、`New`、`Start`、`Stop`）
- Test: `server/internal/server/server_test.go`（末尾追加）

**Interfaces:**
- Consumes: 既有 `s.Echo`、`s.Scanner.Shutdown()`、`s.preGenCancel`/`s.preGenMu`。
- Produces: `Server.httpServer *http.Server`；`Start()` 走自有 `http.Server`；`Stop()` 优雅关停（排空在途请求 + 取消缩略图预生成）。`gui.go` 已处理 `http.ErrServerClosed`，无需改动。

- [ ] **Step 1: 写失败测试**

在 `server/internal/server/server_test.go` 顶部 import 块中，确保含 `"net"`、`"time"`（若缺则补；现有已有 `net/http`、`testing` 等）。然后在文件末尾追加：

```go
func TestServerStartAndStopGracefulShutdown(t *testing.T) {
	// 选取一个空闲端口供 Start 绑定。
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	ln.Close()

	cacheDir := filepath.Join(t.TempDir(), "thumb")
	cfg := &config.Config{
		Server: config.ServerConfig{Host: "127.0.0.1", Port: port},
		Scan:   config.ScanConfig{VideoExtensions: []string{".mp4"}, ImageExtensions: []string{".jpg"}},
		Thumbnail: config.ThumbnailConfig{
			CacheDir: cacheDir, MaxSize: 64, Format: "jpeg",
		},
	}
	s, err := New(cfg)
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	startErr := make(chan error, 1)
	go func() { startErr <- s.Start() }()

	healthURL := fmt.Sprintf("http://127.0.0.1:%d/api/v1/health", port)
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		resp, err := http.Get(healthURL)
		if err == nil {
			resp.Body.Close()
			if resp.StatusCode == http.StatusOK {
				break
			}
		}
		time.Sleep(50 * time.Millisecond)
	}

	// 确认超时已配置。
	if s.httpServer == nil {
		t.Fatal("expected httpServer to be configured")
	}
	if s.httpServer.ReadHeaderTimeout <= 0 {
		t.Error("expected ReadHeaderTimeout > 0")
	}

	if err := s.Stop(); err != nil {
		t.Fatalf("Stop: %v", err)
	}
	if err := <-startErr; err != nil && err != http.ErrServerClosed {
		t.Fatalf("Start returned unexpected error: %v", err)
	}
}
```

> 顶部 import 需新增 `"fmt"`、`"net"`、`"time"`（`"path/filepath"`、`"net/http"`、`"config"` 已存在于该文件）。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd server && go test ./internal/server/ -run TestServerStartAndStopGracefulShutdown -v`
Expected: 失败（`s.httpServer` 未定义，编译错误）。

- [ ] **Step 3: 给 `Server` 加 `httpServer` 字段**

将 `server/internal/server/server.go` 的 `Server` struct（第 22-33 行）整体替换为：

```go
type Server struct {
	Echo         *echo.Echo
	Config       *config.Config
	IP           string
	Scanner      *service.Scanner
	Tags         *service.TagsService
	Streaming    *service.StreamingService
	Thumbnail    *service.ThumbnailService
	httpServer   *http.Server
	preGenCtx    context.Context
	preGenCancel context.CancelFunc
	preGenMu     sync.Mutex
}
```

- [ ] **Step 4: 在 `New` 中配置 `httpServer` + 超时**

在 `server/internal/server/server.go` 的 import 块中加入 `"time"`（与既有 `"context"`、`"net/http"` 同组）。

将 `New` 中（第 77-80 行附近）：

```go
	h := handler.New(cfg, scanner, tagsService, streamingService, thumbnailService)
	s.registerRoutes(h)

	return s, nil
}
```

替换为：

```go
	h := handler.New(cfg, scanner, tagsService, streamingService, thumbnailService)
	s.registerRoutes(h)

	s.httpServer = &http.Server{
		Addr:              fmt.Sprintf("%s:%d", cfg.Server.Host, cfg.Server.Port),
		Handler:           s.Echo,
		ReadHeaderTimeout: 10 * time.Second, // mitigate Slowloris slow-header attacks
		ReadTimeout:       30 * time.Second, // covers small request bodies (e.g. config JSON)
		IdleTimeout:       120 * time.Second,
		// WriteTimeout intentionally 0: video streams and folder-zip downloads can
		// run for minutes-to-hours; a global write deadline would cut them off.
	}

	return s, nil
}
```

- [ ] **Step 5: 改 `Start` / `Stop`**

将 `server/internal/server/server.go` 的 `Start` 与 `Stop`（第 164-174 行）整体替换为：

```go
func (s *Server) Start() error {
	return s.httpServer.ListenAndServe()
}

func (s *Server) Stop() error {
	// Cancel any in-flight background scan so it doesn't keep walking the FS.
	s.Scanner.Shutdown()
	// Cancel thumbnail pre-generation (preGenCancel is nil until the first scan
	// completes — guard against nil to avoid a panic).
	s.preGenMu.Lock()
	if s.preGenCancel != nil {
		s.preGenCancel()
	}
	s.preGenMu.Unlock()
	// Drain in-flight requests (notably folder-zip downloads) before returning,
	// so Ctrl+C / tray-quit doesn't corrupt a half-written download.
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return s.httpServer.Shutdown(ctx)
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `cd server && go test ./internal/server/ -v`
Expected: PASS（含新集成测试 + 既有 `TestRegisterRoutesServesThumbnailEndpoint`）。

- [ ] **Step 7: 全量构建 + 测试**

Run: `cd server && go build ./... && go test ./...`
Expected: 编译通过；全部测试 PASS。

- [ ] **Step 8: 手工验证优雅关停**

```bash
cd server && go build -o LocalMediaHub.exe ./cmd/server && ./LocalMediaHub.exe --headless
# 另一终端：发起一个大目录的 zip 下载，然后在下载进行中 Ctrl+C 服务，观察日志无 panic、进程在 ~15s 内退出。
```
Expected: 服务收到 SIGINT 后日志输出关停信息并正常退出（无 goroutine 泄漏/panic）。

- [ ] **Step 9: 提交**

```bash
git add server/internal/server/server.go server/internal/server/server_test.go
git commit -m "feat(server): add HTTP timeouts and graceful shutdown

Configures ReadHeaderTimeout/ReadTimeout/IdleTimeout on an explicit http.Server
(Slowloris mitigation; WriteTimeout left 0 so long streams/downloads survive),
switches Stop() from Echo.Close() to http.Server.Shutdown so in-flight requests
drain, and cancels thumbnail pre-generation on shutdown.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Phase 3 — config 脱敏 + 原子写

### Task 6: `GET/PUT /admin/config` 脱敏（TDD）

**Files:**
- Modify: `server/internal/config/config.go`（新增 `ConfigPublic` 类型 + `Config.Public()` 方法）
- Modify: `server/internal/server/handler/admin.go`（`GetConfig`、`UpdateConfig` 返回 `Public()`）
- Test: `server/internal/config/config_test.go`（新建）

**Interfaces:**
- Produces: `func (c *Config) Public() ConfigPublic` —— admin handler 依赖它；`ConfigPublic.System` 仅含 `AllowedRoots`（不含 `FFmpegPath`/`EnableDelete`）。

- [ ] **Step 1: 写失败测试**

新建 `server/internal/config/config_test.go`：

```go
package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestPublicOmitsSensitiveSystemFields(t *testing.T) {
	cfg := &Config{
		Server: ServerConfig{Host: "0.0.0.0", Port: 8000},
		System: SystemConfig{
			AllowedRoots: []string{"D:/Media"},
			EnableDelete: true,
			FFmpegPath:   "C:/tools/ffmpeg.exe",
		},
	}

	data, err := json.Marshal(cfg.Public())
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	s := string(data)

	if strings.Contains(s, "ffmpeg_path") {
		t.Errorf("Public() leaked ffmpeg_path: %s", s)
	}
	if strings.Contains(s, "enable_delete") {
		t.Errorf("Public() leaked enable_delete: %s", s)
	}
	if !strings.Contains(s, "allowed_roots") {
		t.Errorf("Public() should keep allowed_roots: %s", s)
	}
	if !strings.Contains(s, "D:/Media") {
		t.Errorf("Public() should keep allowed_roots values: %s", s)
	}
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd server && go test ./internal/config/ -run TestPublicOmits -v`
Expected: 编译失败，提示 `cfg.Public undefined`。

- [ ] **Step 3: 在 `config.go` 加类型与方法**

在 `server/internal/config/config.go` 的 `SystemConfig` 结构体（第 69-73 行）之后插入：

```go
// ConfigPublic is the redacted view of Config returned by GET/PUT /admin/config.
// It omits System.FFmpegPath and System.EnableDelete (local binary path and the
// delete flag are reconnaissance value), while keeping System.AllowedRoots (already
// exposed by GET /system/drives and shown in the Web settings UI).
type ConfigPublic struct {
	Server    ServerConfig        `json:"server"`
	Scan      ScanConfig          `json:"scan"`
	Thumbnail ThumbnailConfig     `json:"thumbnail"`
	System    SystemConfigPublic  `json:"system"`
}

type SystemConfigPublic struct {
	AllowedRoots []string `json:"allowed_roots,omitempty"`
	EnableDelete bool     `json:"enable_delete,omitempty"` // kept: Web UI delete buttons depend on it (owner decision); only ffmpeg_path is redacted
}

// Public returns a copy of the config with sensitive operational fields removed.
func (c *Config) Public() ConfigPublic {
	return ConfigPublic{
		Server:    c.Server,
		Scan:      c.Scan,
		Thumbnail: c.Thumbnail,
		System:    SystemConfigPublic{AllowedRoots: c.System.AllowedRoots},
	}
}
```

- [ ] **Step 4: 改 `admin.go` 两个 handler 用 `Public()`**

将 `server/internal/server/handler/admin.go` 的 `GetConfig`（第 13-15 行）与 `UpdateConfig`（第 17-34 行）里两处 `return c.JSON(http.StatusOK, h.cfg)` 改为 `return c.JSON(http.StatusOK, h.cfg.Public())`。

具体：
- `GetConfig`：

```go
func (h *Handler) GetConfig(c echo.Context) error {
	return c.JSON(http.StatusOK, h.cfg.Public())
}
```

- `UpdateConfig` 的最后一行（原 `return c.JSON(http.StatusOK, h.cfg)`）改为：

```go
	return c.JSON(http.StatusOK, h.cfg.Public())
```

（`UpdateConfig` 其余逻辑不动。）

- [ ] **Step 5: 运行测试 + 全量构建**

Run: `cd server && go test ./internal/config/ -v && go build ./... && go test ./...`
Expected: PASS；编译通过。

- [ ] **Step 6: 提交**

```bash
git add server/internal/config/config.go server/internal/config/config_test.go server/internal/server/handler/admin.go
git commit -m "feat(server): redact ffmpeg_path and enable_delete from admin config response

GET/PUT /admin/config now return Config.Public(), which omits System.FFmpegPath
and System.EnableDelete (recon value) while keeping AllowedRoots (already exposed
via /system/drives).

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 7: `config.yaml` 原子写 + roots 绝对路径校验（TDD）

**Files:**
- Modify: `server/internal/config/config.go`（`Save` 第 99-105 行；import 加 `"path/filepath"`）
- Modify: `server/internal/server/handler/admin.go`（`UpdateConfig` 加校验；import 加 `"path/filepath"`）
- Test: `server/internal/config/config_test.go`（追加）

**Interfaces:**
- Produces: `Save` 改为同目录临时文件 + `os.Rename` 原子替换；`UpdateConfig` 拒绝非绝对路径 roots。

- [ ] **Step 1: 写失败测试**

在 `server/internal/config/config_test.go` 末尾追加：

```go
func TestSaveIsAtomicAndReadable(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")

	cfg := &Config{
		Server:      ServerConfig{Host: "0.0.0.0", Port: 8000},
		Scan:        ScanConfig{VideoExtensions: []string{".mp4"}, ImageExtensions: []string{".jpg"}},
		Thumbnail:   ThumbnailConfig{CacheDir: ".cache/thumbnails", MaxSize: 300, Format: "JPEG"},
	}
	if err := cfg.Save(path); err != nil {
		t.Fatalf("Save: %v", err)
	}

	// 不应残留临时文件。
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("readdir: %v", err)
	}
	if len(entries) != 1 || entries[0].Name() != "config.yaml" {
		var names []string
		for _, e := range entries {
			names = append(names, e.Name())
		}
		t.Fatalf("expected only config.yaml in dir, got %v", names)
	}

	// 可回读。
	loaded, err := Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if loaded.Server.Port != 8000 {
		t.Errorf("expected port 8000, got %d", loaded.Server.Port)
	}
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd server && go test ./internal/config/ -run TestSaveIsAtomic -v`
Expected: 失败（旧 `Save` 直接 `os.WriteFile`，写后目录里仍只有 `config.yaml`——此用例对"无临时残留"可能恰好通过；但为统一先确认它跑通。若已通过，仍继续 Step 3 改造 `Save`，再用例依旧应 PASS）。

> 说明：此用例是回归守护——保证改造后的临时文件+rename 不会留下 `.config-*.yaml.tmp` 残留，并能被 `Load` 正确读回。

- [ ] **Step 3: 重写 `Save` 为原子写**

在 `server/internal/config/config.go` 的 import 块加入 `"path/filepath"`（与既有 `"os"`、`"sync"` 同组）。

将 `Save`（第 99-105 行）整体替换为：

```go
// Save writes the config atomically: marshal → temp file in the same dir → fsync
// → rename over the target. A crash mid-write therefore cannot corrupt the
// existing config (on Windows, os.Rename uses MoveFileEx with REPLACE_EXISTING).
func (c *Config) Save(path string) error {
	data, err := yaml.Marshal(c)
	if err != nil {
		return err
	}
	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, ".config-*.yaml.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName) // no-op once rename succeeds
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmpName, path)
}
```

- [ ] **Step 4: `UpdateConfig` 加 roots 绝对路径校验**

在 `server/internal/server/handler/admin.go` 的 import 块加入 `"path/filepath"`。

将 `UpdateConfig`（第 17-34 行）整体替换为：

```go
func (h *Handler) UpdateConfig(c echo.Context) error {
	var req ConfigUpdateRequest
	if err := c.Bind(&req); err != nil {
		return respondError(c, http.StatusBadRequest, "invalid request body", err)
	}

	// Roots must be absolute so they don't resolve against the server CWD.
	// Existence is NOT required (an external drive may be unmounted).
	for _, r := range req.Roots {
		if !filepath.IsAbs(r) {
			return respondError(c, http.StatusBadRequest, "scan roots must be absolute paths")
		}
	}

	h.cfg.Scan.Roots = req.Roots
	// Roots changed: drop any cached auto-detected drive list so subsequent
	// GetRoots calls reflect the new configuration immediately.
	h.cfg.Scan.InvalidateRootsCache()
	if err := h.cfg.Save("config.yaml"); err != nil {
		return respondInternalError(c, err)
	}

	h.scanner.InvalidateCache()

	return c.JSON(http.StatusOK, h.cfg.Public())
}
```

- [ ] **Step 5: 运行测试 + 全量构建**

Run: `cd server && go test ./internal/config/ -v && go build ./... && go test ./...`
Expected: PASS；编译通过。

- [ ] **Step 6: 手工验证脱敏 + 原子写**

```bash
cd server && go build -o LocalMediaHub.exe ./cmd/server && ./LocalMediaHub.exe --headless
# 另一终端：
curl -s "http://localhost:8000/api/v1/admin/config" | grep -E 'ffmpeg_path|enable_delete'
# Expected: 无输出（已脱敏）。
# 触发一次配置写（PUT 绝对路径 roots），观察 config.yaml 更新且无 .config-*.yaml.tmp 残留。
```

- [ ] **Step 7: 提交**

```bash
git add server/internal/config/config.go server/internal/config/config_test.go server/internal/server/handler/admin.go
git commit -m "feat(server): write config.yaml atomically and validate absolute roots

Save now writes to a same-dir temp file and renames, so a crash mid-write cannot
corrupt config.yaml. UpdateConfig rejects relative scan roots (which would
otherwise resolve against the server CWD).

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Self-Review（作者已执行）

**1. Spec 覆盖**：
- §3 符号链接/UNC 绕过 → Task 2（原语+测试）+ Task 3（系统侧接线）+ Task 4（媒体侧接线）。✅
- §4 黑名单合并 + 段匹配 → Task 1。✅
- §5 校验函数整合（SystemBrowse 单步、删除入 service、移除 ValidateSystemPath）→ Task 3。✅
- §6 HTTP 超时 + 优雅关停（含 preGenCancel）→ Task 5。✅
- §7 config 脱敏 → Task 6。✅
- §8 原子化写 + roots 绝对路径校验 → Task 7。✅
- §9 测试 → Task 1/2/3/4（path_test.go）、Task 5（server_test.go）、Task 6/7（config_test.go）。✅
- §11 决策点（黑名单并集、脱敏范围、WriteTimeout=0、关停 15s、roots 仅绝对路径）→ 分别在 Task 1/6/5/7 落地。✅

**2. 占位符扫描**：无 TBD/TODO；每个代码步骤含完整代码；每条命令含期望输出；手工验证含具体 curl 与期望状态码。✅

**3. 类型/签名一致性**：
- `containsBlockedSegment(absPath string) error`（未导出）—— Task 1 定义，Task 2 `ResolveWithinRoots` 调用。✅
- `ResolveWithinRoots(pathStr, roots) (string, error)` —— Task 2 定义，Task 3（`ValidateSystemMediaAccess`/`ValidateSystemBrowse`/`ValidateDeletion`）、Task 4（`ValidateAccessibleMediaPath`）调用。✅
- `resolveWithin(pathStr, roots) (string, error)`（未导出）—— Task 2 定义，Task 4 `ValidateAccessibleMediaPath` scan 分支调用。✅
- `ValidateSystemMediaAccess → (string, error)`、`ValidateSystemBrowse → (string, error)`、`ValidateDeletion → (string, error)` —— Task 3 定义，system.go 调用。✅
- `ValidateAccessibleMediaPath → (string, error)` —— Task 3 不改它（仍 `error`）、Task 4 改为 `(string,error)` 并被 media.go/images.go/videos.go 调用；签名前后一致。✅
- `Config.Public() ConfigPublic` —— Task 6 定义，admin.go 调用，config_test.go 测试。✅
- 删除清单一致：Task 3 删 `ValidateSystemPath`/`ValidateSystemBrowseAllowed`/`ValidateSystemBrowsePath` 与 handler `isAllowedToDelete`，并已更新 `path_test.go` 中对 `ValidateSystemBrowseAllowed` 的引用。✅
- import 增补一致：server.go +`time`；config.go +`path/filepath`；admin.go +`path/filepath`；system.go 保留 `fmt`（`DeletePath` 仍用）。✅

**4. 已知范围外（备忘，不在本计划）**：`DownloadFolderZip`（folders.go）仍用词法 `IsPathWithinRoots`，存在同类符号链接面；`ScanConfig.GetRoots` 的 `sync.Once` 重置竞态；`streaming.go` Range 测试——均留待后续轮次（见 spec §12）。
