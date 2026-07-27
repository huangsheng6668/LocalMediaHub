package ble

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/models"
	"github.com/localmediahub/server/internal/service"
	"github.com/localmediahub/server/internal/service/bookparser"
)

// ErrUnknownEndpoint is returned by bleApiProvider.HandleBleRequest when the
// decoded endpoint byte does not match any of the Endpoint* constants defined
// in protocol.go.
var ErrUnknownEndpoint = errors.New("ble: unknown endpoint")

// bookService is the narrow interface bleApiProvider consumes from
// service.BookService. Declared as an interface (not the concrete pointer) so
// unit tests can inject a recording stub and assert the path-validation gate
// runs BEFORE BookService is consulted. The production *service.BookService
// satisfies this interface; NewBleApiProvider accepts the concrete type to
// keep its call site in server.go unchanged.
type bookService interface {
	GetBook(path string) (*bookparser.Book, error)
	GetChapterBlocks(ctx context.Context, path string, idx int, clientIP string) ([]bookparser.Block, error)
}

// bleApiProvider adapts existing service/handler data-assembly logic into the
// BLE ApiProvider contract. It returns each endpoint's payload as raw JSON
// bytes (no echo.Context dependency). Logic mirrors the echo handlers in
// server/internal/server/handler/ — intentionally duplicated as a thin BLE
// adapter rather than refactoring handlers out of echo (scope control).
//
// KNOWN SIMPLIFICATION (vs. the echo /api/v1/folders/* handlers):
//   - EndpointFolders does not call os.Stat on each root, so ModifiedTime and
//     the folder-display-name fallback for empty names are omitted. The BLE
//     browse list only needs names + paths so the Android side can render the
//     top-level entry list; the Web UI continues to use the richer echo
//     handler response.
//   - EndpointBrowseFolder resolves the directory and lists one level of
//     entries (folders + files), classifying file types from the config
//     extension lists. Tag enrichment, recursive walks, and the scanner's
//     cacheByDir optimization are NOT replicated here — the BLE browse list
//     just needs file names + types so the Android side can grey out video
//     items it cannot stream over BLE. The Web UI continues to use the echo
//     handler for full-fidelity browsing.
type bleApiProvider struct {
	cfg   *config.Config
	books bookService
}

// NewBleApiProvider wires the provider with the injected config (for roots +
// extension lists) and BookService (for EndpointBookChapter / EndpointBookInfo).
// Both arguments must be non-nil.
func NewBleApiProvider(cfg *config.Config, books *service.BookService) ApiProvider {
	return newBleApiProvider(cfg, books)
}

// newBleApiProvider is the test-friendly constructor that accepts the
// bookService interface directly, so unit tests can inject a recording stub
// and assert the path-validation gate runs before BookService is consulted.
func newBleApiProvider(cfg *config.Config, books bookService) ApiProvider {
	return &bleApiProvider{cfg: cfg, books: books}
}

// HandleBleRequest routes the decoded (endpoint, path, index) to the matching
// data source and returns the marshalled JSON body. Unknown endpoints yield
// ErrUnknownEndpoint; provider-internal errors (path validation failures, I/O
// errors, parser errors) are surfaced unchanged for ServeApiRequest to return
// to the listener.
func (p *bleApiProvider) HandleBleRequest(ctx context.Context, endpoint byte, path string, index int) ([]byte, error) {
	switch endpoint {
	case EndpointBookChapter:
		resolved, err := p.validateBookPath(path)
		if err != nil {
			return nil, err
		}
		blocks, err := p.books.GetChapterBlocks(ctx, resolved, index, "")
		if err != nil {
			return nil, err
		}
		return json.Marshal(blocks)
	case EndpointFolders:
		folders := make([]models.Folder, 0)
		for _, root := range p.cfg.Scan.GetRoots() {
			folders = append(folders, models.Folder{
				Name:         filepath.Base(root),
				Path:         root,
				RelativePath: root,
				IsRoot:       true,
			})
		}
		return json.Marshal(folders)
	case EndpointBrowseFolder:
		result, err := BrowseFolderData(p.cfg, path)
		if err != nil {
			return nil, err
		}
		return json.Marshal(result)
	case EndpointBookInfo:
		resolved, err := p.validateBookPath(path)
		if err != nil {
			return nil, err
		}
		book, err := p.books.GetBook(resolved)
		if err != nil {
			return nil, err
		}
		return json.Marshal(book)
	default:
		return nil, ErrUnknownEndpoint
	}
}

// validateBookPath applies the same security gate the echo GetBookInfo /
// GetBookChapter handlers apply: service.ValidateAccessibleMediaPath with the
// configured scan roots, system allowed roots, and the text-extension
// allow-list. A path outside roots (traversal or absolute-outside) or a path
// whose extension is not in cfg.Scan.TextExtensions yields a wrapped error and
// the caller MUST NOT consult BookService (no file is opened, no metadata is
// leaked). On success the cleaned, resolved absolute path is returned so the
// BookService reads exactly the path that was validated.
func (p *bleApiProvider) validateBookPath(path string) (string, error) {
	// Copy the slice defensively: ValidateAccessibleMediaPath may mutate the
	// allowedExtensions argument in some future revision; mirroring the echo
	// handler's append([]string{}, ...) keeps the shared config slice pristine.
	allowedExts := append([]string{}, p.cfg.Scan.TextExtensions...)
	resolved, err := service.ValidateAccessibleMediaPath(
		path,
		p.cfg.Scan.GetRoots(),
		p.cfg.GetSystemAllowedRoots(),
		allowedExts,
	)
	if err != nil {
		return "", fmt.Errorf("ble: path not accessible: %w", err)
	}
	return resolved, nil
}

// BrowseFolderData resolves path against the configured scan roots, lists the
// directory one level deep, and returns folders + files (with media-type
// classification). It mirrors the BrowseFolder echo handler's browse branch
// minus tag enrichment and the scanner cache (the BLE browse list just needs
// names + types so the Android side can grey out videos).
//
// Security: reuses service.NormalizePath + service.IsPathWithinRoots so the
// same path-traversal defense that gates the HTTP browse endpoint gates the
// BLE one. A path outside the configured roots yields a wrapped error; the
// caller (HandleBleRequest) surfaces it to ServeApiRequest, which surfaces it
// to the listener — no data is leaked about directories outside roots.
//
// Sorting matches the echo handler's implicit ordering (os.ReadDir returns
// entries in lexical order on most platforms), with a defensive sort to make
// the response stable on platforms where ReadDir is not lexical.
func BrowseFolderData(cfg *config.Config, path string) (*models.BrowseResult, error) {
	if cfg == nil {
		return nil, fmt.Errorf("ble: nil config")
	}
	absPath, err := service.NormalizePath(path)
	if err != nil {
		return nil, err
	}
	roots := cfg.Scan.GetRoots()
	valid, err := service.IsPathWithinRoots(absPath, roots)
	if err != nil {
		return nil, err
	}
	if !valid {
		return nil, fmt.Errorf("ble: path outside roots")
	}

	fi, err := os.Stat(absPath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, fmt.Errorf("ble: path not found: %w", err)
		}
		return nil, fmt.Errorf("ble: stat path: %w", err)
	}
	if !fi.IsDir() {
		return nil, fmt.Errorf("ble: not a directory")
	}

	entries, err := os.ReadDir(absPath)
	if err != nil {
		return nil, fmt.Errorf("ble: read dir: %w", err)
	}

	videoExts := extSet(cfg.Scan.VideoExtensions)
	imageExts := extSet(cfg.Scan.ImageExtensions)
	textExts := extSet(cfg.Scan.TextExtensions)

	folders := make([]models.Folder, 0)
	files := make([]models.MediaFile, 0)
	for _, entry := range entries {
		fullPath := filepath.Join(absPath, entry.Name())
		info, err := entry.Info()
		if err != nil {
			continue
		}
		if entry.IsDir() {
			folders = append(folders, models.Folder{
				Name:         entry.Name(),
				Path:         fullPath,
				RelativePath: fullPath,
				IsRoot:       false,
				ModifiedTime: info.ModTime(),
			})
			continue
		}
		ext := strings.ToLower(filepath.Ext(entry.Name()))
		mediaType := classifyByExts(ext, videoExts, imageExts, textExts)
		if mediaType == "" {
			continue
		}
		files = append(files, models.MediaFile{
			Name:         entry.Name(),
			Path:         fullPath,
			RelativePath: fullPath,
			Size:         info.Size(),
			ModifiedTime: info.ModTime(),
			MediaType:    mediaType,
			Extension:    ext,
		})
	}

	// Stable lexical sort (defensive — os.ReadDir is not guaranteed lexical
	// on every GOOS). The echo handler relies on the scanner's pre-sorted
	// cache; the BLE path skips that cache so an explicit sort keeps the
	// response stable across platforms.
	sort.Slice(folders, func(i, j int) bool { return folders[i].Name < folders[j].Name })
	sort.Slice(files, func(i, j int) bool { return files[i].Name < files[j].Name })

	return &models.BrowseResult{
		CurrentPath: absPath,
		Folders:     folders,
		Files:       files,
	}, nil
}

// extSet builds a lower-cased set from a list of extensions. Used by
// classifyByExts to avoid the per-entry allocations that a linear search over
// the config slices would incur on a large directory.
func extSet(exts []string) map[string]bool {
	m := make(map[string]bool, len(exts))
	for _, e := range exts {
		m[strings.ToLower(e)] = true
	}
	return m
}

// classifyByExts returns "video" / "image" / "text" / "" using the pre-built
// lower-cased extension sets. Mirrors the echo handler's classifyMediaType
// without the scanner dependency.
func classifyByExts(ext string, video, image, text map[string]bool) string {
	switch {
	case video[ext]:
		return "video"
	case image[ext]:
		return "image"
	case text[ext]:
		return "text"
	default:
		return ""
	}
}
