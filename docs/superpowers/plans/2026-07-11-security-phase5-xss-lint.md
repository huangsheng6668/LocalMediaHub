# Security Round 29 — Phase 5 Follow-up: XSS Lint Tool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Go static analysis tool (`tools/xsscheck/`) that detects unescaped variables in `innerHTML` assignments across Web UI JS files, preventing future XSS regressions.

**Architecture:** Single Go program in its own module (`tools/xsscheck/go.mod`). Scans `.js` files line-by-line, finds `.innerHTML = <expr>`, parses the right-hand expression (literal / template / concatenation / function call), and reports any variable not wrapped in `escapeHtml(...)`. Table-driven tests with fixture `.js` files cover each expression type. Integration test asserts the real Web UI currently has 0 findings (regression baseline).

**Tech Stack:** Go 1.25+ / stdlib `regexp` only

**Source spec:** `docs/superpowers/specs/2026-07-11-security-phase5-xss-lint-design.md`

**Coverage:** Future XSS regression prevention (Chain-F conditional mitigation at development time)

## Global Constraints

- **Tool MUST be a separate Go module** (`tools/xsscheck/go.mod`) to avoid polluting `server/go.mod` with tooling. (Spec section 5.4)
- **Zero external dependencies.** Use only stdlib (`regexp`, `os`, `filepath`, `fmt`, `strings`). (Spec section 5.4)
- **Function call results are ALLOWED through** (e.g., `items.map(...).join('')`). Tool cannot analyze function bodies; assumes internal escaping. (Spec section 3.2)
- **Exit codes: 0 = clean, 1 = unescaped variables found, 2 = scanner error.** (Spec section 3.2)
- **Integration test MUST assert 0 findings on the real Web UI** — this is the regression baseline. (Spec section 6.2)

---

## File Structure

| File | Type | Responsibility |
|---|---|---|
| `tools/xsscheck/go.mod` | Create | Independent Go module declaration |
| `tools/xsscheck/main.go` | Create | Scanner + analyzer + CLI entry point |
| `tools/xsscheck/main_test.go` | Create | Unit tests + integration test (scan real Web UI) |
| `tools/xsscheck/testdata/clean.js` | Create | Fixture: all innerHTML correctly escaped |
| `tools/xsscheck/testdata/template_unescaped.js` | Create | Fixture: `${userInput}` not escaped |
| `tools/xsscheck/testdata/template_escaped.js` | Create | Fixture: `${escapeHtml(userInput)}` |
| `tools/xsscheck/testdata/concat_unescaped.js` | Create | Fixture: `'<a>' + userInput + '</a>'` |
| `tools/xsscheck/testdata/concat_escaped.js` | Create | Fixture: `'<a>' + escapeHtml(userInput) + '</a>'` |
| `tools/xsscheck/testdata/literal_only.js` | Create | Fixture: `innerHTML = 'static'` |
| `tools/xsscheck/testdata/function_call.js` | Create | Fixture: `innerHTML = items.map(...).join('')` |
| `tools/xsscheck/README.md` | Create | Usage + design + CI integration preview |

---

## Task 1: Build xsscheck tool + tests + fixtures (TDD)

**Files:**
- Create: `tools/xsscheck/go.mod`
- Create: `tools/xsscheck/main.go`
- Create: `tools/xsscheck/main_test.go`
- Create: `tools/xsscheck/testdata/*.js` (8 fixtures)
- Create: `tools/xsscheck/README.md`

**Interfaces:**
- Produces: `go run ./tools/xsscheck [dir]` — scans `*.js` in `dir` (default `server/internal/web`), exits 0/1/2.

- [ ] **Step 1: Create the module structure**

Create `tools/xsscheck/go.mod`:
```
module localmediahub/tools/xsscheck

go 1.25
```

- [ ] **Step 2: Create test fixtures first (TDD)**

Create each fixture in `tools/xsscheck/testdata/`:

**`clean.js`** (0 findings expected — all correctly escaped):
```javascript
import { escapeHtml } from './api.js';

function renderClean(name, path) {
    const safeName = escapeHtml(name);
    const safePath = escapeHtml(path.replace(/\\/g, '/'));
    element.innerHTML = `<a href="${safePath}">${safeName}</a>`;
    element.innerHTML = '<div>static text</div>';
    element.innerHTML = "<span class='x'>also static</span>";
}
```

**`template_unescaped.js`** (1 finding expected):
```javascript
function renderBad(name) {
    element.innerHTML = `<a>${name}</a>`;
}
```

**`template_escaped.js`** (0 findings expected):
```javascript
import { escapeHtml } from './api.js';

function renderGood(name) {
    element.innerHTML = `<a>${escapeHtml(name)}</a>`;
}
```

**`concat_unescaped.js`** (1 finding expected):
```javascript
function renderBad(name) {
    element.innerHTML = '<a>' + name + '</a>';
}
```

**`concat_escaped.js`** (0 findings expected):
```javascript
import { escapeHtml } from './api.js';

function renderGood(name) {
    element.innerHTML = '<a>' + escapeHtml(name) + '</a>';
}
```

**`literal_only.js`** (0 findings expected):
```javascript
function renderStatic() {
    element.innerHTML = '正在读取目录结构...';
    other.innerHTML = "<div class='loading'>Loading</div>";
}
```

**`function_call.js`** (0 findings expected — function results allowed):
```javascript
function renderList(items) {
    element.innerHTML = items.map(i => `<li>${escapeHtml(i)}</li>`).join('');
    element.innerHTML = renderStaticHtml();
}
```

**`mixed.js`** (2 findings expected — tests multiple issues per file):
```javascript
import { escapeHtml } from './api.js';

function renderMixed(name, path) {
    a.innerHTML = `<b>${escapeHtml(name)}</b>`;        // OK
    b.innerHTML = '<a href="' + path + '">link</a>';    // BAD: path not escaped
    c.innerHTML = `<div>${name}</div>`;                  // BAD: name not escaped
}
```

- [ ] **Step 3: Write the failing test**

Create `tools/xsscheck/main_test.go`:

```go
package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestScanFile(t *testing.T) {
	cases := []struct {
		fixture     string
		wantFindings int
	}{
		{"clean.js", 0},
		{"template_unescaped.js", 1},
		{"template_escaped.js", 0},
		{"concat_unescaped.js", 1},
		{"concat_escaped.js", 0},
		{"literal_only.js", 0},
		{"function_call.js", 0},
		{"mixed.js", 2},
	}

	for _, tc := range cases {
		t.Run(tc.fixture, func(t *testing.T) {
			path := filepath.Join("testdata", tc.fixture)
			findings, err := scanFile(path)
			if err != nil {
				t.Fatalf("scanFile(%s) error: %v", path, err)
			}
			if len(findings) != tc.wantFindings {
				t.Errorf("scanFile(%s) = %d findings, want %d",
					tc.fixture, len(findings), tc.wantFindings)
				for _, f := range findings {
					t.Logf("  %d:%d %s — %s", f.line, f.column, f.expr, f.reason)
				}
			}
		})
	}
}

func TestScanRealWebUI(t *testing.T) {
	// Integration test: the real Web UI must have 0 unescaped innerHTML vars.
	// This is the regression baseline — if this test starts failing, someone
	// added a new innerHTML without escaping. Find the file:line in the test
	// output and add escapeHtml() per the existing pattern.
	webUIDir := filepath.Join("..", "..", "server", "internal", "web")
	if _, err := os.Stat(webUIDir); os.IsNotExist(err) {
		t.Skipf("Web UI dir not found at %s (running from wrong CWD?)", webUIDir)
	}

	files, err := filepath.Glob(filepath.Join(webUIDir, "*.js"))
	if err != nil {
		t.Fatalf("glob error: %v", err)
	}
	if len(files) == 0 {
		t.Skip("no .js files found in Web UI dir")
	}

	var total int
	for _, f := range files {
		ff, err := scanFile(f)
		if err != nil {
			t.Errorf("scan %s: %v", f, err)
			continue
		}
		for _, finding := range ff {
			t.Errorf("UNESCAPED: %s:%d — %s\n  %s", f, finding.line, finding.reason, finding.expr)
		}
		total += len(ff)
	}
	if total > 0 {
		t.Errorf("Web UI has %d unescaped innerHTML variable(s) — add escapeHtml()", total)
	}
}

func TestAnalyzeExpr(t *testing.T) {
	// Unit test the analyzer directly with edge cases that don't fit fixture model.
	cases := []struct {
		name     string
		expr     string
		wantBad  bool
	}{
		{"plain literal single quote", `'static text'`, false},
		{"plain literal double quote", `"static text"`, false},
		{"template with literal inside", "`prefix${42}suffix`", false},
		{"template with unquoted var", "`${userInput}`", true},
		{"template with escapeHtml", "`${escapeHtml(userInput)}`", false},
		{"concat with literal", `'a' + 'b'`, false},
		{"concat with unquoted var", `'a' + userInput`, true},
		{"concat with escapeHtml", `'a' + escapeHtml(userInput)`, false},
		{"function call alone", `renderHtml()`, false},
		{"chained method call", `items.map(i => i).join('')`, false},
		{"variable alone", `userInput`, true},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			reason, bad := analyzeExpr(tc.expr)
			if bad != tc.wantBad {
				t.Errorf("analyzeExpr(%q) = (%q, %v), want bad=%v",
					tc.expr, reason, bad, tc.wantBad)
			}
		})
	}
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `cd tools/xsscheck && go test -v`
Expected: FAIL — `undefined: scanFile`, `undefined: analyzeExpr`.

- [ ] **Step 5: Implement `main.go`**

Create `tools/xsscheck/main.go`:

```go
// Command xsscheck scans .js files for innerHTML assignments with unescaped
// variables. Designed to catch future regressions where a developer adds
// `innerHTML = `<a>${userInput}</a>`` without wrapping userInput in escapeHtml().
//
// See docs/superpowers/specs/2026-07-11-security-phase5-xss-lint-design.md for
// the full design rationale (why Go not ESLint, scope, known limitations).
package main

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// Matches `.innerHTML = <expr>` — captures the right-hand side.
// Anchored to end of line so multi-line expressions only catch the first line
// (documented limitation — see README).
var innerHTMLRe = regexp.MustCompile(`\.innerHTML\s*=\s*(.+?)\s*;?\s*$`)

// Matches `${ expr }` inside template literals.
var templateExprRe = regexp.MustCompile(`\$\{([^}]+)\}`)

// Matches `escapeHtml(...)` — expression MUST start with escapeHtml( to be safe.
// Note: doesn't require the closing paren to be at end (allows `escapeHtml(x) + 'literal'`).
var escapeCallRe = regexp.MustCompile(`^escapeHtml\s*\(`)

// isLiteral reports whether expr is a quoted string literal with no interpolation.
func isLiteral(expr string) bool {
	expr = strings.TrimSpace(expr)
	if len(expr) < 2 {
		return false
	}
	// Single or double quoted (no interpolation in JS for these).
	if (expr[0] == '\'' && expr[len(expr)-1] == '\'') ||
		(expr[0] == '"' && expr[len(expr)-1] == '"') {
		return true
	}
	// Numeric or boolean literal.
	if _, err := parseFloatLit(expr); err == nil {
		return true
	}
	if expr == "true" || expr == "false" || expr == "null" || expr == "undefined" {
		return true
	}
	return false
}

func parseFloatLit(s string) (float64, error) {
	return strconvParseFloat(s)
}

// isEscapeCall reports whether expr starts with escapeHtml(.
func isEscapeCall(expr string) bool {
	return escapeCallRe.MatchString(strings.TrimSpace(expr))
}

// isFunctionCall reports whether expr looks like a function call (heuristic:
// contains `(` and ends with `)` after an identifier, or contains a `.` method call).
// Used to allow `items.map(...).join('')` through.
var funcCallRe = regexp.MustCompile(`^[a-zA-Z_$][a-zA-Z0-9_$]*\s*\(`)

func isFunctionCall(expr string) bool {
	expr = strings.TrimSpace(expr)
	if funcCallRe.MatchString(expr) {
		return true
	}
	// Method chain: identifier.method(...) or chain.method(...)
	if strings.Contains(expr, ".") && strings.Contains(expr, "(") && strings.Contains(expr, ")") {
		return true
	}
	return false
}

// isSafe reports whether a single expression (no top-level +) is safe to embed.
func isSafe(expr string) bool {
	expr = strings.TrimSpace(expr)
	if expr == "" {
		return true
	}
	if isLiteral(expr) {
		return true
	}
	if isEscapeCall(expr) {
		return true
	}
	if isFunctionCall(expr) {
		return true
	}
	return false
}

// analyzeExpr returns (reason, bad). bad=true means the expression contains
// an unescaped variable that could carry user input.
func analyzeExpr(expr string) (reason string, bad bool) {
	expr = strings.TrimSpace(expr)
	if expr == "" {
		return "", false
	}

	// Template literal: extract ${...} parts.
	if strings.HasPrefix(expr, "`") {
		matches := templateExprRe.FindAllStringSubmatch(expr, -1)
		for _, m := range matches {
			inner := strings.TrimSpace(m[1])
			if !isSafe(inner) {
				return fmt.Sprintf("template variable %q not wrapped in escapeHtml()", inner), true
			}
		}
		return "", false
	}

	// String concatenation: split on top-level + (simplistic — doesn't handle
	// + inside parentheses or strings; documented limitation).
	if topLevelConcat(expr) {
		parts := splitTopLevelPlus(expr)
		for _, p := range parts {
			p = strings.TrimSpace(p)
			if !isSafe(p) {
				return fmt.Sprintf("concatenation operand %q not wrapped in escapeHtml()", p), true
			}
		}
		return "", false
	}

	// Single expression.
	if !isSafe(expr) {
		return fmt.Sprintf("expression %q not wrapped in escapeHtml()", expr), true
	}
	return "", false
}

// topLevelConcat reports whether expr has a + outside of any quoted string or parens.
// Simplistic: tracks quote state and paren depth.
func topLevelConcat(expr string) bool {
	depth := 0
	var quote byte // 0 = not in quote, otherwise the quote char
	for i := 0; i < len(expr); i++ {
		c := expr[i]
		if quote != 0 {
			if c == quote {
				quote = 0
			}
			continue
		}
		switch c {
		case '\'', '"', '`':
			quote = c
		case '(':
			depth++
		case ')':
			depth--
		case '+':
			if depth == 0 {
				return true
			}
		}
	}
	return false
}

// splitTopLevelPlus splits expr on top-level + (outside quotes/parens).
func splitTopLevelPlus(expr string) []string {
	depth := 0
	var quote byte
	var parts []string
	last := 0
	for i := 0; i < len(expr); i++ {
		c := expr[i]
		if quote != 0 {
			if c == quote {
				quote = 0
			}
			continue
		}
		switch c {
		case '\'', '"', '`':
			quote = c
		case '(':
			depth++
		case ')':
			depth--
		case '+':
			if depth == 0 {
				parts = append(parts, expr[last:i])
				last = i + 1
			}
		}
	}
	parts = append(parts, expr[last:])
	return parts
}

type finding struct {
	file    string
	line    int
	column  int
	expr    string
	reason  string
}

// scanFile reads path line-by-line and returns findings for each
// `.innerHTML = <expr>` line that contains unescaped variables.
func scanFile(path string) ([]finding, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	lines := strings.Split(string(data), "\n")

	var findings []finding
	for i, line := range lines {
		// Strip line comments (best-effort — doesn't handle // inside strings).
		if idx := strings.Index(line, "//"); idx >= 0 {
			// Only strip if // is not inside a string (very simplistic).
			if !insideString(line, idx) {
				line = line[:idx]
			}
		}

		m := innerHTMLRe.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		expr := m[1]
		reason, bad := analyzeExpr(expr)
		if bad {
			findings = append(findings, finding{
				file:   path,
				line:   i + 1,
				column: strings.Index(line, expr) + 1,
				expr:   strings.TrimSpace(expr),
				reason: reason,
			})
		}
	}
	return findings, nil
}

// insideString reports whether position `pos` in `line` is inside a quoted string.
// Simplistic — tracks quote state from line start.
func insideString(line string, pos int) bool {
	var quote byte
	for i := 0; i < pos && i < len(line); i++ {
		c := line[i]
		if quote != 0 {
			if c == quote {
				quote = 0
			}
			continue
		}
		if c == '\'' || c == '"' || c == '`' {
			quote = c
		}
	}
	return quote != 0
}

func main() {
	dir := filepath.Join("..", "..", "server", "internal", "web")
	if len(os.Args) > 1 {
		dir = os.Args[1]
	}

	files, err := filepath.Glob(filepath.Join(dir, "*.js"))
	if err != nil {
		fmt.Fprintf(os.Stderr, "glob error: %v\n", err)
		os.Exit(2)
	}
	if len(files) == 0 {
		fmt.Fprintf(os.Stderr, "no .js files in %s\n", dir)
		os.Exit(2)
	}

	var allFindings []finding
	for _, f := range files {
		ff, err := scanFile(f)
		if err != nil {
			fmt.Fprintf(os.Stderr, "scan %s: %v\n", f, err)
			os.Exit(2)
		}
		allFindings = append(allFindings, ff...)
	}

	if len(allFindings) == 0 {
		fmt.Printf("OK: no unescaped innerHTML variables in %d file(s)\n", len(files))
		os.Exit(0)
	}

	fmt.Printf("Found %d unescaped innerHTML variable(s):\n\n", len(allFindings))
	for _, f := range allFindings {
		fmt.Printf("  %s:%d  %s\n    %s\n\n", f.file, f.line, f.reason, f.expr)
	}
	os.Exit(1)
}
```

**Note**: The code uses a helper `strconvParseFloat` — you'll need to either:
- Import `strconv` and call `strconv.ParseFloat(expr, 64)` directly (delete the `parseFloatLit` wrapper)
- Or keep the wrapper (some teams prefer indirection for testability)

Prefer the direct approach: replace `parseFloatLit` calls with `strconv.ParseFloat(expr, 64)` and add `"strconv"` to imports. Adjust the `isLiteral` function accordingly.

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd tools/xsscheck && go test -v`
Expected: PASS — all `TestScanFile` subcases + `TestAnalyzeExpr` subcases + `TestScanRealWebUI` (0 findings on real Web UI).

If `TestScanRealWebUI` fails, it means a real Web UI file has an unescaped innerHTML variable. **DO NOT modify Web UI files in this task** — instead, report it as DONE_WITH_CONCERNS and let the controller decide (the failure is a real Phase 4 audit miss, not a tool bug).

- [ ] **Step 7: Run `go run` against the real Web UI**

Run from repo root:
```bash
go run ./tools/xsscheck
```
Expected: `OK: no unescaped innerHTML variables in N file(s)` + exit 0.

- [ ] **Step 8: Run vet**

Run: `cd tools/xsscheck && go vet ./...`
Expected: clean (no output).

- [ ] **Step 9: Create README.md**

Create `tools/xsscheck/README.md` per spec section 5.5 (use the content from spec verbatim).

- [ ] **Step 10: Commit**

```bash
git add tools/xsscheck/
git commit -m "feat(tools): xsscheck static analyzer for innerHTML escape coverage (Phase 5 follow-up)"
```

---

## Self-Review

**Spec coverage** (against spec section 5):
- ✅ Go tool in `tools/xsscheck/` (spec 5.1)
- ✅ Zero external deps (spec 5.4 — only stdlib)
- ✅ Separate Go module (spec 5.4 — `tools/xsscheck/go.mod`)
- ✅ Fixtures covering 8 scenarios (spec 5.3)
- ✅ README with usage + CI preview (spec 5.5)
- ✅ Integration test on real Web UI (spec 6.2)
- ✅ Exit codes 0/1/2 (spec 3.2)

**Type consistency**:
- `scanFile(path string) ([]finding, error)` — consistent across test + impl
- `analyzeExpr(expr string) (reason string, bad bool)` — consistent across test + impl
- `finding` struct fields — consistent across scanFile + main output

**Placeholder scan**: No TBD/TODO/"add error handling" patterns. The `strconvParseFloat` indirection is called out in Step 5 with explicit guidance to simplify.

**Known implementation risks** (flagged for executor awareness):
1. **Step 5 `strconvParseFloat` indirection**: Brief shows a helper `parseFloatLit` that wraps `strconv.ParseFloat`. Simplify to direct `strconv.ParseFloat` call. Add `"strconv"` import. Don't keep unnecessary indirection.
2. **Step 5 quote tracking**: `topLevelConcat` / `splitTopLevelPlus` / `insideString` use byte-level quote tracking that doesn't handle escaped quotes (`"\""`). For the current fixture set this is fine, but if a fixture contains escaped quotes the tool may mis-parse. Document as a known limitation.
3. **Step 6 `TestScanRealWebUI` failure path**: If the real Web UI has an unescaped var (Phase 4 audit miss), this test fails. **Do NOT modify Web UI files to force pass.** Report DONE_WITH_CONCERNS with the finding details; controller decides whether to fix the Web UI (separate task) or accept the tool as-is.
4. **Step 7 default dir**: `main()` defaults to `../../server/internal/web` (relative to tool). When run via `go run ./tools/xsscheck` from repo root, this resolves correctly. Verify.

---

## Plan complete

Plan saved to `docs/superpowers/plans/2026-07-11-security-phase5-xss-lint.md`.

Single task (the tool + tests + fixtures are one cohesive deliverable). Estimated effort: small-medium (~250 lines of Go + ~80 lines of fixtures + README).

Execution model recommendation:
- Task 1: standard model (regex logic + quote tracking + integration test on real Web UI requires judgment)
