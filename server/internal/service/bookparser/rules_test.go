package bookparser

import "testing"

func TestIsVolumeHeader(t *testing.T) {
	tests := []struct {
		line      string
		wantMatch bool
		wantVol   string
	}{
		{"第一卷 创世纪", true, "第一卷 创世纪"},
		{"【第一卷】 序幕", true, "【第一卷】 序幕"},
		{"Volume 1 The Beginning", true, "Volume 1 The Beginning"},
		{"第1章 决战", false, ""},
	}

	for _, tt := range tests {
		gotMatch, gotVol := IsVolumeHeader(tt.line)
		if gotMatch != tt.wantMatch {
			t.Errorf("IsVolumeHeader(%q) match = %v, want %v", tt.line, gotMatch, tt.wantMatch)
		}
		if gotMatch && gotVol != tt.wantVol {
			t.Errorf("IsVolumeHeader(%q) vol = %q, want %q", tt.line, gotVol, tt.wantVol)
		}
	}
}

func TestIsEnclosedChapterHeader(t *testing.T) {
	tests := []struct {
		line string
		want bool
	}{
		{"【第123章 决战】", true},
		{"=== 第5章 启程 ===", true},
		{"102. 再次重逢", true},
		{"这是一普通正文句子。", false},
	}

	for _, tt := range tests {
		if got := IsChapterHeader(tt.line); got != tt.want {
			t.Errorf("IsChapterHeader(%q) = %v, want %v", tt.line, got, tt.want)
		}
	}
}
