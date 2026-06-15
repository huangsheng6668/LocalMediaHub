# LocalMediaHub 优化计划

> 基于对 Go 服务端、Android 原生、内嵌 Web UI 三大模块的完整代码审查。
> 审查日期:2026-06-15。实施进度持续更新。

---

## 项目全景

| 模块 | 规模 | 技术栈 | 健康度 |
|---|---|---|---|
| **Go 服务端** | ~3,288 行 / 35 个 `.go` 文件 | Echo v4 + systray | 🟢 架构清晰,有几处安全/健壮性短板 |
| **Android** | 6 个超大文件 >500 行 | Compose + Retrofit + ExoPlayer + Hilt | 🟡 功能完整,技术债偏重 |
| **Web UI** | 单体 `app.js` 1299 行 / 54KB | 原生 JS(无构建) | 🟡 可用但难维护 |
| **Wails 脚手架** | `server/frontend/` 仅 15 行 | 空壳 | 🔴 死代码,应删除或启用 |

---

## 进度总览

| 批次 | 项数 | 完成 | 状态 |
|---|---|---|---|
| **P0 安全与稳定性** | 4 | 4 | ✅ 全部完成 |
| **P1 正确性与健壮性** | 8 | 8 | ✅ 全部完成 |
| **P2 可维护性与体验** | 7 | 7 | ✅ 全部完成 |
| **合计** | **19** | **19** | **100%** |

---

## P0 — 安全与稳定性 ✅ 全部完成

### 1. 服务端缺 `Recover` 中间件 ⚠️ 单点崩溃 ✅
**位置:** `server/internal/server/server.go`
**问题:** 只注册了 `Logger()` + `CORS()`,任一 handler panic 会**直接崩溃整个服务**。
**完成:** 在中间件链最前面加了 `echoMw.Recover()`,panic 转 500 不崩溃。
**工作量:** 5 分钟。

### 2. CORS 全开放 + 无任何鉴权 ✅
**位置:** `server/internal/server/middleware/cors.go`、`server.go`
**问题:** `AllowOrigins: ["*"]`,`PUT /admin/config`、`POST /system/delete` 完全开放。
**完成:** 按用户选择"CORS 仅限局域网 IP",改为动态收集本机所有 IPv4(私有网段优先)+ localhost 生成 origin 白名单。外网浏览器访问 Web UI 会被 CORS 拦截。预留 `Authorization` header 供将来加 token 鉴权复用。

### 3. 路径校验三套并存,旧路由较弱 ✅
**位置:** `path.go`、`videos.go`、`images.go`、`folders.go`、`root.go`
**问题:** 旧路由 `/api/v1/videos/*`、`/images/*`、`/folders/*` 不检查扩展名、不拦截 blocked 目录。
**完成:** `videos.go`/`images.go` 的弱校验迁移到 `ValidateAccessibleMediaPath`。删除从未注册的 `root.go`(`Root`/`RootResponse` 死代码)。删除无调用方的 `StreamingService.ValidatePath`、`ThumbnailService.ValidatePath`。

### 4. 错误响应向客户端泄露内部路径 ✅
**位置:** `handler.go` + 7 个 handler
**问题:** 500 响应直接回 `err.Error()`,暴露服务端文件系统结构。
**完成:** 新增 `respondError`/`respondInternalError`/`respondNotFound` helper。5xx 只回通用消息,真实错误写日志。涉及 videos/images/media/system/admin/search/folders/tags。修了 `DownloadFolderZip` 在 WriteHeader 后出错的 B3 bug(log + return nil)。顺带消除分页重复(`paginateBounds`)。

---

## P1 — 正确性与健壮性 ✅ 全部完成

### 5. Android: `BrowseViewModel` 持有 Activity Context 做下载 + Toast ✅
**位置:** `BrowseViewModel.kt`、`MainActivity.kt`、`BrowseScreen.kt`
**问题:** VM 持有 Activity Context 做下载(泄漏窗口);VM 直接发 Toast(违反 MVVM)。
**完成:** 构造增加 `appContext: Context`(Application context)。`downloadFile`/`downloadFolder` 去掉 context 参数。Toast 改 `_toastMessage: MutableStateFlow<String?>`,UI 层 `LaunchedEffect` 消费。清理 7 个无用 import。

### 6. Android: release 构建配置不当 ✅
**位置:** `app/build.gradle.kts`、`proguard-rules.pro`、`.gitignore`
**问题:** `isMinifyEnabled = false`(无混淆/压缩);release 签名 fallback debug.keystore。
**完成:** 按用户选择"完整混淆+压缩"。开启 `isMinifyEnabled = true` + `isShrinkResources = true`。写完整 proguard-rules.pro(Gson/Retrofit/OkHttp/JNI native/Compose/Coil/Media3/DataStore)。签名 fallback 改为打印醒目警告。`.gitignore` 补 keystore.properties。新增 `keystore.properties.example`。
**效果:** debug 23.89MB → release **6.71MB**(-72%)。

### 7. 服务端扫描/搜索不可取消(context 未传播) ✅
**位置:** `scanner.go`、`search.go`、`admin.go`、`server.go`、所有 handler
**问题:** 扫描和搜索无法取消,慢请求持续吃 IO,关服务时遍历不停。
**完成:** `Scan(ctx, roots)` 接收 context,errgroup 绑定。`GetCached(ctx, roots)` 透传。新增 `TriggerScan(roots)`(Scanner 自有后台 context)+ `Shutdown()`。`searchFoldersCtx` 遍历时检查请求 context。所有 handler 的 `GetCached` 改传 `c.Request().Context()`。同步更新 3 个测试文件。

### 8. `GetRoots()` 每请求重复计算 ✅
**位置:** `config.go`、`admin.go`
**问题:** 自动盘符探测(A-Z 26 次 `os.Stat`)每请求多次执行。
**完成:** `ScanConfig` 增加 `autoRoots` + `sync.Once`,探测结果缓存。`InvalidateRootsCache()` 供 `UpdateConfig` 调用。配了 roots 的场景完全不碰缓存。

### 9. 转码流忽略 HTTP Range / Seek ✅
**位置:** `streaming.go`(服务端)、`VideoPlayerScreen.kt`(客户端)
**问题:** 转码流实时生成,不支持字节 Range;ExoPlayer 拖动转码流时 seek 失效。
**完成:**
- 服务端:转码响应声明 `Accept-Ranges: none`(避免 416)。`start` 参数(input seek)已支持。
- 客户端:新增 `buildStreamUrl(baseUrl, transcode, startSec)` helper。手势拖动 seek 在转码模式下用 `start=<秒>` 重建 MediaItem + `seekTo(0)`;转码切换按钮把当前位置作为 `start` 传入。修复 `isTranscodingEnabled` 作用域。

### 10. mDNS 广播虚拟网卡 IP(VMware/WSL/vbox) ✅
**位置:** `server.go`(服务端)、新建 `util/NetUtil.kt`(客户端)
**问题:** VMware/WSL/VirtualBox 网卡让 mDNS 广播和 HTTP 扫描指向错误的 host-only 子网,设备连不上。
**完成:**
- 服务端 `getAllLocalIPs()`:改用 `net.Interfaces()`(能拿接口名),过滤虚拟网卡前缀(vmnet/vboxnet/vEthernet/docker/tap-/tun-/isatap/teredo)。
- 客户端新建 `NetUtil.kt`:同样的虚拟网卡过滤 + 私有 IP 段用标准 octet 解析(修正原来 `172.2`/`172.3` 前缀匹配的脆弱逻辑)。`ConnectionViewModel.getOwnLanIp()` 委托给它。

### 11. Android LAN 扫描端口动态化(去掉硬编码 8000) ✅
**位置:** `ConnectionViewModel.kt`
**问题:** HTTP 扫描硬编码只扫 `8000`,改端口的服务器找不到。
**完成:** 候选端口 `[savedPort, 8000, 8080, 8888, 9000]`(去重,savedPort 优先)。每个主机串行试候选端口,找到即停。抽出 `probeHostPorts` 独立 suspend 函数(规避 Kotlin inline-lambda 不允许 break 的限制)。

### 12. NSD 并发 resolve 崩溃(FAILURE_ALREADY_ACTIVE) ✅
**位置:** `ConnectionViewModel.kt`
**问题:** `onServiceFound` 里无限制并发 `resolveService`,触发 `FAILURE_ALREADY_ACTIVE`,低版本系统会崩。
**完成:** 新增 `resolveQueue: Channel<NsdServiceInfo>`(无限缓冲)+ resolve worker 协程。每个 resolve 用 `CompletableDeferred` 桥接回调,保证回调返回后才处理下一个。`onServiceFound` 改为只入队。`stopNsdDiscovery` 取消 worker + 关闭队列。

---

## P2 — 可维护性与体验 ✅ 全部完成

### 13. 🔴 Android: 全部 UI 文案硬编码(0 处 `stringResource`)✅
**位置:** `strings.xml` + 11 个 Kotlin 文件
**问题:** 几乎所有 UI 文本为中文硬编码,`strings.xml` 只有 `app_name`。无法国际化、Lint 告警。
**完成:**
- `strings.xml`:从 1 条扩展到 **188 条**,按 13 个功能模块分组。
- **11 个 Kotlin 文件**全部替换为 `stringResource()`:
  - MainActivity(2)、HomeScreen(42)、ConnectionScreen(30)、BrowseScreen(40)、DownloadsScreen(全量)
  - VideoPlayerScreen(7,含手势回调的预缓存模式)、BrowseContent(7)、GridContainers(6)、MediaItems(8)、TagComponents(5)
- ViewModel 里的 `showToast` 字符串保留(ViewModel 不能用 stringResource,通过 toastMessage Flow 传给 UI)
- `formatSeekOffset` 的"分/秒"保留(含逻辑的格式化函数,非纯文案)

### 14. Android: 3 个超大文件需拆分 ✅
**位置:** `HomeScreen.kt`、`BrowseScreen.kt`、`BrowseViewModel.kt`、`VideoPlayerScreen.kt`
**完成:**
- 拆分 `HomeScreen.kt` 的子卡片到 `ui/component/home/HomeComponents.kt`
- 拆分 `BrowseScreen.kt` 的组件到 `ui/component/browse/BrowseComponents.kt`
- 将 `BrowseViewModel.kt` 中的下载业务逻辑剥离重构至新类 `DownloadManager.kt`
- 将 `VideoPlayerScreen.kt` 中的手势处理逻辑抽离重构至 `PlayerGestureDetector.kt`

### 15. Android: 引入 Hilt + Repository 单例化 ✅
**位置:** `build.gradle.kts`(根+app)、`LocalMediaHubApplication.kt`、`MainActivity.kt`、3 个 ViewModel、4 个 Store、`MediaRepository.kt`、`ConnectionScreen.kt`、2 个测试
**问题:** 无 DI,`MediaRepository()` 被三个 VM 各 `new` 一遍;手动 Factory 样板。
**完成:**
- Gradle:加 `kotlin-kapt` + `dagger.hilt.android.plugin` 插件、Hilt 2.50 + hilt-navigation-compose 依赖。
- `LocalMediaHubApplication`:`@HiltAndroidApp`。
- `MainActivity`:`@AndroidEntryPoint` + `AppStoresEntryPoint`(EntryPoint 暴露单例 Store 给 Composable 层)+ 删手动 Store 创建和 Factory,改用 `hiltViewModel()`。
- 4 个 Store + `MediaRepository`:`@Inject constructor`(Store 用 `@ApplicationContext`)。
- 3 个 ViewModel:`@HiltViewModel` + `@Inject constructor`,删内部 `= MediaRepository()` 和手动 Factory。
- `ConnectionScreen`:默认参数改 `hiltViewModel()`。
- 测试:补全新构造参数。

### 16. Web UI: 单体 `app.js` + 28 处 `innerHTML` + 17 处散落 `fetch` ✅
**位置:** `server/internal/web/app.js`
**问题:** ① 28 处 `innerHTML` 拼 HTML(XSS 高发区);② 17 处 fetch 无统一封装;③ 无构建步骤。
**完成:**
- 抽离核心状态、路由与显示模块，细化为 `state.js`、`toast.js`、`api.js`、`router.js`。
- 全量重构 `app.js` 中的 `fetch` 统一接入 `apiRequest` 进行请求和网络错误统一处理。
- 升级 DOM 渲染对用户输入与文件名、路径做 `escapeHtml` 字符实体转义防护，消除 XSS 潜在风险。

### 17. 删除/启用 Wails 死代码 ✅
**位置:** `server/frontend/`、`server/wails.json`
**完成:** 完全清除了桌面脚手架 Wails 空壳代码，走轻量化的托盘(systray) + 内置 Web 核心路线。

### 18. 补齐托盘 Icon ✅
**位置:** `server/internal/systray/icon.go`(新建)、`systray.go`
**问题:** 托盘只调 `SetTitle("LMH")`,没调 `SetIcon`,显示空白/默认图标。
**完成:** 新建 `icon.go`,用标准库 `image/png` 在 init 时生成 32x32 蓝底白"M"PNG。`onReady` 调用 `systray.SetIcon(trayIconBytes)`。

### 19. 服务端:无结构化日志 + 测试覆盖盲区 ✅
**完成:** 全局引入 `log/slog` 并添加了 `tags_test.go` 和 `scanner_test.go` 补齐核心服务层测试。

---

## 实施顺序(实际执行)

```
第一周(稳住基本盘) ✅:
  P0 #1 Recover  →  #4 统一错误  →  #2 CORS/鉴权  →  #3 路径校验统一
  P1 #6 Android release 配置  →  #8 缓存 GetRoots

第二周(正确性) ✅:
  P1 #5 下载重构  →  #7 context 传播  →  #9 转码 Range/Seek
  P1 #12 NSD 排队  →  #10 虚拟网卡过滤  →  #11 端口动态化

第三周起(可维护性) ✅:
  P2 #18 托盘 Icon ✅  →  #15 Hilt 迁移 ✅  →  #13 strings.xml ✅
  P2 #14 拆大文件 ✅  →  #16 Web UI ✅  →  #17 Wails 死代码 ✅  →  #19 日志+测试 ✅
```

---

## 待确认的决策点

- [x] **#2 鉴权强度:** ~~只要 CORS 白名单(轻),还是加 token 密码(重)?~~ → **已决策:CORS 仅限局域网 IP**
- [x] **#17 路线:** ~~Wails 桌面应用 vs 当前 systray + 内嵌 Web,保留哪个?(影响是否删 `server/frontend/`)~~ → **已决策: 保留托盘 + 内联 Web 模式**
- [x] **#13 国际化范围:** ~~仅中文 strings.xml,还是预留多语言?~~ → **已决策:仅中文 strings.xml**
- [x] **#15 DI:** ~~是否引入 Hilt?~~ → **已决策:引入 Hilt**
