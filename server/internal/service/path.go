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

// ValidateSystemPath validates a path for system browsing endpoints.
// It ensures:
//  1. The path is absolute and cleaned (no .. traversal)
//  2. The path is not in a blocked sensitive directory
//  3. If it is a file, its extension must be in the allowed list
func ValidateSystemPath(pathStr string, allowedExtensions []string) error {
	absPath, err := NormalizePath(pathStr)
	if err != nil {
		return err
	}

	if err := containsBlockedSegment(absPath); err != nil {
		return err
	}

	info, err := os.Stat(absPath)
	if err != nil {
		return fmt.Errorf("path not accessible: %w", err)
	}

	if !info.IsDir() {
		ext := strings.ToLower(filepath.Ext(absPath))
		allowed := false
		for _, allowedExt := range allowedExtensions {
			if strings.EqualFold(ext, allowedExt) {
				allowed = true
				break
			}
		}
		if !allowed {
			return fmt.Errorf("access denied: file type not allowed")
		}
	}

	return nil
}

// ValidateSystemMediaAccess validates a media file path for the system
// thumbnail/original/stream endpoints. It enforces that the path is under one
// of the configured system allowed roots, is not inside a blocked directory,
// and is an existing file whose extension is in the allowed list.
//
// Unlike ValidateSystemPath, this also enforces the allowed-roots boundary,
// preventing the system media endpoints from serving files outside the
// directories the operator explicitly opened via system.allowed_roots.
func ValidateSystemMediaAccess(pathStr string, allowedRoots []string, allowedExtensions []string) error {
	if err := ValidateSystemBrowseAllowed(pathStr, allowedRoots); err != nil {
		return err
	}
	absPath, err := NormalizePath(pathStr)
	if err != nil {
		return err
	}
	if err := containsBlockedSegment(absPath); err != nil {
		return err
	}
	return validateMediaFilePath(absPath, allowedExtensions)
}

// ValidateSystemBrowsePath validates a directory path for browsing (listing contents).
// Only checks for path traversal and blocked directories; does NOT restrict extensions
// since the browsing handler already filters by media extensions.
func ValidateSystemBrowsePath(pathStr string) error {
	absPath, err := NormalizePath(pathStr)
	if err != nil {
		return err
	}

	if err := containsBlockedSegment(absPath); err != nil {
		return err
	}

	return nil
}

// ValidateSystemBrowseAllowed checks that the path is under one of the allowed roots.
// If allowedRoots is empty, system browse is disabled until configured.
func ValidateSystemBrowseAllowed(pathStr string, allowedRoots []string) error {
	if len(allowedRoots) == 0 {
		return fmt.Errorf("system browse is not configured")
	}

	ok, err := IsPathWithinRoots(pathStr, allowedRoots)
	if err != nil {
		return err
	}
	if ok {
		return nil
	}
	return fmt.Errorf("access denied: path outside allowed directories")
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
