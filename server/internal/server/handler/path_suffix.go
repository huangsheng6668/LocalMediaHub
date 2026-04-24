package handler

import (
	"fmt"
	"net/url"
	"strings"
)

func stripRouteActionSuffix(path string, suffix string) string {
	if suffix == "" {
		return path
	}
	return strings.TrimSuffix(path, suffix)
}

func decodeWildcardPath(path string, suffix string) (string, error) {
	decoded, err := url.PathUnescape(path)
	if err != nil {
		return "", fmt.Errorf("invalid path encoding: %w", err)
	}
	return stripRouteActionSuffix(decoded, suffix), nil
}
