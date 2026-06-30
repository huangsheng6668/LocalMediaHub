package service

import (
	"path/filepath"
	"testing"
)

func TestParseFFprobeDuration(t *testing.T) {
	cases := map[string]struct {
		out   string
		want  float64
		valid bool
	}{
		"plain seconds":   {"12.5", 12.5, true},
		"integer":         {"60", 60, true},
		"with whitespace": {"  12.500000  \n", 12.5, true},
		"empty":           {"", 0, false},
		"N/A":             {"N/A", 0, false},
		"non-numeric":     {"abc", 0, false},
		"zero":            {"0", 0, false},
		"negative":        {"-1", 0, false},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			got, ok := parseFFprobeDuration(c.out)
			if ok != c.valid {
				t.Errorf("parseFFprobeDuration(%q) valid=%v, want %v", c.out, ok, c.valid)
			}
			if ok && got != c.want {
				t.Errorf("parseFFprobeDuration(%q) = %v, want %v", c.out, got, c.want)
			}
		})
	}
}

func TestMidpointSeek(t *testing.T) {
	cases := []struct {
		name     string
		duration float64
		ok       bool
		want     string
	}{
		{"midpoint of 60s", 60, true, "30.00"},
		{"midpoint of 12.5s", 12.5, true, "6.25"},
		{"unknown duration falls back to 5", 0, false, "5"},
		{"non-positive falls back to 5", -1, true, "5"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := midpointSeek(c.duration, c.ok); got != c.want {
				t.Errorf("midpointSeek(%v,%v) = %q, want %q", c.duration, c.ok, got, c.want)
			}
		})
	}
}

func TestFFprobeSibling(t *testing.T) {
	// Use filepath.Join for both input and expected so the test is separator-agnostic.
	if got, want := ffprobeSibling(filepath.Join("dir", "ffmpeg.exe")), filepath.Join("dir", "ffprobe.exe"); got != want {
		t.Errorf("ffmpeg.exe -> %q, want %q", got, want)
	}
	if got, want := ffprobeSibling("ffmpeg"), "ffprobe"; got != want {
		t.Errorf("bare ffmpeg -> %q, want %q", got, want)
	}
	if got, want := ffprobeSibling(filepath.Join("dir", "avconv.exe")), "ffprobe"; got != want {
		t.Errorf("non-ffmpeg base -> %q, want %q", got, want)
	}
}
