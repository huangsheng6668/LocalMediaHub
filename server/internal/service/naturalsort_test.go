package service

import (
	"testing"
	"time"

	"github.com/localmediahub/server/internal/models"
)

func TestNaturalCompare(t *testing.T) {
	cases := []struct {
		a, b string
		want int
	}{
		{"file2", "file10", -1},
		{"file10", "file2", 1},
		{"file007", "file7", 0},
		{"IMG.JPG", "img.jpg", 0},
		{"007_gjco", "abc", -1},
		{"abc", "007_gjco", 1},
		{"100", "20", 1},
		{"20", "100", -1},
		{"99999999999999999999", "1", 1},
		{"", "", 0},
		{"", "a", -1},
		{"a", "", 1},
		{"2", "10", -1},
		{"img2", "img10", -1},
		{"a", "b", -1},
		{"x", "x", 0},
		{"10", "2", 1},
	}
	for _, tc := range cases {
		if got := naturalCompare(tc.a, tc.b); got != tc.want {
			t.Errorf("naturalCompare(%q, %q) = %d, want %d", tc.a, tc.b, got, tc.want)
		}
	}
}

func TestSortMediaFilesNameAsc(t *testing.T) {
	files := []models.MediaFile{
		{Name: "img10.jpg"}, {Name: "img2.jpg"}, {Name: "img1.jpg"},
	}
	SortMediaFiles(files, "name", "asc")
	want := []string{"img1.jpg", "img2.jpg", "img10.jpg"}
	for i, f := range files {
		if f.Name != want[i] {
			t.Fatalf("name asc order: got %v, want %v", names(files), want)
		}
	}
}

func TestSortMediaFilesSizeDescWithNameTieBreak(t *testing.T) {
	base := time.Date(2025, 1, 1, 0, 0, 0, 0, time.UTC)
	files := []models.MediaFile{
		{Name: "b.mp4", Size: 10, ModifiedTime: base},
		{Name: "a.mp4", Size: 10, ModifiedTime: base},
		{Name: "c.mp4", Size: 99, ModifiedTime: base},
	}
	SortMediaFiles(files, "size", "desc")
	want := []string{"c.mp4", "a.mp4", "b.mp4"} // size desc; 10-tie keeps name asc
	for i, f := range files {
		if f.Name != want[i] {
			t.Fatalf("size desc order: got %v, want %v", names(files), want)
		}
	}
}

func TestSortMediaFilesNumericPutsNonNumericLastBothDirections(t *testing.T) {
	base := time.Date(2025, 1, 1, 0, 0, 0, 0, time.UTC)
	files := []models.MediaFile{
		{Name: "zzz.txt", Size: 1, ModifiedTime: base},
		{Name: "10.txt", Size: 1, ModifiedTime: base},
		{Name: "2.txt", Size: 1, ModifiedTime: base},
	}
	SortMediaFiles(files, "numeric", "asc")
	if files[0].Name != "2.txt" || files[1].Name != "10.txt" || files[2].Name != "zzz.txt" {
		t.Fatalf("numeric asc: got %v", names(files))
	}

	SortMediaFiles(files, "numeric", "desc")
	if files[0].Name != "10.txt" || files[1].Name != "2.txt" || files[2].Name != "zzz.txt" {
		t.Fatalf("numeric desc: got %v", names(files))
	}
}

func names(files []models.MediaFile) []string {
	out := make([]string, len(files))
	for i, f := range files {
		out[i] = f.Name
	}
	return out
}
