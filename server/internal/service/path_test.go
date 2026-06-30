package service

import (
	"os"
	"path/filepath"
	"testing"
)

func TestValidateSystemBrowseRequiresConfiguredRoots(t *testing.T) {
	root := t.TempDir()

	_, err := ValidateSystemBrowse(root, nil)
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

func TestValidateSystemMediaAccessRequiresConfiguredRoots(t *testing.T) {
	root := t.TempDir()
	filePath := filepath.Join(root, "clip.mp4")
	if err := os.WriteFile(filePath, []byte("video"), 0o644); err != nil {
		t.Fatalf("failed to create media file: %v", err)
	}

	_, err := ValidateSystemMediaAccess(filePath, nil, []string{".mp4"})
	if err == nil {
		t.Fatal("expected access to be denied when no system roots are configured")
	}
}

func TestValidateSystemMediaAccessAllowsFileWithinRoots(t *testing.T) {
	root := t.TempDir()
	filePath := filepath.Join(root, "clip.mp4")
	if err := os.WriteFile(filePath, []byte("video"), 0o644); err != nil {
		t.Fatalf("failed to create media file: %v", err)
	}

	_, err := ValidateSystemMediaAccess(filePath, []string{root}, []string{".mp4"})
	if err != nil {
		t.Fatalf("expected media file within roots to be accessible, got %v", err)
	}
}

func TestValidateSystemMediaAccessRejectsPathOutsideRoots(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	filePath := filepath.Join(outside, "secret.jpg")
	if err := os.WriteFile(filePath, []byte("img"), 0o644); err != nil {
		t.Fatalf("failed to create media file: %v", err)
	}

	_, err := ValidateSystemMediaAccess(filePath, []string{root}, []string{".jpg"})
	if err == nil {
		t.Fatal("expected media file outside roots to be denied")
	}
}

func TestValidateSystemMediaAccessRejectsDisallowedExtension(t *testing.T) {
	root := t.TempDir()
	filePath := filepath.Join(root, "notes.txt")
	if err := os.WriteFile(filePath, []byte("txt"), 0o644); err != nil {
		t.Fatalf("failed to create file: %v", err)
	}

	_, err := ValidateSystemMediaAccess(filePath, []string{root}, []string{".mp4", ".jpg"})
	if err == nil {
		t.Fatal("expected non-media extension to be denied")
	}
}

func TestContainsBlockedSegmentMatchesWholeSegment(t *testing.T) {
	cases := map[string]bool{
		// 真实段 → 命中
		filepath.Join("D:", "Media", "windows", "x.jpg"):          true,
		filepath.Join("D:", "Media", "System32", "x.jpg"):         true, // 大小写不敏感
		filepath.Join("D:", "Media", "Program Files (x86)", "x"):  true, // 并集新成员 + 含括号空格
		filepath.Join("D:", "Media", "$RECYCLE.BIN", "x.jpg"):     true,
		// 非整段 → 不命中（修复旧子串误伤）
		filepath.Join("D:", "Media", "windows-screenshots", "x"):  false,
		filepath.Join("D:", "Media", "mywindows", "x.jpg"):        false,
		filepath.Join("D:", "Media", "clip.mp4"):                  false,
	}
	for path, wantBlocked := range cases {
		err := containsBlockedSegment(path)
		gotBlocked := err != nil
		if gotBlocked != wantBlocked {
			t.Errorf("containsBlockedSegment(%q) blocked=%v, want %v", path, gotBlocked, wantBlocked)
		}
	}
}

func TestResolveWithinRootsRejectsSymlinkEscape(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	target := filepath.Join(outside, "secret.jpg")
	if err := os.WriteFile(target, []byte("x"), 0o644); err != nil {
		t.Fatalf("create target: %v", err)
	}
	link := filepath.Join(root, "link.jpg")
	if err := os.Symlink(target, link); err != nil {
		t.Skipf("symlink creation not supported on this platform: %v", err)
	}

	_, err := ResolveWithinRoots(link, []string{root})
	if err == nil {
		t.Fatal("expected symlink escaping roots to be rejected")
	}
}

func TestResolveWithinRootsAllowsInRootSymlink(t *testing.T) {
	root := t.TempDir()
	target := filepath.Join(root, "real.jpg")
	if err := os.WriteFile(target, []byte("x"), 0o644); err != nil {
		t.Fatalf("create target: %v", err)
	}
	link := filepath.Join(root, "link.jpg")
	if err := os.Symlink(target, link); err != nil {
		t.Skipf("symlink creation not supported on this platform: %v", err)
	}

	resolved, err := ResolveWithinRoots(link, []string{root})
	if err != nil {
		t.Fatalf("expected in-root symlink to resolve, got %v", err)
	}
	if _, err := os.Stat(resolved); err != nil {
		t.Errorf("resolved path should be statable, got %v", err)
	}
}

func TestResolveWithinRootsRejectsUNC(t *testing.T) {
	_, err := ResolveWithinRoots(`\\server\share\file.jpg`, []string{`\\server\share`})
	if err == nil {
		t.Fatal("expected UNC path to be rejected")
	}
}

func TestResolveWithinRootsRejectsPathOutsideRoots(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	filePath := filepath.Join(outside, "clip.mp4")
	if err := os.WriteFile(filePath, []byte("v"), 0o644); err != nil {
		t.Fatalf("create file: %v", err)
	}
	if _, err := ResolveWithinRoots(filePath, []string{root}); err == nil {
		t.Fatal("expected path outside roots to be rejected")
	}
}
