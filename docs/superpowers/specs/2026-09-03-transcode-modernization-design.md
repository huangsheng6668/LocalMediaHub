# Transcode Modernization Design（硬件编码链 + 会话治理 + 契约固化）

**Date:** 2026-09-03
**Topic:** 2026-09-03 性能评估的 P0 项：把 `serveTranscoded` 固定的 `libx264 -preset ultrafast` 纯软件编码升级为"探测式硬件编码器链 + 兜底"，加转码会话并发上限，并冻结现有 wire 契约。评估中被否决的 QUIC/HTTP/3 讨论见当次会话记录，不在本 spec 范围。

## 1. Background & Motivation

现状证据：

- `server/internal/service/streaming.go` `serveTranscoded`（L175）：非 copy 路径固定 `-vcodec libx264 -preset ultrafast`。1080p 实时软编码占满 2–4 核——而 server 是跑在用户桌面上的常驻进程，同时还要承担扫描 / 缩略图 / 标签库的工作。Windows 目标机几乎必有 NVIDIA/Intel/AMD GPU 之一，硬编（NVENC/QSV/AMF）CPU 占用低一个数量级且同码率画质更好（ultrafast 是 x264 质量最差档）。
- **无转码并发上限**：多设备各开一路转码 = 多个 ffmpeg 并发；缩略图服务已有 `sem` 信号量先例（thumbnail.go），转码这个更贵的资源反而没有治理。
- **seek 契约（两端已实现，需冻结）**：转码输出是 chunked fMP4 管道，`Accept-Ranges: none`；两端客户端 seek 均通过重建 URL `start=<sec>` 让 ffmpeg 输入侧 `-ss` 快速定位重开转码——Android `VideoPlayerScreen.kt`（`buildStreamUrl` + Round 20 注释），Web `videoPlayer.js`（`transcodeStartOffset` 偏移映射，L213–222）。
- Web 端已对非原生格式**自动**转码（videoPlayer.js L85 "Auto transcode non-native formats"），即硬编收益两端通吃；因此本 spec 的硬约束是：**wire protocol 保持不变，Android / Web 零改动即受益**。

## 2. Goals / Non-Goals

**Goals**

1. 编码器自动探测链：`h264_nvenc` → `h264_qsv` → `h264_amf` → `libx264 ultrafast` 兜底（静态声明 + 运行时验证两级探测），进程级缓存。
2. 转码会话并发上限（config 可配，默认 3），排队不拒绝，尊重客户端断开。
3. `vcodec` 参数语义扩展但向后兼容：新增可选显式编码器名；缺省 = auto。
4. 可观测性：每会话 start/end 结构化日志（编码器、时长、字节），活跃计数。

**Non-Goals**（各自另立 spec）

- HLS 分段与转码随机 seek（Phase B）
- Android codec 错误自动 fallback 转码（Phase C，评估 P3）
- 码率阶梯 / ABR / 字幕烧录
- Web 管理界面 dashboard 的转码面板（依赖本 spec 的 status 端点，后置）

## 3. Design

### 3.1 编码器探测（新文件 `server/internal/service/transcode_encoder.go`）

- **惰性探测**：首次收到转码请求时探测（`sync.Once`），不在启动时 fork ffmpeg——headless 启动不应为探测付出子进程成本，无 GPU 机器也零开销。
- **两级探测**（Windows 上"编译进 ffmpeg 的编码器 ≠ 驱动可用"是常态，如 nvenc 在旧驱动上列出但运行即报错）：
  1. 静态：`ffmpeg -hide_banner -encoders` 输出解析，确认编码器存在；
  2. 运行时验证：对每个候选跑一次微型真实编码（固定字面量、无任何用户输入）：
     ```
     ffmpeg -hide_banner -f lavfi -i testsrc=duration=0.5:size=320x240:rate=10 \
       -frames:v 5 -c:v <candidate> -f null -
     ```
     10s 超时，退出码 0 才进入链。
- 优先级默认 `[h264_nvenc, h264_qsv, h264_amf]`，config 可覆盖顺序；`libx264` 永远是最终兜底、不参与探测。
- 探测结果进程级缓存；每级失败打 WARN（含原因摘要），最终选择打 INFO 一条。

### 3.2 参数构建重构（streaming.go）

- 抽出纯函数 `buildTranscodeArgs(srcPath string, startSec float64, vcodecParam string, enc resolvedEncoder) []string` → 可表驱动单测。
- `vcodec` 契约（冻结给 Phase B/C）：
  | 客户端传值 | 行为 |
  |---|---|
  | `copy` | `-vcodec copy`（不变） |
  | 缺省 / 空 / `auto` | 探测链解析出的硬编，或 libx264 ultrafast 兜底 |
  | `h264_nvenc` / `h264_qsv` / `h264_amf` / `libx264` 之一 | 显式指定；若探测不可用 → WARN + 回退 auto（**永不 500**） |
  | 其他任意值 | 忽略 + 回退 auto（与今天"静默当 libx264"姿态一致） |
- **安全硬规则**：客户端值只作为 allowlist 查表键，映射到固定 argv 字面量；任何原始输入不进 argv（与 `sanitizeMediaArg` 的 CWE-78 姿态一致）。
- 每编码器画质参数为**候选初值**，实施 Task 3 时在开发机验证后锁定进表驱动测试：
  - nvenc：`-rc vbr -cq 23`（`-preset p4` 视 ffmpeg 版本接受度）
  - qsv：`-global_quality 23`
  - amf：`-quality balanced`
  - libx264 兜底：`-preset ultrafast`（与现状逐字节一致，无回归面）
- 音频（`-acodec aac`）、容器（`-f mp4 -movflags frag_keyframe+empty_moov pipe:1`）不变。10-bit 源依赖 ffmpeg 自动滤镜协商转换；测试矩阵覆盖，仅在实测失败时才加显式 `-pix_fmt`。

### 3.3 会话并发上限（streaming.go + server 接线）

- `StreamingService` 增加 `transcodeSem chan struct{}`，容量 = `transcode.max_sessions`（默认 3；`0` = 不限制，保留逃生门）。
- `cmd.Start()` 前获取，`cmd.Wait()` 后 defer 释放；**排队获取尊重 `r.Context()`**——排队期间客户端断开则不再 spawn ffmpeg。
- 不引入 429：静默排队（LAN 家庭并发场景几乎不触顶；避免两端客户端新增错误处理分支）。
- 该上限即转码路径的 CPU DoS 兜底，与缩略图的 512MB 磁盘上限、既有 rate limit 互补。

### 3.4 Config（config.go + config.example.yaml）

```yaml
transcode:
  # 顺序即优先级；libx264 始终最终兜底，无需列出。非法名 WARN + 忽略。
  encoder_preference: [h264_nvenc, h264_qsv, h264_amf]
  max_sessions: 3        # 同时转码会话上限；0 = 不限制
```

- 新增 `TranscodeConfig` struct 挂 `Config`；section 缺省 = 上述默认。两个字段均无敏感信息，照常投影进 `ConfigPublic`。

### 3.5 可观测性

- 每会话 slog：start（编码器 / 源文件名 / startSec）、end（字节数 / 时长 / 结束原因：正常 EOF / 客户端断开 / 进程失败）。
- `GET /api/v1/admin/transcode/status`（admin 路由组，token 模式下走 Bearer）：活跃会话数、上限、当前解析出的编码器链。本端点为可选 task，dashboard 面板消费留待后续。

### 3.6 客户端影响

**零改动。** Android（`VideoPlayerScreen.kt`）与 Web（`videoPlayer.js`）继续传 `transcode=true&start=N`（可选 `vcodec=copy`）；Web 的 copy/libx264 切换 UI 不受影响（libx264 留在 allowlist）。

## 4. Security Review

- `vcodec` 走 allowlist 映射，杜绝参数注入进 argv（CWE-78，同 `sanitizeMediaArg` 边界）。
- config 中 `encoder_preference` 的每一项同样对照已知编码器集合校验，typo → WARN + 忽略该条目。
- 探测子进程 argv 全为固定字面量（testsrc），零用户输入。
- 并发上限封顶转码 CPU 消耗面；不改动路径校验三件套与既有 rate limit 挂载点。

## 5. Testing Plan

- **Go 单测**：
  - `buildTranscodeArgs` 表驱动：参数值 × 编码器矩阵（含非法值、注入尝试 `vcodec=h264_nvenc -x` → 查表失败回退 auto）；
  - 探测解析器：罐头 `-encoders` 输出片段；
  - 信号量：N 个假会话占满，第 N+1 个排队、`r.Context()` 取消即释放（httptest 驱动）。
- **集成**：仿 `server_test.go` gzip 测试的 httptest 模式；ffmpeg 缺失时错误契约不变。
- **手工矩阵**（Windows 开发机）：NVENC 可用 / 驱动缺失两态、10-bit HEVC 源、`vcodec=copy` 回归、Android 拖进度条 + Web 转码后 seek 冒烟。
- 服务端改动 → `cd server && go test ./...`；Android/Web 无代码变更，跑 `node --test` 作未触碰区 sanity。

## 6. Rollout / Compatibility

- 不写 `transcode:` section 的存量用户：无 GPU → 行为与今天逐字节一致（libx264 ultrafast）；有 GPU → 自动获得硬编，唯一可感知差异是 CPU 占用下降。
- 纯服务端变更，Android/Web 发版解耦。
- Phase B（HLS）与 Phase C（Android 自动 fallback）另立 spec，本 spec 冻结的 query 契约是其构建基础。

## 7. Task 分解草案（供 plan 文档展开）

1. `config`：`TranscodeConfig` + example + 单测
2. `transcode_encoder.go` 探测模块 + 单测
3. `buildTranscodeArgs` 抽取 + 表驱动测试（含画质字面量在开发机实证后锁定）
4. 会话信号量 + StreamingService 接线 + 单测
5. （可选）`/admin/transcode/status` 端点
6. `docs/INDEX.md` + `AGENTS.md`（streaming.go 条目）更新
