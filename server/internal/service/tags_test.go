package service

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestTagsService(t *testing.T) {
	tempDir := t.TempDir()
	
	// 1. Create a new service
	svc, err := NewTagsService(tempDir)
	assert.NoError(t, err)
	assert.NotNil(t, svc)
	defer svc.Close()
	
	// Should be empty initially
	tags := svc.GetAllTags()
	assert.Len(t, tags, 0)
	
	// 2. Create tags
	t1, err := svc.CreateTag("Movie", "#FF0000")
	assert.NoError(t, err)
	assert.Equal(t, "Movie", t1.Name)
	assert.Equal(t, "#FF0000", t1.Color)
	assert.NotEmpty(t, t1.ID)
	
	t2, err := svc.CreateTag("Anime", "#00FF00")
	assert.NoError(t, err)
	
	// Duplicate name check
	_, err = svc.CreateTag("Movie", "#0000FF")
	assert.Error(t, err)
	
	tags = svc.GetAllTags()
	assert.Len(t, tags, 2)
	
	// 3. Associations
	filePath := "videos/test.mp4"
	_, err = svc.AssociateFile(t1.ID, filePath)
	assert.NoError(t, err)
	
	fileTagsMap := svc.GetTagsForFiles([]string{filePath})
	assert.Len(t, fileTagsMap[filePath], 1)
	assert.Equal(t, t1.ID, fileTagsMap[filePath][0].ID)
	
	// Duplicate association check
	_, err = svc.AssociateFile(t1.ID, filePath)
	assert.NoError(t, err) // Should handle gracefully
	assert.Len(t, svc.GetTagsForFiles([]string{filePath})[filePath], 1)
	
	// Add another tag to the same file
	_, err = svc.AssociateFile(t2.ID, filePath)
	assert.NoError(t, err)
	assert.Len(t, svc.GetTagsForFiles([]string{filePath})[filePath], 2)
	
	// Get associated files
	files := svc.GetFilesForTag(t1.ID)
	assert.Len(t, files, 1)
	assert.Equal(t, filePath, files[0])
	
	// Untag file
	err = svc.DisassociateFile(t2.ID, filePath)
	assert.NoError(t, err)
	assert.Len(t, svc.GetTagsForFiles([]string{filePath})[filePath], 1)
	
	// Delete tag
	err = svc.DeleteTag(t1.ID)
	assert.NoError(t, err)
	assert.Len(t, svc.GetAllTags(), 1)
	assert.Len(t, svc.GetTagsForFiles([]string{filePath})[filePath], 0)
	
	// Verify persistence (reload service)
	svcReloaded, err := NewTagsService(tempDir)
	assert.NoError(t, err)
	defer svcReloaded.Close()
	assert.Len(t, svcReloaded.GetAllTags(), 1)
	assert.Equal(t, t2.ID, svcReloaded.GetAllTags()[0].ID)
}
