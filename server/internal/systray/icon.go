package systray

import (
	"bytes"
	"image"
	"image/color"
	"image/draw"
	"image/png"
)

// trayIconBytes is a 32x32 PNG rendered at init time so the system tray shows a
// recognizable icon (blue rounded square) instead of a blank/default one.
// Generated procedurally to avoid shipping a binary asset; getlantern/systray
// accepts PNG on Windows (it converts internally).
var trayIconBytes = mustRenderIcon()

func mustRenderIcon() []byte {
	const size = 32
	img := image.NewRGBA(image.Rect(0, 0, size, size))

	// Background: solid blue everywhere, then we'll keep the corners transparent
	// to fake a rounded look.
	bg := color.RGBA{R: 44, G: 96, B: 220, A: 255} // #2C60DC
	draw.Draw(img, img.Bounds(), &image.Uniform{bg}, image.Point{}, draw.Src)

	// Clear the 4 corners (2x2 px) to transparent for a slightly rounded feel.
	transparent := color.RGBA{A: 0}
	for _, c := range [][2]int{{0, 0}, {1, 0}, {0, 1}, {size - 1, 0}, {size - 2, 0}, {size - 1, 1}, {0, size - 1}, {1, size - 1}, {0, size - 2}, {size - 1, size - 1}, {size - 2, size - 1}, {size - 1, size - 2}} {
		img.Set(c[0], c[1], transparent)
	}

	// Draw a simple white "M" glyph using pixel blocks.
	white := color.RGBA{R: 255, G: 255, B: 255, A: 255}
	// "M" as a 12x14 pattern (1 = white pixel), centered.
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
	// Center the 12x14 glyph in the 32x32 canvas: offset x=10, y=9.
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
