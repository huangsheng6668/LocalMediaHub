package bookparser

import (
	"bufio"
	"bytes"
	"fmt"
	"html"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"unicode/utf8"

	"golang.org/x/text/encoding/simplifiedchinese"
	"golang.org/x/text/transform"
)

var (
	chapChapRegex            = regexp.MustCompile(`(?:第\s*)?([一二三四五六七八九十百千零0-9０-９]+)(?:\s*[-~～至到—–—]\s*[一二三四五六七八九十百千零0-9０-９]+)?\s*完?\s*章`)
	chapNumRegex             = regexp.MustCompile(`(?:第\s*)?([一二三四五六七八九十百千零0-9０-９]+)(?:\s*[-~～至到—–—]\s*[一二三四五六七八九十百千零0-9０-９]+)?\s*[章节回卷集部篇]`)
	// Allow decorative prefix before brackets (e.g., ＊＊＊（３）)
	chapParenRegex           = regexp.MustCompile(`^[^\p{L}\p{N}]*[【\[（(]\s*第?\s*([一二三四五六七八九十百千零0-9０-９]+)\s*[章节回]?\s*[】\]）)]`)
	chapDotRegex             = regexp.MustCompile(`([一二三四五六七八九十0-9０-９]{1,4})[、\.]`)
	chapBareRegex            = regexp.MustCompile(`^(\d{1,4})\s+`)
	chapCnBareRegex          = regexp.MustCompile(`^([一二三四五六七八九十]+)\s+`)
	chapEngRegex             = regexp.MustCompile(`(?i)^(?:Chapter|Section|Volume|Book)\s+(\d+)`)
	endMarkerRegex           = regexp.MustCompile(`[章节回卷集部篇]\s*完($|[\s　：:～~、，,;；]|评分|【|（|\()`)
	authorNoteRegex          = regexp.MustCompile(`^(前言|后记|序言|序章|编者按|作者的话)[：:]`)
	pagePrefixRegex          = regexp.MustCompile(`^[^\p{L}\p{N}]*第\s*[0-9一二三四五六七八九十]+\s*页[\s　]+`)
	inlineChapterSuffixRegex = regexp.MustCompile(`^(.+?)((?:第\s*)?[一二三四五六七八九十百千零0-9０-９]{1,4}\s*[章节回])\s*$`)

	// A heading line carrying BOTH a volume and a chapter counter, e.g.
	// "第一卷 第1章" or "【第二卷 第1章】". Without this check the volume
	// branch in splitChapters swallows such lines whole and the book loses
	// every chapter (only the volume fallback could rescue it).
	compoundVolChapRegex = regexp.MustCompile(`^[【\[\s　]*(第\s*[一二三四五六七八九十百千零0-9０-９]+\s*卷)[\s　]*[】\]]?[\s　]*[:：]?[\s　]*(?:第\s*)?[一二三四五六七八九十百千零0-9０-９]+\s*完?\s*[章节回]`)
	// Core "第X卷" token of a volume title, used to decide whether a compound
	// heading enters a new volume (vs. restating the current one).
	volCoreRegex = regexp.MustCompile(`第\s*[一二三四五六七八九十百千零0-9０-９]+\s*卷`)
)

func isEndMarker(trim string) bool {
	if IsChapterHeader(trim) {
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
		m = chapParenRegex.FindStringSubmatch(trim)
	}
	if len(m) == 0 {
		m = chapDotRegex.FindStringSubmatch(trim)
	}
	if len(m) == 0 {
		m = chapBareRegex.FindStringSubmatch(trim)
	}
	if len(m) == 0 {
		m = chapCnBareRegex.FindStringSubmatch(trim)
	}
	if len(m) > 1 {
		s := strings.TrimSpace(m[1])
		if n := chineseToNum(s); n > 0 {
			return fmt.Sprintf("%d", n)
		}
		return s
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
	text, charset, err := globalTxtCache.GetOrLoad(path, info.ModTime(), func() (string, string, error) {
		raw, err := os.ReadFile(path)
		if err != nil {
			return "", "", fmt.Errorf("%w: %v", ErrIoFailure, err)
		}
		decoded, cs := decodeTxt(raw)
		return decoded, cs, nil
	})
	if err != nil {
		return nil, err
	}

	chapters := splitChapters(text, filepath.Base(path))
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
	if strings.Contains(decoded, "&#") {
		decoded = html.UnescapeString(decoded)
	}
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
		volume  string
		volIdx  int
	}
	var metas []markMeta

	// Track volume marker positions for fallback promotion
	type volMark struct {
		title string
		start int
	}
	var volMarks []volMark

	currentVolume := ""
	currentVolIdx := -1

	off := 0
	scanner := bufio.NewScanner(strings.NewReader(text))
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for scanner.Scan() {
		line := scanner.Text()
		trim := strings.TrimSpace(line)
		trim = pagePrefixRegex.ReplaceAllString(trim, "")

		// "第一卷 第1章" / "【第二卷 第1章】": a chapter of the stated volume, not a
		// volume marker. Update volume tracking, then fall through to the regular
		// chapter-header handling below (it advances off) so dedupe logic stays in
		// one place.
		if m := compoundVolChapRegex.FindStringSubmatch(trim); m != nil && utf8.RuneCountInString(trim) <= 100 {
			vol := strings.TrimSpace(m[1])
			if cleanTitleForComparison(volCoreRegex.FindString(currentVolume)) != cleanTitleForComparison(vol) {
				currentVolIdx++
			}
			currentVolume = vol
		} else if isVol, volTitle := IsVolumeHeader(trim); isVol {
			volMarks = append(volMarks, volMark{title: volTitle, start: off})
			currentVolume = volTitle
			currentVolIdx++
			off += utf8.RuneCountInString(line) + 1
			continue
		}

		// Check for inline chapter suffix: a chapter header embedded at the end of a content
		// line (e.g. "...正式开始。    第三章" or "...幸福地在一起！第二章").
		// This must run BEFORE isEndMarker, because long content lines (>80 chars) or lines
		// ending with Chinese punctuation would otherwise be discarded by isEndMarker.
		if !IsChapterHeader(trim) {
			if sub := inlineChapterSuffixRegex.FindStringSubmatch(trim); len(sub) == 3 {
				// Ensure the prefix is genuine content (not just whitespace or punctuation-only).
				prefix := strings.TrimSpace(sub[1])
				if utf8.RuneCountInString(prefix) >= 3 {
					chapTitle := strings.TrimSpace(sub[2])
					lineRunes := utf8.RuneCountInString(line)
					prefixRunes := utf8.RuneCountInString(sub[1])
					// Place the chapter mark at the offset where the chapter token begins.
					chapOff := off + prefixRunes
					key := extractChapKey(chapTitle)
					m := chapterMark{title: chapTitle, start: chapOff}
					if len(marks) > 0 && key != "" {
						prevM := marks[len(marks)-1]
						prevMeta := metas[len(metas)-1]
						if key == prevMeta.chapKey && (chapOff-prevM.start < 3000) {
							c1 := cleanTitleForComparison(prevM.title)
							c2 := cleanTitleForComparison(chapTitle)
							if strings.HasPrefix(c1, c2) || strings.HasPrefix(c2, c1) || len(chapTitle) >= len(prevM.title) {
								marks[len(marks)-1] = m
								metas[len(metas)-1] = markMeta{chapKey: key, volume: currentVolume, volIdx: currentVolIdx}
								off += lineRunes + 1
								continue
							}
						}
					}
					marks = append(marks, m)
					metas = append(metas, markMeta{chapKey: key, volume: currentVolume, volIdx: currentVolIdx})
					off += lineRunes + 1
					continue
				}
			}
		}

		if isEndMarker(trim) {
			off += utf8.RuneCountInString(line) + 1
			continue
		}

		if IsChapterHeader(trim) {
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
						metas[len(metas)-1] = markMeta{chapKey: key, volume: currentVolume, volIdx: currentVolIdx}
						off += utf8.RuneCountInString(line) + 1
						continue
					}
				}
			}
			marks = append(marks, m)
			metas = append(metas, markMeta{chapKey: key, volume: currentVolume, volIdx: currentVolIdx})
		}
		off += utf8.RuneCountInString(line) + 1
	}

	// If no chapter marks found but volume markers exist, promote volumes to chapters
	if len(marks) == 0 && len(volMarks) > 0 {
		for _, vm := range volMarks {
			marks = append(marks, chapterMark{title: vm.title, start: vm.start})
			metas = append(metas, markMeta{chapKey: "", volume: "", volIdx: -1})
		}
	}

	if len(marks) == 0 {
		return []Chapter{{Title: fallbackTitle, Index: 0, CharStart: 0, CharEnd: utf8.RuneCountInString(text)}}
	}

	var chapters []Chapter
	if marks[0].start > 0 {
		preamble := strings.TrimRight(text[:marks[0].start], "\n")
		if strings.TrimSpace(preamble) != "" {
			chapters = append(chapters, Chapter{Title: "序言", Index: 0, CharStart: 0, CharEnd: marks[0].start, Volume: "", VolIndex: -1})
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
		meta := metas[i]
		chapters = append(chapters, Chapter{
			Title:     m.title,
			Index:     len(chapters),
			CharStart: start,
			CharEnd:   end,
			Volume:    meta.volume,
			VolIndex:  meta.volIdx,
		})
	}
	for i := range chapters {
		chapters[i].Index = i
	}
	return chapters
}

func (b *Book) txtChapterBlocks(idx int) ([]Block, error) {
	if idx < 0 || idx >= len(b.Chapters) {
		return nil, fmt.Errorf("chapter index out of range: %d", idx)
	}
	c := b.Chapters[idx]
	_, runes, err := globalTxtCache.GetOrLoadRunes(b.Path, b.ModTime, func() (string, string, error) {
		raw, err := os.ReadFile(b.Path)
		if err != nil {
			return "", "", fmt.Errorf("%w: %v", ErrIoFailure, err)
		}
		decoded, cs := decodeTxt(raw)
		return decoded, cs, nil
	})
	if err != nil {
		return nil, err
	}
	return GetChapterBlocksFromRunes(runes, c.CharStart, c.CharEnd), nil
}
