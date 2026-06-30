package service

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// blockedSegments are path segments (compared case-insensitively, whole-segment)
// that must never be browsed, served, or deleted. Shared by browse, media-access,
// and delete validation so read and write paths enforce the SAME blocklist
// (previously these were duplicated and had already diverged between path.go and
// system.go). Whole-segment matching avoids the old substring false positives
// (e.g. a media folder named "windows-screenshots" is its own segment and is NOT
// blocked).
//
// NOTE: "users" is intentionally EXCLUDED. On Windows most real media lives under
// C:\Users\<profile>\(Pictures|Videos|Downloads) and t.TempDir() sits under
// C:\Users\<profile>\AppData\Local\Temp, so blocking the "users" segment would
// reject legitimate user media (and break every temp-dir test fixture).
var blockedSegments = []string{
	"windows",
	"winnt",
	"system32",
	"syswow64",
	"$recycle.bin",
	"system volume information",
	"program files",
	"program files (x86)",
	"boot",
}

// NormalizePath converts route/query path input into a cleaned absolute local path.
func NormalizePath(pathStr string) (string, error) {
	if strings.TrimSpace(pathStr) == "" {
		return "", fmt.Errorf("path required")
	}

	absPath, err := filepath.Abs(filepath.Clean(filepath.FromSlash(pathStr)))
	if err != nil {
		return "", fmt.Errorf("invalid path: %w", err)
	}

	return absPath, nil
}

// IsPathWithinRoots reports whether pathStr is inside any of the provided roots.
func IsPathWithinRoots(pathStr string, roots []string) (bool, error) {
	absPath, err := NormalizePath(pathStr)
	if err != nil {
		return false, err
	}

	for _, root := range roots {
		absRoot, err := NormalizePath(root)
		if err != nil {
			continue
		}

		rel, err := filepath.Rel(absRoot, absPath)
		if err != nil {
			continue
		}

		if rel == "." {
			return true, nil
		}
		if rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
			return true, nil
		}
	}

	return false, nil
}

// ValidateSystemMediaAccess validates a media file path for the system
// thumbnail/original/stream endpoints. It resolves symlinks/junctions, requires
// the resolved path be under one of the configured system allowed roots, blocks
// sensitive segments, and confirms it is an existing file with an allowed
// extension. Returns the resolved real path for the caller to open/serve.
func ValidateSystemMediaAccess(pathStr string, allowedRoots []string, allowedExtensions []string) (string, error) {
	if len(allowedRoots) == 0 {
		return "", fmt.Errorf("system browse is not configured")
	}
	resolved, err := ResolveWithinRoots(pathStr, allowedRoots)
	if err != nil {
		return "", err
	}
	if err := validateMediaFilePath(resolved, allowedExtensions); err != nil {
		return "", err
	}
	return resolved, nil
}

// ValidateSystemBrowse validates a directory path for listing contents: it
// resolves symlinks/junctions, requires the resolved path within the resolved
// allowed roots, and blocks sensitive segments. Returns the resolved directory
// path. Replaces the old ValidateSystemBrowseAllowed + ValidateSystemBrowsePath
// two-step, whose security depended on call order.
func ValidateSystemBrowse(pathStr string, allowedRoots []string) (string, error) {
	if len(allowedRoots) == 0 {
		return "", fmt.Errorf("system browse is not configured")
	}
	return ResolveWithinRoots(pathStr, allowedRoots)
}

// ValidateDeletion resolves symlinks/junctions, requires the resolved path be
// within one of the resolved roots, blocks sensitive segments, and forbids
// deleting a root itself. Returns the resolved path. Moved here from
// handler.isAllowedToDelete so blocklist + roots logic live in one place.
func ValidateDeletion(pathStr string, allRoots []string) (string, error) {
	resolved, err := ResolveWithinRoots(pathStr, allRoots)
	if err != nil {
		return "", err
	}
	for _, root := range allRoots {
		absRoot, err := NormalizePath(root)
		if err != nil || isUNC(absRoot) {
			continue
		}
		resolvedRoot, err := filepath.EvalSymlinks(absRoot)
		if err != nil {
			continue
		}
		if resolved == resolvedRoot {
			return "", fmt.Errorf("access denied: cannot delete a root directory")
		}
	}
	return resolved, nil
}

// ValidateAccessibleMediaPath checks whether a media file path is accessible from either the
// configured scan roots or the explicit system browse roots.
func ValidateAccessibleMediaPath(pathStr string, scanRoots []string, systemAllowedRoots []string, allowedExtensions []string) error {
	absPath, err := NormalizePath(pathStr)
	if err != nil {
		return err
	}

	ok, err := IsPathWithinRoots(absPath, scanRoots)
	if err != nil {
		return err
	}
	if ok {
		return validateMediaFilePath(absPath, allowedExtensions)
	}

	ok, err = IsPathWithinRoots(absPath, systemAllowedRoots)
	if err != nil {
		return err
	}
	if ok {
		if err := containsBlockedSegment(absPath); err != nil {
			return err
		}
		return validateMediaFilePath(absPath, allowedExtensions)
	}

	return fmt.Errorf("access denied: path outside allowed directories")
}

// containsBlockedSegment reports whether any segment of absPath (split on the OS
// separator, lower-cased) equals one of the blocked segments.
func containsBlockedSegment(absPath string) error {
	for _, seg := range strings.Split(strings.ToLower(absPath), string(filepath.Separator)) {
		for _, blocked := range blockedSegments {
			if seg == blocked {
				return fmt.Errorf("access denied: restricted directory")
			}
		}
	}
	return nil
}

func validateMediaFilePath(absPath string, allowedExtensions []string) error {
	info, err := os.Stat(absPath)
	if err != nil {
		return fmt.Errorf("path not accessible: %w", err)
	}
	if info.IsDir() {
		return fmt.Errorf("access denied: not a file")
	}

	ext := strings.ToLower(filepath.Ext(absPath))
	for _, allowedExt := range allowedExtensions {
		if strings.EqualFold(ext, allowedExt) {
			return nil
		}
	}
	return fmt.Errorf("access denied: file type not allowed")
}

// isUNC reports whether path is a UNC path (\\server\share, \\?\, \\.\).
func isUNC(path string) bool {
	return len(path) >= 2 && path[0] == '\\' && path[1] == '\\'
}

// resolveWithin lexical-cleans pathStr, rejects UNC input, resolves symlinks /
// junctions on BOTH the path and each root via filepath.EvalSymlinks, and
// requires the resolved path to remain inside one of the (similarly resolved)
// roots. It does NOT apply the blocked-segment list. Returns the resolved real
// path so callers open/serve that instead of the link-bearing input — closing
// the "validate lexically, serve follows the link" TOCTOU.
//
// EvalSymlinks requires the path to exist; non-existent paths produce a wrapped
// error that os.IsNotExist can detect (callers map it to 404). Security is not
// weakened: only an EXISTING symlink can escape, and those are resolved here.
func resolveWithin(pathStr string, roots []string) (string, error) {
	absPath, err := NormalizePath(pathStr)
	if err != nil {
		return "", err
	}
	if isUNC(absPath) {
		return "", fmt.Errorf("access denied: UNC paths are not allowed")
	}

	resolvedPath, err := filepath.EvalSymlinks(absPath)
	if err != nil {
		return "", fmt.Errorf("path not accessible: %w", err)
	}

	for _, root := range roots {
		absRoot, err := NormalizePath(root)
		if err != nil || isUNC(absRoot) {
			continue
		}
		resolvedRoot, err := filepath.EvalSymlinks(absRoot)
		if err != nil {
			continue
		}
		rel, err := filepath.Rel(resolvedRoot, resolvedPath)
		if err != nil {
			continue
		}
		if rel == "." || (rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))) {
			return resolvedPath, nil
		}
	}
	return "", fmt.Errorf("access denied: path outside allowed directories")
}

// ResolveWithinRoots is the security boundary for system/media endpoints: it
// resolves symlinks/junctions (resolveWithin) AND applies the blocked-segment
// list. Returns the resolved real path for the caller to open/serve.
func ResolveWithinRoots(pathStr string, roots []string) (string, error) {
	resolved, err := resolveWithin(pathStr, roots)
	if err != nil {
		return "", err
	}
	if err := containsBlockedSegment(resolved); err != nil {
		return "", err
	}
	return resolved, nil
}
