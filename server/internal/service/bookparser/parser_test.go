package bookparser

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestParseUnsupportedForUnknownExt(t *testing.T) {
	dir := t.TempDir()
	unknown := filepath.Join(dir, "x.unknown")
	require.NoError(t, os.WriteFile(unknown, []byte("data"), 0644))
	b, err := Parse(unknown)
	assert.ErrorIs(t, err, ErrUnsupported)
	require.NotNil(t, b)
	assert.Equal(t, "unsupported", b.Format)
}

func TestParseIoFailureForMissingFile(t *testing.T) {
	_, err := Parse(filepath.Join(t.TempDir(), "missing.txt"))
	assert.ErrorIs(t, err, ErrIoFailure)
}
