# 双端 UI 打磨与对齐 — 设计 spec

日期：2026-09-28
状态：已确认（brainstorming 对齐：范围=收敛+守护+aria；标签=Android 圆点风格；方向=Android 向 Web token 真值对齐；守护=Web 单向；aria=关键控件）
前置：`2026-09-27-ink-editorial-redesign-design.md`（已全部合并，视觉真值 = Web `themes.css`）

## 1. 目标与非目标

**目标**：Ink Editorial 合并后的收尾——
- 双端 chrome 主题 token 逐字对齐（Android 向 Web 靠）
- 阅读主题 hex / chrome token / 主题标签三者的跨端对称性由自动化测试守护
- Web 主题标签文案双端统一（Android 圆点风格）
- Web 关键控件 aria 补齐
- 「无 inline style」纳入守护测试

**非目标**：
- 视觉重设计（色值真值已由 2026-09-27 spec 确定，本轮只做对齐）
- 交互/数据流改动、新依赖、Android UI 结构改动
- `surfaceVariant` / `primaryContainer` / `secondaryContainer` 等无明确双端映射字段的全面对齐（守护表外字段允许双端各自演化）
- 全量 a11y 审计（焦点管理/键盘导航/对比度不在本轮范围）

## 2. Chrome token 收敛（Android 向 Web）

真值源：`server/internal/web/css/themes.css` 的 `[data-theme]` 块。
改动面：`android/.../ui/theme/Theme.kt`、`ui/theme/ColorTokens.kt`。

### 2.1 守护映射表

| Web token | Android ColorScheme 字段 |
|---|---|
| `--action-ink` | `primary` |
| `--accent` | `secondary` |
| `--accent-hover` | `tertiary` |
| `--error` | `error` |
| `--surface-app` | `background` |
| `--surface-card` | `surface` |
| `--border-subtle` | `outline` |
| `--text-primary` | `onBackground` |

另：`ColorTokens.kt` 的 `PrimaryText.*` ↔ Web `--accent-text`。

### 2.2 收敛数值表（目标值 = Web 现值；仅列改动字段）

| Scheme | primary（←--action-ink） | secondary（←--accent） | tertiary（←--accent-hover） | error（←--error） | onError |
|---|---|---|---|---|---|
| Light | **#0A0A0A**（原 #26282E） | #3D6B4F ✓ | #2F5640 ✓ | **#DC2626** | #FFFFFF |
| DayBright（新增） | #0A0A0A | #3D6B4F | #2F5640 | #DC2626 | #FFFFFF |
| EyeCare | **#26211A**（原 #4A6B52） | **#4A6B52**（原 #3C5843） | **#3C5843**（原 #6B5E48） | **#C0392B** | #FFFFFF |
| EyeCareGreen | **#17211A**（原 #33593C） | **#33593C**（原 #2A4831） | **#2A4831**（原 #48584A） | **#C0392B** | #FFFFFF |
| Parchment | **#241D14**（原 #4E6B44） | **#4E6B44**（原 #3F5837） | **#3F5837**（原 #75654F） | **#C0392B** | #FFFFFF |
| Dark | #E7E9EE ✓ | #8FBF9F ✓ | #A3CDAF ✓ | **#F87171** | #0F172A |
| NightBlack | #E7E9EE ✓ | **#8FBF9F**（原 #9BA1AC） | **#A3CDAF**（原 #74787F） | **#F87171** | #0F172A |

已对齐且保持不动的字段：`background` / `surface` / `outline` / `onBackground`（六 scheme 现值均与 Web 一致）。
`onError` 取值：浅红底（#DC2626 / #C0392B）→ White（对比 ≥4.5:1）；#F87171 底 → #0F172A。
`PrimaryText.NightBlack`：#E7E9EE → **#A3CDAF**（对齐 night_black `--accent-text`）。

### 2.3 新增 DayBrightColorScheme

Android 现把 `DAY_BRIGHT` 映射到 LightColorScheme（`Theme.kt` 的 `"DAY", "DAY_BRIGHT" -> Light`），
Web `day_bright` 是纯白变体。新建 scheme（其余字段抄 Light）：
`background=#FFFFFF`、`surface=#FFFFFF`、`outline=#E7E7E2`（--border-subtle）、`primaryContainer` 沿用 Light。
`Theme.kt` 分支拆为 `"DAY" -> Light` / `"DAY_BRIGHT" -> DayBright`。

**视觉影响**：day 与纸质主题下 primary 驱动的组件（主按钮 / FilterChip 选中态 / FAB）由苔绿或
#26282E 变墨黑 ink——即 2026-09-27 spec §3.1 的 token 语义（主操作=墨黑、苔绿=accent）。

## 3. Reader 主题 hex 守护（无代码改动，防漂移）

现状：`readerPrefs.js` `THEME_PRESETS` 与 `ReaderSettings.kt` `ReaderTheme` enum 的 hex 逐字一致
（2026-09-27 Phase 4 对齐，纯人工维护）。守护测试解析两份源文件，断言 7 个预设
（DAY / DAY_BRIGHT / EYE_CARE / EYE_CARE_GREEN / PARCHMENT / NIGHT / NIGHT_BLACK）× 6 字段
（bg / fg / chromeBg / chromeFg / muted / border）逐字相同。AUTO 与 CUSTOM 不携带颜色，跳过。

## 4. THEME_LABELS 收敛（Web 改向 Android 圆点风格）

- `readerPrefs.THEME_LABELS` 改为：
  `日间·纸白 / 日间·亮白 / 护眼·米黄 / 护眼·豆沙绿 / 羊皮纸 / 夜间·深空 / 夜间·纯黑 / 跟随系统`
  （保持 `Object.freeze` 与字面量值，XSS-SAFE 豁免前提不变）
- `reader-settings.js` 若有独立主题标签表（含 CUSTOM），同步为「自定义」（plan 阶段审计确认）
- 守护：解析 Kotlin enum 的 `label` 字符串与 Web `THEME_LABELS` 断言一致。
  Android 独有的 `CUSTOM` 在 Web `THEME_LABELS` 无对应 key——守护表显式豁免并注明理由
  （Web 的 settings 网格不展示 CUSTOM；若 reader-settings.js 有独立表则要求其含「自定义」）

## 5. 守护测试形态（Web 单向）

新文件 `server/internal/web/theme-parity.test.mjs`（node:test + jsdom 环境外，纯文件解析，无需 jsdom）：

1. **reader hex 对称**：解析 `readerPrefs.js`（import 后读 `THEME_PRESETS`）↔ 正则解析
   `../../../android/app/src/main/java/com/juziss/localmediahub/data/ReaderSettings.kt` 的
   `Color(0xFFxxxxxx)` 字面量。解析失败（enum 项数/字段数不符预期）→ fail，不 skip。
2. **chrome token 对称**：解析 `themes.css` 各 `[data-theme]` 块的 `--token: #hex` ↔
   正则解析 `Theme.kt` 各 scheme 的 `Color(0xFFxxxxxx)`，按 §2.1 映射表断言 8 字段 × 7 主题。
3. **label 对称**：§4。
4. **无 inline style 守护**：扫描 `server/internal/web/`（排除 `.test.mjs` 与 `vendor/`）断言
   不出现 `style="` HTML 属性（CSSOM 动态赋值 `el.style.prop =` 不在此列）。

路径耦合说明：测试以仓库内相对路径读 Android 源文件——CI/本地均在 monorepo 内运行
（`node --test` 从 `server/internal/web` 启动），路径稳定。文件缺失 → fail 并给出可操作错误信息。

Android 侧不新增跨端测试；现有 `ThemeColorSchemeTest` 等断言本轮同步更新（新色值 + DayBright）。

## 6. Web aria 补齐（关键控件，不动行为）

审计并补齐四类（具体控件清单在 plan 阶段逐文件审计产出）：

| 类别 | 要求 |
|---|---|
| icon-only 按钮 | `aria-label`（中文文案与可见 tooltip 一致） |
| dialog | `role="dialog"` + `aria-modal="true"` + `aria-labelledby` 指向标题元素 |
| 进度条 | `role="progressbar"` + `aria-valuenow`（可行时补 min/max） |
| 侧栏导航 | 活动项 `aria-current="page"` |

改动 DOM 构造处遵守 `// XSS-SAFE:` 注释规则（xsscheck 阻断）。静态属性优先直接写
`index.html`；动态渲染的走既有 innerHTML 模板并保持转义/冻结常量前提。

## 7. 提交切分（每 task 一 commit，Conventional Commits）

1. `feat(android): align color schemes with web ink tokens and add parity guard tests`
   —— Theme.kt / ColorTokens.kt 收敛 + DayBrightColorScheme + `theme-parity.test.mjs`
   （reader hex + chrome token 对称断言）+ Android 单测断言更新（红→绿同 task 完成）
2. `feat(web): unify theme labels with android dot style`
   —— THEME_LABELS 圆点化 + reader-settings 标签同步 + label 对称守护断言
3. `test(web): inline-style guard and xss-safe audit`
   —— 无 inline style 守护断言 + 全量 xsscheck 复核（如有漏注释则补）
4. `feat(web): aria labels for key controls`
   —— 四类控件补齐（审计清单随 task 附在 plan）
5. `docs: sync handbook for ui polish alignment`
   —— AGENTS.md 模块地图措辞（守护测试入口、token 收敛约定）

## 8. 验收

- `cd server/internal/web && node --test` 全绿（含 `theme-parity.test.mjs`）
- `cd tools/xsscheck && go run . ../../server/internal/web` 通过
- `cd android && ./gradlew testDebugUnitTest assembleDebug` 通过
- 人工抽查：Android 纸质主题 / DAY_BRIGHT 下 primary 组件观感；Web 标签渲染

## 9. 风险与回滚

- primary 语义变化影响 Android 纸质主题观感——真值驱动，如需微调改 `themes.css` 单点并让守护测试同步红；
- Kotlin/CSS 源码解析正则脆弱——enum/块格式规整，解析失败 fail-fast（不静默 skip），格式变更时测试会立即暴露；
- 守护表外字段（surfaceVariant 等）仍允许双端各自演化——防止守护范围意外扩大；
- 回滚：每 task 独立 commit，revert 单 task 即可。
