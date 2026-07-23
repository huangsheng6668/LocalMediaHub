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
	regexp.MustCompile(`^[^\p{L}\p{N}]*第\s*[一二三四五六七八九十百千零0-9０-９]+(?:\s*[-~～至到—–—]\s*[一二三四五六七八九十百千零0-9０-９]+)?\s*完?\s*[章节回卷集部篇]`),
	regexp.MustCompile(`^(?:Chapter|Section|Volume|Book)\s+\d+`),
	regexp.MustCompile(`^楔子($|[\s　：:～~、，,;；])`),
	regexp.MustCompile(`^序[章言]($|[\s　：:～~、，,;；])`),
	regexp.MustCompile(`^尾声($|[\s　：:～~、，,;；])`),
	regexp.MustCompile(`^前言($|[\s　：:～~、，,;；])`),
	regexp.MustCompile(`^后记($|[\s　：:～~、，,;；])`),
	regexp.MustCompile(`^终章($|[\s　：:～~、，,;；])`),
	regexp.MustCompile(`^[^\p{L}\p{N}]*番外(?:篇|章|[\s　：:～~、，,;；\-_—\d一二三四五六七八九十0-9０-９]|$)`),
	regexp.MustCompile(`^[^\s\d一二三四五六七八九十]+\s+[一二三四五六七八九十0-9０-９]{1,4}[、\.].*`),
}

var (
	chapChapRegex   = regexp.MustCompile(`第\s*([一二三四五六七八九十百千零0-9０-９]+)(?:\s*[-~～至到—–—]\s*[一二三四五六七八九十百千零0-9０-９]+)?\s*完?\s*章`)
	chapNumRegex    = regexp.MustCompile(`第\s*([一二三四五六七八九十百千零0-9０-９]+)(?:\s*[-~～至到—–—]\s*[一二三四五六七八九十百千零0-9０-９]+)?\s*[章节回卷集部篇]`)
	chapDotRegex    = regexp.MustCompile(`([一二三四五六七八九十0-9０-９]{1,4})[、\.]`)
	chapEngRegex    = regexp.MustCompile(`(?i)^(?:Chapter|Section|Volume|Book)\s+(\d+)`)
	endMarkerRegex  = regexp.MustCompile(`[章节回卷集部篇]\s*完($|[\s　：:～~、，,;；]|评分|【|（|\()`)
	authorNoteRegex = regexp.MustCompile(`^(前言|后记|序言|序章|编者按|作者的话)[：:]`)
	pagePrefixRegex = regexp.MustCompile(`^[^\p{L}\p{N}]*第\s*[0-9一二三四五六七八九十]+\s*页[\s　]+`)
)

func isChapterHeader(trim string) bool {
	for _, re := range commonRules {
		if re.MatchString(trim) {
			return true
		}
	}
	return false
}

func isEndMarker(trim string) bool {
	if isChapterHeader(trim) {
		return false
	}
	if utf8.RuneCountInString(trim) > 80 {
		return true
	}
	if authorNoteRegex.MatchString(trim) && utf8.RuneCountInString(trim) > 20 {
		return true
	}
	if endMarkerRegex.MatchString(trim) {
		return true
	}
	if strings.Contains(trim, "本章完") || strings.Contains(trim, "全书完") || strings.Contains(trim, "全剧终") || strings.Contains(trim, "评分完成") {
		return true
	}
	if strings.Contains(trim, "加") && (strings.Contains(trim, "银元") || strings.Contains(trim, "金币")) {
		return true
	}
	if strings.HasSuffix(trim, "。") || strings.HasSuffix(trim, "！") || strings.HasSuffix(trim, "？") || strings.HasSuffix(trim, "!") || strings.HasSuffix(trim, "?") {
		return true
	}
	if strings.Contains(trim, "节课") || strings.Contains(trim, "；") {
		return true
	}
	return false
}

func chineseToNum(s string) int {
	var buf strings.Builder
	for _, r := range s {
		if r >= '０' && r <= '９' {
			buf.WriteRune(r - '０' + '0')
		} else {
			buf.WriteRune(r)
		}
	}
	norm := buf.String()
	var val int
	if _, err := fmt.Sscanf(norm, "%d", &val); err == nil && val > 0 {
		return val
	}

	cnMap := map[rune]int{'零': 0, '一': 1, '二': 2, '两': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9}
	unitMap := map[rune]int{'十': 10, '百': 100, '千': 1000, '万': 10000}

	total := 0
	num := 0
	for _, r := range norm {
		if v, ok := cnMap[r]; ok {
			num = v
		} else if u, ok := unitMap[r]; ok {
			if num == 0 && u == 10 {
				num = 1
			}
			total += num * u
			num = 0
		} else if r >= '0' && r <= '9' {
			num = int(r - '0')
		}
	}
	total += num
	return total
}

func extractChapKey(trim string) string {
	trim = pagePrefixRegex.ReplaceAllString(trim, "")
	m := chapChapRegex.FindStringSubmatch(trim)
	if len(m) == 0 {
		m = chapNumRegex.FindStringSubmatch(trim)
	}
	if len(m) == 0 {
		m = chapDotRegex.FindStringSubmatch(trim)
	}
	if len(m) > 1 {
		if n := chineseToNum(m[1]); n > 0 {
			return fmt.Sprintf("%d", n)
		}
		return m[1]
	}
	if m2 := chapEngRegex.FindStringSubmatch(trim); len(m2) > 1 {
		return m2[1]
	}
	return ""
}

func cleanTitleForComparison(title string) string {
	title = strings.ReplaceAll(title, " ", "")
	title = strings.ReplaceAll(title, "　", "")
	title = strings.ReplaceAll(title, "\t", "")
	return title
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
	type markMeta struct {
		chapKey string
	}
	var metas []markMeta

	off := 0
	scanner := bufio.NewScanner(strings.NewReader(text))
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for scanner.Scan() {
		line := scanner.Text()
		trim := strings.TrimSpace(line)
		trim = pagePrefixRegex.ReplaceAllString(trim, "")
		if isEndMarker(trim) {
			off += utf8.RuneCountInString(line) + 1
			continue
		}
		matched := false
		for _, re := range commonRules {
			if re.MatchString(trim) {
				matched = true
				break
			}
		}
		if matched {
			key := extractChapKey(trim)
			t := trim
			if idx := strings.Index(t, "www."); idx > 0 {
				t = strings.TrimSpace(t[:idx])
			}
			if utf8.RuneCountInString(t) > 50 {
				runes := []rune(t)
				t = string(runes[:50])
			}
			m := chapterMark{title: t, start: off}
			if len(marks) > 0 && key != "" {
				prevM := marks[len(marks)-1]
				prevMeta := metas[len(metas)-1]
				if key == prevMeta.chapKey && (off-prevM.start < 3000) {
					c1 := cleanTitleForComparison(prevM.title)
					c2 := cleanTitleForComparison(trim)
					prevIsRange := strings.ContainsAny(prevM.title, "-~～至到—–—")
					currIsRange := strings.ContainsAny(trim, "-~～至到—–—")
					if (prevIsRange && !currIsRange) || strings.HasPrefix(c1, c2) || strings.HasPrefix(c2, c1) || len(trim) >= len(prevM.title) {
						marks[len(marks)-1] = m
						metas[len(metas)-1] = markMeta{chapKey: key}
						off += utf8.RuneCountInString(line) + 1
						continue
					}
				}
			}
			marks = append(marks, m)
			metas = append(metas, markMeta{chapKey: key})
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
