# 三端安全审计与修复设计（Security Phase 9）

**日期**: 2026-08-17
**范围**: `server/`（Go）/ `server/internal/web/`（SPA）/ `android/`（Kotlin）/ `server/internal/ble/` + `android/.../ble/`（跨端 BLE）
**目标**: 对三端做全面只读安全审计，将发现按优先级整理为可执行的修复设计；本 spec 是审计结论的权威记录，配套实施 plan 见 `docs/superpowers/plans/2026-08-17-security-phase9-tri-end-audit.md`。

---

## 1. 审计方法与范围

2026-08-17 由 4 个并行只读审计代理分别覆盖：Server（Go）、Android（Kotlin）、Web（SPA）、跨端（BLE 协议 / 供应链 / 仓库卫生）。所有发现均带 file:line 证据（已实际读码核对），并对 Phase 1-8 既有加固逐项验证落实情况（结论见 §6）。

**威胁模型**：家庭/个人局域网环境下的 PC server + 手机客户端。攻击者假设为①同一 LAN 的主机（Wi-Fi 内邻居/被入侵设备）、②物理 BLE 邻近范围内的设备、③本地低权限用户（读日志/缓存文件）、④恶意媒体内容本身（恶意文件名、恶意 epub）。**不在模型内**：公网暴露、服务器被完全攻破后的持久化防御。

---

## 2. 高危发现（4 项）

### H-1 BLE 通道零认证，绕过 HTTP Bearer Token

- 帧格式 `[version 1B][len 2B][payload]` 无 HMAC / 加密 / 序列号 / 重放保护（`server/internal/ble/protocol.go:4-8`）；设计文档自认"不加新鉴权（YAGNI）"（`2026-07-26-ble-gatt-wiring-design.md:148-151`），但 token 只保护 HTTP 协调接口，GATT 通道本身裸奔。
- **PC 端**：`central.go:287-312` 对任何来源 `CMD_API_REQ` 无条件执行读盘；Service UUID 仅 8 字符前缀匹配（`central_adapter.go:367-377,383-407`）且 UUID 是公开常量（`protocol.go:69-71`）——物理邻近者广播同前缀 UUID 即可被 PC 连接并读走媒体库（目录、书籍章节）。
- **Android 端**：Command 特征仅 `PERMISSION_WRITE` 无加密无配对校验（`AndroidBlePeripheralManager.kt:74-83`），写/描述符回调不校验 bondState（`:164-201`）；任意 Central 可注入伪造 `CMD_JSON_CHUNK`，重组后直接进 Gson 渲染 UI（内容投毒），且任何 descriptor 写入都会劫持 `subscriberDevice` 单订阅槽位。
- **自动连接放大器**：`BleSettingsViewModel.kt:257` 无匹配时兜底连扫描列表第一个设备。
- 缓解：指令集只读 + roots 白名单防穿越（`api_provider.go:78-145`）；BLE 默认关闭。

### H-2 媒体内容读端点完全无认证（即使配置了 token）

`server/internal/server/server.go:274-292`：`/folders`、`/folders/*`（除 download）、`/videos`、`/videos/*`、`/images`、`/images/*`、`/texts`、`/search` 全部不挂 `authMw`。token 只保护 admin/system/media/books，内容本身对 LAN 匿名开放——可先枚举目录再无凭证流式读取整个媒体库，而 ZIP 下载却要 token，口径自相矛盾。

### H-3 访问日志 token 脱敏实际失效

redact 中间件只改 `req.URL.RawQuery`（`server.go:202-213`），但 Echo v4.15.1 Logger 打印 `req.RequestURI`（请求行原样保留，从不与 `URL` 同步），`?token=` 原文仍完整进入日志。Web 前端确实会为 `<img>` 拼 `?token=`（`server/internal/web/api.js:48-52`）。既有测试 `server_test.go:638-643` 只断言 `URL.RawQuery`，验证了错误的对象。

### H-4 部署开放模式 + 全程明文 HTTP

本地 `server/config.yaml` `token: ""` + 默认 `host: 0.0.0.0`（`config.example.yaml:2-8`）+ 全仓无 TLS 路径（`server.go:414-416` 仅 `ListenAndServe`）：空 token 时 `auth.go:27-29` 直接放行，LAN 内任意主机即可改配置、触发扫描、串流媒体（仅 delete 被空 token 拦截）。即使配 token 也是明文传输，共享 Wi-Fi 下可被嗅探；Android 端 `network_security_config.xml:3` 对所有域名放行 cleartext 同病相怜。

---

## 3. 中危发现（10 项）

| # | 端 | 问题 | 证据 |
|---|---|---|---|
| M-1 | Server | 无任何请求体大小限制（Echo BodyLimit 未注册），开放模式下 `PUT /admin/config` 可被 GB 级 JSON 打内存 | 全仓 grep 无 BodyLimit；`server.go:165-173` |
| M-2 | Server | 认证失败无速率限制，token 可在线爆破（限流只挂业务路径） | `auth.go:24-55`、`server.go:267-360` |
| M-3 | Server | 缩略图无认证 + 无限速 + 磁盘缓存无 LRU 上限（内存 LRU 200 条，磁盘无限） | `server.go:281-286`、`thumbnail.go:319-331` |
| M-4 | Server | `ValidateDeletion` 禁删根比较大小写敏感，Windows 下 `d:\MEDIA` 可删库根 | `path.go:179`（对比 `relPathWithin:79-92` 本身不区分大小写） |
| M-5 | Server | WriteTimeout=0 且无下载并发上限，1B/s 慢读长期占用句柄 | `server.go:171-172`、`folders.go:196-283` |
| M-6 | Server/BLE | BLE browse 走弱化路径校验 `IsPathWithinRoots`，HTTP 端已堵的 junction 穿越在 BLE 端未堵 | `api_provider.go:162-177`（HTTP 端用 `ResolveBrowsePath`，`path.go:336-338`） |
| M-7 | Android | PiP 广播接收器 API<33 走两参 `registerReceiver` 默认导出，任意 app 可控制播放/暂停/快进（不能注入地址）；`PipActionReceiver` 注释声称用了 NOT_EXPORTED 实际未用且是死代码 | `VideoPlayerActivity.kt:81-85`、`pip/PipActionReceiver.kt:10` |
| M-8 | 跨端 | BLE 原始帧 hex 全量写日志，书籍章节明文进日志；扫描日志输出邻近设备地址/名称 | `central_adapter.go:319,96-102` |
| M-9 | 跨端 | BLE 重组缓冲无显式字节上限（理论 uint16 chunks × 235B ≈ 15MB/流）且无会话 ID，可交叉发流反复重置合法传输 | `BleTransportFallback.kt:70-74,172` |
| M-10 | Web | `settings.js` 引用的 `readerPrefs.THEME_LABELS` 不存在，主题网格渲染抛 TypeError；xsscheck 豁免建立在"不存在的冻结常量"前提上 | `settings.js:59-60,81`、`readerPrefs.js` 无此导出 |

## 4. 低危发现（12 项）

| # | 端 | 问题 | 证据 |
|---|---|---|---|
| L-1 | Server | `blockedSegments` 可被 Windows 8.3 短名/尾部点绕过（`PROGRA~1` 不命中黑名单；需 allowed_roots 覆盖系统盘才可达） | `path.go:41-55` |
| L-2 | Server | pprof 开启后对整个 RFC1918 开放无 token，heap 转储可含书籍正文/密钥 | `server.go:251-254` |
| L-3 | Server | `?token=` GET 回退使 token 进代理日志/抓包/DevTools（不进历史与 Referer，已被 no-referrer 缓解） | `auth.go:35-43`、`videoPlayer.js:16-21` |
| L-4 | Server | tags 清理 LIKE 前缀未转义 `%`/`_`（语义误删/漏删，非注入） | `tags.go:401-410` |
| L-5 | Server | BLE 健康自重启可被邻近攻击者触发（连续 Connect 失败 2 次即重启进程，60s 冷却）形成周期性 DoS | `ble_health.go:16-60`、`restart_windows.go:59-88` |
| L-6 | Android | REST 路径段未 URL 编码（查询参数已编码，路径段直接拼） | `MediaRepository.kt:288-381`、`normalizeRoutePath:626-628` |
| L-7 | Android | ZIP 解压无总量上限（zip bomb，目标在私有目录，仅磁盘耗尽） | `DownloadWorker.kt:258-304` |
| L-8 | Android | release 签名 fail-fast 靠 `taskNames` 字符串匹配，GUI 构建可绕过；debug 密钥无条件兜底填充 | `build.gradle.kts:53-80` |
| L-9 | Android | BLE 写回调忽略 `offset`/`preparedWrite`，分片被当独立帧丢弃 | `AndroidBlePeripheralManager.kt:164-183` |
| L-10 | Android | 无 MTU 协商，244B 帧假设依赖对端 247 MTU | `protocol.go:76`、spec 设计明示不做 |
| L-11 | Web | lightbox `src="${url}"` 靠上游 `encodeURIComponent` 兜底而未过 `escapeHtml`（当前不可利用，模式脆弱）；EPUB 内嵌 `data:`/`http(s)://` 图片 URL 原样透传（CSP 已挡外联） | `lightbox.js:56-60`、`book.go:130-136` |
| L-12 | Web | CSP 缺 `base-uri` / `object-src` / `form-action`（纵深防御） | `security_headers.go:37-42` |

---

## 5. 修复设计

### 5.1 P0：小修三连（先行合入）

1. **H-3 redact 修复**：redact 中间件改写 `req.RequestURI = req.URL.Path + "?" + q.Encode()`（与 `URL.RawQuery` 同步），测试断言改为检查 `req.RequestURI`。
2. **M-4 删除根比较**：Windows 下用 `strings.EqualFold(resolved, absRoot)`（仅根比较处，保持 `filepath.Separator` 语义）。
3. **M-10 THEME_LABELS**：`readerPrefs.js` 显式导出 `Object.freeze` 的 `THEME_LABELS`（从 `THEME_PRESETS` 派生 label），`settings.js` 引用不变；补 web 测试。

### 5.2 P1：媒体读端点并入认证（H-2）

将 `/folders`（列表）、`/folders/*`（浏览，除已认证的 download）、`/videos`、`/videos/*`、`/images`、`/images/*`、`/texts`、`/search` 全部挂 `authMw`。

**兼容性论证**：`auth.go:27-29` 空 token 直接放行——未设 token 的现有实例行为完全不变；设了 token 的实例，Android 端 `AuthInterceptor` 注入所有 OkHttp 请求（含 ExoPlayer 流，`VideoPlayerScreen.kt:167-171` 共用同一 client），Web 端 `apiRequest` 统一注入 header，两端均天然兼容。实施时需验证 Android Coil 图片加载链路同样走带 `AuthInterceptor` 的 client（plan 中列为验证步骤）。

### 5.3 P2：资源限制三件套（M-1/M-2/M-3）

1. **BodyLimit**：全局挂 `echoMw.BodyLimit("4M")`（放行最大的合法 body 是批量缩略图请求与 admin config roots，4M 足够）。
2. **认证失败限速**：`BearerToken` 中间件内嵌每 IP 失败退避（复用 `ratelimit.go` 的桶结构：401 响应计数，超阈后固定窗口内直接 429），不动现有业务限流。
3. **缩略图磁盘缓存上限**：`thumbnail.go` 落盘后异步检查目录总大小，超上限（默认 512MB，config `thumbnails.cache_max_mb`）按 mtime LRU 删除最旧文件；`/images/*` 与 `/videos/*/thumbnail` 限速 30/min/IP。
   （修订 2026-08-17：实际落地为 **60/min/IP**（`server.go` 的 `rateLimitWhen(isThumbnailRequest, RateLimit(60, time.Minute))` 与 `/images/*` 的 `RateLimit(60, time.Minute)`）。网格页并发批量加载缩略图时 30/min 会误伤正常客户端；60/min 在保留"钝化文件名枚举洪水"效果的同时给合法 UI 留出余量。）

（M-5 慢速下载并发闸本 phase 不做，理由见 §7。）

### 5.4 P3：BLE 通道认证（H-1 + M-6/M-8/M-9）

1. **帧级 HMAC 挑战-响应**：
   - 握手：连接建立后 Central 发 `CMD_AUTH_CHALLENGE`（8B 随机 nonce），Peripheral 回 `HMAC-SHA256(K, nonce)`，K = `HKDF-SHA256(server.token, "lmh-ble-v1")`。验证失败立即断开，之后非握手帧一律拒绝。
   - 帧 extension：version 升 2，尾附 8B 递增序列号 + 16B 截断 HMAC（覆盖 header+payload+seq），兼拒重放。version 1 帧仅在握手前允许（挑战帧本身）。
   - 两端对称实现：`server/internal/ble/protocol.go` 与 `android/.../ble/BleProtocol.kt`。
2. **Android 特征加固**：Command/State 特征改 `PERMISSION_WRITE_ENCRYPTED`/`PERMISSION_READ_ENCRYPTED`，回调校验 `device.bondState == BOND_BONDED`；descriptor 写仅接受 CCCD（0x2902）；`offset != 0 || preparedWrite` 返回 `GATT_REQUEST_NOT_SUPPORTED`；连接回调 `requestMtu(247)`（顺带解决 L-9/L-10）。
3. **PC 端选路收紧**：UUID 匹配改为完整 128-bit 精确相等；Android `selectBestDevice` 兜底"连第一个"改为"无匹配即失败"（RSSI 最强兜底保留但需 UUID 精确命中）。
4. **M-6**：`api_provider.go` browse 改用 `service.ResolveBrowsePath`（与 HTTP 端同款，含 UNC/reparse 拒绝）。
5. **M-8**：`central_adapter.go:319` 日志去掉 `data=%x` 只留长度与帧类型；扫描日志去掉邻近设备明细。
6. **M-9**：`BleTransportFallback` 重组缓冲加 1MB 字节上限，超限重置流。（修订：原设计的 8B stream id 不再引入——第 1 项 HMAC 认证落地后"交叉发流重置合法传输"的注入向量已消失，字节上限已足够；见 plan Task 9。）

**不做配对绑定（bonding）为强制项**：Windows/winrt 侧配对 UX 复杂且易失败（与既有"Windows 当 Central"的角色反转决策一致），加密特征 + 应用层 HMAC 已覆盖"邻近伪造"威胁；bonding 列为后续可选。

### 5.5 P4：低危清理

L-2（pprof 叠加 token 校验或仅 loopback）、L-4（LIKE `ESCAPE '\'`）、L-5（自重启阈值改可配置 + 指数退避）、L-6（MediaRepository 路径段逐段 `URLEncoder.encode`）、L-7（解压累加上限 2× contentLength）、L-8（签名守卫改 `gradle.taskGraph.whenReady`）、L-11（lightbox 补 `escapeHtml`；EPUB 绝对/data: URL 剥离）、L-12（CSP 追加 `base-uri 'none'; object-src 'none'; form-action 'self'`）、M-7（PiP 接收器统一 `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`，删除死代码 `PipActionReceiver`）。

---

## 6. 已验证落实、无需重做的加固（审计结论）

路径三件套（UNC/reparse 拒绝、TOCTOU 消除）；SQL 全参数化；ffmpeg/ffprobe 无 shell 数组传参；SHA-256 + 常量时间 token 比较（含 query 回退）；XFF 不可伪造的 IP 提取；Web 43 处 innerHTML sink 全部核对无遗漏 + 阅读器 textContent 渲染 + 无 eval/开放重定向；token 仅 sessionStorage；Android Keystore AES-GCM token 加密；无 WebView/deeplink/FileProvider；Zip Slip 双点防护；Rust JNI 无 unwrap 炸弹；libffmpeg SHA-256+SBOM、Cargo.lock、config.yaml/keystore 均 gitignore、无 secret 入库；epub 大小上限与 manifest 逃逸检查；mDNS 不泄露 token/路径。

---

## 7. 明确不修项（记录决策）

| 项 | 理由 |
|---|---|
| LAN 明文 HTTP / 无 TLS | 产品定位家庭内网，自签 TLS 的证书分发 UX 成本高；文档明示共享网络风险即可。列为远期可选项。 |
| M-5 慢速下载并发闸 | 需全局信号量 + 速率监测，复杂度高；P2 的认证+限速落地后攻击面已需先持 token，收益/成本比低。 |
| L-1 8.3 短名解析 | 可达前提苛刻（allowed_roots 覆盖系统盘）；`os.GetFinalPathNameByHandle` 在 Go 里平台分支复杂，列为 backlog。 |
| L-3 `?token=` GET 回退废弃 | Web `<img>`/`<video>` 无法加 header，`?token=` 是必要机制；H-3 修复后日志侧已堵，浏览器历史/Referer 已被 no-referrer 覆盖。书籍图片已有 HMAC sig 可作长期方向。 |
| H-4 部署开放模式 | 属运营配置而非代码缺陷：本 spec 交付物含 README/config.example 引导强 token；`enable_delete: true` + 空 token 拒绝启动作为 P4 项评估。 |

---

## 8. 验收标准

1. `cd server && go test ./...` 全绿（新增：RequestURI redact、EqualFold 删除根、auth 覆盖面、BodyLimit、401 限速、缩略图缓存上限、BLE HMAC 握手/帧校验测试）。
2. `cd server/internal/web && node --test` 全绿 + `cd tools/xsscheck && go run . ../../server/internal/web` 通过。
3. `cd android && ./gradlew testDebugUnitTest assembleDebug` 全绿（新增：BleProtocol v2 帧编解码、重组上限、PiP receiver 注册、路径段编码测试）。
4. 手工回归：设 token 实例上，未带 token 的 `curl /api/v1/folders` 返回 401；Android/Web 正常浏览播放；BLE 开关打开后两端可完成 HMAC 握手并传输，错误密钥被拒。
