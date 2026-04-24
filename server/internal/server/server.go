package server

import (
	"fmt"
	"net"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/server/handler"
	"github.com/localmediahub/server/internal/server/middleware"
	"github.com/localmediahub/server/internal/service"
)

type Server struct {
	Echo      *echo.Echo
	Config    *config.Config
	IP        string
	Scanner   *service.Scanner
	Tags      *service.TagsService
	Streaming *service.StreamingService
	Thumbnail *service.ThumbnailService
}

func New(cfg *config.Config) (*Server, error) {
	e := echo.New()
	e.HideBanner = true

	ip, err := getLocalIP()
	if err != nil {
		return nil, fmt.Errorf("failed to get local IP: %w", err)
	}

	scanner := service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions)
	tagsService, err := service.NewTagsService(".data")
	if err != nil {
		return nil, fmt.Errorf("failed to create tags service: %w", err)
	}
	streamingService := service.NewStreamingService()
	thumbnailService, err := service.NewThumbnailService(cfg.Thumbnail.CacheDir, cfg.Thumbnail.MaxSize, cfg.Thumbnail.Format)
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

	h := handler.New(cfg, scanner, tagsService, streamingService, thumbnailService)
	s.registerRoutes(h)

	return s, nil
}

func (s *Server) registerRoutes(h *handler.Handler) {
	s.Echo.Use(middleware.CORS())

	// Root
	s.Echo.GET("/", h.Root)

	api := s.Echo.Group("/api/v1")

	// Folders
	api.GET("/folders", h.GetFolders)
	api.GET("/folders/*", h.BrowseFolder)

	// Videos
	api.GET("/videos", h.GetVideos)
	api.GET("/videos/*", h.StreamVideo)

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

	// Admin
	admin := api.Group("/admin")
	admin.GET("/config", h.GetConfig)
	admin.PUT("/config", h.UpdateConfig)
	admin.POST("/scan/trigger", h.TriggerScan)

	// System
	sys := api.Group("/system")
	sys.GET("/drives", h.GetDrives)
	sys.GET("/browse", h.SystemBrowse)
	sys.GET("/thumbnail", h.SystemThumbnail)
	sys.GET("/original", h.SystemOriginal)
	sys.GET("/stream", h.SystemStream)

	// Unified absolute-path media access
	media := api.Group("/media")
	media.GET("/thumbnail", h.MediaThumbnail)
	media.GET("/original", h.MediaOriginal)
	media.GET("/stream", h.MediaStream)

	// Admin page
}

func (s *Server) Start() error {
	addr := fmt.Sprintf("%s:%d", s.Config.Server.Host, s.Config.Server.Port)
	return s.Echo.Start(addr)
}

func (s *Server) Stop() error {
	return s.Echo.Close()
}

func getLocalIP() (string, error) {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return "", err
	}

	var bestIP string
	for _, addr := range addrs {
		if ipNet, ok := addr.(*net.IPNet); ok && !ipNet.IP.IsLoopback() && ipNet.IP.To4() != nil {
			ip := ipNet.IP.To4()
			// Skip APIPA
			if ip[0] == 169 && ip[1] == 254 {
				continue
			}

			// Prefer private LAN IPs
			isPrivate := (ip[0] == 192 && ip[1] == 168) ||
				(ip[0] == 10) ||
				(ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31)

			if isPrivate {
				return ip.String(), nil
			}
			if bestIP == "" {
				bestIP = ip.String()
			}
		}
	}

	if bestIP != "" {
		return bestIP, nil
	}
	return "127.0.0.1", nil
}
