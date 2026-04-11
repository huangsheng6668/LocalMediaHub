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

// ValidateSystemPath validates a path for system browsing endpoints.
// It ensures:
//  1. The path is absolute and cleaned (no .. traversal)
//  2. The path is not in a blocked sensitive directory
//  3. If it is a file, its extension must be in the allowed list
func ValidateSystemPath(pathStr string, allowedExtensions []string) error {
	absPath, err := filepath.Abs(filepath.Clean(pathStr))
	if err != nil {
		return fmt.Errorf("invalid path: %w", err)
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
	absPath, err := filepath.Abs(filepath.Clean(pathStr))
	if err != nil {
		return fmt.Errorf("invalid path: %w", err)
	}

	if err := checkBlocked(absPath); err != nil {
		return err
	}

	return nil
}

// ValidateSystemBrowseAllowed checks that the path is under one of the allowed roots.
// If allowedRoots is empty, all paths are allowed (backward compatible).
func ValidateSystemBrowseAllowed(pathStr string, allowedRoots []string) error {
	if len(allowedRoots) == 0 {
		return nil
	}
	absPath, err := filepath.Abs(filepath.Clean(pathStr))
	if err != nil {
		return fmt.Errorf("invalid path: %w", err)
	}
	for _, root := range allowedRoots {
		absRoot, err := filepath.Abs(filepath.Clean(root))
		if err != nil {
			continue
		}
		if strings.EqualFold(absPath, absRoot) || strings.HasPrefix(strings.ToLower(absPath), strings.ToLower(absRoot)+string(filepath.Separator)) {
			return nil
		}
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
