# 阅读器翻页方式与动画设计文档（COVER / SIMULATION / DRAG）

**日期**：2026-08-01
**范围**：Web（`server/internal/web/`）+ Android（`android/app/src/main/java/com/juziss/localmediahub/`）双端阅读器
**目标读者**：实施 agent、未来维护者
**状态**：已与用户确认，待实施

## 背景

阅读器排版打磨第一阶段（字间距/字体/CUSTOM 主题色，merge `64f7330`）已完成。本阶段是用户确认的四个方向之二：**翻页方式与动画**——为分章模式（CHAPTER）增加真正的翻页动画，体验向 Legado/iBooks 靠拢。

用户决策（brainstorming 确认）：
- **章级翻页**：不造页级分页引擎；一章仍是一个滚动列表，翻页动画作用于**章间过渡**。
- **三种动画全做**：COVER（覆盖/滑动）、SIMULATION（仿真卷曲）、DRAG（拖动跟手），加 NONE（无动画）。
- **新增"翻页动画样式"设置字段**，ReadingMode 保持 CHAPTER/SCROLL 不变。
- **翻页方向**：下一章 = 新页从右进入、旧页向左退出（中文阅读习惯）。
- **DRAG 与垂直滚动共存**：水平拖动翻页，垂直拖动仍滚动章内内容。

## 非目标（明确不做）

- 不造页级分页引擎（一章内容超出仍可垂直滚动）
- SCROLL 模式不加翻页动画
- 目录/书签跳转不走翻页动画（瞬时加载 + fade，避免跨多章方向混乱）
- SIMULATION 不做可拖动卷曲（固定时长动画；跟手卷曲留后续）
- 不引入第三方翻页库（沿用"无新依赖"约束）
- 不改 7 个既有主题预设与现有排版字段

## 数据模型与字段规格

### 新增设置字段（双端一致）

| 字段 | 类型 | 默认 | 语义 |
|------|------|------|------|
| `pageTurnStyle` | enum / string | `NONE` | 翻页动画样式，仅 CHAPTER 模式生效 |

枚举值（双端逐字一致）：
- `NONE` — 无动画（保持现状：点击热区瞬时切章 + 120ms fade）
- `COVER` — 覆盖/滑动：新章从右侧滑入覆盖旧章
- `SIMULATION` — 仿真：纸质书卷曲翻页（贝塞尔曲线 + 阴影）
- `DRAG` — 拖动跟手：页面跟随手指水平拖动，松手按阈值翻页或回弹

### 与 ReadingMode 的关系

- `ReadingMode` 保持 `CHAPTER`/`SCROLL` 不变（Web `chapter`/`scroll`）。
- `pageTurnStyle` **仅在 CHAPTER 模式生效**；SCROLL 模式忽略。
- 切换到 SCROLL 模式时不清空 `pageTurnStyle`（切回 CHAPTER 恢复）。
- 设置面板中"翻页动画"选择器仅在当前为 CHAPTER 模式时可交互；SCROLL 模式下置灰（交互模式同现有"AUTO 选中时禁用具体主题 chips"）。

### 触发路径统一

CHAPTER 模式下，所有**相邻切章**入口统一接入新的翻页动画路径（不再直接 `loadChapter` + fade）：
- 左/右 20% 点击热区（双端现有）
- 底栏"上一章/下一章"按钮（Android）/ `els.prev`·`els.next`（Web）
- 章末 ❖ 符号
- DRAG 额外绑定：阅读区水平拖动手势

**不走翻页动画**（仍瞬时 + fade）：目录跳转、书签跳转——这些是跨多章的"跳转"非"相邻翻页"，动画方向会混乱。

**翻页方向**：下一章 = 新页从右进入、旧页向左退出；上一章反向。

### 动画规格

- **COVER**：旧章 `translateX(-100%)` + 渐隐，新章从 `translateX(100%)` → `0`；时长 280ms，`ease-out`。
- **SIMULATION**：单页卷曲（详见 §SIMULATION 实现细节），时长 400ms，`ease-in-out`。仅做单向卷曲（上一章镜像）。
- **DRAG**：手指拖动实时 `translateX`（0 到 ±100%），松手判定：拖动距离 > 屏宽 25% 或速度足够快 → 完成翻页动画（剩余距离滑过去）；否则回弹归位。

## 双端架构

核心思路：把"切章 + 动画"抽成**翻页控制器**，CHAPTER 模式内容区不再直接 `loadChapter` + fade，而是通过控制器发起"带方向的翻页请求"。控制器负责：加载目标章、根据 `pageTurnStyle` 选择动画、驱动动画、完成后切换显示。

- **Android**：新增 `PageTurnController`（普通 class，持有 `Animatable<Float>` 进度），由 `TextReaderScreen` 在 CHAPTER 分支持有。`ChapterModeContent` 的 `AnimatedContent` fade 替换为控制器驱动的双层渲染（当前章 + 进入/退出的章两个 `Box` 叠放）。
- **Web**：新增 `pageTurn.js` 模块（与 `autoscroll.js`/`readerScrubber.js` 同级子模块），导出 `renderPageTurn({ container, getStyle, onTurn })` 返回 `{ turnTo, dispose }`。`textReader.js` 编排层在 CHAPTER 模式把切章入口改为调 `pageTurnApi.turnTo('next'|'prev')`。

### Web 端改动点

| 文件 | 改动 |
|------|------|
| `readerPrefs.js` | `DEFAULT_SETTINGS` 加 `pageTurnStyle: 'NONE'`；`migrateV1toV2` 校验枚举（非法→NONE）；无新 npm 依赖 |
| `reader-settings.js` | 加"翻页动画"radio 区（NONE/COVER/SIMULATION/DRAG），仅 CHAPTER 模式可交互；与 readingMode 联动禁用 |
| `pageTurn.js`（新建） | `renderPageTurn()`：COVER（translateX + transition）、SIMULATION（clip-path + 阴影）、DRAG（pointer 事件 + 阈值）；返回 `{ turnTo, dispose }` |
| `textReader.js` | CHAPTER 模式接入 `pageTurnApi`；左右热区/prev/next/❖ 统一走 `turnTo`；DRAG 绑定阅读区水平拖动 |
| `style.css` | 翻页层定位/transform/transition/阴影样式 |

### Android 端改动点

| 文件 | 改动 |
|------|------|
| `ReaderSettings.kt` | 新增 `pageTurnStyle: PageTurnStyle = NONE` 字段；`enum class PageTurnStyle(NONE/COVER/SIMULATION/DRAG, val label: String)` |
| `PageTurnController.kt`（新建） | 持有 `Animatable<Float>`（进度 0..1）；`suspend fun turnTo(direction, loadTarget, render)`；COVER/SIMULATION/DRAG 三种实现 |
| `TextReaderScreen.kt` | CHAPTER 分支：`AnimatedContent` fade 替换为 `PageTurnController` 驱动的双层渲染；DRAG 绑定 `detectHorizontalDragGestures`（与现有 `detectTapGestures` 协作） |
| `ReaderSettingsSheet.kt` | 加"翻页动画"FlowRow chips；SCROLL 模式置灰 |
| `PageTurnSimulator.kt`（新建） | SIMULATION 的 `Canvas` 自绘：贝塞尔卷曲路径 + `Brush.linearGradient` 阴影 |

### 手势协作（CHAPTER 模式阅读区）

四种手势按优先级判定：
1. **长按段落** → 书签/复制菜单（现有，最高优先级）
2. **水平拖动**（仅 DRAG 样式）→ 翻页；判定：水平位移 > 垂直位移 且 水平位移 > 8dp 触摸阈值
3. **点击**：松手且无明显位移时，按 x 比例判定——左 20% 上一章、右 20% 下一章、中间切 chrome
4. **垂直滚动**（章内内容超出时）→ 交给 `LazyColumn` 垂直滚动

判定顺序：pointer down 记录起点 → move 时若水平占主导且为 DRAG 样式则消费为翻页（否则不消费，交给滚动）→ up 时若位移 < 阈值视为 tap。Web 端 `pointerdown/move/up` + 同套阈值；Android 端 `detectDragGestures` + `detectTapGestures` 组合。

## SIMULATION 实现细节

### 动画模型

不做"对折成两半"的复杂物理仿真，做**单页卷曲**——视觉等价于"捏住右下角向左上翻"的简化版：
- **几何**：底层是"下一章"（已加载，静止），顶层是"当前章"被卷起。卷曲边界由一条贝塞尔曲线定义，随进度从右边扫向左边。
- **阴影**：卷曲边界处用 `LinearGradient`（Android `Brush` / Web CSS gradient）画窄阴影带模拟折痕；顶层页边缘加柔和投影。
- **动画**：进度 0→1，400ms，`ease-in-out`。0 = 顶层完整覆盖（看到当前章）；1 = 顶层完全卷走（看到下一章）。
- **上一章**：镜像（从左向右卷）。

### 双端实现选择

- **Android**：`PageTurnSimulator` 是 `Canvas` 组件。采用 **clipPath 方案**：顶层 `Box` 用贝塞尔 `Path` 做 clip 露出未卷起部分，底层放下一章；阴影 `Brush.linearGradient` 画在 clip 边界。
- **Web**：顶层 `<section>` 用 CSS `clip-path: polygon(...)` 沿贝塞尔采样点近似（每 8% 进度采一个点连成多边形）；阴影用 `::after` 伪元素 `linear-gradient` 定位在 clip 边界。选 `clip-path: polygon` 而非 SVG path（性能更好、无额外 DOM）。

### 性能与降级

- 章节含图片时，动画作用于内容快照层，图片懒加载不受影响。
- COVER/DRAG 用 transform（GPU 合成层）天然轻量；SIMULATION 的 clipPath 每帧重算——首版不做过早优化，测试中若卡顿再降级（如 SIMULATION 在低端机回退为 COVER）。
- **Web 端 `prefers-reduced-motion`**：若用户系统开启减弱动画，SIMULATION/COVER 自动降级为 NONE（fade）；DRAG 因用户主动拖动不降级。

## 测试计划

**Web**（node:test + jsdom，沿用现有模式）：
- `readerPrefs`：`pageTurnStyle` 默认 NONE、迁移枚举校验（非法值→NONE）
- `pageTurn.test.mjs`（新建）：`renderPageTurn` 返回 `{ turnTo, dispose }`；`turnTo('next')` 触发 onTurn 且方向正确；DRAG pointer 阈值判定（>25% 翻页、<25% 回弹）；dispose 解绑事件
- `reader-settings`：翻页 radio 渲染、CHAPTER/SCROLL 联动禁用
- `textReader` 集成：CHAPTER 模式点右热区 → 调 `turnTo('next')`（mock fetch 模式）

**Android**（Robolectric）：
- `ReaderSettingsMigrationTest`：旧 JSON 无 `pageTurnStyle` → NONE；带字段读取正确
- `PageTurnControllerTest`（新建）：`turnTo('next')` 进度 0→1 完成回调触发；非法方向 no-op；并发 turnTo 后者取消前者
- `ReaderSettingsSheetTest`：翻页 chips 渲染、SCROLL 置灰、点击触发 onChange
- 手势判定纯函数单测（DRAG 阈值判定逻辑抽为纯函数便于测）

## 双端对齐校验清单（实施完成时人工核对）

- [ ] `pageTurnStyle` 四值双端一致，默认 NONE
- [ ] 仅 CHAPTER 模式生效；SCROLL 模式置灰且无动画
- [ ] 下一章方向双端一致（新页从右进入）
- [ ] 左右热区/按钮/❖ 三入口双端都走翻页路径
- [ ] 目录/书签跳转不走翻页动画（瞬时 + fade）
- [ ] DRAG 水平阈值与垂直滚动共存，双端阈值一致（25% 屏宽、8dp 触摸阈值）
- [ ] Web 端 `prefers-reduced-motion` 下 COVER/SIMULATION 降级为 fade

## 风险与回退

1. **SIMULATION 自绘成本**：Compose Canvas + Web clip-path 双端实现复杂度高。若实施中发现某一端无法在合理工时内达到可接受视觉效果，SIMULATION 可临时降级为"COVER + 卷曲阴影伪元素"近似，并在 commit/报告中注明；NONE/COVER/DRAG 不受影响先行落地。
2. **DRAG 手势冲突**：与章内垂直滚动、长按书签、点击热区共存易踩坑。手势判定逻辑抽为纯函数（`resolveGesture(dx, dy, style, threshold)`）先行单测，再接入 UI。
3. **Web pointer 事件兼容**：`pointerdown/move/up` 在旧浏览器需 `touchstart/move/end` 回退。首版假定现代浏览器（项目 Web 端已用 ES modules + matchMedia，无 IE 兼容负担）；若测试发现需回退再加。
