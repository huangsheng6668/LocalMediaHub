# 视频倍速菜单设计（替换点击循环）

- 日期: 2026-08-09
- 状态: 设计已确认，本轮直接实施（轻量流程）
- 范围: 仅 Web 端 `videoPlayer.js` 倍速按钮交互改进

## 背景与动机

倍速功能（2026-08-08 合并 master，merge 893d217）当前是**点击循环** `[0.75,1,1.25,1.5,2,3]`，切到 3x 要点 5 次，不便。改为弹出档位菜单，直接选目标速度。

## 设计

**交互**：点击倍速按钮 → 弹出档位菜单（**完全替换**点击循环）。再点按钮 / 选一项 / 点菜单外部 → 关闭。

**菜单**：
- 位置：按钮**正上方**弹出（控制条在底部，向上展开不挡画面）
- 档位顺序：**快→慢**垂直排列 `[3x, 2x, 1.5x, 1.25x, 1x, 0.75x]`（主流播放器惯例）
- 当前倍速项**高亮**（主色背景 + 白字）
- 点任一项 → 设 `playbackRate` + 更新按钮文字 + 关闭菜单

## 文件改动（4 处）

| 文件 | 改动 |
|---|---|
| `index.html` | 倍速按钮外包 `.video-speed-wrap`（`position: relative`），内含按钮 + 菜单 div（`position: absolute; bottom: 100%`，默认 `hidden`） |
| `dom.js` | 注册 `videoSpeedMenu`（菜单容器）；档位项用事件委托，不逐个注册 |
| `videoPlayer.js` | 移除原 `nextSpeed` 循环 click；按钮 click → toggle 菜单；菜单 click 委托（`data-speed`）→ 设速 + 关闭；document click（菜单外）→ 关闭 |
| `style.css` | 菜单卡片（bg + 圆角 + 阴影）+ 项 hover/active，沿用现有 `--text-main` / `--text-white` 变量 |

## 关键决策

- **档位常量** `PLAYBACK_SPEEDS = [0.75, 1, 1.25, 1.5, 2, 3]` 不变；菜单渲染时倒序（3 在顶）。
- **`nextSpeed` 纯函数**：菜单替换循环后不再使用，但**保留**（exported、有单测覆盖、无害；未来如恢复循环交互可复用）。仅 `videoPlayer.js` 不再 import 它。
- **菜单档位项**：动态生成还是静态写死？→ **静态写死在 index.html**（档位固定 6 个，无需动态；且与现有控制条元素"HTML 声明 + dom.js 注册"范式一致）。每项 `<button data-speed="1.5">1.5x</button>`。
- **事件冒泡**：按钮 click 与 document click（关闭）冲突——按钮 click 调 `e.stopPropagation()` 阻止冒泡到 document，避免"开菜单即关"。

## 数据流

```
点倍速按钮 → toggle 菜单显隐（stopPropagation）
点菜单项(data-speed) → playbackRate = data-speed + 按钮文字 + 高亮项 + 关闭
点 document（非菜单/非按钮）→ 关闭菜单
打开新视频(openVideoPlayer) → 关闭菜单 + 重置 1x + 高亮 1x 项
```

## 错误处理

- 菜单显隐用 `hidden` class 切换（CSS 控制），无状态泄漏
- document click listener 始终绑定（判断 `target.closest('.video-speed-wrap')` 决定是否关闭），无需 add/remove listener 的生命周期管理

## 测试

- `videoPlayer.js` 无自动化测试（项目传统），菜单 UI 靠 **MCP 浏览器验证**：点按钮弹菜单、档位顺序、当前高亮、选项设速、点外部关闭、开新视频重置
- `npm test` 回归（菜单改动不应破坏现有 87 测试）

## 非目标

- 不改其他控制条按钮（播放/音量/全屏/转码）
- 不改倍速档位值
- 不碰 Android / 服务端

## 交互默认值（已与用户确认）

1. 完全替换点击循环
2. 档位顺序快→慢（3x 在顶）
