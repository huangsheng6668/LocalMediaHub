package web

import (
	"bytes"
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io/fs"
	"mime"
	"net/http"
	"path"
	"regexp"
	"strconv"
	"strings"
)

// static.go serves the embedded Web UI with a deliberate HTTP caching policy and
// two startup-time optimisations. It replaces the bare
// `http.FileServer(http.FS(web.Assets))` that used to be mounted at `/*`.
//
// Why it exists (2026-09-30):
//
//  1. embed.FS reports a zero ModTime, so http.ServeContent emits neither
//     Last-Modified nor ETag. The browser therefore could never revalidate:
//     `curl -H 'If-Modified-Since: …' /app.js` returned 200, not 304, and there
//     was no Cache-Control at all. Every navigation re-downloaded the whole
//     asset set (~200 KB gzipped) even though nothing had changed.
//
//  2. index.html linked 13 stylesheets individually. The server speaks plain
//     HTTP/1.1 (browsers do not implement h2c), so the 6-connections-per-origin
//     cap turned those into extra round trips. They are now concatenated into a
//     single response.
//
//  3. The 33-module ES graph loads as a waterfall: each module's imports are
//     only discovered after that module arrives. Emitting <link
//     rel="modulepreload"> for the whole graph lets the browser start every
//     request at parse time instead of wave by wave.
//
// Cache policy: strong ETag + `no-cache` (revalidate) for every asset. The URLs
// carry no build stamp, so a long max-age would risk serving stale JS/CSS after
// a server upgrade. Revalidation makes repeat loads cheap (empty 304s) while
// keeping correctness exact. Full immutability would require rewriting every
// import specifier to a versioned URL — an asset pipeline, which this project
// deliberately does not have.

const (
	// bundlePath is the synthetic URL under which every stylesheet referenced by
	// index.html is served as one concatenated response. It lives at the same
	// directory depth as the original `css/*.css` files so that relative url()
	// references keep resolving identically. (Today all url() references in the
	// stylesheets are absolute `/fonts/…`, but keeping the depth makes the
	// bundle safe if that ever changes.)
	bundlePath = "css/bundle.css"
	indexPath  = "index.html"
	// entryPath is the ES module entry point referenced by index.html. The
	// modulepreload graph is walked from here.
	entryPath = "app.js"
)

// Static is the production handler for the embedded Web UI. It is built once at
// package init; see buildStatic for the failure mode (it degrades to serving the
// original files untouched rather than failing to start).
var Static http.Handler = buildStatic()

type asset struct {
	body     []byte
	gzipBody []byte
	ct       string
	// etag includes the surrounding double quotes, so it can be compared to an
	// If-None-Match entry directly after stripping an optional `W/` prefix.
	etag     string
	gzipETag string
}

// matchETag reports whether an If-None-Match header selects this representation.
// Comparison is weak (RFC 9110 §8.8.3.2): the header may carry a `W/` prefix and
// a comma-separated list, and `*` matches anything.
func matchETag(header, etag string) bool {
	for _, part := range strings.Split(header, ",") {
		part = strings.TrimSpace(part)
		if part == "*" {
			return true
		}
		if strings.TrimPrefix(part, "W/") == etag {
			return true
		}
	}
	return false
}

func (a *asset) match(header string) bool {
	return matchETag(header, a.etag)
}

func (a *asset) serve(w http.ResponseWriter, r *http.Request) {
	useGzip := len(a.gzipBody) > 0 && strings.Contains(r.Header.Get("Accept-Encoding"), "gzip")
	etag := a.etag
	body := a.body
	if useGzip {
		etag = a.gzipETag
		body = a.gzipBody
	}
	h := w.Header()
	h.Set("Content-Type", a.ct)
	h.Set("ETag", etag)
	// `no-cache` means "revalidate before reuse", not "do not store". Combined
	// with the strong ETag this yields empty 304s on repeat loads.
	h.Set("Cache-Control", "no-cache")
	// The gzip middleware re-encodes the body, so the response genuinely varies.
	h.Set("Vary", "Accept-Encoding")

	if matchETag(r.Header.Get("If-None-Match"), etag) {
		// 304 must not carry Content-Length or a body; net/http strips the
		// latter, and omitting the former keeps the response minimal.
		w.WriteHeader(http.StatusNotModified)
		return
	}
	if useGzip {
		h.Set("Content-Encoding", "gzip")
	}
	h.Set("Content-Length", strconv.Itoa(len(body)))
	if r.Method == http.MethodHead {
		w.WriteHeader(http.StatusOK)
		return
	}
	_, _ = w.Write(body)
}

// staticAssets is the immutable, fully pre-computed asset set.
type staticAssets struct {
	files map[string]*asset
	// bundled reports whether the stylesheet concatenation and the modulepreload
	// injection were applied. When false the handler still works; it just serves
	// index.html as authored.
	bundled bool
}

func (s *staticAssets) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	name := strings.TrimPrefix(path.Clean("/"+r.URL.Path), "/")
	if name == "" || name == "." {
		name = indexPath
	}

	a, ok := s.files[name]
	if !ok {
		http.NotFound(w, r)
		return
	}

	a.serve(w, r)
}

// buildStatic loads every embedded asset and computes the derived responses.
// Any failure is non-fatal: the offending optimisation is skipped so the server
// still starts and serves the UI (just unoptimised).
func buildStatic() *staticAssets {
	s := &staticAssets{files: map[string]*asset{}}

	_ = fs.WalkDir(Assets, ".", func(p string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		b, readErr := fs.ReadFile(Assets, p)
		if readErr != nil {
			return nil
		}
		s.files[p] = newAsset(b, p)
		return nil
	})

	raw, err := fs.ReadFile(Assets, indexPath)
	if err != nil {
		return s
	}

	out := raw
	out = s.collapseStylesheets(out)
	out = s.injectModulePreload(out)
	if !bytes.Equal(out, raw) {
		s.files[indexPath] = newAsset(out, indexPath)
		s.bundled = true
	}
	return s
}

// collapseStylesheets concatenates the stylesheets referenced by index.html into
// bundlePath and rewrites the document to link only the bundle. Cascade order is
// preserved because concatenation follows document order — which is exactly the
// order the browser would have applied the individual sheets in.
//
// If any referenced sheet is missing the document is returned unchanged, so a
// typo in index.html degrades to the previous 13-request behaviour rather than
// to a broken page.
func (s *staticAssets) collapseStylesheets(index []byte) []byte {
	links := stylesheetLinks(index)
	if len(links) < 2 {
		return index
	}

	var buf bytes.Buffer
	for _, l := range links {
		b, err := fs.ReadFile(Assets, l.href)
		if err != nil {
			return index
		}
		// Banner comments keep DevTools usable: the Sources/Styles panel shows
		// which file each rule came from.
		fmt.Fprintf(&buf, "\n/* ===== %s ===== */\n", l.href)
		buf.Write(b)
		buf.WriteByte('\n')
	}
	s.files[bundlePath] = newAsset(buf.Bytes(), bundlePath)

	out := index
	for i, l := range links {
		replacement := ""
		if i == 0 {
			replacement = `<link rel="stylesheet" href="/` + bundlePath + `">`
		}
		out = bytes.Replace(out, l.tag, []byte(replacement), 1)
	}
	return out
}

// injectModulePreload emits a <link rel="modulepreload"> for every module
// reachable from entryPath, just before </head>. The browser then starts all
// module requests at parse time instead of discovering them wave by wave — the
// single biggest latency win available to a no-build-step ES module app on
// HTTP/1.1.
//
// modulepreload populates the module map, so the entry's own imports do not
// re-request anything. The graph walk only follows *static* imports; hls.js is
// injected at runtime as a classic script and is deliberately not preloaded
// (that is the P1-1 fix — it must stay lazy).
func (s *staticAssets) injectModulePreload(index []byte) []byte {
	mods := s.moduleGraph()
	if len(mods) == 0 {
		return index
	}

	var buf bytes.Buffer
	for _, m := range mods {
		fmt.Fprintf(&buf, `    <link rel="modulepreload" href="%s">`+"\n", m)
	}
	// Insert before </head> so the hints are seen as early as possible.
	marker := []byte("</head>")
	i := bytes.Index(index, marker)
	if i < 0 {
		return index
	}
	out := make([]byte, 0, len(index)+buf.Len())
	out = append(out, index[:i]...)
	out = append(out, buf.Bytes()...)
	out = append(out, index[i:]...)
	return out
}

// moduleGraph returns the embedded modules reachable from entryPath, in a stable
// (breadth-first, discovery) order, excluding the entry itself.
func (s *staticAssets) moduleGraph() []string {
	if _, ok := s.files[entryPath]; !ok {
		return nil
	}
	seen := map[string]bool{entryPath: true}
	queue := []string{entryPath}
	var out []string

	for len(queue) > 0 {
		cur := queue[0]
		queue = queue[1:]
		a, ok := s.files[cur]
		if !ok {
			continue
		}
		dir := path.Dir(cur)
		for _, spec := range importSpecifiers(a.body) {
			if !strings.HasSuffix(spec, ".js") {
				continue // skip bare/node: specifiers and non-JS assets
			}
			dep := path.Clean(path.Join(dir, spec))
			if seen[dep] {
				continue
			}
			// Only preload files that actually exist in the embedded FS, so a
			// specifier found inside a comment or string cannot produce a 404.
			if _, ok := s.files[dep]; !ok {
				continue
			}
			seen[dep] = true
			out = append(out, dep)
			queue = append(queue, dep)
		}
	}
	return out
}

var (
	linkTagRe = regexp.MustCompile(`(?is)<link\b[^>]*\brel\s*=\s*["']stylesheet["'][^>]*>`)
	hrefRe    = regexp.MustCompile(`(?is)\bhref\s*=\s*["']([^"']+)["']`)
	// Matches `import … from '…'`, `export … from '…'` and side-effect
	// `import '…'`. Dynamic import() is intentionally not matched: those are
	// lazy by design and must not be pulled into the first load.
	importFromRe = regexp.MustCompile(`(?m)\bfrom\s*["']([^"']+)["']`)
	importBareRe = regexp.MustCompile(`(?m)\bimport\s*["']([^"']+)["']`)
)

type styleLink struct {
	tag  []byte
	href string
}

// stylesheetLinks returns the document's local stylesheet references in order.
// Absolute URLs (CDN links) are skipped — they cannot be concatenated into the
// embedded bundle.
func stylesheetLinks(index []byte) []styleLink {
	var out []styleLink
	for _, tag := range linkTagRe.FindAll(index, -1) {
		m := hrefRe.FindSubmatch(tag)
		if m == nil {
			continue
		}
		href := string(m[1])
		if strings.Contains(href, "://") || strings.HasPrefix(href, "//") {
			continue
		}
		href = strings.TrimPrefix(href, "/")
		if href == bundlePath {
			continue
		}
		out = append(out, styleLink{tag: tag, href: href})
	}
	return out
}

// importSpecifiers returns the raw module specifiers referenced by a source file.
func importSpecifiers(src []byte) []string {
	var out []string
	for _, re := range []*regexp.Regexp{importFromRe, importBareRe} {
		for _, m := range re.FindAllSubmatch(src, -1) {
			out = append(out, string(m[1]))
		}
	}
	return out
}

func newAsset(body []byte, name string) *asset {
	sum := sha256.Sum256(body)
	etag := `"` + hex.EncodeToString(sum[:16]) + `"`
	a := &asset{
		body: body,
		ct:   contentType(name),
		etag: etag,
	}

	ext := strings.ToLower(path.Ext(name))
	switch ext {
	case ".js", ".mjs", ".css", ".html", ".svg", ".json":
		var buf bytes.Buffer
		gw, err := gzip.NewWriterLevel(&buf, gzip.BestCompression)
		if err == nil {
			if _, err := gw.Write(body); err == nil {
				if err := gw.Close(); err == nil {
					if buf.Len() > 0 && buf.Len() < len(body) {
						a.gzipBody = buf.Bytes()
						a.gzipETag = strings.TrimSuffix(etag, `"`) + `-gz"`
					}
				}
			}
		}
	}
	return a
}

// contentTypes covers every extension in the //go:embed glob. .js in particular
// must be text/javascript or the browser refuses to execute it as a module.
var contentTypes = map[string]string{
	".html":  "text/html; charset=utf-8",
	".css":   "text/css; charset=utf-8",
	".js":    "text/javascript; charset=utf-8",
	".mjs":   "text/javascript; charset=utf-8",
	".json":  "application/json; charset=utf-8",
	".svg":   "image/svg+xml",
	".png":   "image/png",
	".ico":   "image/x-icon",
	".woff":  "font/woff",
	".woff2": "font/woff2",
}

func contentType(name string) string {
	ext := strings.ToLower(path.Ext(name))
	if ct, ok := contentTypes[ext]; ok {
		return ct
	}
	if ct := mime.TypeByExtension(ext); ct != "" {
		return ct
	}
	return "application/octet-stream"
}
