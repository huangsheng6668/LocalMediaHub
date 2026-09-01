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
	regexp.MustCompile(`^[^\p{L}\p{N}]*(?:上|中|下|前|后)[卷篇部](?:[\s　：:～~、，,;；\-_—]|$).*`),
	regexp.MustCompile(`^[^\p{L}\p{N}]*(?:上|中|下|前篇|后篇)\s*[^\p{L}\p{N}]*$`),
	// Range markers like ━━━ 第1-5章 ━━━ are section groupings, not individual chapters
	regexp.MustCompile(`^(?:={3,}|-{3,}|\*{3,}|━{3,})\s*第?\s*[一二三四五六七八九十0-9０-９]+\s*[-~～至到—–—]\s*[一二三四五六七八九十0-9０-９]+\s*[章节回]?\s*(?:={3,}|-{3,}|\*{3,}|━{3,})?`),
}

var chapterRules = []*regexp.Regexp{
	// With the 第 prefix keep the loose form: compact titles like "第4章我成了神手" are common.
	regexp.MustCompile(`^[^\p{L}\p{N}]*第\s*[一二三四五六七八九十百千零0-9０-９]+(?:\s*[-~～至到—–—]\s*[一二三四五六七八九十百千零0-9０-９]+)?\s*完?\s*[章节回卷集部篇]`),
	// Without 第, the numeral+counter form must be followed by a separator (or end
	// of line) — otherwise prose like "一回到家…" or "一部分记忆…" is mistaken for
	// a chapter heading.
	regexp.MustCompile(`^[^\p{L}\p{N}]*[一二三四五六七八九十百千零0-9０-９]+(?:\s*[-~～至到—–—]\s*[一二三四五六七八九十百千零0-9０-９]+)?\s*完?\s*[章节回卷集部篇](?:$|[\s　：:～~、，,;；_—【\[（()（）\-])`),
	// Allow decorative prefix before brackets (e.g., ＊＊＊（３）)
	regexp.MustCompile(`^[^\p{L}\p{N}]*[【\[（(]\s*第?\s*[一二三四五六七八九十百千零0-9０-９\s]+\s*[章节回]?\s*[】\]）)]\s*.*`),
	regexp.MustCompile(`^(?:={3,}|-{3,}|\*{3,}|━{3,})\s*第?\s*[一二三四五六七八九十0-9]+.*`),
	regexp.MustCompile(`^\d{1,4}\.\s+.*`),
	regexp.MustCompile(`^\d{1,4}\s+[^\s\d].*`),
	regexp.MustCompile(`^[一二三四五六七八九十]+[、\.]\s*.*`),
	// Chinese numeral + space + title (e.g., 一 邂逅, 二 通奸)
	regexp.MustCompile(`^[一二三四五六七八九十]+\s+\S.*`),
	regexp.MustCompile(`^(?:Chapter|Section|Volume|Book)\s+\d+`),
	regexp.MustCompile(`^楔子($|[\s　：:～~、，,;；])`),
	regexp.MustCompile(`^序[章言]($|[\s　：:～~、，,;；])`),
	regexp.MustCompile(`^尾声($|[\s　：:～~、，,;；])`),
	regexp.MustCompile(`^前言($|[\s　：:～~、，,;；])`),
	regexp.MustCompile(`^后记($|[\s　：:～~、，,;；])`),
	regexp.MustCompile(`^终章($|[\s　：:～~、，,;；])`),
	regexp.MustCompile(`^[^\p{L}\p{N}]*番外(?:篇|章|[\s　：:～~、，,;；\-_—\d一二三四五六七八九十0-9０-９]|$)`),
	// 【上篇：...】, 【中篇：...】, 【下篇：...】, 【序言：...】
	regexp.MustCompile(`^【(?:上篇|中篇|下篇|前篇|后篇|序言|番外|尾声)[：:][^】]+】`),
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



