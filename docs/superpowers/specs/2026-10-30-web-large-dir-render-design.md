# Web 大目录增量渲染（Phase A）

日期：2026-10-30
范围：`server/internal/web/browserView.js` / `utils.js` / `scrollMemory.js`（只读复用）
状态：已实施

## 问题

媒体浏览页对单个目录一次性 `innerHTML` 注入全部卡片。实测 2000 个文件的目录：

| 指标 | 基线 |
|---|---|
| DOM 节点 | 24,000+ |
| 首卡出现 | 188ms |
| 全量渲染完成 | 188ms（同一帧内完成） |
| Long Task | 124ms + 119ms（合计 243ms） |
| 滚动帧间隔 | p50 18ms / p95 38ms / max 69ms |

首屏被一次几百毫秒的主线程阻塞占满，点击后页面「卡死」感明显。滚动本身已有
`content-visibility: auto; contain-intrinsic-size: auto 190px`（`browser.css`），
瓶颈在首次 HTML 解析与节点构建，而非后续绘制。

## 方案

**分块注入，数据全量在内存。** 不做虚拟滚动：

- 筛选（`applyListFilters`）、排序（`sortMediaItems`）、搜索、点击分发（`data-index`
  索引 `shownFiles` / `shownFolders`）、收藏/阅读徽章装饰都建立在「全量数组 +
  全量 DOM」契约上，虚拟列表需要重写整条链路，收益与风险不成比例。
- 首次绘制只需要「第一屏够用」的卡片，其余可以逐帧补上。

实现要点：

1. `utils.computeInitialRenderCount(total, firstChunk, anchorIndex)` —— 纯函数，
   返回首批应同步渲染的卡片数：`max(120, anchorIndex + 1)`，并 clamp 到总数。
2. `browserView.js` 把卡片模板拆成 `buildFolderCardHtml` / `buildFileCardHtml`，
   先构建 `cards = [{ path, html }]`（顺序：文件夹 → 文件，与 DOM 顺序一致）。
3. 同步注入首批（120 张，或滚动锚点所在位置 +1），立即 `decorateBrowserList`
   并 `restoreScrollMemory`，随后 `refreshDecorations` 保持原有网络行为。
4. 剩余卡片按 400 张 / 批，`setTimeout(…, 16)` 逐批 `insertAdjacentHTML('beforeend')`，
   每批后重新 `decorateBrowserList`，最后一批完成后再次 `restoreScrollMemory`。
5. 代际取消：模块级 `renderGeneration` + `renderChunkTimer`。任何整表替换入口
   （`renderBrowserList`、`browsePath` 的加载提示、`loadRoots`、`loadSystemDrives`、
   `triggerBrowserSearch` 的检索提示）先 `cancelChunkRendering()`，过期批次直接丢弃。

### 为什么锚点要扩展首批

`restoreScrollMemory` 通过 `.media-card[data-path="…"]` 选择器找锚点卡片，找不到就
静默放弃。若锚点在 120 张之后，首批渲染完时锚点不在 DOM，滚动恢复失效。因此渲染前
用 `recallScroll(state.currentPath)` 取锚点路径，在 `cards` 中 `findIndex`，把首批
扩展到覆盖它（只扩大、不缩小）。

注意文件夹卡片的 `data-path` 是斜杠化形态（既有渲染契约），锚点键必须与 DOM 值一致，
所以 `cards[].path` 对文件夹取 `path.replace(/\\/g, '/')`、对文件取原始路径。

### 为什么每批都要 decorate

`decorateBrowserList` 只处理当时存在的 `.media-card[data-path]`；后追加的卡片不会
自动获得收藏心形与阅读徽章。每批追加后调用一次，代价与卡片数线性，可接受。

## 验证

单元/集成（`cd server/internal/web && node --test`，243 项全绿）：

- `utils.test.mjs`：`computeInitialRenderCount` 的首批、锚点扩展、越界/NaN 兜底。
- `browserChunkRender.test.mjs`（jsdom）：
  - 2000 张时 `renderBrowserList()` 返回后 DOM 恰好 120 张，最终补齐 2000 张；
  - 锚点在第 1500 张时首批直接为 1500 张且锚点卡片存在；
  - 首批未完成即发起新渲染，200ms 后只剩新渲染的 5 张（代际取消生效）。
- `tools/xsscheck`：`insertAdjacentHTML` 带 `// XSS-SAFE:` 注释，扫描通过。

E2E（127.0.0.1:18080，临时 media root 下 2000 张 jpg）：

| 指标 | 基线 | 现测 |
|---|---|---|
| 首批同步渲染 | 188ms（=全量） | **23ms** |
| 全量卡片就绪 | 188ms | 184ms（分摊到 ~160ms 的多个 16ms 批次） |
| Long Task | 243ms | **0** |
| 导航端到端首卡 | — | 50ms（含 fetch） |
| 导航端到端全量 | — | 410ms（已可交互的首帧在 50ms） |
| 滚动帧间隔 | p50 18 / p95 38 / max 69 | p50 18.7 / p95 37.8 / max 58.5 |

功能回归（浏览器内实测）：排序切换后首卡立刻正确且最终 2000 张；只看收藏无收藏时
空态正确、关闭筛选后重新补齐；滚动到 20000px 后进入父目录再返回，首批 298 张
（锚点 f0298 所在），滚动恢复到 20000（分块完成后最终校准）；点击第 2000 张卡片
正确打开对应原图。

## 影响面

- `server/internal/web/utils.js`：新增 `computeInitialRenderCount`
- `server/internal/web/browserView.js`：`renderBrowserList` 拆分为卡片构建 + 分块调度；
  新增 `cancelChunkRendering` / `scheduleChunkAppend`；整表替换入口增加取消调用
- 新增测试：`server/internal/web/utils.test.mjs`、`server/internal/web/browserChunkRender.test.mjs`
- 服务端无改动；`scrollMemory.js` / `library.js` 只读复用

## 已知取舍

- 卡片 HTML 字符串一次性全部构建，仍有一次 O(n) 的字符串开销（2000 张约几十 ms，
  分片时与 120 张同帧完成）。若未来出现 10 万级目录，可改为按批构建。
- 全量 DOM 形态保留，内存占用与原实现相同；`content-visibility` 负责离屏成本。
- 分块期间用户点击卡片时，`shownFiles` / `shownFolders` 已是全量数组，行为不受影响。
