# Task 3 Report: Web THEME_LABELS 冻结常量（M-10）

**Status:** DONE
**Commit:** `0623fc74c3262abe5cbe7a218376ba78e889660c` — `fix(web): export frozen THEME_LABELS for settings theme grid (Phase 9)`

## 做了什么

按 task-3-brief.md 的 TDD 流程（Step 1-5）完成，代码与 commit message 均按 brief 原文使用：

1. **Step 1（写失败测试）** — `server/internal/web/readerPrefs.test.mjs`：
   - 新增 namespace import `import * as readerPrefs from './readerPrefs.js';`（brief 测试代码以 `readerPrefs.X` 形式引用，路径带 `.js` 扩展名；原有 `import { migrateV1toV2 }` 保持不动，既有 4 个测试零改动）。
   - 按 brief 原文追加测试 `THEME_LABELS is frozen and aligned with THEME_PRESETS`：断言导出存在、`Object.isFrozen`、key 集合与 `THEME_PRESETS` 排序后 `deepEqual` 对齐、所有值为非空 string。

2. **Step 2（确认失败）** — `cd server/internal/web && node --test readerPrefs.test.mjs`：4 pass / 1 fail，失败原因为 `AssertionError [ERR_ASSERTION]: THEME_LABELS must be exported`，与 brief 预期一致（修复前 `settings.js` 的 `Object.entries(readerPrefs.THEME_LABELS)` 即 `Object.entries(undefined)` 抛 TypeError 的根因）。

3. **Step 3（实现）** — `server/internal/web/readerPrefs.js`：在 `THEME_PRESETS` 定义之后（现文件第 42-52 行）按 brief 原文新增 `export const THEME_LABELS = Object.freeze({...})`，8 个 key（DAY / DAY_BRIGHT / EYE_CARE / EYE_CARE_GREEN / PARCHMENT / NIGHT / NIGHT_BLACK / AUTO）与 `THEME_PRESETS` 完全对齐，值为中文标签字面量，注释保留"settings.js 的 // XSS-SAFE: 豁免以本常量已冻结且值全部为字面量为前提"的安全提示。

4. **Step 4（验证）**：
   - `cd server/internal/web && node --test`：**88 tests, 88 pass, 0 fail**。
   - `cd tools/xsscheck && go run . ../../server/internal/web`：**OK: no unescaped innerHTML variables in 30 file(s)**。

5. **Step 5（提交）** — 仅 add 两个目标文件，commit message 按 brief 原文。

## 改动范围

| 文件 | 改动 |
|---|---|
| `server/internal/web/readerPrefs.js` | +13 行（THEME_LABELS 导出 + 注释） |
| `server/internal/web/readerPrefs.test.mjs` | +13 行（namespace import + 新测试） |

`settings.js:59-60,81` 既有引用未改动（它们是本修复的消费方，现在拿到真实常量）。

## Self-review 发现

- **改动面干净**：`git status` 确认仅两个目标文件被修改；工作区另有未跟踪的 `docs/superpowers/reviews/` 与 `tools/reformat_novels.py`，为本任务之前已存在的无关内容，未纳入提交。
- **无重复导出**：全 web 目录 grep 确认 `THEME_LABELS` 仅在 `readerPrefs.js` 导出一次，消费方仅 `settings.js`（3 处）。
- **`CUSTOM` 主题不在网格内**：`migrateV1toV2` 允许 `theme: 'CUSTOM'`，但 brief 明确 key 集合与 `THEME_PRESETS` 对齐（不含 CUSTOM），`settings.js:81` 的 `|| newTheme` fallback 已兜底非网格主题值的 toast 展示，行为符合 brief spec。
- **CSP 兼容**：新增代码为纯常量 + 注释，无 inline style / inline script，xsscheck 保持通过。
- **冻结有效性**：`Object.freeze` 为浅冻结，值全部是 string 字面量，浅冻结即足够；测试断言 `Object.isFrozen` 覆盖。

## 测试结果摘要

- `node --test`（readerPrefs 单文件，实现前）：4 pass / 1 fail（`THEME_LABELS must be exported`）→ 确认 TDD 红灯。
- `node --test`（web 全量，实现后）：**88/88 pass**。
- `xsscheck`：**OK（30 files）**。

## Concerns

无。
