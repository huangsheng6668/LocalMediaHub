package service

import (
	"context"
	"io/fs"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"golang.org/x/sync/errgroup"
	"golang.org/x/sync/singleflight"

	"github.com/localmediahub/server/internal/models"
)

type Scanner struct {
	mu             sync.RWMutex
	cache          map[string][]models.MediaFile
	cacheTime      time.Time
	cacheTTL       time.Duration
	videoExts      map[string]bool
	imageExts      map[string]bool
	sf             singleflight.Group
	OnScanComplete func(files []models.MediaFile)
	// bgCtx/bgCancel bound the lifetime of admin-triggered background scans.
	// Unlike the per-request context used by GetCached, this one is owned by the
	// scanner so a TriggerScan keeps running after the HTTP response is sent and
	// can be cancelled by shutting down (Stop cancels it via Shutdown).
	bgCtx    context.Context
	bgCancel context.CancelFunc
}

func NewScanner(videoExts, imageExts []string) *Scanner {
	vExts := make(map[string]bool)
	for _, e := range videoExts {
		vExts[strings.ToLower(e)] = true
	}
	iExts := make(map[string]bool)
	for _, e := range imageExts {
		iExts[strings.ToLower(e)] = true
	}
	ctx, cancel := context.WithCancel(context.Background())
	return &Scanner{
		cache:     make(map[string][]models.MediaFile),
		cacheTTL:  60 * time.Second,
		videoExts: vExts,
		imageExts: iExts,
		bgCtx:     ctx,
		bgCancel:  cancel,
	}
}

// VideoExts returns the video extension map for handler use.
func (s *Scanner) VideoExts() map[string]bool {
	return s.videoExts
}

// ImageExts returns the image extension map for handler use.
func (s *Scanner) ImageExts() map[string]bool {
	return s.imageExts
}

// TriggerScan kicks off a background scan bound to the scanner's own lifecycle
// context (not a request context), so it continues after the triggering HTTP
// response is sent. Any previously triggered scan is cancelled first so two
// background scans never run concurrently.
func (s *Scanner) TriggerScan(roots []string) {
	// Cancel any in-flight background scan before starting a new one.
	s.bgCancel()
	s.bgCtx, s.bgCancel = context.WithCancel(context.Background())
	go s.Scan(s.bgCtx, roots)
}

// Shutdown cancels any in-flight background scan. Call this on server stop so
// triggered scans don't outlive the process.
func (s *Scanner) Shutdown() {
	s.bgCancel()
}

// Scan walks the given roots concurrently, collecting media files. The provided
// ctx allows an in-flight scan to be cancelled (e.g. when the server shuts down
// or a new scan supersedes it). Passing context.Background() is fine for an
// un-cancellable scan; passing the request context from a handler makes a slow
// scan abortable when the client disconnects.
func (s *Scanner) Scan(ctx context.Context, roots []string) ([]models.MediaFile, error) {
	// Use errgroup for concurrent scanning. Bind to ctx so cancellation
	// propagates to every walk goroutine.
	g, gctx := errgroup.WithContext(ctx)

	// Slice of slices to collect results without lock contention during walk
	results := make([][]models.MediaFile, len(roots))

	for i, root := range roots {
		i, root := i, root
		g.Go(func() error {
			var localFiles []models.MediaFile
			err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
				if err != nil {
					return nil
				}
				// Honor cancellation between directory entries so a large scan
				// can be aborted promptly rather than walking the whole tree.
				select {
				case <-gctx.Done():
					return gctx.Err()
				default:
				}
				if d.IsDir() {
					return nil
				}
				ext := strings.ToLower(filepath.Ext(path))
				mediaType := ""
				s.mu.RLock()
				isVideo := s.videoExts[ext]
				isImage := s.imageExts[ext]
				s.mu.RUnlock()

				if isVideo {
					mediaType = "video"
				} else if isImage {
					mediaType = "image"
				} else {
					return nil
				}

				// Only stat media files (not directories or non-media files)
				info, err := d.Info()
				if err != nil {
					return nil
				}

				relPath := path
				if strings.HasPrefix(path, root) {
					relPath = strings.TrimPrefix(path, root)
					if !strings.HasPrefix(relPath, string(filepath.Separator)) {
						relPath = string(filepath.Separator) + relPath
					}
				}

				localFiles = append(localFiles, models.MediaFile{
					Name:         d.Name(),
					Path:         path,
					RelativePath: relPath,
					Size:         info.Size(),
					ModifiedTime: info.ModTime(),
					MediaType:    mediaType,
					Extension:    ext,
				})
				return nil
			})
			if err != nil {
				return err
			}
			results[i] = localFiles
			return nil
		})
	}

	if err := g.Wait(); err != nil {
		return nil, err
	}

	// Merge slices, splitting by media type so list endpoints can read per-type
	// slices directly instead of re-filtering the whole cache on every request.
	allFiles := make([]models.MediaFile, 0)
	videoFiles := make([]models.MediaFile, 0)
	imageFiles := make([]models.MediaFile, 0)
	for _, subList := range results {
		for _, f := range subList {
			allFiles = append(allFiles, f)
			switch f.MediaType {
			case "video":
				videoFiles = append(videoFiles, f)
			case "image":
				imageFiles = append(imageFiles, f)
			}
		}
	}

	s.mu.Lock()
	s.cache["all"] = allFiles
	s.cache["video"] = videoFiles
	s.cache["image"] = imageFiles
	s.cacheTime = time.Now()
	callback := s.OnScanComplete
	s.mu.Unlock()

	if callback != nil {
		go callback(allFiles)
	}

	return allFiles, nil
}

// GetCached returns cached scan results if fresh, otherwise triggers a scan.
// The ctx is forwarded to Scan so a cache-miss scan can be cancelled when the
// caller (typically a request handler) gives up.
func (s *Scanner) GetCached(ctx context.Context, roots []string) ([]models.MediaFile, error) {
	s.mu.RLock()
	cachedValid := time.Since(s.cacheTime) < s.cacheTTL
	if cachedValid {
		if files, ok := s.cache["all"]; ok {
			s.mu.RUnlock()
			return files, nil
		}
	}
	s.mu.RUnlock()

	// Use singleflight to prevent cache stampede
	val, err, _ := s.sf.Do("scan", func() (interface{}, error) {
		return s.Scan(ctx, roots)
	})
	if err != nil {
		return nil, err
	}
	return val.([]models.MediaFile), nil
}

// GetCachedByType returns the cached scan results filtered to mediaType,
// triggering a scan on cache miss (shared via singleflight, same as GetCached).
func (s *Scanner) GetCachedByType(ctx context.Context, roots []string, mediaType string) ([]models.MediaFile, error) {
	s.mu.RLock()
	if time.Since(s.cacheTime) < s.cacheTTL {
		if files, ok := s.cache[mediaType]; ok {
			s.mu.RUnlock()
			return files, nil
		}
	}
	s.mu.RUnlock()

	_, err, _ := s.sf.Do("scan", func() (interface{}, error) {
		return s.Scan(ctx, roots)
	})
	if err != nil {
		return nil, err
	}
	// Scan 刚填充了 cache[mediaType]；读回请求的类型切片。
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.cache[mediaType], nil
}

func (s *Scanner) InvalidateCache() {
	s.mu.Lock()
	s.cache = make(map[string][]models.MediaFile)
	s.cacheTime = time.Time{}
	s.mu.Unlock()
}

func (s *Scanner) Search(files []models.MediaFile, query string) []models.MediaFile {
	result := make([]models.MediaFile, 0)
	q := strings.ToLower(query)
	for _, f := range files {
		if strings.Contains(strings.ToLower(f.Name), q) {
			result = append(result, f)
		}
	}
	return result
}
