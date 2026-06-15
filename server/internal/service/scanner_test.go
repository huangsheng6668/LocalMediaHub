package service

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/localmediahub/server/internal/models"
	"github.com/stretchr/testify/assert"
)

func TestScanner(t *testing.T) {
	tempDir := t.TempDir()
	
	// Create subdirs and media files
	err := os.MkdirAll(filepath.Join(tempDir, "FolderA"), 0755)
	assert.NoError(t, err)
	err = os.MkdirAll(filepath.Join(tempDir, "FolderB"), 0755)
	assert.NoError(t, err)
	
	err = os.WriteFile(filepath.Join(tempDir, "FolderA", "video1.mp4"), []byte("dummy"), 0644)
	assert.NoError(t, err)
	err = os.WriteFile(filepath.Join(tempDir, "FolderA", "image1.jpg"), []byte("dummy"), 0644)
	assert.NoError(t, err)
	err = os.WriteFile(filepath.Join(tempDir, "FolderB", "document.txt"), []byte("dummy"), 0644)
	assert.NoError(t, err)
	err = os.WriteFile(filepath.Join(tempDir, "video2.mkv"), []byte("dummy"), 0644)
	assert.NoError(t, err)
	
	scanner := NewScanner([]string{".mp4", ".mkv"}, []string{".jpg", ".png"})
	assert.NotNil(t, scanner)
	
	// Check configured extensions
	assert.True(t, scanner.VideoExts()[".mp4"])
	assert.True(t, scanner.VideoExts()[".mkv"])
	assert.False(t, scanner.VideoExts()[".avi"])
	assert.True(t, scanner.ImageExts()[".jpg"])
	assert.False(t, scanner.ImageExts()[".txt"])
	
	// Scan
	ctx := context.Background()
	files, err := scanner.Scan(ctx, []string{tempDir})
	assert.NoError(t, err)
	
	// Expecting 3 media files (video1.mp4, image1.jpg, video2.mkv). document.txt is ignored.
	assert.Len(t, files, 3)
	
	var mp4Count, jpgCount, mkvCount int
	for _, f := range files {
		switch f.Extension {
		case ".mp4":
			mp4Count++
			assert.Equal(t, "video", f.MediaType)
		case ".jpg":
			jpgCount++
			assert.Equal(t, "image", f.MediaType)
		case ".mkv":
			mkvCount++
			assert.Equal(t, "video", f.MediaType)
		}
	}
	assert.Equal(t, 1, mp4Count)
	assert.Equal(t, 1, jpgCount)
	assert.Equal(t, 1, mkvCount)
	
	// Test caching
	cachedFiles, err := scanner.GetCached(ctx, []string{tempDir})
	assert.NoError(t, err)
	assert.Len(t, cachedFiles, 3)
	
	// Test TriggerScan and callback
	callbackCalled := make(chan bool, 1)
	scanner.OnScanComplete = func(completeFiles []models.MediaFile) {
		assert.Len(t, completeFiles, 3)
		callbackCalled <- true
	}
	scanner.TriggerScan([]string{tempDir})
	
	select {
	case <-callbackCalled:
		// Success
	case <-time.After(2 * time.Second):
		t.Fatal("Scan complete callback was not triggered in time")
	}
	
	// Test cancellation
	cancelCtx, cancelFunc := context.WithCancel(context.Background())
	cancelFunc() // Cancel immediately
	
	_, err = scanner.Scan(cancelCtx, []string{tempDir})
	// Should return context.Canceled error
	assert.Error(t, err)
	assert.Equal(t, context.Canceled, err)
	
	// Test shutdown
	scanner.Shutdown()
}
