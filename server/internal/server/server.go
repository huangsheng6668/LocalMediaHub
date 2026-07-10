package server

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	_ "net/http/pprof"
	"strings"
	"sync"
	"time"

	"github.com/labstack/echo/v4"
	echoMw "github.com/labstack/echo/v4/middleware"

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
}

func New(cfg *config.Config) (*Server, error) {
	e := echo.New()
	e.HideBanner = true

	ip, err := getLocalIP()
	if err != nil {
		return nil, fmt.Errorf("failed to get local IP: %w", err)
	}

	scanner := service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions)
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

		s.Thumbnail.PreGenerateThumbnails(files, ctx)
	}

	h := handler.New(cfg, scanner, tagsService, streamingService, thumbnailService)

	// Security startup notice: warn loudly when running in open auth mode so
	// operators don't accidentally expose admin/system/media endpoints without
	// a token. When a token is configured, log a quiet info line instead.
	if cfg.Server.Token == "" {
		slog.Warn("==============================================================")
		slog.Warn(" SERVER IS RUNNING IN OPEN AUTH MODE (no token configured).")
		slog.Warn(" Any host on the LAN can call admin/system/media endpoints.")
		slog.Warn(" Set 'server.token' in config.yaml to enable authentication.")
		slog.Warn("==============================================================")
	} else {
		slog.Info("Auth: token-based authentication enabled for admin/system/media routes")
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

	return s, nil
}

func (s *Server) registerRoutes(h *handler.Handler) {
	// Recover must be first so panics in any handler are caught and converted
	// to a 500 instead of crashing the whole process.
	s.Echo.Use(echoMw.Recover())
	s.Echo.Use(echoMw.Logger())
	// CORS is restricted to this host's LAN IPs + localhost so only devices on
	// the local network can drive the embedded Web UI (and its destructive
	// endpoints). See allowedCORSOrigins for details.
	s.Echo.Use(middleware.CORS(allowedCORSOrigins(s.Config.Server.Port)))

	// pprof endpoints for live profiling. Restricted to private/loopback
	// IPs to avoid leaking heap/goroutine data on accidental public exposure.
	pprofGroup := s.Echo.Group("/debug/pprof", middleware.PrivateNetOnly())
	pprofGroup.Any("/*", echo.WrapHandler(http.DefaultServeMux))

	// Static Web UI Assets
	s.Echo.GET("/*", echo.WrapHandler(http.FileServer(http.FS(web.Assets))))

	// Favicon: serve a procedurally-generated PNG so the browser's automatic
	// /favicon.ico request resolves instead of 404ing through the static handler.
	s.Echo.GET("/favicon.ico", s.serveFavicon)

	api := s.Echo.Group("/api/v1")

	api.GET("/health", func(c echo.Context) error {
		return c.JSON(http.StatusOK, map[string]string{"status": "ok"})
	})

	// Folders
	api.GET("/folders", h.GetFolders)
	api.GET("/folders/*", h.BrowseFolder)

	// Videos
	api.GET("/videos", h.GetVideos)
	api.GET("/videos/*", h.GetVideoAsset)

	// Images
	api.GET("/images", h.GetImages)
	api.GET("/images/*", h.GetImageAsset)

	// Search
	api.GET("/search", h.Search)

	// Tags
	api.GET("/tags", h.GetTags)
	api.POST("/tags", h.CreateTag)
	api.DELETE("/tags/:tag_id", h.DeleteTag)
	api.POST("/tags/:tag_id/files/*", h.AssociateTag)
	api.DELETE("/tags/:tag_id/files/*", h.DisassociateTag)
	api.GET("/tags/:tag_id/files", h.GetTaggedFiles)
	api.GET("/tags/:tag_id/media", h.GetTaggedMedia)
	api.GET("/tags/file-tags", h.GetFileTags)

	// Auth middleware: gates sensitive endpoints on the configured token.
	// Empty token = open mode (passthrough), logged at startup.
	authMw := middleware.BearerToken(s.Config.Server.Token)

	// Admin
	admin := api.Group("/admin", authMw)
	admin.GET("/config", h.GetConfig)
	admin.PUT("/config", h.UpdateConfig)
	admin.POST("/scan/trigger", h.TriggerScan)

	// System
	sys := api.Group("/system", authMw)
	sys.GET("/drives", h.GetDrives)
	sys.GET("/browse", h.SystemBrowse)
	sys.GET("/thumbnail", h.SystemThumbnail)
	sys.GET("/original", h.SystemOriginal)
	sys.GET("/stream", h.SystemStream)
	sys.POST("/delete", h.DeletePath)

	// Unified absolute-path media access
	media := api.Group("/media", authMw)
	media.GET("/thumbnail", h.MediaThumbnail)
	media.GET("/original", h.MediaOriginal)
	media.GET("/stream", h.MediaStream)
	media.GET("/duration", h.MediaDuration)

	// Admin page
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
