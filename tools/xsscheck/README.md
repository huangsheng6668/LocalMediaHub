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

This tool is an independent Go module (no root `go.mod` dependency). Run from
its own directory:

```bash
# From the tool directory (scans ../../server/internal/web by default)
cd tools/xsscheck
go run .

# Or scan a specific directory
go run . path/to/js/files
```

The default target directory is `../../server/internal/web` (relative to the
tool), which resolves to `server/internal/web/*.js` at repo root.

## Exit codes

- 0: no unescaped variables
- 1: unescaped variables found (see output)
- 2: scanner error (file system / parse error)

## What the tool checks

For each `.innerHTML = <expr>` line, the tool analyzes the right-hand side:

1. **Quoted literal** (`'...'` / `"..."`) — OK (no interpolation in JS).
2. **Template literal** (`` `...${expr}...` ``) — each `${...}` must be a
   literal, an `escapeHtml(...)` call, a function call, or a tracked safe
   variable.
3. **String concatenation** (`a + b`) — each operand must meet the same criteria.
4. **Function call** (`renderHtml()`, `items.map(...).join('')`) — OK (static
   analysis cannot inspect function bodies; developer responsibility).
5. **Bare variable** — flagged unless the variable was assigned earlier in the
   file from a safe expression (e.g., `const safeName = escapeHtml(name)`).

## Limitations

- **Single-line analysis**: multi-line expressions are detected when the first
  line starts as a function call with unbalanced braces/parens (e.g.,
  `items.map(x => {`), but full multi-line template strings are not reassembled.
- **Function bodies not analyzed**: `innerHTML = renderItem(name)` is allowed —
  the tool assumes `renderItem` escapes internally.
- **Safe-variable tracking is file-scoped and single-pass**: if a variable is
  reassigned an unsafe value later in the file, it is removed from the safe set;
  but complex control flow (branches, loops) is not modeled.
- **Byte-level quote tracking**: escaped quotes inside strings (`"\"") are not
  handled. No current fixture or Web UI file triggers this.
- Does not check `outerHTML`, `insertAdjacentHTML`, `document.write`, `eval`.

## CI integration (Phase 6)

This tool will be a step in the future CI workflow:

```yaml
- run: cd tools/xsscheck && go run .
```

Until CI is set up, run manually before PRs touching `server/internal/web/*.js`.

## Tests

```bash
cd tools/xsscheck
go test -v
```

- `TestScanFile` — table-driven scan of each `testdata/*.js` fixture.
- `TestScanRealWebUI` — integration test: scans the real Web UI, expects 0
  findings. This is the regression baseline.
- `TestAnalyzeExpr` — unit tests the expression analyzer with edge cases.
