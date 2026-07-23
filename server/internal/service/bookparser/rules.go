package bookparser

import (
	"regexp"
	"strings"
	"unicode/utf8"
)

var volumeRules = []*regexp.Regexp{
	regexp.MustCompile(`^[^\p{L}\p{N}]*第\s*[一二三四五六七八九十百千零0-9０-９]+\s*卷\s*.*`),
	regexp.MustCompile(`^(?:Volume|Vol\.)\s*\d+.*`),
	regexp.MustCompile(`^[^\p{L}\p{N}]*卷\s*[一二三四五六七八九十0-9]+\s*.*`),
}

var chapterRules = []*regexp.Regexp{
	regexp.MustCompile(`^[^\p{L}\p{N}]*第\s*[一二三四五六七八九十百千零0-9０-９]+(?:\s*[-~～至到—–—]\s*[一二三四五六七八九十百千零0-9０-９]+)?\s*完?\s*[章节回卷集部篇]`),
	regexp.MustCompile(`^[【\[（(]\s*第?\s*[一二三四五六七八九十百千零0-9０-９]+\s*[章节回]\s*.*[】\]）)]`),
	regexp.MustCompile(`^(?:={3,}|-{3,}|\*{3,})\s*第?\s*[一二三四五六七八九十0-9]+\s*[章节]?.*`),
	regexp.MustCompile(`^\d{1,4}\.\s+.*`),
	regexp.MustCompile(`^[一二三四五六七八九十]+[、\.]\s*.*`),
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


func IsVolumeHeader(line string) (bool, string) {
	trim := strings.TrimSpace(line)
	if utf8.RuneCountInString(trim) > 100 {
		return false, ""
	}
	for _, re := range volumeRules {
		if re.MatchString(trim) {
			return true, trim
		}
	}
	return false, ""
}

func IsChapterHeader(line string) bool {
	trim := strings.TrimSpace(line)
	for _, re := range chapterRules {
		if re.MatchString(trim) {
			return true
		}
	}
	return false
}



