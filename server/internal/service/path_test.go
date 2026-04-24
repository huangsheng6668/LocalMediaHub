package service

import (
	"os"
	"path/filepath"
	"testing"
)

func TestValidateSystemBrowseAllowedRequiresConfiguredRoots(t *testing.T) {
	root := t.TempDir()

	err := ValidateSystemBrowseAllowed(root, nil)
	if err == nil {
		t.Fatal("expected access to be denied when no system roots are configured")
	}
}

func TestIsPathWithinRoots(t *testing.T) {
	root := t.TempDir()
	child := filepath.Join(root, "nested", "video.mp4")
	outside := t.TempDir()

	ok, err := IsPathWithinRoots(child, []string{root})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !ok {
		t.Fatalf("expected %q to be within %q", child, root)
	}

	ok, err = IsPathWithinRoots(outside, []string{root})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if ok {
		t.Fatalf("expected %q to be outside %q", outside, root)
	}
}

func TestValidateAccessibleMediaPathAllowsScanRoots(t *testing.T) {
	root := t.TempDir()
	filePath := filepath.Join(root, "poster.jpg")
	if err := os.WriteFile(filePath, []byte("img"), 0o644); err != nil {
		t.Fatalf("failed to create media file: %v", err)
	}

	if err := ValidateAccessibleMediaPath(filePath, []string{root}, nil, []string{".jpg"}); err != nil {
		t.Fatalf("expected scan-root media path to be accessible, got %v", err)
	}
}

func TestValidateAccessibleMediaPathAllowsSystemRoots(t *testing.T) {
	root := t.TempDir()
	filePath := filepath.Join(root, "clip.mp4")
	if err := os.WriteFile(filePath, []byte("video"), 0o644); err != nil {
		t.Fatalf("failed to create media file: %v", err)
	}

	if err := ValidateAccessibleMediaPath(filePath, nil, []string{root}, []string{".mp4"}); err != nil {
		t.Fatalf("expected system-root media path to be accessible, got %v", err)
	}
}

func TestValidateAccessibleMediaPathRejectsPathsOutsideAllRoots(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	filePath := filepath.Join(outside, "clip.mp4")
	if err := os.WriteFile(filePath, []byte("video"), 0o644); err != nil {
		t.Fatalf("failed to create outside media file: %v", err)
	}

	err := ValidateAccessibleMediaPath(filePath, []string{root}, nil, []string{".mp4"})
	if err == nil {
		t.Fatal("expected media path outside configured roots to be denied")
	}
}
