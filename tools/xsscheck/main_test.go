package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestScanFile(t *testing.T) {
	cases := []struct {
		fixture      string
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
		// Round 32 S4 rule-extension fixtures.
		{"xsssafe_comment_above.js", 0},    // justification on the line above
		{"outerhtml_unescaped.js", 1},      // outerHTML sink without justification
		{"insert_adjacent_unescaped.js", 1}, // insertAdjacentHTML sink without justification
		{"doc_write_unescaped.js", 1},      // document.write sink without justification
		{"multiline_sink.js", 0},           // multi-line map() callback with comment
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
		name    string
		expr    string
		wantBad bool
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
			reason, bad := analyzeExpr(tc.expr, nil)
			if bad != tc.wantBad {
				t.Errorf("analyzeExpr(%q) = (%q, %v), want bad=%v",
					tc.expr, reason, bad, tc.wantBad)
			}
		})
	}
}
