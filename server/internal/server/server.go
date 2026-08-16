package server

import (
	"context"
	"fmt"
	"net"
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
	// BLE is unavailable or the build lacks the bluetooth tag). It is consumed
	// internally by the build-tag-guarded wireBleAutoRestart method
	// (windows && bluetooth), which injects a ConnectRecorder (BleHealthMonitor)
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
	// Bluetooth adapter is present OR the build was compiled without the
	// "bluetooth" tag, NewCentralScanner returns (nil, err) and we leave
	// h.BLECentral as a true nil interface so the /api/v1/ble/* handlers'
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
	// unavailable or the build is not windows && bluetooth (stub method).
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

	// pprof endpoints for live profiling. Round 32 S3: routes are OFF by
	// default and only registered when the operator explicitly opts in via
	// config.debug.pprof=true OR the --debug-pprof CLI flag (flag wins).
	// PrivateNetOnly is retained as defense-in-depth so even when enabled,
	// heap/goroutine data is only reachable from private/loopback IPs.
	if s.Config.Debug.Pprof {
		pprofGroup := s.Echo.Group("/debug/pprof", middleware.PrivateNetOnly())
		pprofGroup.Any("/*", echo.WrapHandler(http.DefaultServeMux))
	}

	// Static Web UI Assets
	s.Echo.GET("/*", echo.WrapHandler(http.FileServer(http.FS(web.Assets))))

	// Favicon: serve a procedurally-generated PNG so the browser's automatic
	// /favicon.ico request resolves instead of 404ing through the static handler.
	s.Echo.GET("/favicon.ico", s.serveFavicon)

	api := s.Echo.Group("/api/v1")

	// Auth middleware: gates sensitive endpoints on the configured token.
	// Empty token = open mode (passthrough), logged at startup.
	authMw := middleware.BearerToken(s.Config.Server.Token)

	api.GET("/health", func(c echo.Context) error {
		return c.JSON(http.StatusOK, map[string]string{"status": "ok"})
	})

	// Folders
	api.GET("/folders", h.GetFolders)
	api.GET("/folders/*", h.BrowseFolder,
		authZipDownload(authMw),
		rateLimitWhen(isFolderZipDownload, middleware.RateLimit(2, 5*time.Minute)))

	// Videos
	api.GET("/videos", h.GetVideos)
	api.GET("/videos/*", h.GetVideoAsset,
		rateLimitWhen(isTranscodeRequest, middleware.RateLimit(5, time.Minute)))

	// Images
	api.GET("/images", h.GetImages)
	api.GET("/images/*", h.GetImageAsset)

	// Texts
	api.GET("/texts", h.GetTexts)

	// Search
	api.GET("/search", h.Search)

	// Tags
	api.GET("/tags", h.GetTags)
	// Mutation endpoints are auth-gated (security hardening): tag create /
	// delete and file association write to the shared tags DB, so they must
	// not be reachable unauthenticated. Reads stay public (documented design).
	api.POST("/tags", h.CreateTag, authMw)
	api.DELETE("/tags/:tag_id", h.DeleteTag, authMw)
	api.POST("/tags/:tag_id/files/*", h.AssociateTag, authMw)
	api.DELETE("/tags/:tag_id/files/*", h.DisassociateTag, authMw)
	api.GET("/tags/:tag_id/files", h.GetTaggedFiles)
	api.GET("/tags/:tag_id/media", h.GetTaggedMedia)
	api.GET("/tags/file-tags", h.GetFileTags)

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

// authZipDownload requires authentication ONLY for the /download suffix of
// /api/v1/folders/*, keeping regular folder browsing public per the existing
// product design while closing the unauthenticated whole-tree ZIP download.
func authZipDownload(auth echo.MiddlewareFunc) echo.MiddlewareFunc {
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			if isFolderZipDownload(c) {
				return auth(next)(c)
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
	ips := getAllLocalIPs()
	if len(ips) == 0 {
		return "127.0.0.1", nil
	}
	// getAllLocalIPs returns private LAN IPs first, so the head is the best pick.
	return ips[0], nil
}

// getAllLocalIPs returns all usable IPv4 addresses on this machine, with
// private LAN addresses first, then any other non-loopback address, and
// finally 127.0.0.1. APIPA (169.254.x.x) addresses and addresses on virtual
// adapters (VMware vmnet*, VirtualBox vboxnet*, Hyper-V/WSL vEthernet*,
// Docker) are skipped so mDNS broadcasts the host's real LAN IP rather than a
// host-only/VM subnet that other devices can't reach. The ordering makes the
// first element a good default for mDNS broadcast, while the full list is used
// to build the CORS allow-list so browsers on the LAN can reach the embedded
// Web UI.
func getAllLocalIPs() []string {
	var private, others []string

	ifaces, err := net.Interfaces()
	if err != nil {
		return []string{"127.0.0.1"}
	}
	for _, ifc := range ifaces {
		// Skip virtual machine / container adapters — their addresses sit on
		// isolated host-only or NAT subnets that LAN clients cannot route to.
		if isVirtualAdapter(ifc.Name) {
			continue
		}
		addrs, err := ifc.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			ipNet, ok := addr.(*net.IPNet)
			if !ok || ipNet.IP.IsLoopback() {
				continue
			}
			ip := ipNet.IP.To4()
			if ip == nil {
				continue
			}
			// Skip APIPA (link-local) addresses.
			if ip[0] == 169 && ip[1] == 254 {
				continue
			}

			isPrivate := (ip[0] == 192 && ip[1] == 168) ||
				(ip[0] == 10) ||
				(ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31)

			if isPrivate {
				private = append(private, ip.String())
			} else {
				others = append(others, ip.String())
			}
		}
	}

	result := append(private, others...)
	result = append(result, "127.0.0.1")
	return result
}

// virtualAdapterPrefixes lists lower-cased interface-name prefixes that mark a
// virtual machine / container / tunnel adapter. Matches are skipped by
// getAllLocalIPs so discovery targets the physical LAN only.
var virtualAdapterPrefixes = []string{
	"vmnet",       // VMware
	"vboxnet",     // VirtualBox host-only
	"vethernet",   // Hyper-V / WSL
	"docker",      // Docker bridge
	"virtualbox",  // alt VirtualBox naming
	"tap-",        // OpenVPN / TAP
	"tun-",        // tunnel adapters
	"isatap",      // ISATAP tunneling
	"teredo",      // Teredo tunneling
}

func isVirtualAdapter(name string) bool {
	lower := strings.ToLower(name)
	for _, p := range virtualAdapterPrefixes {
		if strings.HasPrefix(lower, p) {
			return true
		}
	}
	return false
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
