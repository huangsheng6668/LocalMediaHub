# Web 视频播放器增强设计（倍速 / 滚轮 / 进度记忆）

- 日期: 2026-08-08
- 状态: 设计已确认，待写实施计划
- 范围: 仅 Web 端（`server/internal/web`）视频播放器，不涉及 Android、不涉及服务端

## 背景与动机

参考 [xxxily/h5player](https://github.com/xxxily/h5player)（油猴用户脚本，用于增强第三方视频网站的 HTML5 `<video>`）的功能点，在 LocalMediaHub Web 端**原生实现** 3 个高性价比播放增强功能。

**为什么不引入 h5player 本身**：它是注入第三方视频网站（B站/YouTube…）的用户脚本，依赖 Tampermonkey；而 LocalMediaHub 的 Web 端是我们自己托管的页面，拥有 `videoPlayer.js` 的源码控制权，直接改自己的代码即可，引入用户脚本反而多余。h5player 的价值在于**功能灵感**。

**当前 Web 端现状**（`videoPlayer.js`，322 行）已具备：
- 播放/暂停（按钮 + 点击视频 + 空格键）
- 进度 seek（拖动条 + ←/→ 键 ±5s）
- 音量（音量条 + ↑/↓ 键 ±0.05）
- 全屏
- 服务器转码三态切换（原画 / 快速流 remux `vcodec=copy` / 兼容转码 `vcodec=libx264`）
- 控制层 2s 自动隐藏
- 非原生格式（.ts/.mkv/.avi/.wmv/.flv）自动转码

**缺少**：倍速、滚轮调节、跨会话进度记忆。

**进度持久化现状核查**：
- 小说阅读用 `localStorage` + `book_progress:` 前缀（`textReader.js`）
- Android 端视频进度存设备本地（`PlaybackProgressEntry` / `RecentActivityStore` / `filterContinueWatching`），**不上报服务端**
- 服务端 handler 目录**无任何**视频进度/播放历史接口
- Web 端视频进度**完全无持久化**

结论：整个项目是「纯客户端本地存储」哲学，Web 端用 `localStorage` 与现有两套范式一致，零后端改动。

## 范围（本次）

三个功能：
1. 倍速播放
2. 鼠标滚轮调音量
3. 跨会话进度记忆（localStorage）

**明确不做**（留待后续 Phase）：
- 视频截图、画中画 PiP、画面滤镜（第二/三梯队）
- 服务端跨设备进度同步（服务端无接口；Android 为纯本地）

## 架构决策

采用「进度记忆抽模块，倍速 + 滚轮内联」：
- 进度记忆是数据持久化 + 业务判定（是否看完），需解耦可测 → 单独建 `videoProgress.js`
- 倍速/滚轮是控制条 UI 交互，与 `videoPlayer.js` 强耦合 → 内联
- 与现有 `textReader.js` + `progress.js` 的拆分范式一致（编排器管 UI，progress 模块管数据/纯函数）

## 文件改动

| 文件 | 改动 |
|---|---|
| 新建 `server/internal/web/videoProgress.js` | 数据层：纯函数 + localStorage |
| `server/internal/web/videoPlayer.js` | 倍速按钮、滚轮监听、进度读写 UI 逻辑 |
| `server/internal/web/index.html` | 控制条 `controls-right` 加倍速按钮（全屏键左边） |
| `server/internal/web/dom.js` | 注册 `btnVideoSpeed` 元素引用 |

## 组件设计

### `videoProgress.js`（数据层，可单测纯函数）

**key 设计**：`'video_progress:' + relativePath`
- 用 `file.relative_path`（与文件身份绑定，不随服务端目录变化；`openVideoPlayer` 已使用 `file.relative_path`）

**导出函数**：
- `saveProgress(relPath, { positionMs, durationMs })`
  - 写入 `{ positionMs, durationMs, updatedAt: Date.now() }`
  - 内部 `try/catch`：localStorage 不可用/配额满时静默
- `loadProgress(relPath) -> { positionMs, durationMs } | null`
  - 无记录 / JSON.parse 失败 → 返回 `null`
- `clearProgress(relPath)`
- `isCompleted(positionMs, durationMs) -> bool`
  - `durationMs <= 0` → `false`
  - `positionMs / durationMs >= 0.95` → `true`

### `videoPlayer.js` 改动

#### 倍速
- 档位：`[0.75, 1, 1.25, 1.5, 2, 3]`
- 控制条按钮 `id="btn-video-speed"`，初始文字 `1x`，放在 `controls-right` 全屏按钮左边
- 点击循环顺序：`1 → 1.25 → 1.5 → 2 → 3 → 0.75 → 1`
- 同步 `video.playbackRate` 与按钮文字
- `openVideoPlayer` 时重置 `playbackRate = 1`、按钮文字 `1x`
- 纯函数 `nextSpeed(currentRate, speeds)` 便于单测

#### 滚轮
- 在视频 wrapper（`videoPlayer.parentElement`）监听 `wheel`
- `deltaY < 0` → 音量 `+0.05`；`deltaY > 0` → 音量 `-0.05`（与 ↑/↓ 键一致）
- `preventDefault` 防止页面滚动
- 同步 `videoVolume` 条值与 `btnVideoMute` 文字（`🔊`/`🔇`）
- 进度条（`#video-progress` range input）上的滚轮交给浏览器默认行为，不拦截
- 纯函数 `wheelToVolume(currentVolume, deltaY, step=0.05)` 便于单测

#### 进度记忆
- **绝对位置**计算：`state.transcodeStartOffset + videoPlayer.currentTime`（转码流 currentTime 是片段相对值）
- **写入时机**：
  - `timeupdate` 节流每 5s 一次
  - 关闭弹窗（`btnCloseVideoModal`）时 flush
  - `pause` 事件时 flush
- **读取时机**（`openVideoPlayer` 内 `loadProgress` 之后）：
  - 有记录且 `!isCompleted`：
    - 原画流（`useTranscode=false`）：`loadedmetadata` 后 `seekTo(position)`
    - 转码流（`useTranscode=true`）：用 `&start=<position/1000>` 重建 URL（复用现有 `start` 参数机制）
    - toast「已从 XX:XX 继续」
  - `isCompleted` 为真：`clearProgress`，从头播
- **清除时机**：
  - `ended` 事件 → `clearProgress`
  - 读取时判定 `isCompleted` → `clearProgress`

## 数据流

```
openVideoPlayer → loadProgress → 续播 seek / start 参数 | 清除+从头
timeupdate(5s) / close / pause → saveProgress(绝对位置)
ended / isCompleted → clearProgress
```

## 错误处理

- localStorage 不可用 / 配额满 → `saveProgress` 内 `try/catch` 静默，不影响播放
- `loadProgress` JSON 损坏 → 返回 `null`，按无记录处理
- 转码流续播 → 复用现有 `?start=` 重建路径机制，不引入新机制

## 测试（TDD）

- `videoProgress.js` 纯函数单测（localStorage mock 或 jsdom）：
  - `saveProgress` / `loadProgress` 正常读写往返
  - `loadProgress` 无记录返回 `null`、JSON 损坏返回 `null`
  - `isCompleted` 边界：94.9% → `false`、95% → `true`、`durationMs <= 0` → `false`
  - `clearProgress` 删除后 `loadProgress` 返回 `null`
- UI 逻辑抽纯函数单测：
  - `nextSpeed(1, [0.75,1,1.25,1.5,2,3])` → `1.25`；`nextSpeed(0.75, …)` → `1`（循环）
  - `wheelToVolume(0.5, -100)` → `0.55`；上限 1 / 下限 0 钳制
- 沿用 reader 的 TDD 范式（纯函数导出 + 单测）

## 交互默认值（已与用户确认）

1. 倍速档位 `[0.75, 1, 1.25, 1.5, 2, 3]`，点击循环
2. 滚轮调音量（±0.05）
3. 看完（≥95%）静默清除记录、下次从头播
4. 续播时 toast「已从 XX:XX 继续」

## 非目标 / 未来

- Phase 2: 视频截图、画中画 PiP
- Phase 3: 画面滤镜面板（亮度/对比度/饱和度/旋转/镜像）
- 未来: 服务端跨设备进度同步（`videoProgress.js` 已抽成数据层，换实现即可，UI 不动）
