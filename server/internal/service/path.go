package service

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// blockedPaths are directories that should never be accessible via system browsing.
var blockedPaths = []string{
	"windows",
	"winnt",
	"system32",
	"syswow64",
	"$recycle.bin",
	"system volume information",
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

	if err := checkBlocked(absPath); err != nil {
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

// ValidateSystemBrowsePath validates a directory path for browsing (listing contents).
// Only checks for path traversal and blocked directories; does NOT restrict extensions
// since the browsing handler already filters by media extensions.
func ValidateSystemBrowsePath(pathStr string) error {
	absPath, err := NormalizePath(pathStr)
	if err != nil {
		return err
	}

	if err := checkBlocked(absPath); err != nil {
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
		if err := checkBlocked(absPath); err != nil {
			return err
		}
		return validateMediaFilePath(absPath, allowedExtensions)
	}

	return fmt.Errorf("access denied: path outside allowed directories")
}

// checkBlocked returns an error if absPath falls inside a blocked directory.
func checkBlocked(absPath string) error {
	lowerPath := strings.ToLower(absPath)
	for _, blocked := range blockedPaths {
		sep := string(filepath.Separator)
		if strings.Contains(lowerPath, sep+blocked+sep) || strings.Contains(lowerPath, sep+blocked) {
			return fmt.Errorf("access denied: restricted directory")
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
