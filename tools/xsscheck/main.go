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
	"strconv"
	"strings"
)

// Matches `.innerHTML = <expr>` / `.outerHTML = <expr>` /
// `insertAdjacentHTML(pos, <expr>)` / `document.write(<expr>)` — captures the
// right-hand side (the expression whose value flows into the DOM).
// Anchored to end of line so multi-line expressions only catch the first line.
var innerHTMLRe = regexp.MustCompile(`\.innerHTML\s*=\s*(.+?)\s*;?\s*$`)
var outerHTMLRe = regexp.MustCompile(`\.outerHTML\s*=\s*(.+?)\s*;?\s*$`)
var insertAdjRe = regexp.MustCompile(`insertAdjacentHTML\s*\(\s*[^,]+,\s*(.+?)\s*\)\s*;?\s*$`)
var docWriteRe = regexp.MustCompile(`document\.write\s*\(\s*(.+?)\s*\)\s*;?\s*$`)

// xssSafeCommentRe matches the justification comment that exempts a sink
// from the escapeHtml requirement. The comment must appear on the SAME line
// as the sink OR on the line immediately above it.
var xssSafeCommentRe = regexp.MustCompile(`//\s*XSS-SAFE:`)

// escapeHtmlCallRe matches a call to escapeHtml( anywhere in a sink expression.
var escapeHtmlCallRe = regexp.MustCompile(`\bescapeHtml\s*\(`)

// sinkPattern groups the regexes above so we can apply the same safe-guard
// logic uniformly. Each entry tags the sink type for diagnostics.
type sinkPattern struct {
	kind string
	re   *regexp.Regexp
}

var sinkPatterns = []sinkPattern{
	{"innerHTML", innerHTMLRe},
	{"outerHTML", outerHTMLRe},
	{"insertAdjacentHTML", insertAdjRe},
	{"document.write", docWriteRe},
}

// Matches `${ expr }` inside template literals.
var templateExprRe = regexp.MustCompile(`\$\{([^}]+)\}`)

// Matches `escapeHtml(...)` — expression MUST start with escapeHtml( to be safe.
var escapeCallRe = regexp.MustCompile(`^escapeHtml\s*\(`)

// Matches a variable assignment: `const/let/var name = expr` or `name = expr`
// or `name += expr`. Used to build the safe-identifier set.
var assignRe = regexp.MustCompile(`^\s*(?:const|let|var)\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*=\s*(.+?)\s*;?\s*$`)
var assignPlainRe = regexp.MustCompile(`^\s*([a-zA-Z_$][a-zA-Z0-9_$]*)\s*(\+?=)\s*(.+?)\s*;?\s*$`)

// Matches a function-call-like start: `identifier(` or `identifier.method`.
var callStartRe = regexp.MustCompile(`^[a-zA-Z_$][a-zA-Z0-9_$]*(?:\.[a-zA-Z_$][a-zA-Z0-9_$]*)*\s*[\.(]`)

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
	// Numeric literal.
	if _, err := strconv.ParseFloat(expr, 64); err == nil {
		return true
	}
	if expr == "true" || expr == "false" || expr == "null" || expr == "undefined" {
		return true
	}
	return false
}

// isEscapeCall reports whether expr starts with escapeHtml(.
func isEscapeCall(expr string) bool {
	return escapeCallRe.MatchString(strings.TrimSpace(expr))
}

// isFunctionCall reports whether expr looks like a function call (heuristic:
// starts with `identifier(` or `identifier.method(`).
func isFunctionCall(expr string) bool {
	expr = strings.TrimSpace(expr)
	if callStartRe.MatchString(expr) {
		return true
	}
	return false
}

// isSafe reports whether a single expression operand is safe to embed.
// safeVars is the set of identifiers known to hold pre-escaped content.
func isSafe(expr string, safeVars map[string]bool) bool {
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
	// Identifier that was previously assigned from a safe expression.
	if safeVars != nil && safeVars[expr] {
		return true
	}
	return false
}

// analyzeExpr returns (reason, bad). bad=true means the expression contains
// an unescaped variable that could carry user input.
// safeVars is the set of identifiers known to hold pre-escaped content.
func analyzeExpr(expr string, safeVars map[string]bool) (reason string, bad bool) {
	expr = strings.TrimSpace(expr)
	if expr == "" {
		return "", false
	}

	// Multi-line function call: the regex captured only the first line of a
	// multi-line expression (e.g., `items.map(x => { ... }).join('')`).
	// If the captured fragment starts like a function/method call and has
	// unbalanced braces or parens, treat the whole expression as a function
	// call (safe — static analysis can't look inside function bodies).
	if isMultiLineCallStart(expr) {
		return "", false
	}

	// Template literal: extract ${...} parts.
	if strings.HasPrefix(expr, "`") {
		matches := templateExprRe.FindAllStringSubmatch(expr, -1)
		for _, m := range matches {
			inner := strings.TrimSpace(m[1])
			if !isSafe(inner, safeVars) {
				return fmt.Sprintf("template variable %q not wrapped in escapeHtml()", inner), true
			}
		}
		return "", false
	}

	// String concatenation: split on top-level +.
	if topLevelConcat(expr) {
		parts := splitTopLevelPlus(expr)
		for _, p := range parts {
			p = strings.TrimSpace(p)
			if !isSafe(p, safeVars) {
				return fmt.Sprintf("concatenation operand %q not wrapped in escapeHtml()", p), true
			}
		}
		return "", false
	}

	// Single expression.
	if !isSafe(expr, safeVars) {
		return fmt.Sprintf("expression %q not wrapped in escapeHtml()", expr), true
	}
	return "", false
}

// isMultiLineCallStart reports whether expr looks like the first line of a
// multi-line function call (starts with identifier/identifier. and has
// unbalanced `{` or `(` indicating the expression continues on subsequent lines).
func isMultiLineCallStart(expr string) bool {
	expr = strings.TrimSpace(expr)
	if !callStartRe.MatchString(expr) {
		return false
	}
	// Count brace/paren depth (outside strings).
	depthBrace, depthParen := 0, 0
	var quote byte
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
		case '{':
			depthBrace++
		case '}':
			depthBrace--
		case '(':
			depthParen++
		case ')':
			depthParen--
		}
	}
	// If braces or parens are unbalanced, the expression spans multiple lines.
	return depthBrace > 0 || depthParen > 0
}

// topLevelConcat reports whether expr has a + outside of any quoted string or parens.
func topLevelConcat(expr string) bool {
	depth := 0
	var quote byte
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
	file   string
	line   int
	column int
	expr   string
	reason string
}

// collectSafeVars scans all lines in the file for variables assigned from
// safe expressions (escapeHtml(...) calls, function calls, method chains, or
// template literals whose interpolations are all safe). Returns the set of
// identifier names known to hold pre-escaped content.
func collectSafeVars(lines []string) map[string]bool {
	safe := map[string]bool{}

	// First pass: collect direct `const/let/var name = <safeExpr>` assignments.
	for _, raw := range lines {
		line := raw
		if idx := strings.Index(line, "//"); idx >= 0 {
			if !insideString(line, idx) {
				line = line[:idx]
			}
		}

		// `const/let/var name = expr`
		if m := assignRe.FindStringSubmatch(line); m != nil {
			name := m[1]
			rhs := m[2]
			_, bad := analyzeExpr(rhs, safe)
			if !bad {
				safe[name] = true
			}
			continue
		}
		// `name += expr` or `name = expr` (name must already be known for +=).
		if m := assignPlainRe.FindStringSubmatch(line); m != nil {
			name := m[1]
			op := m[2]
			rhs := m[3]
			_, bad := analyzeExpr(rhs, safe)
			if op == "+=" {
				// `html += expr` is safe only if html was already safe AND expr is safe.
				if safe[name] && !bad {
					// stays safe
				} else {
					// can't confirm; remove from safe set if it was there.
					delete(safe, name)
				}
			} else {
				// plain `=` reassignment: update safe set.
				if !bad {
					safe[name] = true
				} else {
					delete(safe, name)
				}
			}
		}
	}

	return safe
}

// scanFile reads path line-by-line and returns findings for each
// `.innerHTML = <expr>` line that contains unescaped variables.
func scanFile(path string) ([]finding, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	lines := strings.Split(string(data), "\n")

	safeVars := collectSafeVars(lines)

	var findings []finding
	for i, raw := range lines {
		// Compute the comment-stripped line for the existing expression analyzer.
		line := raw
		if idx := strings.Index(line, "//"); idx >= 0 {
			if !insideString(line, idx) {
				line = line[:idx]
			}
		}

		// Try every supported sink pattern (innerHTML / outerHTML /
		// insertAdjacentHTML / document.write). At most one will match a given
		// line because each is anchored to end-of-line.
		for _, sp := range sinkPatterns {
			m := sp.re.FindStringSubmatch(line)
			if m == nil {
				continue
			}
			expr := m[1]

			// Enforce the Round 32 S4 rule: every sink MUST either
			// (a) carry a `// XSS-SAFE:` comment on the same line or the
			//     line immediately above, OR
			// (b) call escapeHtml( somewhere in its expression.
			// We check (b) on the raw (comment-stripped) sink expression: if
			// escapeHtml is called anywhere, the sink is considered guarded.
			// We check (a) against the unstripped raw line and the previous
			// raw line, so the justification survives comment stripping.
			if escapeHtmlCallRe.MatchString(expr) {
				// escapeHtml is in the expression — safe.
				continue
			}
			justified := xssSafeCommentRe.MatchString(raw)
			if !justified && i > 0 {
				justified = xssSafeCommentRe.MatchString(lines[i-1])
			}
			if justified {
				continue
			}

			// Also run the legacy analyzer: it flags RAW (unescaped, no-comment)
			// variables even when the rule above has not yet been applied. This
			// preserves the original Phase 5 behavior for templates / concats.
			reason, bad := analyzeExpr(expr, safeVars)
			if !bad {
				// Expression is statically safe (literal / function call /
				// tracked safe-var) but lacks the required justification
				// comment. Upgrade to a finding so the rule is enforced.
				reason = fmt.Sprintf("%s sink without // XSS-SAFE: comment or escapeHtml() call", sp.kind)
				bad = true
			}
			if bad {
				findings = append(findings, finding{
					file:   path,
					line:   i + 1,
					column: strings.Index(raw, expr) + 1,
					expr:   strings.TrimSpace(expr),
					reason: reason,
				})
			}
			break // a line can only match one sink kind
		}
	}
	return findings, nil
}

// insideString reports whether position `pos` in `line` is inside a quoted string.
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
