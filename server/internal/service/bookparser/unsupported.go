package bookparser

import (
	"os"
	"path/filepath"
)

func parseUnsupported(path string, info os.FileInfo) (*Book, error) {
	return &Book{
		Path:     path,
		Format:   "unsupported",
		Title:    filepath.Base(path),
		Chapters: nil,
		ModTime:  info.ModTime(),
	}, ErrUnsupported
}
