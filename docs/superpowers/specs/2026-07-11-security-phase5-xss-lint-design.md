# Security Round 29 — Phase 5 Follow-up: XSS Lint Tool Design

> **日期**：2026-07-11
> **范围**：自定义 Go 静态分析工具，检测 Web UI JS 中 `innerHTML` 赋值的未转义变量
> **威胁模型**：继承 Round 29 主 spec；本工具是"防御未来回归"的纵深防御
> **依赖**：无（独立工具）；未来 Phase 6 CI 会集成此工具
> **审计轮次**：Round 29 Phase 5 follow-up（主 spec Phase 5 已确认当前残余风险低，本工具防止未来回归）
> **源 spec**：`docs/superpowers/specs/2026-07-10-security-audit-design.md`（section 5.5）

---

## 0. 摘要

主 spec Phase 5（Web UI XSS 整改）探索阶段确认：**当前所有动态字段已覆盖 `escapeHtml`**，残余风险仅为"未来新增字段遗漏 escape"（spec section 1.3 + Phase 4 exploration 验证）。

本 follow-up 不做"全面迁移到 textContent"（主 spec 5.5 标注的工作量大，留作未来），而是提供**一个 Go 静态分析工具**，让开发者/CI 在 Web UI 改动时自动检测"新引入的未转义 innerHTML 拼接"。

**核心价值**：
- 零外部依赖（Go 已是项目主语言，不引入 Node.js / ESLint 工具链）
- 单一职责：只检测 `innerHTML =` 后的未转义变量
- Phase 6 CI 的一个步骤（放大器）

---

## 1. 范围与方法论

### 1.1 范围

仅 `server/internal/web/*.js`（17 个 vanilla JS 文件）。

**不含**：
- 全面 textContent 迁移（主 spec 5.5 标注为"工作量大，留作未来"）
- CSP `'unsafe-inline'` 移除（需先做 textContent 迁移）
- ESLint 集成（违背项目"零 JS 工具链"理念）
- IDE 集成（如 VS Code 插件）—— 留作未来

### 1.2 为什么不用 ESLint

项目刻意保持零 Node.js 工具链（纯 Go + Kotlin + vanilla JS）。引入 ESLint 意味着：
- 新增 `package.json` + `node_modules`
- 开发者需安装 Node.js
- 与项目"轻量、单语言工具链"理念冲突

Go 静态分析脚本与项目工具链一致，零外部依赖。

### 1.3 探索阶段已确认的事实

- `server/internal/web/*.js` 共 17 个文件
- `innerHTML` 使用：30+ 处（多数是静态 HTML 字符串，如 `'正在读取目录结构...'`）
- `escapeHtml` 定义在 `api.js:36`，被 `browserView.js` / `dashboard.js` 等正确使用
- 当前**无未转义的动态字段**（Phase 4 探索 + 本轮 grep 验证）

---

## 2. 威胁与缓解对照

| 风险 | 本工具缓解 |
|---|---|
| **未来开发者新增 `innerHTML = \`<a href="${userInput}">\`` 遗漏 escapeHtml** | 工具检测到 `userInput` 未被 `escapeHtml(...)` 包裹 → 报错 |
| 静态 HTML 字符串（无变量插值）| 工具识别为 OK |
| 已转义的变量（`escapeHtml(name)`）| 工具识别为 OK |
| 函数调用结果（`items.map(...).join('')`）| 工具**允许通过**（静态分析无法深入函数体；假设函数内部已 escape） |

**攻击链影响**：
- **Chain-F**（CVSS 5.4 Medium）：T4-01 XSS → 当前无 token 无用；若未来加 token，CSP 已让 inline script 失效（Phase 4）。本工具提供"开发期检测"，让 Chain-F 在编写阶段就被拦截。

---

## 3. 设计决策

### 3.1 已确认决策（来自 brainstorming）

| 决策 | 选择 | 理由 |
|---|---|---|
| 工具语言 | **Go** | 项目主语言，零外部依赖 |
| 工具位置 | `tools/xsscheck/main.go` | 独立可执行，`go run ./tools/xsscheck` |
| 运行时机 | **手动 + 留作 Phase 6 CI 步骤** | 不引入 pre-commit hook 复杂性；CI 是放大器 |
| 规则严格度 | **函数调用结果允许通过** | 静态分析无法深入函数体；假设函数内部已 escape |

### 3.2 规则细节

工具扫描每个 `.js` 文件的每一行：

1. **匹配 `innerHTML =` 赋值**：正则 `\.innerHTML\s*=\s*(.+)`
2. **解析右侧表达式**：
   - **纯字面量字符串**（`'...'` / `"..."`，无 `${}`）→ **OK**
   - **模板字符串**（`` `...${var}...` ``）：
     - 对每个 `${expr}`：
       - 若 `expr` 形如 `escapeHtml(...)` → **OK**
       - 若 `expr` 是字面量（数字、字符串）→ **OK**
       - 否则 → **报错**（未转义变量）
   - **字符串拼接**（`a + b`）：
     - 对每个非字面量操作数：
       - 若形如 `escapeHtml(...)` → **OK**
       - 否则 → **报错**
   - **函数调用**（`foo().bar()`）→ **OK**（静态分析无法深入）
   - **变量引用**（`someVar`）→ **报错**（未转义）

3. **退出码**：
   - `0` = 无问题
   - `1` = 发现未转义变量（输出文件:行号 + 变量名）
   - `2` = 解析错误（严重 bug，需人工检查）

### 3.3 已知限制（文档化）

| 限制 | 影响 | 缓解 |
|---|---|---|
| 无法分析函数体内部 | 若 `renderItem(name)` 内部未 escape，工具不知道 | 假设函数内部已 escape；开发者责任 |
| 不检测 `outerHTML` / `insertAdjacentHTML` | 漏报 | YAGNI；当前代码不用这些 API |
| 不检测 DOM API（`document.write`, `eval`） | 漏报 | 当前代码不用；ESLint 规则可补（若未来引入） |
| 单行分析（不支持多行模板字符串跨行） | 漏报 | 当前代码 innerHTML 赋值都在单行或开始于同一行；未来需扩展 |
| 字符串拼接的 `+` 优先级解析简单 | 可能误报/漏报复杂表达式 | 工具报告时附带原始行，开发者可判断 |

---

## 4. 修改清单

| 文件 | 改动 | Task |
|---|---|---|
| `tools/xsscheck/main.go` | 新建：扫描器主程序 | 1 |
| `tools/xsscheck/main_test.go` | 新建：表驱动测试 + fixtures | 1 |
| `tools/xsscheck/testdata/*.js` | 新建：测试 fixture 文件 | 1 |
| `tools/xsscheck/README.md` | 新建：使用说明 + Phase 6 CI 集成预告 | 1 |
| `tools/xsscheck/go.mod` | 新建：独立 module（避免污染主 go.mod） | 1 |

---

## 5. 实施细节

### 5.1 `tools/xsscheck/main.go` 结构

```go
package main

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// 匹配 .innerHTML = <expr>
var innerHTMLRe = regexp.MustCompile(`\.innerHTML\s*=\s*(.+)$`)

// 匹配模板字符串中的 ${expr}
var templateExprRe = regexp.MustCompile(`\$\{([^}]+)\}`)

// 匹配 escapeHtml(...) 调用
var escapeHtmlRe = regexp.MustCompile(`^\s*escapeHtml\(.+\)\s*$`)

type finding struct {
	file   string
	line   int
	column int
	expr   string
	reason string
}

func main() {
	dir := "server/internal/web"
	if len(os.Args) > 1 {
		dir = os.Args[1]
	}

	files, err := filepath.Glob(filepath.Join(dir, "*.js"))
	if err != nil {
		fmt.Fprintf(os.Stderr, "glob error: %v\n", err)
		os.Exit(2)
	}

	var findings []finding
	for _, f := range files {
		ff, err := scanFile(f)
		if err != nil {
			fmt.Fprintf(os.Stderr, "scan %s: %v\n", f, err)
			os.Exit(2)
		}
		findings = append(findings, ff...)
	}

	if len(findings) == 0 {
		fmt.Printf("OK: no unescaped innerHTML variables in %d file(s)\n", len(files))
		os.Exit(0)
	}

	fmt.Printf("Found %d unescaped innerHTML variable(s):\n\n", len(findings))
	for _, f := range findings {
		fmt.Printf("  %s:%d  %s\n    %s\n\n", f.file, f.line, f.reason, f.expr)
	}
	os.Exit(1)
}

// scanFile 读取文件，逐行扫描 innerHTML 赋值。
func scanFile(path string) ([]finding, error) {
	// 读取 + 逐行处理 + 应用规则
	// ... 见 plan 实施细节
}
```

### 5.2 规则实现（伪代码）

```go
func analyzeExpr(expr string) (reason string, bad bool) {
	expr = strings.TrimSpace(expr)
	
	// 1. 纯字面量字符串 '...' 或 "..."
	if (strings.HasPrefix(expr, "'") && strings.HasSuffix(expr, "'")) ||
		(strings.HasPrefix(expr, "\"") && strings.HasSuffix(expr, "\"")) {
		// 检查内部是否有 ${} (双引号字符串里不会有，单引号也不会)
		return "", false
	}
	
	// 2. 模板字符串 `...${var}...`
	if strings.HasPrefix(expr, "`") {
		// 提取所有 ${expr}
		matches := templateExprRe.FindAllStringSubmatch(expr, -1)
		for _, m := range matches {
			inner := m[1]
			if !isSafeExpr(inner) {
				return fmt.Sprintf("template variable %q not wrapped in escapeHtml()", inner), true
			}
		}
		return "", false
	}
	
	// 3. 字符串拼接 a + b
	if strings.Contains(expr, "+") {
		parts := strings.Split(expr, "+")
		for _, p := range parts {
			p = strings.TrimSpace(p)
			if !isSafeExpr(p) {
				return fmt.Sprintf("concatenation operand %q not wrapped in escapeHtml()", p), true
			}
		}
		return "", false
	}
	
	// 4. 其他（函数调用、变量引用等）
	if !isSafeExpr(expr) {
		return fmt.Sprintf("expression %q not wrapped in escapeHtml()", expr), true
	}
	return "", false
}

func isSafeExpr(expr string) bool {
	expr = strings.TrimSpace(expr)
	// 字面量
	if isLiteral(expr) {
		return true
	}
	// escapeHtml(...) 包裹
	if escapeHtmlRe.MatchString(expr) {
		return true
	}
	// 函数调用（静态分析无法深入，允许）
	if isFunctionCall(expr) {
		return true
	}
	return false
}
```

### 5.3 测试 fixtures

`tools/xsscheck/testdata/`：

- `clean.js`：所有 innerHTML 都正确 escape 或纯字面量 → 期望 0 finding
- `template_unescaped.js`：`${userInput}` 未 escape → 期望 1 finding
- `template_escaped.js`：`${escapeHtml(userInput)}` → 期望 0 finding
- `concat_unescaped.js`：`'<a>' + userInput + '</a>'` → 期望 1 finding
- `concat_escaped.js`：`'<a>' + escapeHtml(userInput) + '</a>'` → 期望 0 finding
- `literal_only.js`：`innerHTML = 'static text'` → 期望 0 finding
- `function_call.js`：`innerHTML = items.map(...).join('')` → 期望 0 finding

### 5.4 `tools/xsscheck/go.mod`

独立 module，避免污染主 `server/go.mod`：

```
module localmediahub/tools/xsscheck

go 1.25
```

### 5.5 `tools/xsscheck/README.md`

```markdown
# xsscheck

Static analysis tool that detects unescaped variables in `innerHTML` assignments.

## Why

The Web UI uses `innerHTML` extensively (30+ sites). Most are static HTML strings,
but some interpolate user-controlled data (file names, paths, tag names). All
current sites correctly wrap dynamic fields in `escapeHtml()` (Phase 4 audit).
This tool catches **future regressions** where a developer adds a new
`innerHTML = \`<a>${userInput}\`` without escaping.

## Why not ESLint

Project deliberately avoids Node.js toolchain. This Go tool fits the existing
single-language (Go + Kotlin) tooling.

## Run

```bash
# From repo root
go run ./tools/xsscheck

# Or scan a specific directory
go run ./tools/xsscheck path/to/js/files
```

## Exit codes

- 0: no unescaped variables
- 1: unescaped variables found (see output)
- 2: scanner error (file system / parse error)

## Limitations

- Cannot analyze function bodies (e.g., `innerHTML = renderItem(name)` is allowed
  — tool assumes `renderItem` escapes internally).
- Single-line analysis only (multi-line template strings starting on the
  `innerHTML =` line are handled; continuation lines are not).
- Does not check `outerHTML`, `insertAdjacentHTML`, `document.write`, `eval`.

## CI integration (Phase 6)

This tool will be a step in the future CI workflow:
```yaml
- run: go run ./tools/xsscheck
```

Until CI is set up, run manually before PRs touching `server/internal/web/*.js`.
```

---

## 6. 测试方案

### 6.1 单元测试

`main_test.go` 表驱动：
- `TestAnalyzeExpr`：覆盖各表达式类型（template / concat / literal / function call）
- `TestScanFile`：扫描每个 fixture 文件，断言 finding 数量

### 6.2 集成测试

`TestScanRealWebUI`：扫描实际的 `server/internal/web/*.js`，期望 **0 findings**（因为主 spec 已审计所有动态字段已覆盖）。这是"回归基线"——若未来此测试失败，说明有人引入了未转义的 innerHTML。

**注**：此集成测试假设项目结构（`server/internal/web/` 存在）。若工具被复制到其他项目，需调整路径。

### 6.3 验证完成标准

- ✅ 所有 fixture 测试通过（clean → 0，各 unescaped 场景 → 正确 finding）
- ✅ 实际 Web UI 扫描返回 0 findings（回归基线）
- ✅ `go vet ./tools/xsscheck/` clean
- ✅ 退出码正确（0/1/2）

---

## 7. 风险与权衡

| 风险 | 影响 | 缓解 |
|---|---|---|
| 误报（合法代码被标记） | 中 | 工具输出原始行，开发者可判断；若误报多，规则可放宽 |
| 漏报（实际 XSS 未被检测） | 中 | 文档化限制（函数体、多行、其他 API）；ESLint 规则可补 |
| 单行分析不支持多行模板 | 低 | 当前代码 innerHTML 都在单行；未来可扩展 |
| 工具本身成为维护负担 | 低 | 规则简单（~150 行 Go）；测试覆盖各场景 |
| Phase 6 CI 未落地前工具闲置 | 低 | README 明确"PR 前手动运行"；未来 CI 自动放大 |

---

## 8. 验证完成标准

- ✅ `tools/xsscheck/main.go` + 测试 + fixtures + README + go.mod 全部到位
- ✅ `go run ./tools/xsscheck` 扫描实际 Web UI 返回 0 findings（exit 0）
- ✅ 单元测试覆盖各场景（clean / template unescaped / template escaped / concat / literal / function call）
- ✅ `go test ./tools/xsscheck/...` 全 green
- ✅ `go vet ./tools/xsscheck/...` clean

---

## 9. 后续衔接

| 待办 | 内容 | 备注 |
|---|---|---|
| Phase 6（CI） | 集成本工具作为 CI 步骤 | 独立会话 |
| Phase 2 backlog | `.sha256` path field 校验 + config-time skip | 已在 Round 29 ledger 记录 |
| 全面 textContent 迁移 | 主 spec 5.5 标注的工作量大 | 留作未来 |

---

## 文档信息

- **创建日期**：2026-07-11
- **审计轮次**：Round 29 Phase 5 follow-up
- **方法论**：brainstorming skill（澄清 → 方案 → 设计）
- **下一步**：经用户审核后，调用 writing-plans skill 转为实施计划
