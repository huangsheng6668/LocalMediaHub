# 阅读器边缘手势与翻页键设计文档（亮度/音量侧滑 + 音量键/键盘翻页）

**日期**：2026-08-01
**范围**：Web（`server/internal/web/`）+ Android（`android/app/src/main/java/com/juziss/localmediahub/`）双端阅读器
**目标读者**：实施 agent、未来维护者
**状态**：已与用户确认，待实施

## 背景

阅读器排版打磨第三阶段（仿 Legado 体验的方向之三：阅读交互细节）。第一、二阶段（排版字段/字体/CUSTOM 主题色、COVER/SIMULATION/DRAG 翻页动画）已合并 master。本阶段聚焦两项交互：**边缘垂直滑动调亮度/音量** + **音量键（Android）/方向键+空格（Web）翻页**。

用户决策（brainstorming 确认）：
- **分区**：左 15% 调亮度、右 15% 调音量（Legado 经典）。
- **Web 右边缘不响应调音**（无系统音量 API），右半屏仍作点击热区。
- **边缘区与点击热区共存**：边缘 tap = 翻章、边缘 drag = 调亮/音（按位移判定）。
- **音量键纯翻页**（Android onKeyDown 返回 true 拦截，开关开启时）。
- **亮度仅会话内**（退出阅读器恢复系统值，避免误调全局过暗）。
- **不含**长按选词、阅读时长统计（后者与第四阶段"书架与进度管理"重叠）。

## 非目标（明确不做）

- 长按选词/划词（本轮排除）
- 阅读时长统计/阅读日历（第四阶段做）
- Web 调系统音量（无 API）
- 亮度跨会话持久化（仅会话内）
- 不引入第三方手势库（沿用"无新依赖"约束）
- 不改现有翻页动画（COVER/SIMULATION/DRAG）与排版字段

## 数据模型与字段规格

### 新增设置字段（双端一致）

| 字段 | 类型 | 默认 | 语义 |
|------|------|------|------|
| `edgeBrightnessVolume` | Boolean | `true` | 边缘垂直滑动调亮度/音量 |
| `volumeKeyTurn` | Boolean | `true` | 音量键（Android）/方向键+空格（Web）翻页 |

### 边缘手势分区

- **左 15%**（`xRatio < 0.15`）→ 亮度
- **右 15%**（`xRatio > 0.85`）→ 音量（Android）/ 不响应（Web，仅作点击热区）
- **中间 70%** → 不接管，交现有手势（DRAG 水平拖动翻页 / 章节内垂直滚动 / 点击翻章）

边缘区（15%）与现有"左右 20% 点击热区翻章"重叠（15% ⊂ 20%）。判定规则：
- pointerdown 记起点 → move 时若**垂直位移占主导**（`|dy| > |dx|`）且处于边缘区 → 接管为亮度/音量调节
- 若**无明显位移**（up 时）→ 视为 tap，走现有点击翻章
- 即"边缘 tap = 翻章、边缘 drag = 调亮/音"，二者不冲突

### 亮度/音量映射

- **亮度范围** `[0.15, 1.0]`（Android `screenBrightness` / Web CSS `filter: brightness()` 或覆盖层）。**向下拖 = 增亮、向上拖 = 减暗**（与 Legado 一致）。灵敏度：全屏高度对应 0.15→1.0 全程。
- **音量范围**（Android）`[0, AudioManager.getStreamMaxVolume(STREAM_MUSIC)]`，同样的 dy 映射。
- **指示器**：调节过程屏幕中央显示半透明指示器（图标 + 当前值百分比，2 秒淡出）——双端各一个 `BrightnessIndicator`/`VolumeIndicator` 小组件。

### 持久化

- 亮度**仅会话内**：Android `window.attributes.screenBrightness` 会话内生效，`onDestroy` 复原为 `BRIGHTNESS_OVERRIDE_NONE`；Web CSS 类会话内生效，离开阅读页移除。**不写入 settings**。
- 音量键翻页开关、边缘调节开关 → 持久化到 settings（跨会话）。

### 音量键/键盘翻页

- **Android**：`TextReaderActivity.onKeyDown`：
  - `KEYCODE_VOLUME_UP → turn(PREV)`、`KEYCODE_VOLUME_DOWN → turn(NEXT)`
  - 开关开启时返回 true 拦截系统音量调节；关闭时走默认（调系统音量，返回 false）
- **Web**：`document.addEventListener('keydown')`（仅阅读页，路由离开解绑）：
  - `ArrowUp`/`ArrowLeft` → prev
  - `ArrowDown`/`ArrowRight`/`Space` → next
  - 开关开启时响应；关闭时不响应
- **方向约定**（与翻页动画一致）：翻到**下一章** = "前进"键（音量下/向下/向右/空格）；**上一章** = "后退"键（音量上/向上/向左）

## 双端架构

核心思路：把"边缘手势判定"抽成独立模块（纯函数 + 控制器），与上轮 `PageTurnController`/`pageTurn.js` 同构。亮度/音量指示器各端一个小组件。

- **Android**：
  - `EdgeBrightnessController.kt`（新）：纯函数 `resolveEdgeZone(xRatio): EdgeZone`、`mapDragToBrightness(dy, viewHeight): Float`、`mapDragToVolume(dy, viewHeight, max): Int`；`enum EdgeZone { BRIGHTNESS, VOLUME, NONE }`
  - 接入：`TextReaderScreen` 在现有 pointerInput 链上**新增**一个 `detectVerticalDragGestures`（与 `detectTapGestures`/`detectHorizontalDragGestures` 并列），仅在边缘区+垂直主导时接管
  - 亮度/音量调节实际调用放 `TextReaderActivity`（持有 window/AudioManager），通过 ViewModel 回调或 LocalContext 传入
  - `BrightnessIndicator`/`VolumeIndicator` Compose overlay 小组件
- **Web**：
  - `edgeBrightness.js`（新，与 `pageTurn.js` 同级）：纯函数 `resolveBrightnessZone(xRatio): Boolean`（仅左 15% true）+ `renderEdgeBrightness({ contentEl, getEnabled, getBrightness, onBrightnessChange, onIndicator })` 返回 `{ dispose }`
  - 接入：`textReader.js` 在 contentEl 绑 `pointerdown/move/up`（与 DRAG 手势 pointer 链协调：边缘+垂直才接管，否则不消费让 DRAG/scroll 继续）
  - CSS：阅读区 `filter: brightness(X)` 或覆盖层 div
  - 指示器：阅读区中央绝对定位 div（2 秒淡出）

### Web 端改动点

| 文件 | 改动 |
|------|------|
| `readerPrefs.js` | `DEFAULT_SETTINGS` 加 `edgeBrightnessVolume: true`、`volumeKeyTurn: true`；`migrateV1toV2` 校验 boolean |
| `reader-settings.js` | 加两个 checkbox 开关 |
| `edgeBrightness.js`（新建） | `resolveBrightnessZone`（纯函数）+ `renderEdgeBrightness(...)` 返回 `{ dispose }` |
| `textReader.js` | 接入 `edgeBrightnessApi`；绑 `keydown`（翻页键，开关开启时）；协调 pointer 链 |
| `style.css` | 阅读区亮度滤镜/覆盖层 + 指示器样式 |

### Android 端改动点

| 文件 | 改动 |
|------|------|
| `ReaderSettings.kt` | 加 `edgeBrightnessVolume: Boolean = true`、`volumeKeyTurn: Boolean = true` |
| `EdgeBrightnessController.kt`（新建） | 纯函数 + `enum EdgeZone` |
| `TextReaderActivity.kt` | `onKeyDown` 拦截音量键；暴露 `setBrightness(Float)`/`setVolume(Int)`/`getVolume()`；`onDestroy` 复原亮度 |
| `TextReaderViewModel.kt` | 持有 `brightness: MutableStateFlow<Float>`、`volume: MutableStateFlow<Int>` + 指示器可见性；提供 `onEdgeDrag` 回调 |
| `TextReaderScreen.kt` | 新增 detectVerticalDragGestures pointerInput；渲染 BrightnessIndicator/VolumeIndicator overlay；经 LocalContext 调 ViewModel 亮度/音量方法 |
| `ReaderSettingsSheet.kt` | 加两个开关 |

### 手势优先级（与上轮手势栈整合）

CHAPTER/SCROLL 模式下阅读区手势栈（按接管优先级）：
1. **长按段落** → 书签/复制菜单（最高）
2. **边缘垂直拖动**（开关开启 + `xRatio<0.15` 或 `>0.85` + `|dy|>|dx|` + 超过 8dp slop）→ 亮度/音量。**仅 Android 右边缘调音量**；Web 右边缘不接管。
3. **水平拖动**（DRAG 样式 + 水平主导）→ 翻页
4. **点击**（无明显位移）→ 左 20%/右 20% 翻章，中间切 chrome
5. **垂直滚动**（非边缘 + 垂直主导）→ 章节内滚动

判定顺序：pointer down 记起点 → move 时按上述优先级判定接管方（一次手势只被一方接管）→ up 时若无人接管视为 tap。与上轮 DRAG 的"先判水平主导"对偶——这里"先判垂直主导且在边缘"优先于水平。

## 测试计划

### 纯函数先行测试

- **`resolveEdgeZone(xRatio): EdgeZone`**（Android）/ **`resolveBrightnessZone(xRatio): Boolean`**（Web，仅左 15%）：分区判定
- **`mapDragToBrightness(dy, viewHeight): Float`**：dy 全屏高度对应 0.15→1.0；向下增亮、向上减暗；clamp [0.15, 1.0]
- **`mapDragToVolume(dy, viewHeight, max): Int`**（Android）：dy 映射到 [0, max]；clamp
- **`resolveKeyTurn(keyCode)`**（Android）/ **`resolveKeyTurn(key)`**（Web）：音量上/向上/向左 → PREV；音量下/向下/向右/空格 → NEXT；其他 → null

### 测试矩阵

**Web**（node:test + jsdom）：
- `readerPrefs`：两字段默认 true、迁移 boolean 校验
- `edgeBrightness.test.mjs`（新）：`resolveBrightnessZone`（左 15% true、其余 false）；`renderEdgeBrightness` 返回 dispose；pointer 边缘垂直滑动 → `onBrightnessChange` 被调用且值单调；中间区域不接管
- `reader-settings`：两个 checkbox 渲染 + change 持久化
- `textReader` 集成：keydown ArrowDown → `pageTurnApi.turnTo('next')`（mock）；开关关闭时不响应

**Android**（Robolectric）：
- `ReaderSettingsMigrationTest`：旧 JSON 无两字段 → 默认 true
- `EdgeBrightnessControllerTest`（新）：resolveEdgeZone/mapDragToBrightness/mapDragToVolume/resolveKeyTurn 纯函数全覆盖
- `ReaderSettingsSheetTest`：两个开关渲染 + 点击 onChange
- `TextReaderActivity` onKeyDown：开关开 → 返回 true + 触发翻页回调；关 → 返回 false（Robolectric `shadowOf(activity).keyPressed`）

### 视觉/设备验证项（自动化无法覆盖）

- 亮度/音量指示器 overlay 的实际显示与淡出（真机/浏览器）
- Android `window.screenBrightness` 实际改变屏幕亮度（真机）
- 边缘垂直滑动与 DRAG 水平拖动、章节内滚动的实际手感（真机）
- 音量键翻页在锁屏/后台时的行为边界（真机）

## 双端对齐校验清单（实施完成时人工核对）

- [ ] `edgeBrightnessVolume` + `volumeKeyTurn` 默认 true，双端一致
- [ ] 边缘分区阈值一致（左 15% 亮度；右 15% 仅 Android 调音、Web 不响应）
- [ ] 亮度范围 [0.15, 1.0]，向下增亮，双端一致
- [ ] 亮度仅会话内（退出恢复），双端一致
- [ ] 翻页键方向双端一致（音量下/向下/向右/空格 = next，音量上/向上/向左 = prev）
- [ ] 音量键翻页开关关闭时不拦截系统音量（Android）/ 键盘翻页不响应（Web）
- [ ] 边缘 tap 仍能翻章（与边缘 drag 不冲突）
- [ ] SCROLL 模式下边缘垂直滑动仍调亮度（不与整屏滚动冲突——边缘 15% 接管）

## 风险与回退

1. **Android 音量键拦截与系统行为**：onKeyDown 返回 true 拦截后，用户在阅读器内无法用音量键调系统音量——需明确告知（设置面板开关描述注明"阅读时音量键翻页，关闭可恢复系统音量调节"）。默认开（与 Legado 一致），用户可关。
2. **手势优先级冲突**：边缘垂直拖动 vs DRAG 水平拖动 vs 章节滚动——三者判定逻辑抽纯函数先行单测（同上轮策略），确保"一次手势只被一方接管"。
3. **Web brightness CSS 伪装效果**：`filter: brightness()` 在某些浏览器/主题下与预期不符（如夜间主题再 brightness 0.5 可能过暗）。首版用覆盖层 `rgba(0,0,0,alpha)` 方案（更可控：alpha = 1-brightness，覆盖在内容上模拟变暗，无法变亮于系统值）——接受"Web 只能调暗不能调亮"的限制（CSS 无法让屏幕比系统更亮）。
4. **Android window.screenBrightness 持久泄漏**：若 Activity 异常退出未走 onDestroy，亮度可能残留——onDestroy + onStop 双保险复原；首版接受极小概率残留（用户下次进入阅读器会重置）。
