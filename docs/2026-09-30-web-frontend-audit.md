# Web 前端审查报告（LocalMediaHub Web Manager）

- 审查日期：2026-09-30
- 审查范围：`server/internal/web/`（`index.html` + 33 个 ES 模块 + 14 个 CSS 文件）
- 代码规模：HTML 409 行 / JS 约 6,000 行 / CSS 约 2,400 行
- 审查方式：静态阅读 + 实测（启动 `LocalMediaHub.exe --headless`，curl 探测真实响应头）

## 总体评价

架构底子相当扎实，不是需要推倒重来的状态：

- **CSS 设计系统已成型**：`themes.css` 定义完整的语义 token（`--surface-*` / `--text-*` / `--accent-*` / `--action-ink`），7 套主题（day / day_bright / eye_care / eye_care_green / parchment / night / night_black），`theme-parity.test.mjs` 守护与 Android 端色值对称。
- **XSS 防护严格**：CSP `script-src 'self'` 无 `unsafe-inline`，所有动态字段经 `escapeHtml()`，代码里有大量 `// XSS-SAFE:` 标注；`innerHTML` 只用于纯字面量模板或已转义数据。
- **已有的性能优化**：`content-visibility: auto` + `contain-intrinsic-size` 跳过离屏卡片布局、`loading="lazy"` + `decoding="async"` 缩略图、rAF 节流滚动处理器、`Promise.allSettled` 并行请求、`boot.js` 防 FOUC。
- **CSS 重复定义极少**：全量扫描仅 2 处同名选择器重复定义。

**问题集中在三个方向**：① 亮色主题下有一批「暗色假设」的硬编码颜色导致元素不可见；② 静态资源零缓存策略 + 单文件 415KB 无条件加载；③ 交互可达性（键盘 / 触屏 / 屏幕阅读器）有系统性缺口。

---

## P0 — 正确性缺陷（建议优先修）

### P0-1 亮色主题下滚动条完全不可见

`css/base.css:70-77`

```css
::-webkit-scrollbar-thumb {
    background: rgba(255, 255, 255, 0.1);   /* 白色 10% */
}
::-webkit-scrollbar-thumb:hover {
    background: rgba(255, 255, 255, 0.2);
}
```

主滚动容器是 `.view-container`（`overflow-y: auto`），背景为 `--surface-app: #F7F7F5`。白色 10% 叠加在浅灰上，对比度约等于 0 —— **day / day_bright / eye_care / parchment 四套亮色主题下滚动条几乎看不见**，用户失去滚动位置反馈。

修复：改用 token，让主题自动接管。

```css
::-webkit-scrollbar-thumb {
    background: var(--text-muted);
    opacity: 0.35;
    border-radius: 10px;
}
::-webkit-scrollbar-thumb:hover { background: var(--text-secondary); }
```

补充：项目未设置 `scrollbar-color` / `scrollbar-width`，Firefox 走的是系统默认样式，与 Chromium 不一致。建议补 `html { scrollbar-width: thin; scrollbar-color: var(--text-muted) transparent; }`。

### P0-2 亮色主题下卡片徽标、操作按钮边框不可见

`css/views/browser.css` 有 20+ 处 `rgba(255, 255, 255, x)` / `rgba(0, 0, 0, x)` 硬编码，全部是「暗色 UI」假设。亮色主题下失效的项：

| 行 | 规则 | 亮色主题下的实际效果 |
|---|---|---|
| `162` | `.card-preview { background: rgba(255,255,255,0.03) }` | 与卡片同为白色，预览区与内容区无区分 |
| `261` | `.card-badge { background: rgba(255,255,255,0.08) }` | 格式徽标（TXT / MP4）背景不可见 |
| `294` | `.card-action-btn { border: 1px solid rgba(255,255,255,0.1) }` | 操作按钮描边不可见 |
| `452-453` | `.card-badge--unsupported { color: #fca5a5 }` | 浅红字在近白底上对比度不足 |
| `459` | `.fav-btn.active { color: #f87171 }` | ~~同上~~ **此项判定有误，见下方更正** |

修复方向：全部换成语义 token（`--surface-hover` / `--border-subtle` / `--error`），因为 `--error` 已经在 6 套主题里都定义好了（`#DC2626` / `#C0392B` / `#F87171`），却几乎没被用上。

> **更正（修复阶段复核）**：`.fav-btn.active { color: #f87171 }` **不是缺陷，已刻意保留硬编码**。
> 复核 `library.js:162` 的 `favBtn.classList.toggle('active', …)` 与 `browser.css:288` 的
> `.card-actions-overlay { --overlay-btn-bg: rgba(0, 0, 0, 0.7) }` 后确认：该按钮位于**固定深色浮层**
> 之上（缩略图内容不可预测，浮层底色必须与主题无关地固定为深色），浅红 `#f87171` 在深底上
> 对比度约 5.4:1，两种主题下表现一致且正确。
>
> 本项误判反而牵出了**真正的缺陷**：同一浮层上的 `.card-action-btn { color: var(--text-muted) }`
> 在 night 主题取到 `#74787F`，压在 `rgba(0,0,0,0.7)` 上对比度仅约 3:1；hover 态用
> `var(--text-on-accent)` 在 night 主题取到 `#0F172A`，深底深字**完全不可见**。
> 已改为浮层作用域内的自定义属性 `--overlay-btn-fg` / `--overlay-btn-fg-strong`（固定浅色）。
> 教训：判断「硬编码颜色是否是 bug」前，必须先确认该元素的实际**背景来源**，不能只看属性值。

### P0-3 dashboard 空状态双层嵌套 `.empty-state`

`js/dashboard.js:62-64` 与 `80-81`

```js
elements.dashboardRecent.classList.add('empty-state');
elements.dashboardRecent.innerHTML = '<div class="empty-state">暂无最近媒体数据</div>';
```

`#dashboard-recent` 自身已经是 `.recent-list`，被加上 `.empty-state` 后，内部又插入一个 `.empty-state`。而 `components.css:222` 给 `.empty-state` 定义了 `padding: 64px 24px` + `display: flex` 居中，`dashboard.css:67` 又给 `.recent-list.empty-state` 定义了 `align-items: center` —— **两层 flex 居中 + 两层 padding 叠加**，空状态会显得异常空旷且垂直位置偏移。

修复：二选一，不要叠加。

```js
// 方案 A：只保留容器级（推荐，与初始 HTML 的 class 一致）
elements.dashboardRecent.classList.add('empty-state');
elements.dashboardRecent.textContent = '暂无最近媒体数据';

// 方案 B：只保留内层组件
elements.dashboardRecent.classList.remove('empty-state');
elements.dashboardRecent.innerHTML =
  '<div class="empty-state"><p class="empty-state__desc">暂无最近媒体数据</p></div>';
```

同时 `index.html:149` 的初始文案「正在获取最近活动数据...」直接放在 `.recent-list.empty-state` 里，也是同一处不一致的体现。

### P0-4 统计卡分隔线在窄屏错位

`css/views/dashboard.css:22` + `css/responsive.css:25-27`

```css
.stat-card + .stat-card { border-left: 1px solid var(--border-soft); padding-left: var(--space-5); }
```

```css
@media (max-width: 1023px) { .stats-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 767px)  { .stats-grid { grid-template-columns: 1fr; } }
```

3 张卡片在 2 列布局下，第 3 张换行成为行首，却依然带 `border-left` —— 容器最左侧出现一条无意义的竖线。手机端单列时更明显：**每张卡片左侧都有一条悬空竖线**。

修复：只在桌面单行布局下启用分隔线。

```css
@media (min-width: 1024px) {
    .stat-card + .stat-card { border-left: 1px solid var(--border-soft); padding-left: var(--space-5); }
}
```

---

## P1 — 性能与可用性

### P1-1 `vendor/hls.min.js` 415 KB 无条件加载（收益最大的一项）

`index.html:406`

```html
<script src="vendor/hls.min.js" defer></script>
```

实测：`vendor/hls.min.js` = **415,253 字节**，是全部前端 JS（265 KB）的 1.56 倍。但绝大多数会话（浏览目录、看图、读小说、直连播放）**根本不需要 hls.js** —— 只有 Android/浏览器播放需转码的视频时才用得上。

`videoPlayer.js:57` 已经是 `new window.Hls({...})`，改成动态导入几乎零成本：

```js
// videoPlayer.js —— 在 strategy === 'hlsjs' 分支内
const { default: Hls } = await import('./vendor/hls-loader.js');
// hls-loader.js: export default window.Hls;（保留 classic script 加载方式）
```

或者更彻底：把 `vendor/hls.min.js` 改成 ESM 包装，在 `applySource` 里 `await import()`，并把该分支改成 async。

预期收益：首屏字节数从约 680 KB 降到约 265 KB（未压缩口径）。

### P1-2 静态资源零缓存策略（实测确认）

实测命令与结果：

```bash
curl -s -D - -o /dev/null http://127.0.0.1:8000/app.js
# HTTP/1.1 200 OK
# Accept-Ranges: bytes
# Content-Encoding: gzip
# （无 Cache-Control，无 ETag，无 Last-Modified）

curl -s -o /dev/null -w "%{http_code}" \
  -H 'If-Modified-Since: Wed, 30 Sep 2026 09:00:00 GMT' \
  http://127.0.0.1:8000/app.js
# 200  ← 不是 304
```

原因在 `server/internal/server/server.go:296`：

```go
s.Echo.GET("/*", echo.WrapHandler(http.FileServer(http.FS(web.Assets))))
```

`embed.FS` 的 `ModTime()` 返回零值，`http.ServeContent` 因此**既不写 `Last-Modified` 也不生成 `ETag`**，条件请求永远落空。加上没有 `Cache-Control`，浏览器只能走启发式缓存 —— 结果就是**每次导航都完整重传全部资源**（gzip 后约 200 KB）。

`serveFavicon`（`server.go:497`）倒是设了 `max-age=604800`，说明项目里已经有缓存头意识，只是静态资源这条路径漏了。

修复：包一层自定义 handler，按文件类型分级设置。

```go
// 内容哈希或构建版本号作为 ETag；HTML 必须 no-cache 以便发版即生效
func staticAssets() http.Handler {
    fs := http.FS(web.Assets)
    fileServer := http.FileServer(fs)
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        p := strings.TrimPrefix(r.URL.Path, "/")
        if p == "" { p = "index.html" }
        if strings.HasSuffix(p, ".html") {
            w.Header().Set("Cache-Control", "no-cache") // 每次校验，命中则 304
        } else {
            w.Header().Set("Cache-Control", "public, max-age=3600")
        }
        fileServer.ServeHTTP(w, r)
    })
}
```

配合「HTML `no-cache` + 子资源长缓存」，再给 CSS/JS 引入内容哈希文件名（或统一加 `?v=<buildVersion>` 查询串），就能做到发版即时生效且静态资源零重传。

补充：`HEAD /` 当前返回 **405**（`Allow: OPTIONS, GET`），因为 `/*` 只注册了 `GET`。建议补 `s.Echo.HEAD("/*", ...)`，让预取/健康检查/部分工具正常工作。

### P1-3 首屏 50 个请求，且运行在 HTTP/1.1

实测统计：

| 类别 | 数量 |
|---|---|
| HTML | 1 |
| CSS（14 个 `<link>`） | 14 |
| classic script（`boot.js` / `hls.min.js`） | 2 |
| ES 模块（`app.js` 递归依赖，见下） | 33 |
| **合计** | **50** |

`app.js` 的递归 import 图（33 个模块）包含 `textReader.js`(50 KB) / `browserView.js`(28 KB) / `videoPlayer.js`(21 KB) / `pageTurn.js`(20 KB) —— 也就是说**打开仪表盘就会下载阅读器、视频播放器、翻页动画的全部代码**。

服务端是 `ListenAndServe` 明文 HTTP，**没有 h2c，走 HTTP/1.1**，浏览器同域并发上限 6 → 50 个请求至少 9 轮往返。局域网延迟低（~1ms）所以体感尚可，但这是可优化的结构性问题。

建议按性价比排序：

1. **给 14 个 CSS 加 `<link rel="preload" as="style">`** 或合并为 2-3 个（无构建工具的话，可用 Go `embed` + 启动时拼接返回）。CSS 是渲染阻塞资源，14 个串行往返直接压在首屏。
2. **路由级代码分割**：`router.js` 里的 `renderTextReader` / `renderBookshelf` / `loadSystemDrives` 改成 `await import()`，把 reader / video / pageTurn 移出首屏依赖图。
3. **`<link rel="preload">` 关键字体**：`base.css` 里两个 `@font-face`（LXGW WenKai / Noto Serif SC）是 CJK 字体，体积大，`font-display: swap` 已做，但可加 preload 缩短换字抖动。

### P1-4 dashboard 首屏请求有冗余

`js/dashboard.js:32-40` 并行发 5 个请求，其中：

```js
apiRequest(`${state.apiBase}/api/v1/videos?page=1&page_size=1`),   // 只为拿 total
apiRequest(`${state.apiBase}/api/v1/videos?page=1&page_size=3`),   // 又请求一次 videos
```

**`videos` 端点被请求两次**，而 `page_size=3` 的响应里本来就带 `total`。合并后 5 → 4 个请求：

```js
const [textsRes, videosRes, imagesRes, transcodeRes] = await Promise.allSettled([
    apiRequest(`${state.apiBase}/api/v1/texts?page=1&page_size=1`),
    apiRequest(`${state.apiBase}/api/v1/images?page=1&page_size=1`),
    apiRequest(`${state.apiBase}/api/v1/videos?page=1&page_size=3`), // total + recent 一次拿
    apiRequest(`${state.apiBase}/api/v1/admin/transcode/status`),
]);
// 统计用 videosRes.value.total，最近列表用 videosRes.value.items
```

更彻底的做法是后端加一个 `/api/v1/stats/overview` 聚合端点，把 3 个 `page_size=1` 的计数请求压成 1 个。

### P1-5 媒体卡片网格无法键盘操作

`js/browserView.js:291-411` 生成的卡片是 `div`，只挂了 `data-action` 靠委托 click 驱动：

```html
<div class="media-card" data-action="open" data-path="...">
```

**没有 `tabindex`、没有 `role`、没有 `<a>`/`<button>`**。后果：键盘用户按 Tab 完全无法到达任何媒体卡片，只能靠鼠标。`bookshelf.js:78` 的书架卡片（`div.bookshelf-card` + `addEventListener('click')`）同理。

同时 `aria-label="收藏"` / `aria-label="删除文件"` 在每张卡上完全重复，屏幕阅读器读出来是一串无差别的「收藏 收藏 收藏」，无法区分操作对象。

修复建议：

```js
// 卡片本体可聚焦 + 可回车触发
`<div class="media-card" role="button" tabindex="0"
      data-action="open" data-path="..." aria-label="打开 ${safeName}">`

// 委托层补键盘事件
elements.browserList.addEventListener('keydown', (e) => {
  if (e.key !== 'Enter' && e.key !== ' ') return;
  const el = e.target.closest('[data-action]');
  if (!el || el !== e.target) return;   // 只处理卡片本体，避免与内部按钮冲突
  e.preventDefault();
  onBrowserListClick({ target: el });
});
```

`aria-label` 带上文件名：`aria-label="收藏 ${safeName}"`。

### P1-6 模态框无焦点陷阱，也无焦点恢复

`js/lightbox.js` 和 `js/videoPlayer.js` 的模态框都声明了 `role="dialog" aria-modal="true"`（`index.html:310` / `359`），但实测确认：**打开时不移入焦点，Tab 会跑到模态框背后的侧边栏和主内容区，关闭后也不把焦点还给触发元素。**

```bash
grep -rn 'focus()\|inert\|tabindex' lightbox.js videoPlayer.js
# → 无输出（仅 keydown 的 Escape/方向键处理）
```

`aria-modal="true"` 只是给屏幕阅读器的声明，并不会真的阻止 Tab 逃逸。修复：

```js
// 打开时
modal.classList.add('active');
lastFocused = document.activeElement;
modal.querySelector('.btn-close').focus();
// 或给背景加 inert
document.querySelector('.app-container').inert = true;

// 关闭时
document.querySelector('.app-container').inert = false;
lastFocused?.focus();
```

`inert` 属性现代浏览器全支持，是这里最省事的方案。

### P1-7 触屏设备上卡片操作按钮永久不可达

`css/views/browser.css:280-283`

```css
.card-actions-overlay { opacity: 0; }
.media-card:hover .card-actions-overlay,
.media-card.tagged .card-actions-overlay { opacity: 1; }
```

**触屏没有 hover**。在手机/平板上，收藏、阅读状态、删除三个按钮永远不会显现（除非卡片已被标记 `.tagged`）。而项目有完整的移动端断点（`responsive.css` 到 767px），说明移动端是目标场景。

修复：

```css
@media (hover: none) {
    .card-actions-overlay { opacity: 1; }   /* 触屏常显，或降低不透明度 */
}
```

### P1-8 Toast 无无障碍声明，且无堆叠上限

`js/toast.js`（全文 16 行）

```js
toast.className = `toast ${type}`;
container.appendChild(toast);
setTimeout(() => { /* 3s 后移除 */ }, 3000);
```

两个问题：

1. **无 `aria-live`** —— 屏幕阅读器完全感知不到操作反馈（「文件删除成功」「扫描启动失败」全部静默）。`index.html:401` 的 `.toast-container` 应加 `role="status" aria-live="polite"`（错误类用 `aria-live="assertive"`）。
2. **无数量限制** —— 连点「立即扫描媒体」会瞬间堆出十几条 toast，`position: fixed; bottom: 24px; right: 24px` 的容器会向上溢出视口。建议限制最多 3 条，超出时移除最旧的。

顺带：`toast.style.opacity = '0'` 用内联样式做退场，而 `components.css:316` 已有 `@keyframes slideIn` 入场动画却没有对应的出场 keyframes，退场只有 `opacity` 变化没有位移，视觉上略显生硬。

---

## P2 — 一致性与可维护性

### P2-1 死 CSS：标签管理整块（约 110 行）

`css/views/browser.css:318-429` 的标签管理样式，在 `index.html` 和全部 JS 中**零引用**（已用 grep 逐一验证）：

```
tag-manager-container   引用数 0     tag-chip            引用数 0
tag-creator-card        引用数 0     btn-tag-delete      引用数 0
tag-list-card           引用数 0     color-picker-grid   引用数 0
tags-container          引用数 0     color-dot           引用数 0
```

另外 `components.css:55-73` 的 `.btn-secondary` / `.btn-danger`、`components.css:78-91` 的 `.btn-transcode` 也是 0 引用（`.btn-secondary` 注释里自己写着 "Unused in markup today; kept per class-name contract"）。

这一块还携带了旧设计系统的残留色（`rgba(124, 58, 237, 0.2)` 紫色聚焦环、`rgba(239, 68, 68, ...)` 硬编码红），与 Ink Editorial 的苔绿 `--accent: #3D6B4F` 直接冲突。**如果确认前端不再提供标签管理 UI，建议整块删除**；如果只是暂时下线，至少应把紫色残留换成 token。

### P2-2 `--font-mono` 从未定义

三处引用，零处定义（已全量扫描验证）：

```
css/views/dashboard.css:118    font-family: var(--font-mono, monospace);
css/views/settings.css:26      font-family: var(--font-mono, monospace);
css/views/settings.css:33      font-family: var(--font-mono, monospace);
```

`themes.css` 定义了 `--font-display` / `--font-sans`，漏了 `--font-mono`。目前靠 fallback 侥幸工作，但 `themes.css` 里 7 套主题的字体策略无法覆盖它。建议在 `:root` 补一行：

```css
--font-mono: ui-monospace, "SF Mono", "Cascadia Mono", Consolas, "Noto Sans Mono CJK SC", monospace;
```

### P2-3 `browser.css` 的 `.form-group` 块实际不生效（且带紫色残留）

`css/views/browser.css:340-368` 定义了一整套 `.form-group input/textarea` 样式（含 `background: rgba(0,0,0,0.3)` 深色底和 `rgba(124, 58, 237, 0.2)` 紫色聚焦环）。但 `components.css:270-289` 用更高优先级的选择器覆盖了它：

```css
/* components.css —— specificity (0,3,1) */
.form-group input:not([type="checkbox"]):not([type="radio"]) { background: var(--surface-card); ... }
```

两层加载顺序是 `components.css` → `views/browser.css`，但 components 的 `:not()` 链把 specificity 抬到 (0,3,1)，稳赢 browser 的 (0,1,1)。

所以 browser 这一块**唯一真正命中的是 `.form-group input[type="checkbox"]` 和 `[type="radio"]`** —— 而 `settings.js:67` 注入的全局主题 radio 在 `settings.css:62-66` 被设为 `opacity: 0`，看不见。**结论：这一整块是失效的遗留代码**，建议删除或改为只服务 checkbox/radio 的显式规则。

### P2-4 `apiRequest` 不处理 204 / 无超时

`js/api.js:6-33`

```js
const res = await fetch(url, finalOptions);
...
return res.json();   // ← 无条件解析 JSON
```

后端 `handler/tags.go:47` 明确返回 `c.NoContent(http.StatusNoContent)`。任何走 `apiRequest` 调该端点的调用都会抛 `SyntaxError: Unexpected end of JSON input` —— 错误信息完全误导（看起来像解析失败，实际是成功响应）。

```js
if (res.status === 204) return null;
const ct = res.headers.get('content-type') || '';
if (!ct.includes('application/json')) return null;
return res.json();
```

另外**没有任何超时控制**。局域网断连或服务端卡住时，`fetch` 会一直 pending，UI 停在「正在读取目录结构...」且无任何提示。建议统一加 `AbortSignal.timeout(15000)`，并区分超时错误文案。

### P2-5 主题映射在两处重复硬编码

`boot.js:8-12` 和 `app.js:26-34` 各自维护一份 `THEME_KEY → css-theme` 映射：

```js
// boot.js
var MAP = { DAY: 'day', DAY_BRIGHT: 'day_bright', ... NIGHT_BLACK: 'night_black' };
// app.js
const themeMap = { 'DAY': 'day', 'DAY_BRIGHT': 'day_bright', ... };
```

`boot.js` 必须在 `<head>` 同步执行（防 FOUC），所以不能直接 import 模块 —— 但可以让 `boot.js` 只读 `document.documentElement.dataset.theme` 的映射，或把映射表下沉为 `themeMap.json` 由两边共享。当前状态是**新增主题时容易只改一处**（`theme-parity.test.mjs` 守护的是色值，不守护这个映射）。

同理，`boot.js:18` 和 `app.js:23` 的 `prefers-color-scheme` 判断也是重复的。

### P2-6 `handleRoute` 用 6 个位置参数

`js/router.js:16`

```js
export function handleRoute(elements, renderDashboard, loadRoots, browsePath, renderBookmarks, renderSettings)
```

`app.js` 里有 3 处调用（`L64` / `L93` / `L98`），每次都原样重复这 6 个参数：

```js
handleRoute(elements, renderDashboard, loadRoots, browsePath, renderBookmarks, renderSettings);
```

`router.js` 自己已经 `import { renderTextReader }` / `renderBookshelf` / `loadSystemDrives` 了，说明依赖注入做了一半。建议统一成 router 内部 import（去掉 6 个参数），或封装成 `export function navigate()` 让调用方零参数。

顺带：`router.js` 里 `elements.xxx` 全部做了 null 检查（约 20 处 `if (elements.pageTitle)`），而 `settings.js:91` / `lightbox.js:123` / `dashboard.js:88` 直接 `.addEventListener` 不做检查。**风格不统一**，且后者的风险更实际：`setupEventListeners`（`app.js:108`）里任何一个模块抛错，**后续所有模块的监听器注册全部中断**，页面表现为「某些按钮完全没反应」且无报错提示。

### P2-7 `state.js` 缺少已使用的字段声明

`settings.js:22-23` 写入两个字段，`state.js` 里没有声明：

```js
state.enableDelete = !!(data.system && data.system.enable_delete);
state.thumbMax = (data.thumbnail && data.thumbnail.max_size) || 300;
```

而 `browserView.js:297` / `401` 用 `state.enableDelete` 决定是否渲染删除按钮 —— 在 `loadConfig()` 失败（网络异常）时它是 `undefined`，行为退化为「删除按钮消失」而不报错。方向上是保守的，但**依赖未声明字段**会让 IDE 补全和后续维护都失准。建议在 `state.js` 里显式声明 `enableDelete: false, thumbMax: 300`。

### P2-8 `escapeHtml` 放在 `api.js`

`js/api.js:73-81` 定义了 `escapeHtml`，被 `browserView.js` / `dashboard.js` / `settings.js` / `lightbox.js` 等 6+ 个模块 `import { escapeHtml } from './api.js'`。`api.js` 的职责是 HTTP 请求封装，安全工具函数放在这里语义错位。`utils.js` 已经是「纯工具函数」的归属地，建议迁移（迁移时同步改 import）。

### P2-9 其他细节

| 位置 | 问题 | 建议 |
|---|---|---|
| `index.html:368` | `<img id="lightbox-img" src="" alt="预览">` 空 `src` | 用 `removeAttribute('src')`，`lightbox.js:103/125/193` 同 |
| `browserView.js:379` | `.card-thumb` 类在 CSS 中零定义，纯靠 `.card-preview img` 命中 | 删除死类名，或补上显式规则 |
| `browserView.js:552` | `img.style.display = 'none'` 内联样式，与项目「CSP 下改用 class」的约定不一致 | 换成 `.card-thumb--failed` 类 |
| `index.html` head | 缺 `<meta name="theme-color">` | 补上，移动端浏览器 UI 会跟随主题色 |
| `index.html` body | 缺 skip-link（「跳到主内容」） | 键盘用户需要跳过 5 项侧边栏导航 |
| `index.html:97` | 主题切换按钮缺 `aria-pressed` | 双态按钮应暴露当前状态 |
| `index.html:245` | `<label>应用全局主题</label>` 无 `for`，未与 radio 组关联 | 用 `<fieldset><legend>` 语义 |
| `index.html:317` | 关闭按钮用文本 `✕` 而非 SVG，与其他图标不一致 | 统一为 SVG |
| `delete.js:16/61` | 用原生 `confirm()` 做不可逆操作确认 | 样式不可控、无法主题化；建议换 `<dialog>` |
| `settings.js:47-52` | 用 `el.style.color = 'var(--error)'` 设内联样式 | 与 CSP-safe 的 class 方案不一致，建议 `.is-danger` 类 |
| `utils.js:20` | `safeBtoa` 使用已废弃的 `unescape()` | 换 `TextEncoder` + `btoa` |
| `api.js:42-63` | `chapterCache` 命名是 LRU 但 `has()` 命中不更新顺序，实为 FIFO | 命中时 `delete` + `set` 重排，或改名 |
| `css/views/video.css:35` | `rgba(15, 11, 25, 0.65)` 带紫色调，与墨色体系不符 | 改为中性黑 `rgba(10,10,10,0.65)` |
| 全局 | 5 处 `@media` 中 `responsive.css` 用了 16 处 `!important` | 后续可用 `@layer` 重构优先级，去掉 `!important` |
| 全局 | 无 `@container`，组件复用受视口宽度限制 | 卡片/阅读器面板可改容器查询 |

---

## 建议的修复顺序

按「影响面 ÷ 改动成本」排序：

**第一批（改动小、收益立竿见影）**

1. P0-1 滚动条颜色 → 2 行 CSS
2. P0-3 dashboard 空状态嵌套 → 2 行 JS
3. P0-4 统计卡分隔线断点 → 3 行 CSS（加 `@media`）
4. P2-2 补 `--font-mono` → 1 行 CSS
5. P1-8 toast 加 `aria-live` + 上限 → 约 8 行

**第二批（需要小规模重构）**

6. P0-2 browser.css 硬编码颜色 → 逐条替换为 token（约 20 处）
7. P1-1 hls.js 动态导入 → 约 15 行，省 415 KB
8. P1-4 dashboard 请求合并 → 约 10 行
9. P1-7 触屏 hover 兜底 → 4 行 CSS
10. P2-1 删除死 CSS → 减约 130 行

**第三批（架构层，建议单独立项）**

11. P1-2 静态资源缓存策略 + ETag（服务端改动）
12. P1-3 首屏请求数优化（CSS 合并 / 路由级代码分割 / preload）
13. P1-5 + P1-6 键盘可达性与焦点管理
14. P2-6 `handleRoute` 参数收敛 + 统一空值保护策略

---

## 附：复现验证命令

```bash
# 1. 启动服务
cd server && ./LocalMediaHub.exe --headless

# 2. 验证静态资源无缓存头（应看到 200 而非 304，且无 Cache-Control/ETag）
curl -s -D - -o /dev/null http://127.0.0.1:8000/app.js
curl -s -o /dev/null -w "%{http_code}\n" \
  -H 'If-Modified-Since: Wed, 30 Sep 2026 09:00:00 GMT' \
  http://127.0.0.1:8000/app.js

# 3. 验证 HEAD 不被支持
curl -sI http://127.0.0.1:8000/          # 405 Method Not Allowed

# 4. 统计 hls.js 体积占比
du -b server/internal/web/vendor/hls.min.js          # 415253
du -cb server/internal/web/*.js | tail -1            # 265514

# 5. 验证死 CSS 类（应输出 0）
for c in tag-manager-container tag-chip color-dot btn-danger; do
  echo -n "$c: "; grep -rl "$c" server/internal/web/index.html server/internal/web/*.js | wc -l
done

# 6. 验证 --font-mono 未定义
grep -rn -- '--font-mono' server/internal/web/css/ | grep -v 'var('   # 无输出

# 7. 前端测试套件（审查时基线：201 个测试全部通过）
cd server/internal/web && npm test
```

---

## 附：与现有测试的关系

审查时基线为 **201 个测试全部通过**（`node --test`，耗时约 6.5s）。现有测试覆盖的是**行为契约**（`library.test.mjs` 28.9 KB / `textReader.test.mjs` 27.5 KB 是主体），不覆盖视觉与性能。

这意味着本报告中 **P0-1 / P0-2 / P0-4 这类纯 CSS 缺陷、以及 P1-1 / P1-2 / P1-3 这类性能问题，现有测试完全无法发现** —— 它们不会因修复而变红，也不会因回归而报警。

若采纳建议，可考虑补两类守护：

1. **CSS token 守卫**（扩展 `theme-parity.test.mjs` 的思路）：扫描 `css/**`，断言亮色主题下不出现 `rgba(255,255,255,*)` 作为前景/背景色、断言所有 `var(--x)` 的 `--x` 都在 `themes.css` 中有定义（可直接捕获 P2-2 的 `--font-mono`）。
2. **死 CSS 守卫**：收集 `css/**` 中的类选择器，与 `index.html` + `*.js` 的字符串做差集，超出阈值即失败（可捕获 P2-1）。

第 1 条尤其值得做 —— 它把「亮色主题可用性」从人工走查变成 CI 门禁，而当前项目恰恰在这一点上积累了 20+ 处硬编码。

---

# 附二：修复与验证记录（2026-09-30）

## 已修复项

| 编号 | 内容 | 落点 |
|---|---|---|
| P0-1 | 滚动条滑块 4 套亮色主题下不可见 | `css/base.css` 改用 `--text-muted` |
| P0-3 | dashboard 空状态双层 `.empty-state` 嵌套 | `dashboard.js` 改为单层 |
| P0-4 | 窄屏 `.stat-card` 左侧分隔线悬空 | `css/views/dashboard.css` 加 1023px 断点 |
| P1-1 | 首屏无条件下载 415 KB hls.js | `videoPlayer.js` 改为按需注入 script |
| P1-4 | dashboard 重复请求 `/api/v1/videos` | 合并为单次，5 → 4 请求 |
| P1-5 | 媒体卡片键盘不可达、aria-label 无区分度 | `browserView.js` / `bookshelf.js` 补 `role`/`tabindex`/具名 label + 委托 Enter 处理 |
| P1-6 | 关闭浮层后焦点丢失、`src=""` 触发多余请求 | 新增 `modalFocus.js`（`inert` 冻结背景 + 焦点还原） |
| P1-8 | toast 无退场动画、`showToast` 无上限 | `toast.js` 重写，最多 3 条 + `.toast--leaving` |
| P2-1 | 死 CSS（标签管理整块 ~110 行、`.btn-danger` 等） | 删除；`.btn-secondary` 实际在用，保留并更正注释 |
| P2-2 | `--font-mono` 被引用 3 次、定义 0 次 | `themes.css` 补定义 |
| P2-3 | `.card-preview` / `.card-badge` 亮色主题失效 | 换 `--surface-hover` |
| P2-6 | 单模块抛错会静默中断后续所有监听器注册 | `app.js` 包 `safeSetup` |

## 修复过程中新发现并处理的问题

1. **`.card-action-btn` 在 night 主题几乎不可见**（由 P0-2 误判牵出，详见上文更正）：
   图标色 `--text-muted` 在 `rgba(0,0,0,0.7)` 上约 3:1，hover 色 `--text-on-accent` 在 night 主题
   为 `#0F172A` 深底深字完全不可见。改为浮层作用域固定浅色。

2. **自己引入的 CSS 源序缺陷（已修）**：为触屏补的 `@media (hover: none)` 块最初写在
   `.card-action-btn { width: 28px }` **之前**。媒体查询不提升 specificity，同权重靠源序决胜，
   因此 `36px` 被后置的 `28px` 覆盖，整块实际失效。已把媒体查询移到该区段所有基础规则之后
   （现位于 `browser.css` 354 行，基础规则在 291 / 310 / 339 行）。

   **这条值得记住**：媒体查询必须排在它所覆盖的基础规则之后，否则静默失效 ——
   而这类失效在「桌面宽屏 + 有鼠标」的开发环境下**永远不会被肉眼发现**。

## 验证方式与结果

用 CDP 驱动无头 Chrome 对 `127.0.0.1:8000` 做真实渲染验证（真实数据：451 texts / 394 videos / 29953 images）。

| 断言 | 结果 |
|---|---|
| 首屏加载 hls.min.js | `false`（415 KB 不再无条件下载） |
| 首屏静态请求 | 1 HTML + 13 CSS + 35 JS = 49（改动前 50，含 hls） |
| `/api/v1/videos` 命中次数 | `1` |
| 滚动条滑块颜色 | day `rgb(138,143,152)` / night `rgb(116,120,127)`，均等于 `--text-muted` |
| `--font-mono` 计算值 | `ui-monospace, "SF Mono", "Cascadia Mono"` |
| 卡片 `role`/`tabindex`/可聚焦 | 均 `true`，1 次 Tab 可达 |
| 卡片 aria-label | 具名且互不重复（170 张卡全通过） |
| 键盘 Enter 激活卡片 | `hashChanged: true` |
| 窄屏 `.stat-card` 左边框 | `["0px","0px","0px"]` |
| 触屏浮层常显 | `opacity: 1`（桌面 `0`，撤回模拟后恢复 `0`） |
| 触屏按钮尺寸 | `[36,36]`（桌面 `[28,28]`） |
| 页面异常 / 控制台错误 | `[]` / `[]` |

测试套件：`node --test` **203/203 通过**（基线 201，新增 `css-tokens.test.mjs` 2 个守卫）；
`go test ./internal/server/...` 全部 `ok`。

## 验证方法学上的两个坑（供后续复用）

1. **`Emulation.setDeviceMetricsOverride({ mobile: true })` 不会翻转 `(hover: none)` / `(pointer: coarse)`。**
   第二轮据此测得的 `touchOverlayOpacity: "0"` 是**假阴性**。必须显式调用
   `Emulation.setEmulatedMedia({ features: [{ name: 'hover', value: 'none' }, { name: 'pointer', value: 'coarse' }] })`，
   并输出 `matchMedia('(hover: none)').matches` 自证模拟生效。

2. **断言写窄会把正确实现判成失败。** 第二轮 `delBtnAllLabeled: false` 的原因是断言只放行
   `/^删除文件 .+/`，而该目录下 170 张卡**全部是文件夹**，标签是 `删除文件夹 X`。
   修正为 `/^删除(文件|文件夹) .+/` 后通过（`delBtnLabelPrefixes: ["删除文件夹"]`，标签互不重复）。
   → 断言应校验**语义模式**（有区分度、非空、带名称），而非某一条具体文案。

## 仍未处理（留待后续）

- **P1-2 静态资源零缓存**：`embed.FS` 的 `ModTime()` 为零值，`http.ServeContent` 既不发
  `Last-Modified` 也不发 `ETag`，`curl -H 'If-Modified-Since: …' /app.js` 实测返回 **200 而非 304**，
  且无 `Cache-Control`。根因在 `server.go:296` 的 `http.FileServer(http.FS(web.Assets))`。
  需服务端改造（`serveFavicon` 在 `server.go:497` 已设 `max-age=604800`，可参照）。
- **`HEAD /` 返回 405**（`Allow: OPTIONS, GET`）。
- **首屏 49 个请求**：HTTP/1.1 无 h2c，受 6 连接上限约束，建议合并 CSS / 路由级代码分割。
- P2-4 `apiRequest` 未处理 204（`tags.go:47` 返回 `NoContent` 时 `res.json()` 会抛）且无超时。
- P2-5 `boot.js` 与 `app.js` 各存一份主题映射。
- P2-7 `state.enableDelete` / `state.thumbMax` 未在 `state.js` 声明。
- P2-8 `escapeHtml` 位置错放在 `api.js`。

> 注意：`css/` 与 `*.js` 经 `//go:embed` 编入二进制，**改动后必须重新 `go build` 才会生效**，
> 直接改磁盘文件对已运行的进程无效。

---

# 附三：剩余项处理（2026-09-30，第二批）

上一批遗留的 7 项已全部处理。以下为改动、实测数据与新发现。

## 服务端：静态资源改为自建 handler

新增 `server/internal/web/static.go`（+ `static_test.go`，7 个 Go 测试），替换
`server.go:296` 原来的 `http.FileServer(http.FS(web.Assets))`。

### P1-2 缓存与 ETag（已修）

`embed.FS` 的 `ModTime()` 是零值 → `http.ServeContent` 既不写 `Last-Modified` 也不生成
`ETag`。现在改为**启动时对每个嵌入文件算 SHA-256**，取前 16 字节作强 ETag。

| 探测 | 修复前 | 修复后 |
|---|---|---|
| `HEAD /` | **405**（`Allow: OPTIONS, GET`） | **200**，带 `ETag` / `Content-Length` |
| `GET /app.js` + `If-None-Match` | **200**（无验证器可比） | **304，0 字节** |
| `Cache-Control` | 无 | `no-cache`（+ `Vary: Accept-Encoding`） |

浏览器实测二次导航：**47 个请求里 36 个是 304**，只有 8 个 API 调用和主文档是 200。

**为什么用 `no-cache` 而不是长 `max-age`**：资源 URL 上没有构建指纹，长缓存会在服务端升级后
继续提供旧 JS/CSS。`no-cache` = 「复用前先验证」，配合强 ETag 得到空 304，正确性与带宽兼得。
要做到完全不可变，需要把整个 ES 模块图的 import 说明符都改写成带版本号的 URL —— 那等于引入
构建流水线，与本项目「无构建步骤」的取向冲突，故不做。

### HEAD 405（已修）

Echo 的 `GET` 不隐含 `HEAD`，原路由只注册了 `GET /*`。现显式注册
`GET /*` + `HEAD /*`（`/favicon.ico` 同理）。`server.go` 中一行注释说明了原因。

### P1-3 首屏请求收敛（已修）

1. **CSS 打包**：启动时按 index.html 的 `<link>` 顺序拼接为 `/css/bundle.css`，
   并把文档改写成只引用这一个。级联顺序 = 文档顺序 = 拼接顺序，因此不变；bundle 放在
   `css/` 同层深度，相对 `url()` 解析路径不变（现网所有 `url()` 都是绝对的 `/fonts/…`，
   但保持深度可在未来变更时继续安全）。段间插入 `/* ===== css/xxx.css ===== */` 注释，
   DevTools 仍能看出规则来自哪个文件。
2. **modulepreload**：启动时从 `app.js` 出发 BFS 静态 import 图，为全部 33 个模块注入
   `<link rel="modulepreload">`。ES 模块是「取到一个才知道它 import 谁」的瀑布，
   preload 让浏览器在解析 HTML 时就并发拉全部模块。`modulepreload` 会填充 module map，
   因此**不产生重复请求**（实测 `duplicateJsRequests: 0`）。

| 指标 | 修复前 | 修复后 |
|---|---|---|
| 首屏 CSS 请求 | 13 | **1** |
| 首屏 JS 请求 | 35 | 35（提前并发，不新增） |
| 首屏静态请求合计 | 49 | **37** |
| 首屏总请求（含 API） | 57 | **45** |

动态 `import()` 刻意**不**匹配（避免把懒加载拉进首屏）；`vendor/hls.min.js` 不在 import 图中，
因此 P1-1 的懒加载不受影响（实测 `firstLoadHls: false`）。

## 前端 JS（4 项，已修）

| 编号 | 改动 |
|---|---|
| P2-4 | `apiRequest` 对 204/205 直接 `return null`；加 `AbortController` 超时（默认 30s，可用 `options.timeoutMs` 覆盖，调用方自带 `signal` 时不被劫持）。顺带把 `new CustomEvent(...)` 改为 `new window.CustomEvent(...)` |
| P2-5 | `boot.js` 与 `app.js` 的 7 项主题映射表**双双删除** |
| P2-7 | `state.enableDelete` / `state.thumbMax` 在 `state.js` 显式声明 |
| P2-8 | `escapeHtml` 迁到 `utils.js`，4 个引用方同步更新，不在 `api.js` 留兼容再导出 |

### P2-5 的意外简化：那张映射表本身就是冗余

7 个 preset 到 CSS 主题名的映射，逐条核对后是**纯粹的小写转换**：

```
DAY → day    DAY_BRIGHT → day_bright    EYE_CARE → eye_care
EYE_CARE_GREEN → eye_care_green    PARCHMENT → parchment
NIGHT → night    NIGHT_BLACK → night_black
```

所以两处 7 项表都可以直接换成 `String(key).toLowerCase()`，不需要共享模块、不需要构建步骤、
不增加请求。安全性来自 `themes.css` 里 `:root, [data-theme="day"]` —— 未知值会落到 `:root`
的日间配色，而不是变成无样式页面。`themeMap.test.mjs` 守住这个前提。

### P2-4 的一个隐藏问题

加测试时发现 `api.js` 用的是裸 `CustomEvent`。浏览器里它等价于 `window.CustomEvent`，
但在 jsdom 下裸全局是 Node 的类，`dispatchEvent` 会拒绝它（`parameter 1 is not of type 'Event'`）。
这是既有代码的潜在可测性缺陷（此前没有测试覆盖 401 路径所以未暴露），已改为 `window.CustomEvent`。

## 实测验证（无头 Chrome，真实数据）

| 断言 | 结果 |
|---|---|
| 204 端到端：POST 建标签 → DELETE | `deleteThrew: null`，`deleteResolvedToNull: true`（修复前抛 SyntaxError） |
| 删除不存在的标签 | `"tag not found"`（错误文案仍正确透出） |
| 7 个 preset 的 `data-theme` | boot.js（pre-paint）与 app.js **逐一相等**，且等于小写 preset |
| `AUTO` + 深色偏好 / 浅色偏好 | `night` / `day` |
| 首屏 CSS / 静态请求 | 1 / 37（原 13 / 49） |
| modulepreload 造成的重复请求 | 0 |
| 卡片标题转义、具名 aria-label | 通过 |
| 键盘 Enter 激活卡片 | `hashChanged: true` |
| `state.enableDelete` | `true`（`boolean`，配置加载后） |
| 页面异常 / 控制台错误 | `[]` / `[]` |

测试套件：`node --test` **216/216 通过**（本批新增 `themeMap.test.mjs` 4 个 +
`api.test.mjs` 扩充到 12 个）；`go test ./internal/web/` **7/7 通过**。

> `go test ./internal/service/` 有 2 个 symlink 测试失败（`TestResolveWithinRootsRejectsSymlinkEscape`
> / `…InRootSymlink`）。已用 `git stash` 在干净基线上复现，**属既有失败**，与本次改动无关 ——
> 原因是 Windows 下创建符号链接需要开发者模式/管理员权限，测试里 `os.Symlink` 实际没建成，
> 于是「逃逸」不会发生。非本批引入。

## 新发现（未处理，留待决策）

1. **`fonts/NotoSerifSC-Regular.woff2` = 16.7 MB**，`LXGWWenKai-Regular.woff2` = 8.0 MB，
   两者合计约 24 MB 且**被编入二进制**。它们只在阅读器选用对应字体时才拉取
   （`font-display: swap`，且首屏请求列表里不含字体，实测确认），因此不影响首屏；
   但用户在阅读器切到「思源宋体」时要下载 16.7 MB。根治需做 CJK 字体子集化，
   属独立的字体工程任务，且会改动字形覆盖范围，未擅自处理。
2. **`HEAD /favicon.ico`** 现在返回 200，但 `serveFavicon` 未设 ETag，故带
   `If-None-Match` 仍返回 200（它靠 `max-age=604800` 避免重新请求，行为自洽）。
   若要统一验证器语义可补 ETag。
