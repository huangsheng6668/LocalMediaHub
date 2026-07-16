package bookparser

import (
	"fmt"
	"os"
)

func parseEpub(path string, info os.FileInfo) (*Book, error) {
	return nil, fmt.Errorf("not yet implemented")
}

func (b *Book) epubChapterText(idx int) (string, error) {
	return "", fmt.Errorf("not yet implemented")
}
