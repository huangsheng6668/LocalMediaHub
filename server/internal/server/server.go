package server

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	_ "net/http/pprof"
	"strings"
	"sync"
	"time"

	"github.com/labstack/echo/v4"
	echoMw "github.com/labstack/echo/v4/middleware"

	"github.com/localmediahub/server/internal/ble"
	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/models"
	"github.com/localmediahub/server/internal/netutil"
	"github.com/localmediahub/server/internal/server/handler"
	"github.com/localmediahub/server/internal/server/middleware"
	"github.com/localmediahub/server/internal/service"
	"github.com/localmediahub/server/internal/web"
)

type Server struct {
	Echo         *echo.Echo
	Config       *config.Config
	IP           string
	Scanner      *service.Scanner
	Tags         *service.TagsService
	Streaming    *service.StreamingService
	Thumbnail    *service.ThumbnailService
	httpServer   *http.Server
	preGenCtx    context.Context
	preGenCancel context.CancelFunc
	preGenMu     sync.Mutex
	// bleListenerCtx is cancelled on Stop() to terminate the long-lived
	// CMD_API_REQ dispatcher (ble.Central.RunApiListener). Nil when BLE is
	// unavailable or no provider was wired.
	bleListenerCtx    context.Context
	bleListenerCancel context.CancelFunc
	// bleScanner is the concrete Central-role scanner built in New (nil when
	// BLE is unavailable, e.g. no Bluetooth radio on the host). It is consumed
	// internally by the platform-guarded wireBleAutoRestart method (windows),
	// which injects a ConnectRecorder (BleHealthMonitor)
	// for stuck-detection auto-restart. The recorder is wired inside New AFTER
	// this field is set, because the BleHealthMonitor needs *Server for the
	// restarter, so it can only be built once the Server is constructible.
	bleScanner ble.CentralScanner
}

func New(cfg *config.Config) (*Server, error) {
	e := echo.New()
	e.HideBanner = true

	// Round N security hardening: derive client IPs from the socket address
	// only. Echo's default IPExtractor trusts X-Forwarded-For / X-Real-IP
	// headers, which any client can forge (no reverse proxy sits in front of
	// this LAN server). Forged IPs would rotate the rate-limit buckets
	// (middleware.RateLimit), bypass PrivateNetOnly for /debug/pprof, and let
	// an attacker reuse BookSigner HMAC signatures bound to a victim's IP.
	e.IPExtractor = echo.ExtractIPDirect()

	ip, err := getLocalIP()
	if err != nil {
		return nil, fmt.Errorf("failed to get local IP: %w", err)
	}

	scanner := service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions, cfg.Scan.TextExtensions)
	if err := scanner.StartWatching(cfg.Scan.Roots); err != nil {
		fmt.Printf("Warning: failed to start filesystem watcher: %v\n", err)
	}
	tagsService, err := service.NewTagsService(".data")
	if err != nil {
		return nil, fmt.Errorf("failed to create tags service: %w", err)
	}
	streamingService := service.NewStreamingService(cfg.System.FFmpegPath)
	thumbnailService, err := service.NewThumbnailService(cfg.Thumbnail.CacheDir, cfg.Thumbnail.MaxSize, cfg.Thumbnail.Format, cfg.System.FFmpegPath)
	if err != nil {
		return nil, fmt.Errorf("failed to create thumbnail service: %w", err)
	}
	bookService := service.NewBookService()
	// Round 32 Task 5: per-process HMAC secret for signing book image URLs.
	// A fresh secret is generated on every startup, so all outstanding
	// signed URLs become invalid the moment the server restarts. Failure to
	// read 32 bytes from crypto/rand is fatal — surface it to the caller.
	bookSigner, err := service.NewBookSigner()
	if err != nil {
		return nil, fmt.Errorf("failed to create book signer: %w", err)
	}
	bookService.SetSigner(bookSigner)

	s := &Server{
		Echo:      e,
		Config:    cfg,
		IP:        ip,
		Scanner:   scanner,
		Tags:      tagsService,
		Streaming: streamingService,
		Thumbnail: thumbnailService,
	}

	scanner.OnScanComplete = func(files []models.MediaFile) {
		s.preGenMu.Lock()
		if s.preGenCancel != nil {
			s.preGenCancel()
		}
		var ctx context.Context
		ctx, s.preGenCancel = context.WithCancel(context.Background())
		s.preGenMu.Unlock()

		// Round 32 Task 6: 分层预热。
		// Tier 1 来源：HotDirs(64) 返回 per-directory 访问计数 Top-64 的集合
		//   （来自 hotDirTracker，聚合自所有交互请求路径，5min+Shutdown 持久化）。
		// Tier 2 来源：cfg.Scan.GetRoots() 的根目录下直接文件，作为浏览入口。
		// Tier 3（其他深层文件）由 PreGen 跳过，依赖懒生成。
		hotDirs := s.Thumbnail.HotDirs(64)
		scanRoots := cfg.Scan.GetRoots()

		s.Thumbnail.PreGenerateThumbnails(files, ctx, hotDirs, scanRoots)
	}

	// books: BookService wired in Task 8 — drives /api/v1/books/info|chapter.
	h := handler.New(cfg, scanner, tagsService, streamingService, thumbnailService, bookService, bookSigner)

	// BLE GATT wiring Task 4: construct the BLE Central non-fatally. If no
	// Bluetooth adapter is present, NewCentralScanner returns (nil, err) and we
	// leave h.BLECentral as a true nil interface so the /api/v1/ble/* handlers'
	// `== nil` checks route to the "ble unavailable" responses (zero-regression).
	// CRITICAL (Gotcha 1): assign h.BLECentral ONLY when bleCentral is non-nil.
	// Assigning a nil *ble.Central would create a non-nil interface wrapping a
	// nil pointer, which would defeat the handlers' nil check.
		bleScanner, bleErr := ble.NewCentralScanner()
		if bleErr != nil {
			fmt.Printf("BLE Central disabled; /api/v1/ble/* will report unavailable: %v\n", bleErr)
		} else {
			s.bleScanner = bleScanner
			bleCentral := ble.NewCentral(bleScanner)
			// Phase 9 (H-1a) + 2026-08-30 open mode: derive the BLE auth key
			// from the effective BLE secret — ble.token first, server.token
			// fallback. Both empty = OPEN mode: no handshake, data frames
			// ride unauthenticated v1 (mirrors the open-LAN HTTP posture;
			// the WARN below states the accepted trade-off).
			effBleSecret := cfg.BLE.EffectiveToken(cfg.Server.Token)
			bleCentral.SetAuthToken(effBleSecret)
			if effBleSecret == "" {
				slog.Warn("BLE running in OPEN mode: any device in range can exchange data; set ble.token to require authentication")
			}
		// Spec §3.1: inject the bleApiProvider so the long-lived listener can
		// serve CMD_API_REQ frames for every endpoint (book chapter, folders,
		// browse folder, book info) out of the box. The provider adapts cfg +
		// BookService into the ble.ApiProvider contract.
		bleCentral.SetApiProvider(ble.NewBleApiProvider(cfg, bookService))
		h.BLECentral = bleCentral
		// Start the long-lived API-request dispatcher in a goroutine tied to
		// server lifetime. RunApiListener loops over WaitNotify + dispatches
		// each CMD_API_REQ to ServeApiRequest. It exits cleanly when
		// bleListenerCtx is cancelled in Stop(). The listener is intentionally
		// best-effort: if it errors (e.g. adapter reset), the goroutine logs
		// and returns — the HTTP server keeps serving and the Android client
		// simply re-requests over HTTP once Wi-Fi recovers.
		s.bleListenerCtx, s.bleListenerCancel = context.WithCancel(context.Background())
		go func() {
			if err := bleCentral.RunApiListener(s.bleListenerCtx); err != nil &&
				s.bleListenerCtx.Err() == nil {
				fmt.Printf("BLE API listener exited: %v\n", err)
			}
		}()
		fmt.Printf("BLE Central ready (service=%s); API listener started\n", ble.ServiceUUID)
	}

	s.registerRoutes(h)

	s.httpServer = &http.Server{
		Addr:              fmt.Sprintf("%s:%d", cfg.Server.Host, cfg.Server.Port),
		Handler:           s.Echo,
		ReadHeaderTimeout: 10 * time.Second, // mitigate Slowloris slow-header attacks
		ReadTimeout:       30 * time.Second, // covers small request bodies (e.g. config JSON)
		IdleTimeout:       120 * time.Second,
		// WriteTimeout intentionally 0: video streams and folder-zip downloads can
		// run for minutes-to-hours; a global write deadline would cut them off.
	}

	// BLE stuck-detection auto-restart (spec §3): parse LMH_BLE_RESTART_TS for
	// cooldown, build the self-restarter + health monitor, and inject the
	// monitor as the BLE scanner's ConnectRecorder. No-op when BLE is
	// unavailable or on non-Windows builds (stub method).
	// Done at the end of New so the restarter's *Server ref is fully
	// constructed; both the headless and GUI entry points inherit it for free.
	s.wireBleAutoRestart()

	return s, nil
}

func (s *Server) registerRoutes(h *handler.Handler) {
	// Recover must be first so panics in any handler are caught and converted
	// to a 500 instead of crashing the whole process.
	s.Echo.Use(echoMw.Recover())
	s.Echo.Use(echoMw.Logger())
	// Phase 9 (M-1): 全局请求体上限。合法最大 body 是 admin config roots
	// 与批量缩略图请求，4MiB 远超需求；防 LAN 内超大 JSON 打内存。
	s.Echo.Use(echoMw.BodyLimit("4M"))
	// Round 32 Task 5 (S2): redact ?token= from access log.
	// CRITICAL: registered AFTER echoMw.Logger() above. Echo middleware
	// executes in LIFO order on the request side, so a middleware registered
	// later runs FIRST. By registering this after Logger, our redact func
	// sees the request BEFORE Logger does — letting us overwrite RawQuery so
	// the log line shows token=REDACTED instead of the bearer value.
	// _ = c.QueryParams() forces Echo to parse and cache the query params
	// into its internal context BEFORE we mutate RawQuery; downstream code
	// (notably middleware.BearerToken's c.QueryParam("token") fallback)
	// continues to see the original value via that cached map.
	// Redact ?token= from access log while caching query params for AuthMw
	s.Echo.Use(func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			_ = c.QueryParams() // 触发 QueryParams() 解析并缓存到 context
			req := c.Request()
			q := req.URL.Query()
			if q.Get("token") != "" {
				q.Set("token", "REDACTED")
				req.URL.RawQuery = q.Encode()
				// Echo Logger 打印 req.RequestURI（请求行原文，不随 URL 同步），必须一并改写
				req.RequestURI = req.URL.Path + "?" + req.URL.RawQuery
			}
			return next(c)
		}
	})
	// B3: gzip JSON responses to reduce LAN transfer time (a 500-item folder
	// listing is ~500KB raw → ~80KB compressed, a ~6x wire reduction).
	// Skip binary endpoints (video/image/zip are already compressed, so gzip
	// would waste CPU for ~0 ratio gain and double-buffer large streams).
	//
	// CRITICAL: the Skipper MUST use c.Request().URL.Path (actual request path),
	// NOT c.Path() (route template). Route templates like "/api/v1/videos/*"
	// do NOT contain "/stream", so c.Path() would fail to skip transcoded
	// streams and double-gzip video bytes. URL.Path is the concrete path the
	// client requested (e.g. "/api/v1/videos/foo/stream") and matches correctly.
	s.Echo.Use(echoMw.GzipWithConfig(echoMw.GzipConfig{
		Level: 5,
		Skipper: func(c echo.Context) bool {
			path := c.Request().URL.Path
			if strings.Contains(path, "/stream") ||
				strings.Contains(path, "/thumbnail") ||
				strings.Contains(path, "/original") ||
				strings.Contains(path, "/download") {
				return true
			}
			return false
		},
	}))
	// Phase 4: security headers must run BEFORE CORS so OPTIONS preflight
	// responses also carry X-Frame-Options / X-Content-Type-Options /
	// Referrer-Policy / Content-Security-Policy. See middleware.SecurityHeaders.
	s.Echo.Use(middleware.SecurityHeaders())
	// CORS is restricted to this host's LAN IPs + localhost so only devices on
	// the local network can drive the embedded Web UI (and its destructive
	// endpoints). See allowedCORSOrigins for details.
	s.Echo.Use(middleware.CORS(allowedCORSOrigins(s.Config.Server.Port)))

	// Auth middleware: gates sensitive endpoints on the configured token.
	// Empty token = open mode (passthrough), logged at startup.
	// Phase 9 (M-2): per-IP auth-failure backoff on the same instance —
	// 10 failed attempts per IP per 60s window escalates to 429, throttling
	// online token brute-forcing. Successful auth resets the IP counter.
	// Declared before the pprof block so the /debug/pprof group can share it
	// (Phase 9 L-2).
	authFailLimiter := middleware.NewAuthFailureLimiter(10, time.Minute)
	authMw := middleware.BearerToken(s.Config.Server.Token, authFailLimiter)

	// pprof endpoints for live profiling. Round 32 S3: routes are OFF by
	// default and only registered when the operator explicitly opts in via
	// config.debug.pprof=true OR the --debug-pprof CLI flag (flag wins).
	// PrivateNetOnly is retained as defense-in-depth so even when enabled,
	// heap/goroutine data is only reachable from private/loopback IPs.
	// Phase 9 (L-2): authMw is layered on top — in token mode the profiles
	// additionally require the Bearer token (heap/goroutine dumps can embed
	// secrets and every LAN peer is a "private" IP, so PrivateNetOnly alone
	// is not a meaningful gate there). Open mode keeps the previous
	// PrivateNetOnly-only semantics (BearerToken is a passthrough).
	if s.Config.Debug.Pprof {
		pprofGroup := s.Echo.Group("/debug/pprof", middleware.PrivateNetOnly(), authMw)
		pprofGroup.Any("/*", echo.WrapHandler(http.DefaultServeMux))
	}

	// Static Web UI Assets
	s.Echo.GET("/*", echo.WrapHandler(http.FileServer(http.FS(web.Assets))))

	// Favicon: serve a procedurally-generated PNG so the browser's automatic
	// /favicon.ico request resolves instead of 404ing through the static handler.
	s.Echo.GET("/favicon.ico", s.serveFavicon)

	api := s.Echo.Group("/api/v1")

	// authMw is declared above the pprof block and shared by every gated
	// group below (admin / system / media / books / ble + /debug/pprof).

	api.GET("/health", func(c echo.Context) error {
		return c.JSON(http.StatusOK, map[string]string{"status": "ok"})
	})

	// LAN app pairing (zero-touch setup): unauthenticated by design, gated by
	// the opt-in `server.lan_pairing` config flag and rate-limited. See
	// handler.Pair for the security trade-off notes.
	api.POST("/pair", h.Pair, middleware.RateLimit(5, time.Minute))

	// Folders
	// Phase 9 (H-2): media read endpoints are auth-gated. Previously the
	// media library could be enumerated and streamed anonymously by anyone on
	// the LAN; now /folders, /videos, /images, /texts and /search all require
	// the bearer token. authZipDownload (zip-download-only auth) was removed —
	// the whole /folders/* route is now unconditionally gated, which covers
	// zip downloads too. Open-mode (empty token) deployments are unchanged:
	// middleware.BearerToken is a passthrough when no token is configured.
	api.GET("/folders", h.GetFolders, authMw)
	api.GET("/folders/*", h.BrowseFolder,
		authMw,
		rateLimitWhen(isFolderZipDownload, middleware.RateLimit(2, 5*time.Minute)))

	// Videos
	api.GET("/videos", h.GetVideos, authMw)
	api.GET("/videos/*", h.GetVideoAsset,
		authMw,
		rateLimitWhen(isTranscodeRequest, middleware.RateLimit(5, time.Minute)),
		// Phase 9 (M-3): thumbnail misses fork ffmpeg/ffprobe + decode a
		// full-size frame — a CPU/disk amplifier when filenames are enumerated.
		rateLimitWhen(isThumbnailRequest, middleware.RateLimit(60, time.Minute)))

	// Images
	api.GET("/images", h.GetImages, authMw)
	// Phase 9 (M-3): each miss decodes a full-size image to build the thumbnail;
	// rate-limit to blunt filename-enumeration floods.
	api.GET("/images/*", h.GetImageAsset, authMw, middleware.RateLimit(60, time.Minute))

	// Texts
	api.GET("/texts", h.GetTexts, authMw)

	// Search
	api.GET("/search", h.Search, authMw)

	// Tags
	// Phase 9 (I-3): tag READS are auth-gated too (same reasoning as the
	// H-2 media reads): the tag graph enumerates which files exist on the
	// host — an information disclosure when token mode is on. Empty-token
	// (open mode) deployments are unchanged: middleware.BearerToken is a
	// passthrough when no token is configured (compat argument identical to
	// the media reads: Android AuthInterceptor + web apiRequest inject the
	// header on every call).
	api.GET("/tags", h.GetTags, authMw)
	// Mutation endpoints are auth-gated (security hardening): tag create /
	// delete and file association write to the shared tags DB, so they must
	// not be reachable unauthenticated.
	api.POST("/tags", h.CreateTag, authMw)
	api.DELETE("/tags/:tag_id", h.DeleteTag, authMw)
	api.POST("/tags/:tag_id/files/*", h.AssociateTag, authMw)
	api.DELETE("/tags/:tag_id/files/*", h.DisassociateTag, authMw)
	api.GET("/tags/:tag_id/files", h.GetTaggedFiles, authMw)
	api.GET("/tags/:tag_id/media", h.GetTaggedMedia, authMw)
	api.GET("/tags/file-tags", h.GetFileTags, authMw)

	// Admin
	admin := api.Group("/admin", authMw)
	admin.GET("/config", h.GetConfig)
	admin.PUT("/config", h.UpdateConfig)
	admin.POST("/scan/trigger", h.TriggerScan, middleware.RateLimit(2, 30*time.Second))

	// System
	sys := api.Group("/system", authMw)
	sys.GET("/drives", h.GetDrives)
	sys.GET("/browse", h.SystemBrowse)
	sys.GET("/thumbnail", h.SystemThumbnail)
	sys.GET("/original", h.SystemOriginal)
	sys.GET("/stream", h.SystemStream, rateLimitWhen(isTranscodeRequest, middleware.RateLimit(5, time.Minute)))
	sys.POST("/delete", h.DeletePath, middleware.RateLimit(5, time.Minute))

	// Unified absolute-path media access
	media := api.Group("/media", authMw)
	media.GET("/thumbnail", h.MediaThumbnail)
	media.GET("/original", h.MediaOriginal)
	media.GET("/stream", h.MediaStream, rateLimitWhen(isTranscodeRequest, middleware.RateLimit(5, time.Minute)))
	media.GET("/duration", h.MediaDuration)
	// Batch thumbnail endpoint (grid N+1 collapse). Rate-limited because it
	// fans out to up to 64 thumbnail generations per request.
	media.POST("/thumbnails", h.MediaThumbnails, middleware.RateLimit(10, time.Minute))

	// Books (text-reader, Task 8): metadata + per-chapter text content for
	// .txt / .epub files. Auth-gated because chapter content can be large and
	// these endpoints perform disk I/O on each chapter fetch. The /image
	// endpoint serves raw image bytes from inside an epub; BearerToken
	// middleware accepts a ?token= query fallback so <img> tags can
	// authenticate via the rewritten Src URL produced by GetChapterBlocks.
	books := api.Group("/books", authMw)
	books.GET("/info", h.GetBookInfo)
	books.GET("/chapter", h.GetBookChapter)
	// /image is deliberately OUTSIDE the auth group: <img> tags cannot send
	// Authorization headers, so a sig-only request would be 401'd by authMw
	// before the handler's HMAC check could run (making the signing mechanism
	// dead code in token mode). The handler authenticates via ?sig= (preferred)
	// or the deprecated ?token= (verified constant-time inside the handler).
	api.GET("/books/image", h.GetBookImage)
	// Round 32 Task 5: returns a signed <img src> URL bound to (clientIP,
	// path, manifestID). Authenticated via authMw — the endpoint itself
	// never returns a usable URL to an unauthenticated caller.
	books.GET("/sign-image", h.SignImage)

	// BLE GATT wiring Task 4: control channel for coordinating the PC's BLE
	// Central (scan/connect/send) from Android over Wi-Fi. Auth-gated like the
	// other action endpoints above — the spec (§6) requires Bearer Token on
	// /api/v1/ble/*. When BLE is unavailable the handlers return HTTP 200 with
	// an empty/error body (zero-regression), never 500.
	bleGroup := api.Group("/ble", authMw)
	bleGroup.GET("/scan", h.ScanBLE)
	bleGroup.POST("/connect", h.ConnectBLE)
	bleGroup.POST("/send", h.SendBLE)

	// Admin page
}

// isTranscodeRequest reports whether the request asks for real-time ffmpeg
// transcoding (one process fork per request — a CPU/DoS amplifier).
func isTranscodeRequest(c echo.Context) bool {
	return c.QueryParam("transcode") == "true"
}

// isFolderZipDownload reports whether the request targets the recursive
// folder-zip endpoint (walks the whole subtree and streams a ZIP — a disk-I/O
// amplifier).
func isFolderZipDownload(c echo.Context) bool {
	return strings.HasSuffix(c.Param("*"), "/download")
}

// isThumbnailRequest reports whether the request targets the thumbnail
// sub-resource of a wildcard media route (each miss forks ffmpeg/ffprobe or
// decodes a full-size image — a CPU/disk amplifier when filenames are
// enumerated). Matches on c.Param("*") — the wildcard portion of the concrete
// URL, same convention as isFolderZipDownload and the GetVideoAsset dispatch.
// c.Path() would return the route template ("/api/v1/videos/*") which never
// ends in "/thumbnail".
func isThumbnailRequest(c echo.Context) bool {
	return strings.HasSuffix(c.Param("*"), "/thumbnail")
}

// rateLimitWhen applies limiter only to requests satisfying cond. Used to
// guard transcode + zip-download without rate-limiting normal browsing or
// direct (non-transcoded) streams.
func rateLimitWhen(cond func(echo.Context) bool, limiter echo.MiddlewareFunc) echo.MiddlewareFunc {
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			if cond(c) {
				return limiter(next)(c)
			}
			return next(c)
		}
	}
}

// serveFavicon returns the procedurally-generated brand favicon PNG, cached for
// a week. Registered at /favicon.ico so the browser's automatic favicon request
// does not 404.
func (s *Server) serveFavicon(c echo.Context) error {
	c.Response().Header().Set("Cache-Control", "public, max-age=604800")
	return c.Blob(http.StatusOK, "image/png", web.FaviconPNG)
}

func (s *Server) Start() error {
	return s.httpServer.ListenAndServe()
}

func (s *Server) Stop() error {
	// Cancel any in-flight background scan so it doesn't keep walking the FS.
	s.Scanner.Shutdown()
	// Stop the BLE API-request listener (no-op when BLE was unavailable at
	// startup — bleListenerCancel is nil in that case).
	if s.bleListenerCancel != nil {
		s.bleListenerCancel()
	}
	// Close tags database connection
	if err := s.Tags.Close(); err != nil {
		fmt.Printf("Warning: failed to close tags database: %v\n", err)
	}
	// Cancel thumbnail pre-generation (preGenCancel is nil until the first scan
	// completes — guard against nil to avoid a panic).
	s.preGenMu.Lock()
	if s.preGenCancel != nil {
		s.preGenCancel()
	}
	s.preGenMu.Unlock()
	// Flush duration cache (durations.json) and cancel debounce goroutines.
	// Idempotent and safe to call even if nothing was ever cached.
	s.Thumbnail.Shutdown()
	// Drain in-flight requests (notably folder-zip downloads) before returning,
	// so Ctrl+C / tray-quit doesn't corrupt a half-written download.
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	return s.httpServer.Shutdown(ctx)
}

func getLocalIP() (string, error) {
	return netutil.GetLocalIP(), nil
}

// getAllLocalIPs delegates to the shared netutil implementation so the CORS
// allow-list, mDNS advertiser, and GUI tray URL all agree on which addresses
// to advertise (virtual adapters skipped, private LAN IPs first).
func getAllLocalIPs() []string {
	return netutil.GetAllLocalIPs()
}

// allowedCORSOrigins builds the list of browser origins permitted by CORS.
// It covers localhost (any port), 127.0.0.1, and every LAN IPv4 address this
// machine exposes, on the configured server port. This keeps cross-origin
// access restricted to the local network instead of the whole internet while
// still allowing other devices on the LAN to open the Web UI.
func allowedCORSOrigins(port int) []string {
	ips := getAllLocalIPs()
	origins := make([]string, 0, len(ips)*2+4)
	seen := make(map[string]struct{}, len(origins))
	add := func(o string) {
		if _, ok := seen[o]; !ok {
			seen[o] = struct{}{}
			origins = append(origins, o)
		}
	}
	for _, ip := range ips {
		add(fmt.Sprintf("http://%s:%d", ip, port))
	}
	// localhost variants (common ports included so dev tooling keeps working).
	for _, p := range []int{port, 80, 443, 5173} {
		add(fmt.Sprintf("http://localhost:%d", p))
	}
	return origins
}
