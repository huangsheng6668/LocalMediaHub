package handler

import "testing"

func TestStripRouteActionSuffix(t *testing.T) {
	tests := []struct {
		name   string
		path   string
		suffix string
		want   string
	}{
		{
			name:   "browse suffix",
			path:   "C:/Media/browse",
			suffix: "/browse",
			want:   "C:/Media",
		},
		{
			name:   "stream suffix",
			path:   "C:/Media/movie.mp4/stream",
			suffix: "/stream",
			want:   "C:/Media/movie.mp4",
		},
		{
			name:   "thumbnail suffix",
			path:   "C:/Media/poster.jpg/thumbnail",
			suffix: "/thumbnail",
			want:   "C:/Media/poster.jpg",
		},
		{
			name:   "original suffix",
			path:   "C:/Media/poster.jpg/original",
			suffix: "/original",
			want:   "C:/Media/poster.jpg",
		},
		{
			name:   "unchanged when suffix missing",
			path:   "C:/Media",
			suffix: "/browse",
			want:   "C:/Media",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := stripRouteActionSuffix(tt.path, tt.suffix)
			if got != tt.want {
				t.Fatalf("stripRouteActionSuffix(%q, %q) = %q, want %q", tt.path, tt.suffix, got, tt.want)
			}
		})
	}
}

func TestDecodeWildcardPath(t *testing.T) {
	tests := []struct {
		name   string
		path   string
		suffix string
		want   string
	}{
		{
			name:   "decodes unicode and spaces",
			path:   "F:/manga-translator-ui/output/(%E5%90%8C%E4%BA%BACG%E9%9B%86)%20[%E3%82%B0%E3%83%A9%E3%82%B9%E3%82%BF%E3%83%BC%E3%83%88%E3%83%AB]/browse",
			suffix: "/browse",
			want:   "F:/manga-translator-ui/output/(同人CG集) [グラスタートル]",
		},
		{
			name:   "decodes encoded slash markers when present",
			path:   "F:%2FMedia%2FAnime%2Fposter.jpg%2Foriginal",
			suffix: "/original",
			want:   "F:/Media/Anime/poster.jpg",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := decodeWildcardPath(tt.path, tt.suffix)
			if err != nil {
				t.Fatalf("decodeWildcardPath(%q, %q) returned error: %v", tt.path, tt.suffix, err)
			}
			if got != tt.want {
				t.Fatalf("decodeWildcardPath(%q, %q) = %q, want %q", tt.path, tt.suffix, got, tt.want)
			}
		})
	}
}
