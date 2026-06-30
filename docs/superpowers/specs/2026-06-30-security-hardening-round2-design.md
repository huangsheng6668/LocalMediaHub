# 安全加固设计（Security Hardening · Round 2）

- **日期**: 2026-06-30
- **范围**: Go 服务端（本轮聚焦服务端；Android Zip Slip 与 Web XSS 已在第一轮处理）
- **策略**: C — 全面防御
- **状态**: 待评审
- **前置**: [2026-06-29 安全加固第一轮](./2026-06-29-security-hardening-design.md)。第一轮已用 `ValidateSystemMediaAccess`（`server/internal/service/path.go:108`）为系统媒体端点加上 `allowed_roots` 边界，但其校验是**词法**的；本轮封堵它留下的符号链接/UNC 绕过，并补齐相邻的稳健性与纵深防御项。

---

## 1. 背景与动机

第一轮为 `/api/v1/system/*` 与 `/api/v1/media/*` 建立了"必须在配置根内"的词法边界。但一次对当前源码的复核发现：**词法校验挡不住符号链接/目录联接（junction）**——而 `os.Stat`、`http.ServeFile`/`c.File` 都会跟随链接。结果是：允许根里只要存在一个指向 `allowed_roots` 之外的 junction，词法判定"在根内"、实际却服务了根外文件，**第一轮刚建起来的边界被绕过**。

此外复核还发现几个与安全/稳健性相邻、但第一轮未覆盖的缺口：HTTP 服务零超时（Slowloris 类占用）、`Stop()` 硬关会掐断在途下载、`GET /admin/config` 回传本地 `ffmpeg_path` 等侦察信息、`config.yaml` 非原子写、两份**已分叉**的黑名单。

本轮（范围 C）一次性收口这些服务端项。所有结论已带 `文件:行号` 核实。

---

## 2. 目标与非目标

### 目标
1. **封堵符号链接/UNC 路径绕过**：路径校验改为"解析后重新校验"，并让 validator **返回解析后的真实路径**，handler 据此打开/服务（消除"校验用词法路径、服务跟随链接"的 TOCTOU）。
2. **合并两份黑名单**为一份权威清单，改按**路径段**精确匹配，删除 `system.go` 的重复副本。
3. **整合校验函数**：`SystemBrowse` 的"两步校验"折为单步；删除路径校验从 handler 收进 `service`；移除第一轮遗留的 `ValidateSystemPath`。
4. **HTTP 超时 + 优雅关停**：自建 `http.Server` 设超时；`Stop()` 改 `Shutdown(ctx)` 排空在途请求。
5. **config 脱敏**：`GET /admin/config` 用 DTO 回传，去掉 `ffmpeg_path`/`enable_delete`。
6. **原子化 config 写**：`Save` 改"临时文件 + rename"。
7. **测试**：针对绕过/黑名单/原子写的表驱动测试。

### 非目标（留待后续轮次）
- 鉴权 / Token / TLS / mDNS 配对（维持现有"自用可信 LAN + CORS 局域网白名单"模型）。
- 性能项（缩略图同步生成、扫描缓存、Android 大图 OOM、Web 冗余请求）。
- 并发竞态（`ScanConfig.GetRoots` 的 `sync.Once` 重置竞态——见第一轮 §9；与本轮的原子 `Save` 无关，不做）。
- `streaming.go` Range 测试（第一轮 §9 列入后续；本轮测试聚焦路径/黑名单/配置）。

---

## 3. 漏洞：符号链接 / UNC 路径绕过（核心）

### 3.1 根因

`server/internal/service/path.go`：

- `NormalizePath`（`:21-32`）：`filepath.Abs(filepath.Clean(filepath.FromSlash(pathStr)))`，**纯词法，无 `filepath.EvalSymlinks`**。
- `IsPathWithinRoots`（`:35-61`）：用词法 `filepath.Rel` 比对，不解析链接。
- `checkBlocked`（`:186-195`）：词法子串匹配。
- 第一轮新增的 `ValidateSystemMediaAccess`（`:108-120`）/`ValidateAccessibleMediaPath`（`:157-183`）均建立其上，因此**都不解析链接**。

`server/internal/server/handler/system.go` 与 `media.go`：先校验，再对**原始 `pathStr`** 操作——`SystemOriginal` `c.File(pathStr)`（`system.go:161`）、`SystemStream` `ServeFile(pathStr)`（`:174`）、`SystemBrowse` `os.Stat/os.ReadDir(pathStr)`（`:60,:72`）、`SystemThumbnail` `GenerateSystemThumbnail(pathStr)`（`:140`，读链接目标生成缩略图）。`os.Stat`、`http.ServeFile`、`c.File`、`os.ReadDir` 全部**跟随符号链接/junction**。

**后果**：设 `system.allowed_roots: ["D:/Media"]`，`D:/Media/secret` 是一个指向 `C:/Users/admin` 的 junction。请求 `/api/v1/system/original?path=D:/Media/secret/private.jpg`：
- 词法校验：`D:/Media/secret/private.jpg` 在 `D:/Media` 内 → 通过。
- `c.File` 跟随 junction → 实际读 `C:/Users/admin/private.jpg`（根外）。
- 若扩展名属媒体类型、且不在黑名单目录 → **成功越界读取**。

`/system/thumbnail`、`/system/stream`、`/system/browse`、`/media/*` 同理（缩略图会把根外文件生成缩略图；浏览会列出 junction 指向目录的内容，且其子项可继续点入）。这直接绕过了第一轮建立的 `allowed_roots` 边界。

### 3.2 修复方案

**新增解析原语** `service.ResolveWithinRoots`：

```go
// ResolveWithinRoots 规范化输入 → EvalSymlinks 解析请求路径与各根 → 拒绝 UNC →
// 要求"解析后的真实路径"仍在"同样解析过的某个根"内 → 检查黑名单段。
// 返回解析后的真实路径，供 handler 实际打开/服务（不再用原始 pathStr）。
func ResolveWithinRoots(pathStr string, roots []string) (resolved string, err error)
```

要点：
1. 词法清洗：`filepath.Abs(filepath.Clean(...))` 作为输入形式。`..` 穿越由清洗后的包含判断（下条第 3 点）兜底——清洗会先词法消解 `..`，逃出根者必被包含判断拒绝，无需另设原始 `..` 黑名单（避免误伤 `D:\Media\..\Media\file` 这类冗余但合法的输入）。
2. **拒绝 UNC**：原始输入以 `\\` 开头（含 `\\?\`、`\\.\`、`\\server\share`）一律拒绝（防御性，pre-clean；`Clean`/`Abs` 对 UNC 处理不一致，显式拒绝更稳）。
3. `filepath.EvalSymlinks` **同时作用于请求路径与每个根**，对**解析后的形式**做包含判断 → junction/symlink 指向根外即拒。
   - `EvalSymlinks` 要求路径存在；本组端点本就要求文件/目录存在（`validateMediaFilePath`/`SystemBrowse` 都会 `os.Stat`），存在性天然满足；不存在则 `EvalSymlinks` 报错 → 映射为 403/404。
4. 黑名单段检查（见 §4）作用于**解析后路径**。

**各安全 validator 改用 `ResolveWithinRoots`，签名由 `error` 改为 `(resolved string, error)`**：

| 函数（`path.go`） | 改动 |
|---|---|
| `ValidateSystemMediaAccess(pathStr, roots, exts)` | 调 `ResolveWithinRoots` → `validateMediaFilePath(resolved, exts)`；返回 `resolved` |
| `ValidateAccessibleMediaPath(pathStr, scanRoots, sysRoots, exts)` | 先对 scanRoots 解析+校验（媒体文件），否则对 sysRoots 解析+校验（含黑名单）；返回 `resolved` |
| `ValidateSystemBrowse(pathStr, roots)`（新，合并 `ValidateSystemBrowseAllowed`+`ValidateSystemBrowsePath`） | 调 `ResolveWithinRoots`；返回 `resolved`（目录） |
| `ValidateDeletion(pathStr, allRoots)`（新，从 handler `isAllowedToDelete` 迁入） | 调 `ResolveWithinRoots` + "不能删根本身"；返回 `resolved` |

**改 handler**：`SystemThumbnail`/`SystemOriginal`/`SystemStream`/`SystemBrowse`/`MediaThumbnail`/`MediaOriginal`/`MediaStream`/`MediaDuration`/`DeletePath` 均改为 `resolved, err := service.ValidateX(...)`，**后续打开/服务全部用 `resolved`**（如 `c.File(resolved)`、`os.ReadDir(resolved)`、`ServeFile(resolved)`、`GenerateSystemThumbnail(resolved)`）。

**`IsPathWithinRoots` 保持词法不变**：它被搜索的 scoped 过滤当作**显示过滤**用（被过滤者皆为已扫描媒体，非安全边界）；改为 `EvalSymlinks` 会拖慢热路径。新增的 `ResolveWithinRoots` 才是安全边界。

**行为变化（已纳入决策）**：媒体目录里若有指向 `allowed_roots` 之外的 junction/符号链接，加固后该条目变 403——把目标加进 `allowed_roots` 即可恢复。

### 3.3 完成第一轮遗留清理
- 移除 `ValidateSystemPath`（第一轮 §3.2 显式留待移除）。
- `ValidateSystemBrowseAllowed`/`ValidateSystemBrowsePath` 折进 `ValidateSystemBrowse`（如无其它调用方则删除；保留则改为薄封装）。

---

## 4. 黑名单合并 + 段匹配

### 4.1 根因
- `path.go:11-18` `blockedPaths`：6 项（windows/winnt/system32/syswow64/$recycle.bin/system volume information）。
- `system.go:195-198` `isAllowedToDelete` 内联副本：10 项（多 program files/program files (x86)/users/boot）。**两份已分叉**。
- `checkBlocked`（`path.go:190`）用 `strings.Contains(lowerPath, sep+blocked)`：`sep+blocked`（无尾分隔符）会匹配"任意段的结尾"，误伤如 `MyBootleg\windows-screenshots`；且末段命中靠第二条子句，边界脆弱。

### 4.2 修复方案
- `service` 一份权威 `blockedSegments`（并集，全小写）：`windows, winnt, system32, syswow64, $recycle.bin, system volume information, program files, program files (x86), boot`。**注意：不包含 `users`**——Windows 下绝大多数真实媒体位于 `C:\Users\<用户>\(Pictures|Videos|Downloads)`、`t.TempDir()` 位于 `C:\Users\<用户>\AppData\Local\Temp`，若拦截 `users` 段会误杀合法用户媒体并破坏所有临时目录测试。（round-1 的删除黑名单含 `users`，本轮统一时**主动剔除**该条。）
- 新 `containsBlockedSegment(resolvedPath) error`：把**解析后**路径按 `filepath.Separator` 切段、逐段小写、在集合中**整段**匹配。
  - `\windows\system32\foo` → 命中（段 `windows`、`system32`）。
  - `D:\Media\MyBootleg\windows-screenshots` → **不**命中（`windows-screenshots` 是单独一段）。
- 全部 `checkBlocked` 调用点与 `isAllowedToDelete` 的内联副本改用它；删除 `system.go` 重复清单。
- 决策（见 §11）：并集对**浏览**也生效——浏览本就限在 `allowed_roots` 内，属纯纵深防御。

---

## 5. 校验函数整合

- 新增内部 `resolveAndAuthorize(pathStr, roots) (string, error)`：`ResolveWithinRoots` 的"解析 + 根校验 + 黑名单"核心（不含扩展名/文件类型判断），供各 public validator 复用。
- public validator（§3.2 表）在 `resolveAndAuthorize` 之上叠加各自检查：
  - 媒体访问：扩展名 + 必须是文件。
  - 浏览：必须是目录。
  - 删除：不能是根本身。
- `SystemBrowse`（`system.go:53-58`）改为**单次**调用 `ValidateSystemBrowse`，消除"两步调用、顺序一改边界失效"。
- `DeletePath`（`system.go:241`）的 `h.isAllowedToDelete` 改调 `service.ValidateDeletion`（逻辑迁出 handler）。

---

## 6. HTTP 超时 + 优雅关停

### 6.1 根因
- `server/internal/server/server.go:164-167` `Start()` 直接 `s.Echo.Start(addr)`：未构造 `http.Server`，`ReadHeader/Read/Write/IdleTimeout` 全为 0 → Slowloris 类慢连接长期占用连接与 goroutine。
- `server.go:169-174` `Stop()` 用 `s.Echo.Close()`（硬关）；`Scanner.Shutdown()` 已取消后台扫描，但**在途的视频流/zip 下载被直接掐断**（zip 可能写到一半损坏），且 `preGenCancel`（缩略图预生成取消函数，`server.go:67-72`）**未在 Stop 中调用**。

### 6.2 修复方案
- `Server` struct 增 `httpServer *http.Server`（`Handler: s.Echo`）。`Start()` 用 `s.httpServer.ListenAndServe()`，并设：
  - `ReadHeaderTimeout: 10s`（防 Slowloris 慢请求头）
  - `ReadTimeout: 30s`（覆盖 POST 体，如配置更新的小 JSON；GET 流式请求无 body，不受影响）
  - `IdleTimeout: 120s`
  - **`WriteTimeout: 0`**（长视频流/zip 下载可达数分钟~小时，全局写超时会掐断正常流；Slowloris 由 `ReadHeaderTimeout` 覆盖）
- `Stop()` 改：
  1. `s.Scanner.Shutdown()`（取消后台扫描，保留）
  2. `s.preGenCancel()`（取消缩略图预生成，**新增**）
  3. `ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)`；`defer cancel()`；`return s.httpServer.Shutdown(ctx)`（排空在途请求，主要为避免 zip 下载写一半损坏）

决策（见 §11）：关停期限 15s。主动退出时若有人正在观看长视频会被掐断——可接受（操作者主动发起退出）。

---

## 7. config 脱敏

### 7.1 根因
`server/internal/server/handler/admin.go:13-15` `GetConfig` 直接 `c.JSON(http.StatusOK, h.cfg)`，原样回传 `System.AllowedRoots / EnableDelete / FFmpegPath`（`config.go:69-73`）。`ffmpeg_path`（本地二进制绝对路径）是纯侦察价值；`enable_delete` 暴露删除开关状态。

### 7.2 修复方案
- 新增 `ConfigResponse` DTO（或 `PublicConfig` 视图），`GetConfig` 返回该 DTO：
  - **去掉** `system.ffmpeg_path`、`system.enable_delete`。
  - **保留** `system.allowed_roots`：已有 `/api/v1/system/drives` 专门返回它、Web 设置页可能展示，且其本身非密。
- 其余字段（`server`/`scan`/`thumbnail`）原样。
- `UpdateConfig` 返回值同样改用脱敏 DTO（当前 `admin.go:33` 返回 `h.cfg`，一并改）。

---

## 8. 原子化 config 写

### 8.1 根因
`server/internal/config/config.go:99-105` `Save` 直接 `os.WriteFile(path, data, 0644)` 覆盖。崩溃/断电中途可能写出半截文件 → 下次启动加载损坏配置。

### 8.2 修复方案
`Save` 改为同目录临时文件 + 原子 rename：

```go
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
    defer os.Remove(tmpName) // rename 成功后为 no-op
    if _, err := tmp.Write(data); err != nil { tmp.Close(); return err }
    if err := tmp.Sync(); err != nil { tmp.Close(); return err }
    if err := tmp.Close(); err != nil { return err }
    return os.Rename(tmpName, path) // Windows: Go 用 MoveFileEx(REPLACE_EXISTING)，原子
}
```

另在 `UpdateConfig`（`admin.go:17`）增加轻量校验：`req.Roots` 各项必须为绝对路径（拒绝相对路径——否则会相对 CWD 解析）；**不**校验存在性（外置盘可能未挂载，校验存在会误拒）。

---

## 9. 测试

### 9.1 `server/internal/service/path_test.go`（扩展现有 9 个用例）
- **符号链接逃逸**：根内建符号链接指向根外目录，断言 `ValidateSystemMediaAccess`/`ValidateAccessibleMediaPath` 拒绝，且 `ResolveWithinRoots` 返回根内解析路径（用 `os.Symlink`；创建失败则 `t.Skip` 以兼容无权限环境，含 Windows junction 用例按平台守护）。
- **UNC / `..` 输入**：`\\server\share\x`、`\\?\C:\...`、含 `..` 的输入 → 拒绝。
- **黑名单段匹配**：解析后含真段 `\windows`、`\program files (x86)` → 命中；`myapp\windows-screenshots` → **不**误伤。
- **返回值即真实路径**：`ResolveWithinRoots` 对合法路径返回清洗后的真实绝对路径（保证 handler 不再服务原始串）。
- **删除校验**：删根本身 → 拒绝；根内子项 → 放行（返回解析路径）。

### 9.2 `server/internal/config/config_test.go`（新增）
- `Save` 产出合法 YAML（回读比对）。
- `Save` 后无 `.config-*.yaml.tmp` 残留临时文件。
- （可选）`UpdateConfig` 相对路径 roots 被拒。

---

## 10. 实现顺序与提交策略

改动集中在服务端，建议按内聚度分次提交、每次 `go test ./...` + 手工回归：

1. **路径解析（§3）+ 黑名单（§4）+ 函数整合（§5）**：`path.go` 新增 `ResolveWithinRoots`/`resolveAndAuthorize`/`containsBlockedSegment`/`ValidateDeletion`，重构各 validator 返回解析路径；改所有相关 handler 用 `resolved`；移除 `ValidateSystemPath`、删 `system.go` 黑名单副本与 `isAllowedToDelete`。→ 补 `path_test.go`。
2. **HTTP 超时 + 优雅关停（§6）**：`server.go` 加 `httpServer` 字段、改 `Start`/`Stop`、补 `preGenCancel`。
3. **config 脱敏（§7）+ 原子写（§8）**：`admin.go` DTO、`config.go` 原子 `Save`、`UpdateConfig` 绝对路径校验。→ 补 `config_test.go`。

不涉及 Android/Web，无需重新构建 APK。

---

## 11. 已决策点

| 决策 | 选择 | 理由 |
|---|---|---|
| 部署/威胁模型 | 仅自用可信 LAN，不加鉴权 | 用户确认；维持现有 CORS 局域网白名单模型 |
| 符号链接策略 | 解析后重新校验（`EvalSymlinks` 路径与根） | 唯一能堵住链接逃逸的方式；指向根外的条目变 403，加根可恢复 |
| validator 返回值 | 返回解析后真实路径 | 消除"校验用词法、服务跟随链接"的 TOCTOU |
| `IsPathWithinRoots` | 保持词法 | 搜索 scoped 过滤为显示用途、非安全边界；改解析会拖慢热路径 |
| 黑名单范围 | 并集（浏览+删除共享同一份），但**剔除 `users`** | 浏览本就限在 `allowed_roots`，并集为纯纵深防御；`users` 会误杀 Windows 用户目录下的媒体故不纳入（见 §4.2） |
| config 脱敏范围 | 去掉 `ffmpeg_path`+`enable_delete`，保留 `allowed_roots` | 前两者纯侦察/状态泄露；后者已有专用端点且 UI 可能展示 |
| HTTP `WriteTimeout` | 0（不设） | 保护长视频流/zip 下载；Slowloris 由 `ReadHeaderTimeout` 覆盖 |
| 优雅关停期限 | 15s | 足够排空 zip 下载；主动退出时长视频可被掐断，可接受 |
| config roots 校验 | 仅绝对路径，不校验存在性 | 防相对路径误解析；外置盘可能未挂载，不误拒 |

---

## 12. 后续轮次（不在本 spec，仅备忘）

- **性能**：缩略图异步生成 + `Cache-Control` 头、扫描器按类型缓存、scoped 搜索去重复 normalize、Android 大图 OOM、Web 仪表盘冗余请求与 stitch scroll 节流。
- **并发竞态**：`ScanConfig.GetRoots` 的 `sync.Once` 重置竞态（第一轮 §9）。
- **测试补强**：`streaming.go` Range（第一轮 §9）、`thumbnail.go`、`media.go`。
- **可维护性**：`BrowseViewModel` 拆分、`app.js` 模块化、`RetrofitClient` 改可注入。
