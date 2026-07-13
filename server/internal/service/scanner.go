package service

import (
	"context"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/fsnotify/fsnotify"
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
	bgCtx      context.Context
	bgCancel   context.CancelFunc
	watcher    *fsnotify.Watcher
	watchRoots []string

	// cacheDirs 是扫描后收集的去重目录列表（字典序排序），cacheDirMap 记录每个目录的 mtime。
	// 只包含"含媒体文件"的目录（递归向上收集祖先目录），空目录不在内。
	// searchFoldersCached 用它做内存前缀扫，替代原 searchFoldersCtx 的 WalkDir。
	cacheDirs   []string
	cacheDirMap map[string]time.Time
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
		cache:       make(map[string][]models.MediaFile),
		cacheTTL:    60 * time.Second,
		videoExts:   vExts,
		imageExts:   iExts,
		bgCtx:       ctx,
		bgCancel:    cancel,
		cacheDirs:   nil,
		cacheDirMap: nil,
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
	// s.mu guards bgCtx/bgCancel against concurrent Shutdown/TriggerScan.
	s.mu.Lock()
	if s.bgCancel != nil {
		s.bgCancel()
	}
	s.bgCtx, s.bgCancel = context.WithCancel(context.Background())
	ctx := s.bgCtx
	s.mu.Unlock()
	go s.Scan(ctx, roots)
}

// Shutdown cancels any in-flight background scan and closes the file watcher.
func (s *Scanner) Shutdown() {
	s.mu.Lock()
	if s.bgCancel != nil {
		s.bgCancel()
	}
	if s.watcher != nil {
		s.watcher.Close()
		s.watcher = nil
	}
	s.mu.Unlock()
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

	// 共享 dirMap：walk goroutine 收集祖先目录，mutex 保护。
	// 目录数远少于文件数，锁竞争可忽略。
	var dirMu sync.Mutex
	dirMap := make(map[string]time.Time)

	// Slice of slices to collect results without lock contention during walk
	results := make([][]models.MediaFile, len(roots))

	for i, root := range roots {
		i, root := i, root
		g.Go(func() error {
			var localFiles []models.MediaFile
			cleanRoot := filepath.Clean(root)
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

				// C3：递归收集父目录及所有祖先目录到共享 dirMap
				dir := filepath.Clean(filepath.Dir(path))
				for dir != "" && dir != cleanRoot {
					dirMu.Lock()
					_, exists := dirMap[dir]
					if !exists {
						// 首次加入时 stat 一次拿 mtime
						var mtime time.Time
						if statInfo, err := os.Stat(dir); err == nil {
							mtime = statInfo.ModTime()
						}
						dirMap[dir] = mtime
						dirMu.Unlock()

						parent := filepath.Clean(filepath.Dir(dir))
						if parent == dir {
							break // 已到文件系统根节点，防死循环
						}
						dir = parent
					} else {
						// 祖先目录此前已被完整加入，提前 break 减少锁竞争
						dirMu.Unlock()
						break
					}
				}
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

	// 把 dirMap 转为排序切片 + 映射
	cacheDirs := make([]string, 0, len(dirMap))
	cacheDirMap := make(map[string]time.Time, len(dirMap))
	for dir, mtime := range dirMap {
		cacheDirs = append(cacheDirs, dir)
		cacheDirMap[dir] = mtime
	}
	sort.Strings(cacheDirs)

	s.mu.Lock()
	s.cache["all"] = allFiles
	s.cache["video"] = videoFiles
	s.cache["image"] = imageFiles
	s.cacheDirs = cacheDirs
	s.cacheDirMap = cacheDirMap
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

// GetCachedDirs 返回已知目录列表，可选按 scope 前缀过滤。
// scope="" 返回全部；scope="D:/Media" 返回该前缀下的目录。
// 与 GetCached 共享 TTL + singleflight（cache miss 时触发 Scan 填充 cacheDirs）。
// 返回 (dirs, mtimes, error)：mtimes[dir] 为目录 mtime，调用方可查。
func (s *Scanner) GetCachedDirs(ctx context.Context, roots []string, scope string) ([]string, map[string]time.Time, error) {
	dirs, mtimes, err := s.peekCachedDirs(scope)
	if err == nil {
		return dirs, mtimes, nil
	}

	// cache miss → 触发 Scan（singleflight 防击穿）
	_, err, _ = s.sf.Do("scan", func() (interface{}, error) {
		return s.Scan(ctx, roots)
	})
	if err != nil {
		return nil, nil, err
	}

	return s.peekCachedDirs(scope)
}

// peekCachedDirs 持读锁从 cache 读取 scope 范围内的目录 + mtime。
// cache 无效或为空时返回 error，由 caller 触发 Scan。
func (s *Scanner) peekCachedDirs(scope string) ([]string, map[string]time.Time, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if time.Since(s.cacheTime) >= s.cacheTTL || s.cacheDirs == nil {
		return nil, nil, fmt.Errorf("cache invalid")
	}

	dirs := s.filterDirsByScope(scope)
	mtimes := make(map[string]time.Time, len(dirs))
	for _, d := range dirs {
		mtimes[d] = s.cacheDirMap[d]
	}
	return dirs, mtimes, nil
}

// filterDirsByScope 持读锁调用，返回 scope 前缀下的目录。
// scope="" 返回全部。scope 不以 filepath.Separator 结尾时内部补齐。
// 为兼容 Windows 路径大小写不敏感特性，Windows 下用 strings.EqualFold 做前缀对比。
// 注意：dir == scope 自身（无尾部分隔符）也算命中，便于上层把 scope 当作可选根。
func (s *Scanner) filterDirsByScope(scope string) []string {
	if scope == "" {
		out := make([]string, len(s.cacheDirs))
		copy(out, s.cacheDirs)
		return out
	}
	prefix := scope
	if !strings.HasSuffix(prefix, string(filepath.Separator)) {
		prefix += string(filepath.Separator)
	}

	out := make([]string, 0)
	isWindows := runtime.GOOS == "windows"
	for _, dir := range s.cacheDirs {
		// 精确等于 scope 自身时直接命中（不要求尾部分隔符）
		if isWindows {
			if strings.EqualFold(dir, scope) {
				out = append(out, dir)
				continue
			}
			// Windows 下大小写折叠的无分配前缀匹配
			if len(dir) >= len(prefix) && strings.EqualFold(dir[:len(prefix)], prefix) {
				out = append(out, dir)
			}
		} else {
			if dir == scope {
				out = append(out, dir)
				continue
			}
			if strings.HasPrefix(dir, prefix) {
				out = append(out, dir)
			}
		}
	}
	return out
}

func (s *Scanner) InvalidateCache() {
	s.mu.Lock()
	s.cache = make(map[string][]models.MediaFile)
	s.cacheDirs = nil
	s.cacheDirMap = nil
	s.cacheTime = time.Time{}
	s.mu.Unlock()
}

// StartWatching initializes the fsnotify watcher, registers all roots recursively,
// and spawns a background event-listening loop.
func (s *Scanner) StartWatching(roots []string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.watcher != nil {
		s.watcher.Close()
	}

	watcher, err := fsnotify.NewWatcher()
	if err != nil {
		return err
	}
	s.watcher = watcher
	s.watchRoots = roots

	// Watch all directories recursively
	for _, root := range roots {
		_ = filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
			if err != nil {
				return nil
			}
			if d.IsDir() {
				_ = watcher.Add(path)
			}
			return nil
		})
	}

	// Start listening to events
	go s.watchEvents()

	return nil
}

func (s *Scanner) watchEvents() {
	var scanTimer *time.Timer
	const debounceDuration = 2 * time.Second

	for {
		s.mu.RLock()
		watcher := s.watcher
		s.mu.RUnlock()
		if watcher == nil {
			return
		}

		select {
		case event, ok := <-watcher.Events:
			if !ok {
				return
			}

			// Invalidate cache immediately on change events
			if event.Op&fsnotify.Write == fsnotify.Write ||
				event.Op&fsnotify.Create == fsnotify.Create ||
				event.Op&fsnotify.Remove == fsnotify.Remove ||
				event.Op&fsnotify.Rename == fsnotify.Rename {

				s.InvalidateCache()

				// If a new directory is created, dynamically add it to the watcher
				if event.Op&fsnotify.Create == fsnotify.Create {
					if info, err := os.Stat(event.Name); err == nil && info.IsDir() {
						s.mu.Lock()
						if s.watcher != nil {
							_ = s.watcher.Add(event.Name)
						}
						s.mu.Unlock()
					}
				}

				// Debounce triggering scan to warm cache
				s.mu.Lock()
				if scanTimer != nil {
					scanTimer.Stop()
				}
				scanTimer = time.AfterFunc(debounceDuration, func() {
					s.mu.RLock()
					roots := s.watchRoots
					s.mu.RUnlock()
					s.TriggerScan(roots)
				})
				s.mu.Unlock()
			}
		case _, ok := <-watcher.Errors:
			if !ok {
				return
			}
		}
	}
}
