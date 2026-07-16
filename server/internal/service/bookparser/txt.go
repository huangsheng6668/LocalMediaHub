package bookparser

import (
	"fmt"
	"os"
)

func parseTxt(path string, info os.FileInfo) (*Book, error) {
	return nil, fmt.Errorf("not yet implemented")
}

func (b *Book) txtChapterText(idx int) (string, error) {
	return "", fmt.Errorf("not yet implemented")
}
