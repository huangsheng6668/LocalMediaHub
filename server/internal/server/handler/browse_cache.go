package handler

import (
	"container/list"
	"os"
	"strings"
	"sync"
	"time"

	"golang.org/x/sync/singleflight"

	"github.com/localmediahub/server/internal/models"
	"github.com/localmediahub/server/internal/service"
)

const (
	// browseCacheTTL is the hard staleness bound. Adds/removes normally bump
	// the parent directory mtime and invalidate immediately, but that check is
	// only as fresh as the OS timestamp (NTFS stamps directory mtimes from the
	// ~15.6ms clock tick), and in-place edits never move it at all — so every
	// entry expires after this TTL regardless.
	browseCacheTTL = 5 * time.Second
	// browseCacheMaxDirs / browseCacheMaxFiles are the LRU limits. A 5000-file
	// entry costs roughly 1 MB, so 100k files caps the cache near 20 MB.
	browseCacheMaxDirs  = 128
	browseCacheMaxFiles = 100_000
)

type browseListing struct {
	folders []models.Folder
	files   []models.MediaFile
}

type browseCacheEntry struct {
	key        string
	dirKey     string
	dirModTime time.Time
	storedAt   time.Time
	folders    []models.Folder
	files      []models.MediaFile
}

// browseCache caches one directory's fully built, sorted listing per
// (dir, sort, order) so pagination and re-entry skip ReadDir + stat + sort.
// Entries are immutable once stored; callers only slice and serialize them.
type browseCache struct {
	mu         sync.Mutex
	ll         *list.List
	items      map[string]*list.Element
	totalFiles int
	sf         singleflight.Group
}

func newBrowseCache() *browseCache {
	return &browseCache{
		ll:    list.New(),
		items: make(map[string]*list.Element),
	}
}

func browseCacheKey(dirKey, sortField, order string) string {
	return dirKey + "|" + sortField + "|" + order
}

func (c *browseCache) get(key, dirKey string, dirModTime time.Time, now time.Time) (browseListing, bool) {
	if c == nil {
		return browseListing{}, false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	el, ok := c.items[key]
	if !ok {
		return browseListing{}, false
	}
	e := el.Value.(*browseCacheEntry)
	if e.dirKey != dirKey || !e.dirModTime.Equal(dirModTime) || now.Sub(e.storedAt) >= browseCacheTTL {
		c.removeElement(el)
		return browseListing{}, false
	}
	c.ll.MoveToFront(el)
	return browseListing{folders: e.folders, files: e.files}, true
}

func (c *browseCache) put(key, dirKey string, dirModTime time.Time, now time.Time, folders []models.Folder, files []models.MediaFile) {
	if c == nil {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if el, ok := c.items[key]; ok {
		c.removeElement(el)
	}
	el := c.ll.PushFront(&browseCacheEntry{
		key:        key,
		dirKey:     dirKey,
		dirModTime: dirModTime,
		storedAt:   now,
		folders:    folders,
		files:      files,
	})
	c.items[key] = el
	c.totalFiles += len(files)
	for c.ll.Len() > browseCacheMaxDirs || c.totalFiles > browseCacheMaxFiles {
		back := c.ll.Back()
		if back == nil {
			break
		}
		c.removeElement(back)
	}
}

func (c *browseCache) removeElement(el *list.Element) {
	e := el.Value.(*browseCacheEntry)
	c.ll.Remove(el)
	delete(c.items, e.key)
	c.totalFiles -= len(e.files)
}

// InvalidateDir drops every sort/order variant for one directory.
func (c *browseCache) InvalidateDir(dir string) {
	if c == nil {
		return
	}
	dirKey := service.PathKey(dir)
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, el := range c.items {
		if el.Value.(*browseCacheEntry).dirKey == dirKey {
			c.removeElement(el)
		}
	}
}

// InvalidateTree drops dir and every cached descendant, for recursive deletes.
func (c *browseCache) InvalidateTree(dir string) {
	if c == nil {
		return
	}
	dirKey := service.PathKey(dir)
	prefix := dirKey
	if !strings.HasSuffix(prefix, string(os.PathSeparator)) {
		prefix += string(os.PathSeparator)
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, el := range c.items {
		key := el.Value.(*browseCacheEntry).dirKey
		if key == dirKey || strings.HasPrefix(key, prefix) {
			c.removeElement(el)
		}
	}
}

// load collapses concurrent builds for the same key into one readDir pass.
func (c *browseCache) load(key string, build func() (browseListing, error)) (browseListing, error) {
	if c == nil {
		return build()
	}
	v, err, _ := c.sf.Do(key, func() (interface{}, error) {
		return build()
	})
	if err != nil {
		return browseListing{}, err
	}
	return v.(browseListing), nil
}

func (c *browseCache) len() int {
	if c == nil {
		return 0
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.ll.Len()
}
