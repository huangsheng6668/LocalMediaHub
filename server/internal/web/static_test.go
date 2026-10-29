package web

import (
	"bytes"
	"compress/gzip"
	"io"
	"io/fs"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"testing"
)

// do issues a request against the package-level Static handler.
func do(t *testing.T, method, target string, hdr map[string]string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, target, nil)
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	Static.ServeHTTP(rec, req)
	return rec
}

// TestStylesheetsCollapsedIntoOneBundle is the guard for the first-load request
// count: index.html must link exactly one local stylesheet, and the bundle must
// carry every original sheet's content in document order (cascade order).
func TestStylesheetsCollapsedIntoOneBundle(t *testing.T) {
	rec := do(t, http.MethodGet, "/", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("GET / = %d, want 200", rec.Code)
	}
	body := rec.Body.String()

	local := regexp.MustCompile(`(?is)<link\b[^>]*\brel\s*=\s*["']stylesheet["'][^>]*>`).FindAllString(body, -1)
	if len(local) != 1 {
		t.Fatalf("index.html links %d stylesheets, want exactly 1 (the bundle)", len(local))
	}
	if !strings.Contains(local[0], "/"+bundlePath) {
		t.Fatalf("surviving stylesheet link is not the bundle: %s", local[0])
	}

	bundle := do(t, http.MethodGet, "/"+bundlePath, nil)
	if bundle.Code != http.StatusOK {
		t.Fatalf("GET /%s = %d, want 200", bundlePath, bundle.Code)
	}
	got := bundle.Body.String()

	// Every stylesheet referenced by the *authored* index.html must be present.
	raw, err := fs.ReadFile(Assets, indexPath)
	if err != nil {
		t.Fatalf("read embedded index.html: %v", err)
	}
	links := stylesheetLinks(raw)
	if len(links) < 2 {
		t.Fatalf("authored index.html references %d stylesheets, expected the bundle to be worthwhile", len(links))
	}
	for _, l := range links {
		want, err := fs.ReadFile(Assets, l.href)
		if err != nil {
			t.Fatalf("read embedded %s: %v", l.href, err)
		}
		if !bytes.Contains(bundle.Body.Bytes(), want) {
			t.Errorf("bundle is missing stylesheet %s", l.href)
		}
	}

	// Order must match document order, otherwise the cascade changes.
	prev := -1
	for _, l := range links {
		i := strings.Index(got, "/* ===== "+l.href+" ===== */")
		if i < 0 {
			t.Fatalf("bundle has no banner for %s", l.href)
		}
		if i < prev {
			t.Fatalf("bundle order breaks document order at %s", l.href)
		}
		prev = i
	}
}

// TestModulePreloadCoversGraph guards the waterfall fix. Every module reachable
// from the entry point must be preloaded, and nothing outside that graph (in
// particular the lazily-injected hls.js) may be.
func TestModulePreloadCoversGraph(t *testing.T) {
	rec := do(t, http.MethodGet, "/", nil)
	body := rec.Body.String()

	preloads := regexp.MustCompile(`<link rel="modulepreload" href="([^"]+)">`).FindAllStringSubmatch(body, -1)
	if len(preloads) == 0 {
		t.Fatal("index.html carries no modulepreload hints")
	}

	got := map[string]bool{}
	for _, m := range preloads {
		got[m[1]] = true
	}
	for _, want := range Static.(*staticAssets).moduleGraph() {
		if !got[want] {
			t.Errorf("module %s is not preloaded", want)
		}
	}
	if got[entryPath] {
		t.Errorf("entry module %s should not preload itself", entryPath)
	}
	// The 415 KB HLS library is injected on demand as a classic script;
	// preloading it would undo the lazy-load fix. Note this is vendor/hls.min.js
	// specifically — hlsCompat.js is a small pure-logic helper that IS part of
	// the module graph and must be preloaded, so a substring test on "hls" would
	// wrongly flag it.
	if got["vendor/hls.min.js"] {
		t.Error("vendor/hls.min.js must stay lazy, but it is preloaded")
	}
}

// TestETagRevalidation guards the cache fix: the response must carry a validator
// and a repeat request must be answered 304 with no body.
func TestETagRevalidation(t *testing.T) {
	for _, target := range []string{"/", "/" + bundlePath, "/app.js", "/css/base.css"} {
		first := do(t, http.MethodGet, target, nil)
		if first.Code != http.StatusOK {
			t.Fatalf("GET %s = %d, want 200", target, first.Code)
		}
		etag := first.Header().Get("ETag")
		if etag == "" {
			t.Errorf("%s has no ETag", target)
			continue
		}
		if cc := first.Header().Get("Cache-Control"); cc != "no-cache" {
			t.Errorf("%s Cache-Control = %q, want no-cache", target, cc)
		}

		second := do(t, http.MethodGet, target, map[string]string{"If-None-Match": etag})
		if second.Code != http.StatusNotModified {
			t.Errorf("GET %s with matching If-None-Match = %d, want 304", target, second.Code)
		}
		if second.Body.Len() != 0 {
			t.Errorf("304 for %s carried a %d-byte body", target, second.Body.Len())
		}
		if second.Header().Get("ETag") != etag {
			t.Errorf("304 for %s dropped the ETag", target)
		}
	}

	// A stale validator must still produce a full response.
	stale := do(t, http.MethodGet, "/app.js", map[string]string{"If-None-Match": `"deadbeef"`})
	if stale.Code != http.StatusOK {
		t.Errorf("GET /app.js with stale ETag = %d, want 200", stale.Code)
	}
	// Weak and list forms must both match.
	etag := do(t, http.MethodGet, "/app.js", nil).Header().Get("ETag")
	for _, hdr := range []string{"W/" + etag, `"x", ` + etag, "*"} {
		if r := do(t, http.MethodGet, "/app.js", map[string]string{"If-None-Match": hdr}); r.Code != http.StatusNotModified {
			t.Errorf("If-None-Match %q = %d, want 304", hdr, r.Code)
		}
	}
}

// TestHeadIsSupported guards the 405 fix.
func TestHeadIsSupported(t *testing.T) {
	for _, target := range []string{"/", "/app.js", "/" + bundlePath} {
		rec := do(t, http.MethodHead, target, nil)
		if rec.Code != http.StatusOK {
			t.Errorf("HEAD %s = %d, want 200", target, rec.Code)
		}
		if rec.Body.Len() != 0 {
			t.Errorf("HEAD %s returned a body of %d bytes", target, rec.Body.Len())
		}
		if rec.Header().Get("Content-Length") == "" {
			t.Errorf("HEAD %s has no Content-Length", target)
		}
		if rec.Header().Get("ETag") == "" {
			t.Errorf("HEAD %s has no ETag", target)
		}
	}
}

func TestUnsupportedMethodRejected(t *testing.T) {
	rec := do(t, http.MethodPost, "/", nil)
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("POST / = %d, want 405", rec.Code)
	}
	if allow := rec.Header().Get("Allow"); !strings.Contains(allow, "GET") || !strings.Contains(allow, "HEAD") {
		t.Errorf("Allow = %q, want it to include GET and HEAD", allow)
	}
}

// TestTraversalAndUnknownPathsAreNotFound confirms the lookup is confined to the
// pre-built asset map.
func TestTraversalAndUnknownPathsAreNotFound(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	for _, p := range []string{"/../static.go", "/..%2f..%2fetc/passwd", "/nope.js", "/css/", "/css"} {
		req.URL.Path = p
		rec := httptest.NewRecorder()
		Static.ServeHTTP(rec, req)
		if rec.Code != http.StatusNotFound {
			t.Errorf("GET %s = %d, want 404", p, rec.Code)
		}
	}
}

// TestJavaScriptContentType matters because a module served with the wrong MIME
// type is refused by the browser.
func TestJavaScriptContentType(t *testing.T) {
	for _, target := range []string{"/app.js", "/boot.js", "/vendor/hls.min.js"} {
		rec := do(t, http.MethodGet, target, nil)
		if ct := rec.Header().Get("Content-Type"); !strings.HasPrefix(ct, "text/javascript") {
			t.Errorf("%s Content-Type = %q, want text/javascript", target, ct)
		}
	}
	if ct := do(t, http.MethodGet, "/"+bundlePath, nil).Header().Get("Content-Type"); !strings.HasPrefix(ct, "text/css") {
		t.Errorf("bundle Content-Type = %q, want text/css", ct)
	}
	if ct := do(t, http.MethodGet, "/", nil).Header().Get("Content-Type"); !strings.HasPrefix(ct, "text/html") {
		t.Errorf("index Content-Type = %q, want text/html", ct)
	}
}

func TestStaticServesPrecompressedGzipWithDistinctETag(t *testing.T) {
	h := Static
	reqPlain := httptest.NewRequest(http.MethodGet, "/app.js", nil)
	recPlain := httptest.NewRecorder()
	h.ServeHTTP(recPlain, reqPlain)
	if recPlain.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", recPlain.Code)
	}
	etagPlain := recPlain.Header().Get("ETag")

	reqGz := httptest.NewRequest(http.MethodGet, "/app.js", nil)
	reqGz.Header.Set("Accept-Encoding", "gzip, deflate")
	recGz := httptest.NewRecorder()
	h.ServeHTTP(recGz, reqGz)
	if recGz.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", recGz.Code)
	}
	if recGz.Header().Get("Content-Encoding") != "gzip" {
		t.Fatalf("expected Content-Encoding: gzip, got %q", recGz.Header().Get("Content-Encoding"))
	}
	etagGz := recGz.Header().Get("ETag")
	if etagGz == "" || etagGz == etagPlain {
		t.Fatalf("expected distinct gzip ETag, plain=%q gz=%q", etagPlain, etagGz)
	}

	gr, err := gzip.NewReader(bytes.NewReader(recGz.Body.Bytes()))
	if err != nil {
		t.Fatal(err)
	}
	defer gr.Close()
	unzipped, err := io.ReadAll(gr)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(unzipped, recPlain.Body.Bytes()) {
		t.Fatal("decompressed gzip body does not match plain body")
	}
}
