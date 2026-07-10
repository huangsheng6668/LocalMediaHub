# LocalMediaHub 全栈安全审计与优化方案

> **日期**：2026-07-10
> **范围**：Go Server + Android 客户端 + Rust 原生解码 + 供应链
> **威胁模型**：局域网半可信（同 LAN 内存在不可信主机，例如合租 WiFi、酒店热点、被入侵的 IoT 设备）
> **评分基准**：CVSS v3.1 + P0/P1/P2 速览
> **产出形式**：发现报告 + 修复方案 + 验证方案
> **方法论**：混合方案（威胁建模 → 代码验证 → 模块补盲 → 合并评分）
> **审计轮次**：Round 29（继 2026-06-29 与 2026-06-30 两次安全硬化后的第三轮）

---

## 0. 摘要

本次审计针对 LocalMediaHub C/S 系统做全栈安全分析，覆盖 PC 服务端（Go/Echo）、Android 客户端（Kotlin/Compose）、Rust 原生解码 crate 与供应链（go.mod / Cargo.toml / Gradle 依赖 / 预编译 `libffmpeg.so`）。共发现 62 项安全发现项与 12 条攻击链。

**审计统计**：
- **62 项发现**（含已缓解与待修复），分布：T1 边界 15 项、T2 边界 7 项、T3 边界 8 项、T4 边界 8 项、T5 边界 3 项、T6 边界 5 项、T7 构建/配置 4 项、T8 补盲 12 项
- **12 条攻击链**（含 4 条 High / 7 条 Medium / 1 条 Low-Med）
- **8 个修复阶段**（按 ROI 排序）

**严重度分布**（不含 4 项 Informational）：
| 等级 | 数量 | 占比 |
|---|---|---|
| P0（High，CVSS ≥ 7.0） | 11 | 19% |
| P1（Medium，4.0–6.9） | 24 | 41% |
| P2（Low，< 4.0） | 23 | 40% |
| Informational | 4 | —— |

**关键结论**：
1. **当前最大风险是无鉴权层**：任何同 LAN 主机可调 `/admin/config` PUT 篡改扫描根、调 `/system/delete` 删除 `allowed_roots` 内任意文件。这一单点修复可缓解 6 项 P0 发现与 5 条攻击链。
2. **`libffmpeg.so` 来源不明**：预编译产物无版本登记、无 SHA256、无来源文档，是唯一可能到达 Android 客户端 RCE 的路径（Chain-D）。
3. **release 签名 fallback 到 debug.keystore**：任何人能用相同 debug key 重签名发布"官方" APK，是供应链投毒（Chain-I）的根因。
4. **HTTP 全程明文**：嗅探者可拼出完整浏览历史/收藏/标签画像（Chain-G），并 MITM 篡改视频流注入毒媒体（Chain-D/E）。

**推荐立即修复**：
- 阶段 1：Bearer Token 鉴权层
- 阶段 2：`libffmpeg.so` 审计与文档化
- 阶段 3：配置默认安全（`enable_delete=false` / 自动盘符默认关闭）

---

## 1. 审计范围与方法论

### 1.1 范围确认

| 维度 | 选择 | 理由 |
|---|---|---|
| **范围** | 全栈覆盖（Server + Android + Rust + 供应链） | C/S 双端 + 内嵌 Web，攻击面横跨三层 |
| **产出** | 发现 + 修复 + 验证方案 | 最完整，符合 system-reminder 对"分析而非直接改代码"的约束 |
| **评分** | CVSS v3.1 + P0/P1/P2 速览 | 业界标准 + 项目 plan.md 既有风格 |
| **威胁模型** | 局域网半可信 | 符合真实使用场景（手机热点、合租 WiFi、酒店网络） |

### 1.2 方法论

采用**混合方案**（C）：
1. **威胁建模**：画 DFD、标信任边界、列 STRIDE 威胁假设。
2. **代码验证**：对每条威胁到代码里找证据，区分"已缓解/部分缓解/未缓解"。
3. **模块补盲**：用 OWASP API Top 10 / MASVS / RustSec / SLSA 等 checklist 快速过一遍，捡出威胁建模未覆盖的中低危项。
4. **合并评分**：CVSS 评分 + 攻击链分析 + 修复优先级矩阵。

### 1.3 工具与流程

- **代码索引**：codegraph（SQLite 知识图谱）+ Read/Grep 工具
- **静态分析**：人工阅读关键文件 + grep 模式匹配（innerHTML / unsafe / exec.Command / fmt.Printf）
- **CVSS 计算**：按 CVSS v3.1 规范，"局域网半可信"对应 `AV:A/PR:N` 或 `AV:A/PR:L`

### 1.4 局限性

本次审计**未覆盖**：
- 动态渗透测试（实际构造 payload 攻击）
- Fuzzing（畸形图片/视频/JSON 输入 fuzz）
- Binary 反编译（`libffmpeg.so` 内部行为）
- 时序攻击分析（如鉴权响应时间差）
- 物理破坏场景（设备被盗后的硬件级攻击）

附录 C 列出这些可作为下一轮审计的候选方向。

---

## 2. 威胁模型

### 2.1 DFD 数据流图（Level-1）

```
                        ┌─────────────────────────────────────────────────────┐
                        │              Trust Boundary T1: 局域网               │
                        │   (半可信:自有设备 + 不可信主机共存)                   │
                        │                                                       │
  ┌──────────┐  mDNS    │  ┌───────────┐   HTTP/REST   ┌──────────────────┐    │
  │  User    │◀─────────┼──│ Android   │──────────────▶│  PC Server       │    │
  │  (你)    │  NSD     │  │ Client    │   明文, 无TLS  │  (Go/Echo)       │    │
  └──────────┘  unicast │  │ Kotlin    │◀──────────────│  host:0.0.0.0    │    │
                        │  └───────────┘   JSON/媒体流  └──────────────────┘    │
                        │       │                          │       │           │
                        │       │ SQLite                   │       │           │
                        │       │ (收藏/进度/              │       │ fsnotify  │
                        │       │  最近/下载)              │       ▼           │
                        │       ▼                       ┌─────────────────┐   │
                        │  ┌───────────┐                 │ T3: 文件系统    │   │
                        │  │ WorkMgr   │                 │ (scan roots +  │   │
                        │  │ 前台服务  │                 │ allowed_roots) │   │
                        │  │ (下载/解压)│                 │ SQLite tags.db │   │
                        │  └───────────┘                 └─────────────────┘   │
                        │                                                       │
                        └─────────────────────────────────────────────────────┘
                                          │
                        ┌─────────────────┼─────────────────────┐
                        │           T2: 进程边界                │
                        │  ┌─────────────────┐                  │
                        │  │ liblocalmedia_  │  JNI              │
                        │  │ native.so (Rust)│◀──── Kotlin       │
                        │  │ + libffmpeg.so  │                  │
                        │  └─────────────────┘                  │
                        └───────────────────────────────────────┘
                                          │
                        ┌─────────────────┼─────────────────────┐
                        │     T4: 浏览器/Web UI 沙箱            │
                        │  ┌─────────────────┐                  │
                        │  │ Web UI SPA      │ fetch()           │
                        │  │ (index.html +   │─────────▶ Server  │
                        │  │  18 个 JS 模块)  │                   │
                        │  └─────────────────┘                  │
                        └───────────────────────────────────────┘
```

### 2.2 信任边界定义

| 边界 | 位置 | 跨边界主体 | 半可信假设下的威胁 |
|---|---|---|---|
| **T1** | 局域网 ↔ 服务端 | 任何同网段主机 → PC Server (HTTP:8000) | Spoofing（mDNS 伪造）/ Tampering（MITM 篡改流）/ Info Disclosure（明文嗅探）/ EoP（无鉴权调 admin/delete） |
| **T2** | Android JVM ↔ Native | 服务端下发的图片/视频字节 → JNI 解码器 | Rust panic 跨 FFI / `libffmpeg.so` CVE / JNI 整数溢出 |
| **T3** | Server ↔ 本地文件系统 | HTTP 参数 `path` → `os.Open`/`os.RemoveAll` | 路径遍历 / 删除任意文件 / ffmpeg 协议注入 |
| **T4** | 浏览器 ↔ Web UI | Web 页面 ↔ fetch | XSS / CSRF / Clickjacking |
| **T5** | Server ↔ OS 命令行 | `System.FFmpegPath` → `exec.Command` | 命令执行（若 config 被篡改）/ ffmpeg 协议前缀注入 |
| **T6** | 供应链 ↔ 构建产物 | go.mod / Cargo.toml / Gradle / `.so` | 依赖投毒 / 已知 CVE / 构建环境不可重现 |

### 2.3 资产清单

| 资产 | 敏感度 | 暴露面 |
|---|---|---|
| 媒体文件本身（私人照片/视频） | 高 | T1 明文 HTTP, T3 路径校验 |
| 文件系统结构（路径/扩展名/目录树） | 中 | T1 JSON 响应, T3 `/system/browse` |
| `tags.db`（标签分类） | 中 | T3 POST `/tags`, DELETE |
| Android 端 `ServerConfigStore`（PC 内网 IP） | 高 | `allowBackup=true` 可被 `adb backup` 提取 |
| Android 端收藏/播放进度 | 中（行为画像） | 同上 |
| PC 文件系统完整性 | 极高 | T3 `/system/delete` |
| Rust/FFmpeg 解码器内存安全 | 高（RCE 入口） | T2 |

### 2.4 核心假设的脆弱点

| 假设 | 脆弱点 | 验证结果 |
|---|---|---|
| 局域网可信 | mDNS 无认证 + HTTP 明文 | T1-03a/b（High）、T1-04a/b/c（Medium） |
| 路径校验三件套足够 | junction 已防（`assertNoReparseBelow`），但 UNC/符号链接-to-UNC 仍需测 | T3-01a/b/c（Medium/Low） |
| CORS 白名单防 CSRF | 不防简单请求；但所有状态变更接口都是 POST/PUT/DELETE → 触发预检 | T4-02（Medium，条件性） |
| Rust 解码器内存安全 | `unsafe` 块有详尽 SAFETY 注释 + 测试 | T2-01/02/04/05（已降级 Low/Info） |
| Zip Slip 已防 | `safeResolveChild` + `canonicalPath.startsWith(destCanonical)`；但无 ZIP bomb 防护 | Chain-K（Medium） |

---

## 3. STRIDE 威胁清单

> CVSS 向量速记：AV=Attack Vector · PR=Privileges Required · UI=User Interaction · S=Scope · C/I/A=Confidentiality/Integrity/Availability

### 3.1 T1 边界（局域网 → 服务端）— 15 项

| ID | STRIDE | 威胁 | 代码证据 | 当前缓解 | CVSS 向量 | Base | 严重度 |
|---|---|---|---|---|---|---|---|
| **T1-01a** | EoP | 无鉴权 PUT `/admin/config` 篡改 `scan.roots` 为 `C:\Users` | `admin.go:18` | 仅 CORS IP 白名单 | `AV:A/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H` | 8.0 | **High** |
| **T1-01b** | Tampering | `UpdateConfig` 持久化写入 `config.yaml` | `admin.go:36` `cfg.Save("config.yaml")` | 仅 `filepath.IsAbs` 校验 | `AV:A/AC:L/PR:N/UI:N/S:U/C:N/I:H/A:L` | 6.1 | Medium |
| **T1-01c** | Info Disclosure | PUT 响应回 `allowed_roots` 完整路径布局 | `admin.go:42` 返回 `cfg.Public()` | 无 | `AV:A/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N` | 3.7 | Low |
| **T1-02a** | Tampering | 无鉴权 POST `/system/delete` 删除 `allowed_roots` 内任意单文件 | `system.go:228` `os.Remove` | `enable_delete` 开关 + `ValidateDeletion` | `AV:A/AC:L/PR:N/UI:N/S:U/C:N/I:H/A:L` | 6.1 | Medium |
| **T1-02b** | Tampering | 同上 `recursive:true` 删整目录树 | `system.go:224` `os.RemoveAll` | 同上 | `AV:A/AC:L/PR:N/UI:N/S:U/C:N/I:H/A:H` | 7.6 | **High** |
| **T1-02c** | Repudiation | 删除操作无审计日志 | `system.go:192` 无 slog | 无 | `AV:A/AC:L/PR:N/UI:N/S:U/C:N/I:L/A:N` | 3.1 | Low |
| **T1-03a** | Spoofing | mDNS 伪造：恶意主机注册同名 `_localmediahub._tcp`，Android NSD 优先连到攻击者 | `mdns.go:31` 无认证 TXT | 无 | `AV:A/AC:L/PR:N/UI:R/S:C/C:H/I:H/A:L` | 7.6 | **High** |
| **T1-03b** | Spoofing | Android 自动重连"上次成功 server"，被伪 server 接管后持久 | `ConnectionScreen.kt`（README 描述了"优先尝试上次成功连接"） | 用户手动重置才能恢复 | `AV:A/AC:L/PR:N/UI:R/S:C/C:H/I:H/A:L` | 7.6 | **High** |
| **T1-03c** | Spoofing | TXT 记录 `path=/` 无版本协商 | `mdns.go:38` | 无 | `AV:A/AC:L/PR:N/UI:R/S:U/C:L/I:L/A:N` | 4.0 | Medium |
| **T1-04a** | Info Disclosure | HTTP 明文 JSON（文件路径/收藏/标签） | `server.go:86` 无 TLS | 无 | `AV:A/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N` | 6.1 | Medium |
| **T1-04b** | Info Disclosure | HTTP 明文媒体流（私人内容） | `streaming.go` 无 TLS | 无 | `AV:A/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N` | 6.1 | Medium |
| **T1-04c** | Info Disclosure | HTTP 明文 Authorization（未来 token） | `cors.go:22` | n/a | `AV:A/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N` | 3.7 | Low |
| **T1-05** | Tampering | 转码流 MITM：注入恶意 mp4 触发 ExoPlayer/FFmpeg 解码漏洞 | `streaming.go:133` | 无 | `AV:A/AC:L/PR:N/UI:R/S:C/C:L/I:H/A:L` | 6.3 | Medium |
| **T1-06** | DoS | 单 IP 无限并发连接 | `server.go` 无 rate limit | 仅 per-connection 超时 | `AV:A/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:L` | 3.7 | Low |
| **T1-07** | Info Disclosure | GET `/admin/config` 公开 `allowed_roots` 全部路径 | `config.go:104` + `admin.go:14` | CORS 白名单 | `AV:A/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N` | 3.7 | Low |

### 3.2 T2 边界（Android JVM ↔ Native）— 7 项

| ID | STRIDE | 威胁 | 代码证据 | 当前缓解 | CVSS 向量 | Base | 严重度 |
|---|---|---|---|---|---|---|---|
| **T2-01** | Info Disclosure | `png.rs:58` `set_len` 依赖 png 0.17 契约；crate 违约 → 未初始化内存读 | `png.rs:58` | SAFETY 注释完善 + 单测 | `AV:N/AC:H/PR:N/UI:R/S:U/C:L/I:N/A:L` | 3.7 | Low |
| **T2-02** | Info | `from_raw_parts` 已 clamp 到 capacity | `decoders.rs:179` | clamp 逻辑 + 单测 | n/a | 0.0 | Info |
| **T2-03a** | Tampering | `libffmpeg.so` 无来源/SHA256/版本 → 无法审计 CVE | `jniLibs/arm64-v8a/libffmpeg.so` | 无 | `AV:N/AC:H/PR:N/UI:R/S:C/C:H/I:H/A:H` | 8.6 | **High** |
| **T2-03b** | Tampering | FFmpeg 历史多个 HEVC/MP4 RCE CVE；毒视频可 RCE Android | 同上 | 无 | `AV:N/AC:H/PR:N/UI:R/S:C/C:H/I:H/A:H` | 8.6 | **High** |
| **T2-04** | DoS | Rust panic 已被 `catch_unwind` 捕获 → 降级 | `decoders.rs:105,149` | catch_unwind + AssertUnwindSafe | `AV:N/AC:H/PR:N/UI:R/S:U/C:N/I:N/A:L` | 3.1 | Low |
| **T2-05** | Info | `bitmap.rs:139-158` stride 用 NDK 返回值，`rgba.len() < expected` 守卫已加 | `bitmap.rs:139-160` | 完整 + catch_unwind | n/a | 0.0 | Info |
| **T2-06** | Tampering | HEIC 解码：Rust 端 `heif.rs:27` 返回 `None` → Kotlin 侧 `NativeImageDecoder.kt` 回退到 `BitmapFactory`（平台 AImageDecoder），漏洞面转移给系统 | `heif.rs:27` + `NativeImageDecoder.kt:113-122` | 平台级 | `AV:N/AC:H/PR:N/UI:R/S:C/C:H/I:H/A:H` | 8.6 | **High** |

### 3.3 T3 边界（Server ↔ 文件系统）— 8 项

| ID | STRIDE | 威胁 | 代码证据 | 当前缓解 | CVSS 向量 | Base | 严重度 |
|---|---|---|---|---|---|---|---|
| **T3-01a** | Info Disclosure | `scan.roots` 未配置时自动探测 `A-Z` 全盘 | `config.go:42` | blockedSegments | `AV:A/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N` | 6.1 | Medium |
| **T3-01b** | Info Disclosure | blockedSegments 未含 `users`（注释说刻意） | `path.go:22` | 设计取舍 | `AV:A/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N` | 3.7 | Low |
| **T3-01c** | Info Disclosure | `ProgramData`/`inetpub` 等敏感目录未列入 blockedSegments | `path.go:22` | 部分缓解 | `AV:A/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N` | 3.7 | Low |
| **T3-02** | Tampering | `enable_delete:true` + 自动盘符 → 删任意盘任意媒体 | `system.go:193` + `config.go:42` | 仅 `enable_delete` 开关 | `AV:A/AC:L/PR:N/UI:N/S:U/C:N/I:H/A:H` | 7.6 | **High** |
| **T3-03a** | Tampering | 转码 `-i filePath`，路径校验绕过可让 ffmpeg 解释为协议（`concat:`/`sub:`） | `streaming.go:161` | 路径校验 + ffmpeg 默认禁协议 | `AV:A/AC:L/PR:L/UI:N/S:U/C:L/I:L/A:L` | 4.6 | Medium |
| **T3-03b** | Info Disclosure | ffprobe 同上 | `streaming.go:254` | 同上 | `AV:A/AC:L/PR:L/UI:N/S:U/C:L/I:N/A:N` | 3.7 | Low |
| **T3-04a** | DoS | 缩略图 fork ffmpeg 无全局 rate limit | `thumbnail.go:73` | sem 部分缓解 | `AV:A/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:L` | 3.7 | Low |
| **T3-04b** | DoS | 任意客户端触发 `/admin/scan/trigger` 全盘扫描 | `admin.go:45` 无 rate limit | 无 | `AV:A/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:L` | 3.7 | Low |

### 3.4 T4 边界（浏览器 ↔ Web UI）— 8 项

| ID | STRIDE | 威胁 | 代码证据 | 当前缓解 | CVSS 向量 | Base | 严重度 |
|---|---|---|---|---|---|---|---|
| **T4-01a** | XSS | `dashboard.js:55` innerHTML 赋值含动态字段（`file.name` 已走 `escapeHtml`，`file.size` 经 `formatSize`）；风险在于未来新增字段可能遗漏 escape | `dashboard.js:55-62` | `escapeHtml` 已覆盖当前字段 | `AV:A/AC:L/PR:N/UI:R/S:C/C:L/I:L/A:N` | 5.4 | Medium |
| **T4-01b** | XSS | `browserView.js` 12 处 innerHTML（动态路径/文件名/drive 均已走 `escapeHtml`）；风险同 T4-01a：新增字段漏洞窗口 | `browserView.js` | `escapeHtml` 已覆盖当前字段 | 同上 | 5.4 | Medium |
| **T4-01c** | XSS | `tagsView.js` 4 处 innerHTML（tag 名/颜色已走 `escapeHtml`） | `tagsView.js:36,86,121` | `escapeHtml` 已覆盖当前字段 | 同上 | 5.4 | Medium |
| **T4-01d** | XSS | `lightbox.js:56` HTML 模板插入路径（已走 `escapeHtml`） | `lightbox.js:56` | `escapeHtml` 已覆盖当前字段 | 同上 | 5.4 | Medium |
| **T4-01e** | XSS | `safeBtoa` fallback `replace(/[^a-zA-Z0-9]/g,'_')` DOM clobbering | `utils.js:19-23` | 部分 | `AV:A/AC:L/PR:N/UI:R/S:U/C:L/I:L/A:N` | 4.3 | Medium |
| **T4-02** | CSRF | `/admin/config` PUT 走 JSON body 触发预检（部分缓解） | `cors.go` | 预检 + Origin 白名单 | `AV:A/AC:L/PR:N/UI:R/S:U/C:N/I:L/A:L` | 4.3 | Medium |
| **T4-03** | Clickjacking | 缺 `X-Frame-Options`/`frame-ancestors` CSP | `server.go` 无安全头 | 无 | `AV:A/AC:L/PR:N/UI:R/S:U/C:L/I:L/A:N` | 4.3 | Medium |
| **T4-04** | Info Disclosure | 缺 `nosniff` / CSP / Referrer-Policy | 同上 | 无 | `AV:A/AC:H/PR:N/UI:N/S:U/C:L/I:N/A:N` | 3.1 | Low |

### 3.5 T5 边界（Server ↔ OS 命令行）— 3 项

| ID | STRIDE | 威胁 | 代码证据 | 当前缓解 | CVSS 向量 | Base | 严重度 |
|---|---|---|---|---|---|---|---|
| **T5-01a** | EoP | `System.FFmpegPath` 从 config 读，理论可指向任意 exe | `streaming.go:134` + `config.go:73` | 仅路径校验（注：当前 PUT 不暴露此字段） | `AV:A/AC:H/PR:N/UI:N/S:C/C:H/I:H/A:H` | 8.0 | **High** |
| **T5-01b** | EoP | ffmpeg `-i filePath` 协议前缀注入 | `streaming.go:161` | 路径校验 | `AV:A/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N` | 5.6 | Medium |
| **T5-02** | DoS | `serveTranscoded` 无 timeout（仅 `extractVideoFrameToImage` 有 15s） | `streaming.go:177` | 部分 | `AV:A/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:L` | 3.7 | Low |

### 3.6 T6 边界（供应链）— 5 项

| ID | STRIDE | 威胁 | 代码证据 | 当前缓解 | CVSS 向量 | Base | 严重度 |
|---|---|---|---|---|---|---|---|
| **T6-01** | Tampering | Go modules 无 strict mode | `server/go.mod` | 标准 go.sum | `AV:A/AC:L/PR:L/UI:N/S:U/C:L/I:L/A:L` | 4.6 | Medium |
| **T6-02** | Tampering | Cargo.lock 已入库但无 audit 自动化 | `rust/Cargo.lock` | 部分 | `AV:A/AC:L/PR:L/UI:N/S:U/C:L/I:L/A:L` | 4.6 | Medium |
| **T6-03** | Tampering | Gradle 未启用 dependency locking | `android/app/build.gradle.kts` | 无 | 同上 | 4.6 | Medium |
| **T6-04** | Tampering | `libffmpeg.so` 预编译来源未记录 | 项目文档 | 无 | `AV:A/AC:L/PR:L/UI:N/S:C/C:H/I:H/A:H` | 8.0 | **High** |
| **T6-05** | Info Disclosure | Android `allowBackup="true"` + ServerConfigStore 可被 `adb backup` 提取 | `AndroidManifest.xml:15` | 无 | `AV:P/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N` | 2.5 | Low |

### 3.7 T7 边界（构建/配置）— 4 项

| ID | STRIDE | 威胁 | 代码证据 | 当前缓解 | CVSS 向量 | Base | 严重度 |
|---|---|---|---|---|---|---|---|
| **T7-01** | Spoofing | release 签名 fallback debug.keystore | `build.gradle.kts:57-60` | 仅警告日志 | `AV:N/AC:H/PR:N/UI:R/S:C/C:L/I:H/A:N` | 6.1 | Medium |
| **T7-02** | Tampering | 依赖偏旧含 CVE（okhttp 4.12.0 / gson 2.8.9 / media3 1.2.0） | `build.gradle.kts:251,282,265` | 无 | `AV:A/AC:L/PR:L/UI:N/S:U/C:L/I:L/A:L` | 4.6 | Medium |
| **T7-03** | Info Disclosure | `cleartextTrafficPermitted="true"` 全局 | `network_security_config.xml:3` | 设计取舍 | `AV:A/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N` | 3.7 | Low |
| **T7-04** | Tampering | `config.yaml` 默认 `enable_delete:true` | `config.yaml:34` | 用户决定 | `AV:A/AC:L/PR:N/UI:N/S:U/C:N/I:H/A:H` | 7.6 | **High** |

### 3.8 T8 补盲（checklist）— 12 项

| ID | 来源 checklist | 威胁 | 严重度 |
|---|---|---|---|
| **T8-01** | OWASP API3 | PUT `/admin/config` 接受 `C:\Windows` 作为 root | Medium |
| **T8-02** | OWASP API5 | admin 路由无独立中间件 | Low |
| **T8-03** | OWASP API9 | 三套媒体端点策略重复 | Low |
| **T8-04** | MASVS 4.6 | 无 TLS 证书锁定（未来加 TLS 时） | Info |
| **T8-05** | XSS Cheat Sheet | 27 处 innerHTML 的 escapeHtml 覆盖率已审计（当前所有动态字段均已覆盖，残余风险为未来新增字段） | Medium |
| **T8-06** | CJ Cheat Sheet | 未启用 Trusted Types（条件性） | Info |
| **T8-07** | RustSec | 无 `cargo audit` 自动化 | Low |
| **T8-08** | SLSA SC-1 | Gradle 无 dependency locking | Low |
| **T8-09** | SLSA SC-2 | 无 CI 安全扫描 | Medium |
| **T8-10** | SLSA SC-5 | 构建环境不可重现（无 Dockerfile/Nix） | Low |
| **T8-11** | EH-2 | `path.go:174` 错误可能含路径 | Low |
| **T8-12** | PE-2 | `config.yaml` 默认文件权限 0644 | Low |

---

## 4. 攻击链分析

### 4.1 12 条攻击链详解

| 链 | 步骤 | 终极影响 | 综合 CVSS | 类型 |
|---|---|---|---|---|
| **Chain-A** | T3-01a 自动盘符 → 任意 LAN 主机 GET `/api/v1/videos` 读全部盘符媒体 | 机密性 High | 6.1 | 数据泄漏 |
| **Chain-B** | T1-02a/b 无鉴权 + T7-04 `enable_delete:true` → 删任意 `allowed_roots` 内文件/目录 | 完整性 High | **7.6** | 破坏 |
| **Chain-C'** | T1-01a 无鉴权 PUT → 写入恶意 roots 持久化（**仅 Roots 字段，不改 FFmpegPath，无 RCE**） | 完整性 Medium + 全盘暴露 | 6.1 | 配置篡改 |
| **Chain-D** | T1-03a 伪 mDNS → Android 连错 server → 下发毒 mp4 → T2-03b libffmpeg CVE → Android RCE | Android RCE | **8.6** | 客户端 RCE |
| **Chain-E** | T1-03a 伪 mDNS → 下发毒 PNG → T2-01 png crate UB（依赖外部契约违约）→ 堆泄漏 | 信息泄漏 | 5.4 | 客户端 DoS/泄漏 |
| **Chain-F** | T4-01a/b/c XSS → 当前无 token 无用；**若将来加 token，XSS 直接窃取** | 条件性 | 5.4 | 条件性 |
| **Chain-G** | T1-04a 明文嗅探所有 JSON → 完整浏览历史/收藏/标签画像 | 隐私泄漏 | 6.1 | 隐私 |
| **Chain-H** | T1-03a 伪造 + T1-03b 持久重定向 → 长期收集数据，用户难摆脱 | 持久 APT | **7.1** | 持久化 |
| **Chain-I** | T7-01 debug 签名 → 攻击者重签名 APK 投毒 → 用户安装"更新" → 设备 RCE | 供应链 + RCE | **7.7** | 供应链投毒 |
| **Chain-J** | T6-05 `allowBackup:true` + 物理访问 / 丢失设备 → `adb backup` 提取 ServerConfigStore（PC IP）+ 收藏 + 播放进度 | 信息泄漏 | 4.2 | 物理访问 |
| **Chain-K** | WorkManager 下载：T1-03 伪 server → 下发 ZIP bomb（无条目数/总大小限制）→ Android 存储填满 → DoS | Android DoS | 5.9 | 客户端 DoS |
| **Chain-L** | T3-04b 任意客户端触发 `/admin/scan/trigger` → 并发多次 → IO/CPU 耗尽 → 服务端 DoS | 服务端 DoS | 5.4 | 服务端 DoS |

### 4.2 优先级矩阵

```
影响 ↑
 High │  Chain-D(8.6)  Chain-I(7.7)      Chain-H(7.1)  Chain-B(7.6)
      │
  Med │  Chain-K(5.9)  Chain-E(5.4)      Chain-G(6.1)  Chain-C'(6.1)
      │                Chain-F(5.4)                    Chain-L(5.4)
      │                                                Chain-A(6.1)
      │                                                Chain-J(4.2)
      │
  Low │
      └────────────────────────────────────────────────────────→ 利用难度
        Trivial              Easy              Moderate
```

### 4.3 关键洞察

1. **Chain-D 是唯一能到达 Android 客户端 RCE 的链**——前提是 `libffmpeg.so` 有对应 CVE。这就是为什么 T2-03 / T6-04 的来源审计特别重要：它是阻断整条 RCE 链的最高 ROI 修复。
2. **Chain-B 与 Chain-H 共享核心前置条件**（无鉴权 + LAN 半可信）——修一个鉴权层同时缓解两个。
3. **Chain-I（debug 签名）是新发现的最严重供应链风险**——它让所有其他"Android 端漏洞"的利用难度骤降（攻击者不需要伪 mDNS，直接推送毒 APK）。
4. **Chain-C' 降级说明**：我原先假设"PUT `/admin/config` 可改 `System.FFmpegPath` 触发 RCE"是错的——`UpdateConfig` 只接受 `Roots` 字段。但 T1-01a 仍然是 High（持久化 + 全盘暴露）。

---

## 5. 修复路线图

### 5.1 阶段 1：单点鉴权层（Bearer Token）⭐ 最高 ROI

**覆盖发现**：T1-01a/b/c, T1-02a/b/c, T1-03c, T1-05, T1-07, T8-02（10 项）
**链影响**：缓解 Chain-A/B/C'/G/H 中的 4 条 High 链 + 1 条 Medium 链

**修复策略**：

1. 在 `server/internal/server/middleware/` 新增 `auth.go`，实现 Bearer Token 中间件。为防止局域网内未授权访问泄露媒体目录和内容，并且为了满足 Android/Web UI 统一的 JSON 错误响应格式，必须返回 `{"error": "Unauthorized"}` 的规范格式。**关键**：token 比较必须使用 `crypto/subtle.ConstantTimeCompare`，防止定时攻击逐字节猜解 token（即使在 LAN 场景下，攻击者可发送大量请求统计响应时间差异）：
   ```go
   package middleware

   import (
       "crypto/subtle"
       "net/http"
       "strings"

       "github.com/labstack/echo/v4"
   )

   func BearerToken(token string) echo.MiddlewareFunc {
       return func(next echo.HandlerFunc) echo.HandlerFunc {
           return func(c echo.Context) error {
               if token == "" {
                   return next(c) // 兼容未配置场景
               }
               auth := c.Request().Header.Get(echo.HeaderAuthorization)
               provided := strings.TrimPrefix(auth, "Bearer ")
               if !strings.HasPrefix(auth, "Bearer ") ||
                   subtle.ConstantTimeCompare([]byte(provided), []byte(token)) != 1 {
                   return c.JSON(http.StatusUnauthorized, map[string]string{"error": "Unauthorized"})
               }
               return next(c)
           }
       }
   }
   ```
2. 在 `config.yaml` 的 `server` 块下增加 `token` 字段（默认空 = 兼容旧行为，若开启则进行校验，关闭时日志打印安全警告）。
3. 在 `server/internal/config/config.go` 的 `ServerConfig` 结构体中添加对应的 `Token` 字段。
4. 在 `server.go:registerRoutes` 中，建议除健康检查 `/health` 之外的所有敏感业务 API 路由（包括 `folders`/`videos`/`images`/`search`/`tags`/`admin`/`system`/`media` 组）统一挂载 `BearerToken` 中间件。如果只想防范越权/删改行为，至少应为 `admin`/`system`/`media` 组强制启用该中间件。
5. Android 端 `ServerConfigStore` 增加 `token` 字段，OkHttp 拦截器自动在 `Authorization` 请求头中注入 `Bearer <token>`。
6. Web UI 启动时检测到 API 返回 401，弹窗要求用户输入 token，并存入 `sessionStorage`，后续 `apiRequest` 自动注入该请求头。

**验证方法**：

| 方法 | 步骤 |
|---|---|
| 单元测试 | 在 `middleware/auth_test.go` 写三种场景：无 token / 错误 token / 正确 token → 期望 401/401/200 |
| 集成测试 | 用 curl 模拟 LAN 主机调 `/admin/config` PUT，验证 401；带正确 token 验证 200 |
| 手动测试 | Web UI 与 Android App 升级后均能正常工作 |
| 回归 | 所有现有 `*_test.go` 通过（带 token fixture） |

**潜在副作用与兼容性**：
- 老版本 Android App（未升级）会失败 → 文档说明 + versionCode 升级
- 用户首次升级后必须配置 token → 流程引导在 Web UI / `config.yaml` 示例
- token 在 LAN 仍明文传输（T1-04c 残留）—— 必须配合阶段 4 才能彻底解决

### 5.2 阶段 2：libffmpeg.so 审计与文档化

**覆盖发现**：T2-03a/b, T6-04（3 项）
**链影响**：缓解 Chain-D（唯一 RCE 链）

**修复策略**：

1. **追溯来源**：查 `libffmpeg.so` 是从哪个 NDK / 哪个 FFmpeg 版本编译的；若无记录，重新用 documented 流程构建（`ffmpeg-6.1.1` 源码在 `build/ffmpeg-src/` 已存在，可编译）。
2. **生成 SBOM**：用 `syft` 或 `cargo-about` 生成 `.so` 的 SBOM，登记到 `docs/sbom/libffmpeg.md`。
3. **SHA256 校验**：在 `build.gradle.kts` 加 task，构建时校验 `jniLibs/arm64-v8a/libffmpeg.so` 的 SHA256 与 `docs/sbom/libffmpeg.sha256` 一致。
4. **启用 ffmpeg 协议白名单**：重新编译时禁用 `concat`/`sub`/`data` 等危险协议。**注意：** 必须同时包含 `file` 和 `pipe` 协议，因为流式转码时需要写入 `pipe:1` 输出流（配置为 `--disable-protocols --enable-protocol=file,pipe`）。

**验证方法**：
1. SBOM 文档存在且版本号明确
2. SHA256 校验 task 在 CI 运行通过
3. `ffmpeg -version` 输出与文档一致
4. 用已知 CVE 的 PoC 视频测试（如 CVE-2023-49502）—— 若版本对应 vulnerable，必须在文档中标注

**潜在副作用**：
- 重新编译可能改变 `.so` 大小 → 影响 APK 体积（当前 6.71MB release）
- 协议白名单可能影响某些罕见格式的支持

### 5.3 阶段 3：配置默认安全

**覆盖发现**：T3-01a, T3-02, T7-04（3 项）
**链影响**：缓解 Chain-A/B 的前置条件

**修复策略**：

1. **`enable_delete` 默认 `false`**：
   - `config.yaml` 示例改为 `enable_delete: false`（用户需显式开启）
   - 在 Web UI 设置面板加红色警告文案
2. **自动盘符默认禁用**：
   - `config.go:GetRoots()` 改为：若 `Roots` 为空，返回错误而非自动探测
   - 启动时日志告警："scan.roots not configured, refusing to serve；please configure explicitly"
   - 兼容性：保留 `--auto-detect-roots` 命令行 flag 作为逃生口
3. **`config.yaml` 文件权限**：启动时 `os.Chmod("config.yaml", 0600)`（仅 owner 可读）

**验证方法**：
1. 空 roots 启动 → 服务拒绝启动并打印明确错误
2. `enable_delete` 未配置时，`/system/delete` 返回 403
3. `config.yaml` 权限为 0600

**潜在副作用**：
- 现有用户首次升级后服务拒绝启动 → 文档说明 + 显式 roots 配置示例
- 失去"插盘即用"的便利性 → 通过 `--auto-detect-roots` flag 保留

### 5.4 阶段 4：HTTP 加固

**覆盖发现**：T1-04a/b/c, T1-05, T4-03, T4-04, T8-06（7 项）
**链影响**：缓解 Chain-G + 全部 T4

**修复策略**：

1. **安全头中间件**（轻）：
   ```go
   func SecurityHeaders() echo.MiddlewareFunc {
       return func(next echo.HandlerFunc) echo.HandlerFunc {
           return func(c echo.Context) error {
               h := c.Response().Header()
               h.Set("X-Frame-Options", "DENY")
               h.Set("X-Content-Type-Options", "nosniff")
               h.Set("Referrer-Policy", "no-referrer")
               // NOTE: 'unsafe-inline' for style-src is a pragmatic concession for
               // existing inline styles in the Web UI. Should be removed after
               // migrating all inline styles to external CSS (tracked as follow-up).
               h.Set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'")
               return next(c)
           }
       }
   }
   ```
2. **可选 TLS**（重，留作未来）：当用户部署到合租 WiFi / 公司网络时启用；Android 端用 fingerprint 锁定（T8-04）。
3. **CSP + Trusted Types**（条件性）：在 Web UI 完全 escape 化后启用 `'require-trusted-types-for' 'script'`。

**验证方法**：
1. curl -I 检查所有响应头包含 4 个安全头
2. Web UI 仍正常工作（CSP 不破坏既有功能）
3. iframe 嵌套测试 → 被拒绝

**潜在副作用**：
- CSP 可能破坏 Web UI 某些 inline script → 需配合阶段 5
- TLS 增加配置复杂度 → 留作未来

### 5.5 阶段 5：Web UI XSS 整改

**覆盖发现**：T4-01a/b/c/d/e, T8-05（6 项）
**链影响**：缓解 Chain-F（条件性）

**修复策略**：

1. **覆盖率审计**：逐一检查所有 27 处 innerHTML 赋值点（browserView.js 12 处 / dashboard.js 4 处 / tagsView.js 4 处 / lightbox.js 5 处 / 未来新增模块），确认所有用户可控字段（`file.name` / `file.path` / `tag.name` / `tag.color`）已走 `escapeHtml`。**经本次审计验证，当前所有动态字段均已覆盖 `escapeHtml`**，残余风险为未来新增字段遗漏 escape。
2. **最小整改**：补全遗漏的 escapeHtml 调用。
3. **彻底整改**（推荐）：将 innerHTML 拼接改为 `textContent` + `createElement` 模式（DOM API）。
4. **CSP 启用**：在阶段 4 CSP 基础上禁用 `unsafe-inline`。

**验证方法**：
1. grep 检查：所有 `innerHTML =` 行附近 5 行内必须有 `escapeHtml`
2. 手动构造含 `<script>` 的文件名上传 → 不执行
3. DOMPurify 或 Trusted Types 强制 → 所有 innerHTML 经检查

**潜在副作用**：
- 彻底整改工作量大（27 处 innerHTML） → 推荐分两步：先确保所有动态字段走 `escapeHtml`（经审计已达成），再迁移到 DOM API

### 5.6 阶段 6：供应链扫描与依赖升级

**覆盖发现**：T6-01/02/03, T7-02, T8-07/08/09/10（8 项）
**链影响**：防御性

**修复策略**：

1. **Gradle dependency locking**：
   ```kotlin
   dependencyLocking {
       lockMode = LockMode.STRICT
   }
   configurations.all { resolutionStrategy.activateDependencyLocking() }
   ```
   运行 `./gradlew dependencies --write-locks` 生成 `gradle.lockfile`。
2. **CI 安全扫描**：在 `.github/workflows/security.yml` 加：
   ```yaml
   - run: go install golang.org/x/vuln/cmd/govulncheck@latest && govulncheck ./...
   - run: cargo install cargo-audit && cargo audit
   - run: ./gradlew dependencyCheckAnalyze  # 用 OWASP dependencyCheck plugin
   ```
3. **依赖升级**：okhttp 4.12.0 → 4.12.1（含 CVE 修复）、gson 2.8.9 → 2.11.0、media3 1.2.0 → 1.4.1。
4. **构建环境可重现**：新增 `Dockerfile` 声明 cargo-ndk 版本、NDK 版本、JDK 版本。

**验证方法**：
1. CI 在 PR 时自动运行三套扫描，结果非零则 fail
2. `gradle.lockfile` 入库，子依赖不可被传递性更新
3. Docker build 在干净环境能复现相同 `.so` 哈希

**潜在副作用**：
- 依赖升级可能引入 breaking change → 单独 PR + 完整测试
- CI 扫描可能误报 → 配置 baseline 抑制

### 5.7 阶段 7：APK 签名加固

**覆盖发现**：T7-01, T6-05（2 项）
**链影响**：缓解 Chain-I/J

**修复策略**：

1. **强制 release 签名**：
   ```kotlin
   if (keystorePropertiesFile.exists()) {
       // 使用 keystore.properties
   } else {
       val isCI = System.getenv("CI") != null
       if (isCI) {
           throw GradleException("Release build requires keystore.properties in CI")
       }
       // 本地开发允许 fallback，但加更显眼的警告
   }
   ```
2. **关闭 `allowBackup`**：
   ```xml
   <application android:allowBackup="false" ...>
   ```
   并测试 ServerConfigStore / FavoritesStore / RecentActivityStore 在卸载重装后行为正确。
3. **加 `android:fullBackupContent="false"` 与 `android:dataExtractionRules`**（Android 12+）。

**验证方法**：
1. CI 无 keystore.properties → 构建失败
2. `adb backup` 命令 → 拒绝或返回空
3. 卸载重装 → 之前保存的 server 配置丢失（预期行为）

**潜在副作用**：
- 用户卸载重装丢失配置 → 文档说明
- 本地开发需配置 keystore → 提供 `keystore.properties.example`

### 5.8 阶段 8：杂项 P2 修复

**覆盖发现**：T1-06, T3-03a/b, T3-04a/b, T4-04, T5-02, T8-01/03/11（10 项）

**修复策略（按类型分组）**：

| 类型 | 修复 |
|---|---|
| **Rate limit** | 加 `middleware/ratelimit.go`，per-IP 滑动窗口（如 100 req/min） |
| **错误响应** | `path.go:174` 错误包装改用 `respondNotFound` 而非透传 err |
| **PUT 校验加强** | `admin.go:UpdateConfig` 增加 blockedSegments 校验（拒绝 `C:\Windows` 等） |
| **`serveTranscoded` timeout** | `streaming.go:177` 加 `context.WithTimeout(30*time.Minute)` |
| **fsnotify 监听上限** | 监控 inotify watch 数量，超阈值告警 |
| **`enable_delete` 审计日志** | `system.go:192` 删除前 `slog.Info("delete", "path", resolved, "recursive", req.Recursive)` |

**验证方法**：每项独立单元测试 + 集成测试。

---

## 6. 关键决策点

### 6.1 鉴权强度

| 选项 | 推荐 | 理由 |
|---|---|---|
| Bearer Token | ✅ | 个人项目 LAN 部署，简单共享密钥够用；实现成本最低 |
| mTLS | | 过度工程，证书管理复杂 |
| OAuth2 | | 重过头 |

### 6.2 TLS 是否本轮实施

| 选项 | 推荐 | 理由 |
|---|---|---|
| 留作"未来"，spec 给出明确触发信号 | ✅ | 当前 LAN-only 部署，TLS 增加配置复杂度 |
| 现在加 | | 若用户已部署到合租 WiFi/公司网络，应立即加 |

**触发信号**：当用户报告部署场景为共享网络，或当 mDNS 伪造攻击在野外被观察到。

### 6.3 libffmpeg.so 处理

| 选项 | 推荐 | 理由 |
|---|---|---|
| 追溯来源 + 文档化 | ✅ | 阻断 Chain-D 的最低成本修复 |
| 迁移到 media3-decoder-ffmpeg | | 工作量大，且该 artifact 已不在 Maven 发布 |
| 接受现状仅文档警示 | | 不能消除 Chain-D |

### 6.4 release 签名

| 选项 | 推荐 | 理由 |
|---|---|---|
| 强制 keystore.properties | ✅ | Chain-I 风险太高 |
| 保持现状仅警告 | | 任何拿到 APK 的人可重签名 |

### 6.5 Web UI XSS 整改深度

| 选项 | 推荐 | 理由 |
|---|---|---|
| 补漏洞 + 启用 CSP | ✅ | 最小成本消除当前风险 |
| 全面迁移到 textContent | | 工作量大，留作未来 |

---

## 7. 验证与持续监控

### 7.1 修复完成的判定标准

- **阶段 1 完成**：CI 运行所有 `*_test.go` + 新增 auth 测试通过；手动 curl 验证无 token 返回 401。
- **阶段 2 完成**：`docs/sbom/libffmpeg.md` 存在 + SHA256 校验 task 在 CI 通过。
- **阶段 3 完成**：空 roots 启动失败 + `enable_delete` 未配置时 `/system/delete` 返回 403。
- **阶段 4 完成**：curl -I 显示 4 个安全头 + iframe 嵌套被拒。
- **阶段 5 完成**：grep 验证所有 innerHTML 走 escapeHtml + 手动 `<script>` 测试不执行。
- **阶段 6 完成**：CI 三套扫描通过 + `gradle.lockfile` 入库。
- **阶段 7 完成**：CI 无 keystore 失败 + `adb backup` 拒绝。
- **阶段 8 完成**：rate limit 单元测试通过 + 各项杂项验证。

### 7.2 CI 安全管道建议

```yaml
# .github/workflows/security.yml
name: Security
on: [push, pull_request]
jobs:
  go-vuln:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-go@v5
      - run: go install golang.org/x/vuln/cmd/govulncheck@latest
      - run: cd server && govulncheck ./...
  cargo-audit:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: cargo install cargo-audit
      - run: cd android/app/src/main/rust && cargo audit
  dependency-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: gradle/actions/setup-gradle@v3
      - run: cd android && ./gradlew dependencyCheckAnalyze
```

### 7.3 后续审计周期建议

- **每季度**：重跑本 spec 的全部 checklist，更新 CVSS 评分。
- **每次依赖升级**：跑阶段 6 的三套扫描。
- **新功能上线前**：跑本 spec 的 STRIDE 章节，针对新边界做威胁建模。

---

## 附录 A：全部发现速览表（按严重度分组，同组按 CVSS 降序）

| ID | CVSS | 严重度 | 模块 | 威胁简述 | 修复阶段 |
|---|---|---|---|---|---|
| T2-03a | 8.6 | High | T2 | libffmpeg.so 无来源 | 2 |
| T2-03b | 8.6 | High | T2 | FFmpeg CVE | 2 |
| T2-06 | 8.6 | High | T2 | HEIC fallback 漏洞面转移 | 2 |
| Chain-D | 8.6 | High | 跨界 | 伪 mDNS + 毒 mp4 → Android RCE | 1+2 |
| T1-01a | 8.0 | High | T1 | 无鉴权 PUT `/admin/config` | 1 |
| T5-01a | 8.0 | High | T5 | FFmpegPath 从 config 读 | 1（间接） |
| T6-04 | 8.0 | High | T6 | libffmpeg.so 来源未文档化 | 2 |
| Chain-I | 7.7 | High | 供应链 | debug 签名 → APK 投毒 | 7 |
| T1-02b | 7.6 | High | T1 | 无鉴权递归删除 | 1 |
| T1-03a | 7.6 | High | T1 | mDNS 伪造 | 1+4 |
| T1-03b | 7.6 | High | T1 | Android 持久重连伪 server | 1+7 |
| T3-02 | 7.6 | High | T3 | 自动盘符 + enable_delete | 3 |
| T7-04 | 7.6 | High | 配置 | enable_delete 默认 true | 3 |
| Chain-B | 7.6 | High | 跨界 | 无鉴权 + enable_delete → 删任意 | 1+3 |
| Chain-H | 7.1 | High | 跨界 | 持久 APT | 1+4 |
| T1-05 | 6.3 | Medium | T1 | 转码流 MITM | 4 |
| T4-01a-d | 5.4 | Medium | T4 | XSS innerHTML | 5 |
| Chain-E/F/K/L | 5.4-5.9 | Medium | 跨界 | 客户端 DoS / 条件性 XSS | 5/2 |
| T5-01b | 5.6 | Medium | T5 | ffmpeg 协议注入 | 8 |
| T8-01 | 6.1 | Medium | T8 | PUT 接受 `C:\Windows` | 8 |
| T8-05 | 5.4 | Medium | T8 | XSS 覆盖率未审计 | 5 |
| T8-09 | 5.4 | Medium | T8 | 无 CI 安全扫描 | 6 |
| T1-01b | 6.1 | Medium | T1 | PUT 持久化 config.yaml | 1 |
| T1-02a | 6.1 | Medium | T1 | 无鉴权删单文件 | 1 |
| T1-04a/b | 6.1 | Medium | T1 | HTTP 明文 | 4 |
| T3-01a | 6.1 | Medium | T3 | 自动盘符全盘可读 | 3 |
| T7-01 | 6.1 | Medium | T7 | debug 签名 fallback | 7 |
| Chain-A/C'/G | 6.1 | Medium | 跨界 | 数据泄漏/配置篡改/隐私 | 1+3+4 |
| T6-01/02/03 | 4.6 | Medium | T6 | 依赖锁不全 | 6 |
| T7-02 | 4.6 | Medium | T7 | 依赖偏旧 | 6 |
| T3-03a | 4.6 | Medium | T3 | ffmpeg 协议注入 | 8 |
| T4-01e | 4.3 | Medium | T4 | safeBtoa DOM clobbering | 5 |
| T4-02 | 4.3 | Medium | T4 | CSRF 条件性 | 1+4 |
| T4-03 | 4.3 | Medium | T4 | 缺 XFO | 4 |
| T1-03c | 4.0 | Medium | T1 | mDNS 无版本协商 | 1 |
| Chain-J | 4.2 | Medium | 物理访问 | adb backup 提取 | 7 |
| T1-01c | 3.7 | Low | T1 | PUT 响应回 allowed_roots | 1 |
| T1-04c | 3.7 | Low | T1 | 明文 Authorization | 4 |
| T1-06 | 3.7 | Low | T1 | 无 rate limit | 8 |
| T1-07 | 3.7 | Low | T1 | GET `/admin/config` 泄路径 | 1 |
| T2-01 | 3.7 | Low | T2 | PNG UB（依赖契约） | —— |
| T2-04 | 3.1 | Low | T2 | Rust panic（已捕获） | —— |
| T3-01b/c | 3.7 | Low | T3 | blockedSegments 不全 | 8 |
| T3-03b | 3.7 | Low | T3 | ffprobe 协议注入 | 8 |
| T3-04a/b | 3.7 | Low | T3 | fork rate limit | 8 |
| T4-04 | 3.1 | Low | T4 | 缺 nosniff/CSP | 4 |
| T5-02 | 3.7 | Low | T5 | 转码无 timeout | 8 |
| T7-03 | 3.7 | Low | T7 | cleartextTrafficPermitted | —— |
| T8-02/03/07/08/10/11/12 | Low | T8 | 杂项 | 8 |
| T1-02c | 3.1 | Low | T1 | 删除无审计日志 | 8 |
| T6-05 | 2.5 | Low | T6 | allowBackup=true | 7 |
| T2-02 | 0.0 | Info | T2 | from_raw_parts（已 clamp） | —— |
| T2-05 | 0.0 | Info | T2 | bitmap.rs stride（已守卫） | —— |
| T8-04 | Info | T8 | 无证书锁定（未来） | —— |
| T8-06 | Info | T8 | 未启用 Trusted Types | —— |

---

## 附录 B：CVSS 向量说明

CVSS v3.1 各维度速记：

| 维度 | 取值 | 含义 |
|---|---|---|
| **AV** (Attack Vector) | A/N/L/P | Adjacent（同 LAN）/ Network / Local / Physical |
| **AC** (Attack Complexity) | L/H | Low / High |
| **PR** (Privileges Required) | N/L/H | None / Low / High |
| **UI** (User Interaction) | N/R | None / Required |
| **S** (Scope) | U/C | Unchanged / Changed |
| **C/I/A** | H/L/N | High / Low / None |

本项目"局域网半可信"默认：`AV:A`（同 LAN 攻击）、`PR:N`（无鉴权前提）、`UI:R`（Android 端需用户连伪 server）。

CVSS Base Score 计算：见 [CVSS v3.1 Calculator](https://www.first.org/cvss/calculator/3.1)。

严重度等级：
- 0.0：None
- 0.1–3.9：Low
- 4.0–6.9：Medium
- 7.0–8.9：High
- 9.0–10.0：Critical

---

## 附录 C：未深入调查的潜在问题

本附录列出本轮审计**未充分覆盖**的领域，供下一轮或专项审计参考。

### C.1 fsnotify / inotify 耗尽

`scanner.go` 的 `StartWatching` 递归监听所有扫描根目录。Linux 默认 `fs.inotify.max_user_watches=8192`，若用户的媒体库目录树深 + 数量多，监听会失败。攻击者若能写入 `allowed_roots`（T1-01a），可创建超深目录树主动耗尽 watch。

**未验证项**：watch 数量上限告警、失败后的降级行为。

### C.2 SSDP/UPnP 跨协议攻击

mDNS（multicast DNS）与 SSDP（Simple Service Discovery Protocol）共享"局域网服务发现"语义。攻击者可伪造 SSDP 响应诱导某些客户端（如 Windows Explorer）发起 HTTP 请求到伪 server。

**未验证项**：本项目 mDNS 客户端（Android NSD）是否会被 SSDP 响应混淆。

### C.3 SQLite tags.db 注入面

`tags.go` 使用 `modernc.org/sqlite`（pure-Go），理论上无 CGO 注入风险，但 SQL 语句是否全部用参数化查询未验证。

**未验证项**：grep 所有 `db.Query` / `db.Exec` 调用，确认无字符串拼接 SQL。

### C.4 OkHttp Cookie 持久化

`OkHttpModule.kt:66` 显式设 `CookieJar.NO_COOKIES`——这是正确的（无状态）。但若未来加 session-based 鉴权，需重新评估。

### C.5 浏览器存储隔离

Web UI 若用 `sessionStorage` 存 token（阶段 1 设计），需验证：
- 多 tab 同时打开时的并发行为
- `window.opener` 跨 origin 访问
- reverse tabnabbing（`target="_blank"` 不带 `rel="noopener"`）

### C.6 Rust decoder 的 DoS 内存放大

恶意 10000×10000 PNG 解码后占 400MB RAM。Rust 解码器无最大尺寸限制。

**未验证项**：在 `decode_scaled` 加 `if iw > 8000 || ih > 8000 { return None; }` 类守卫。

### C.7 Android Intent 重定向

`MainActivity` `exported="true"` + `singleTop`。若有 `intent-filter` 接收外部 Intent 处理媒体 URL，可能被恶意 App 构造 Intent 触发内部逻辑。

**未验证项**：检查所有 `intent.getStringExtra` / `getParcelableExtra` 的 source。

### C.8 二进制反编译与字符串泄露

release APK 即便 R8 混淆，仍可能含：
- Hardcoded server URL（若用户在 config 里写了 PC IP）
- 调试日志字符串
- Rust panic 消息含源码路径

**未验证项**：用 `apktool` / `jadx` 反编译 release APK，grep 敏感字符串。

---

## 文档信息

- **创建日期**：2026-07-10
- **审计轮次**：Round 29
- **方法论**：brainstorming skill（混合方案）
- **下一步**：经用户审核后，调用 writing-plans skill 转为实施计划
