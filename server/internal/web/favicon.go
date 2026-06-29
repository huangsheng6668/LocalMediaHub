package web

import (
	"bytes"
	"image"
	"image/color"
	"image/draw"
	"image/png"
)

// FaviconPNG is a 32x32 blue "M" favicon rendered at init time, mirroring the
// system-tray icon design for brand consistency. It is served at /favicon.ico so
// the browser's automatic request resolves instead of returning a 404.
// Generated procedurally to avoid shipping a binary asset (same approach as
// internal/systray/icon.go).
var FaviconPNG = mustRenderFavicon()

func mustRenderFavicon() []byte {
	const size = 32
	img := image.NewRGBA(image.Rect(0, 0, size, size))

	// Solid blue background (#2C60DC), matching the tray icon.
	bg := color.RGBA{R: 44, G: 96, B: 220, A: 255}
	draw.Draw(img, img.Bounds(), &image.Uniform{bg}, image.Point{}, draw.Src)

	// Clear the 2x2 corner pixels for a slightly rounded look.
	transparent := color.RGBA{A: 0}
	for _, c := range [][2]int{{0, 0}, {1, 0}, {0, 1}, {size - 1, 0}, {size - 2, 0}, {size - 1, 1}, {0, size - 1}, {1, size - 1}, {0, size - 2}, {size - 1, size - 1}, {size - 2, size - 1}, {size - 1, size - 2}} {
		img.Set(c[0], c[1], transparent)
	}

	// White "M" glyph (12x14 pattern, 1 = white), centered at offset (10, 9).
	white := color.RGBA{R: 255, G: 255, B: 255, A: 255}
	m := [14][12]int{
		{1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1},
		{1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1},
		{1, 1, 1, 0, 0, 0, 0, 0, 0, 1, 1, 1},
		{1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1},
		{1, 1, 1, 1, 1, 0, 0, 1, 1, 1, 1, 1},
		{1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1},
		{1, 1, 1, 1, 1, 0, 0, 1, 1, 1, 1, 1},
		{1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1},
		{1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1},
		{1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1},
		{1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1},
		{1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1},
		{1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1},
		{1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1},
	}
	offX, offY := 10, 9
	for y, row := range m {
		for x, v := range row {
			if v == 1 {
				img.Set(offX+x, offY+y, white)
			}
		}
	}

	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		// png.Encode on an in-memory image only fails on Write errors, which a
		// bytes.Buffer never produces; panic is acceptable here.
		panic(err)
	}
	return buf.Bytes()
}
