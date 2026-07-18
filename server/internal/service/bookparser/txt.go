package bookparser

import (
	"bufio"
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"unicode/utf8"

	"golang.org/x/text/encoding/simplifiedchinese"
	"golang.org/x/text/transform"
)

var commonRules = []*regexp.Regexp{
	regexp.MustCompile(`^[^\p{L}\p{N}]*第\s*[一二三四五六七八九十百千零0-9０-９]+\s*[章节回卷集部篇]`),
	regexp.MustCompile(`^Chapter\s+\d+`),
	regexp.MustCompile(`^楔子($|[\s　：:～~、，,;；])`),
	regexp.MustCompile(`^序章($|[\s　：:～~、，,;；])`),
	regexp.MustCompile(`^尾声($|[\s　：:～~、，,;；])`),
	regexp.MustCompile(`^前言($|[\s　：:～~、，,;；])`),
	regexp.MustCompile(`^后记($|[\s　：:～~、，,;；])`),
}

func parseTxt(path string, info os.FileInfo) (*Book, error) {
	if info.Size() > MaxTxtSize {
		return nil, fmt.Errorf("%w: %d bytes", ErrTooLarge, info.Size())
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrIoFailure, err)
	}
	decoded, charset := decodeTxt(raw)
	chapters := splitChapters(decoded, filepath.Base(path))
	return &Book{
		Path:     path,
		Format:   "txt",
		Title:    filepath.Base(path),
		Charset:  charset,
		Chapters: chapters,
		ModTime:  info.ModTime(),
	}, nil
}

func decodeTxt(raw []byte) (string, string) {
	var decoded string
	var charset string
	if bytes.HasPrefix(raw, []byte{0xEF, 0xBB, 0xBF}) {
		decoded, charset = string(raw[3:]), "UTF-8"
	} else if utf8.Valid(raw) {
		decoded, charset = string(raw), "UTF-8"
	} else {
		dec := simplifiedchinese.GB18030.NewDecoder()
		gb, _, err := transform.String(dec, string(raw))
		if err == nil && utf8.ValidString(gb) {
			decoded, charset = gb, "GB18030"
		} else {
			decoded, charset = string(raw), "UTF-8"
		}
	}
	decoded = strings.ReplaceAll(decoded, "\r\n", "\n")
	return decoded, charset
}

type chapterMark struct {
	title string
	start int
}

func splitChapters(text, fallbackTitle string) []Chapter {
	var marks []chapterMark
	off := 0
	scanner := bufio.NewScanner(strings.NewReader(text))
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for scanner.Scan() {
		line := scanner.Text()
		trim := strings.TrimSpace(line)
		for _, re := range commonRules {
			if re.MatchString(trim) {
				marks = append(marks, chapterMark{title: trim, start: off})
				break
			}
		}
		off += utf8.RuneCountInString(line) + 1
	}

	if len(marks) == 0 {
		return []Chapter{{Title: fallbackTitle, Index: 0, CharStart: 0, CharEnd: utf8.RuneCountInString(text)}}
	}

	var chapters []Chapter
	if marks[0].start > 0 {
		preamble := strings.TrimRight(text[:marks[0].start], "\n")
		if strings.TrimSpace(preamble) != "" {
			chapters = append(chapters, Chapter{Title: "序言", Index: 0, CharStart: 0, CharEnd: marks[0].start})
		}
	}
	for i, m := range marks {
		start := m.start
		var end int
		if i+1 < len(marks) {
			end = marks[i+1].start
		} else {
			end = utf8.RuneCountInString(text)
		}
		chapters = append(chapters, Chapter{
			Title:     m.title,
			Index:     len(chapters),
			CharStart: start,
			CharEnd:   end,
		})
	}
	for i := range chapters {
		chapters[i].Index = i
	}
	return chapters
}

// txtChapterBlocks reads the file, decodes via [decodeTxt], slices by
// rune offsets CharStart..CharEnd, then splits on "\n\n" into multiple
// text Blocks (empty paragraphs filtered). Returns a single "[本章节为空]"
// placeholder block if the slice produces no non-empty paragraphs.
func (b *Book) txtChapterBlocks(idx int) ([]Block, error) {
	c := b.Chapters[idx]
	raw, err := os.ReadFile(b.Path)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrIoFailure, err)
	}
	text, _ := decodeTxt(raw)
	runes := []rune(text)
	start := clampInt(c.CharStart, 0, len(runes))
	end := clampInt(c.CharEnd, 0, len(runes))
	if start > end {
		start = end
	}
	slice := string(runes[start:end])
	paras := strings.Split(slice, "\n\n")
	blocks := make([]Block, 0, len(paras))
	for _, p := range paras {
		if s := strings.TrimSpace(p); s != "" {
			blocks = append(blocks, Block{Type: "text", Value: s})
		}
	}
	if len(blocks) == 0 {
		return []Block{{Type: "text", Value: "[本章节为空]"}}, nil
	}
	return blocks, nil
}

// clampInt returns v clipped to [lo, hi]. Used for CharStart/CharEnd bounds.
func clampInt(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}
