package bookparser

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"unicode/utf8"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"golang.org/x/text/encoding/simplifiedchinese"
)

func writeBytes(t *testing.T, path string, data []byte) {
	t.Helper()
	require.NoError(t, os.WriteFile(path, data, 0644))
}

func TestTxtUtf8BomDetected(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "u8.txt")
	body := []byte{0xEF, 0xBB, 0xBF}
	body = append(body, []byte("第一章 开始\n正文一\n第二章 结束\n正文二")...)
	writeBytes(t, p, body)
	b, err := Parse(p)
	require.NoError(t, err)
	assert.Equal(t, "txt", b.Format)
	assert.Equal(t, "UTF-8", b.Charset)
	assert.GreaterOrEqual(t, len(b.Chapters), 2)
}

func TestTxtGB18030Detected(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "gb.txt")
	enc := simplifiedchinese.GBK.NewEncoder()
	s, err := enc.String("第一章 开始\n正文内容")
	require.NoError(t, err)
	writeBytes(t, p, []byte(s))
	b, err := Parse(p)
	require.NoError(t, err)
	assert.Equal(t, "GB18030", b.Charset)
	txt, err := b.ChapterText(0)
	require.NoError(t, err)
	assert.True(t, utf8.ValidString(txt))
	assert.Contains(t, txt, "开始")
}

func TestTxtNoChapterMatchBecomesSingleChapter(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "plain.txt")
	writeBytes(t, p, []byte("这是一本没有任何章节标记的书。"))
	b, err := Parse(p)
	require.NoError(t, err)
	require.Len(t, b.Chapters, 1)
	assert.Equal(t, "plain.txt", b.Chapters[0].Title)
}

func TestTxtChapterOffsetsRoundTrip(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "r.txt")
	writeBytes(t, p, []byte("第一章 A\n这是第一章的正文内容\n第二章 B\n这是第二章的正文内容"))
	b, err := Parse(p)
	require.NoError(t, err)
	require.Len(t, b.Chapters, 2)
	c0, err := b.ChapterText(0)
	require.NoError(t, err)
	assert.Contains(t, c0, "这是第一章的正文内容")
	assert.NotContains(t, c0, "第二章")
	c1, err := b.ChapterText(1)
	require.NoError(t, err)
	assert.Contains(t, c1, "这是第二章的正文内容")
}

func TestTxtChapterOffsetsRoundTripCRLF(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "r_crlf.txt")
	writeBytes(t, p, []byte("第一章 A\r\n这是第一章的正文内容\r\n第二章 B\r\n这是第二章的正文内容"))
	b, err := Parse(p)
	require.NoError(t, err)
	require.Len(t, b.Chapters, 2)
	c0, err := b.ChapterText(0)
	require.NoError(t, err)
	assert.Contains(t, c0, "这是第一章的正文内容")
	assert.NotContains(t, c0, "第二章")
	c1, err := b.ChapterText(1)
	require.NoError(t, err)
	assert.Contains(t, c1, "这是第二章的正文内容")
}

func TestTxtChapterOffsetsRoundTripCRLF_Drift(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "drift.txt")
	content := "第一章 A\r\nline1\r\nline2\r\nline3\r\nline4\r\nline5\r\nline6\r\nline7\r\nline8\r\nline9\r\nline10\r\n第二章 B\r\n这是第二章的正文内容"
	writeBytes(t, p, []byte(content))
	b, err := Parse(p)
	require.NoError(t, err)
	require.Len(t, b.Chapters, 2)
	c1, err := b.ChapterText(1)
	require.NoError(t, err)
	
	// Chapter 1 should start exactly with the chapter title "第二章 B", not with "lineX" from Chapter 0.
	assert.True(t, strings.HasPrefix(strings.TrimSpace(c1), "第二章 B"), "c1 should start with '第二章 B', but got: %q", c1)
}

func TestTxtChapterRegexComprehensive(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "comprehensive.txt")
	content := "楔子～开启故事\n第一章　龙回故乡\n第１章：走马上任\n第229章-尾声\n第０１章、第一次性交示范课\n第2章我成了神手\n第 2 章 我成了神手\n第2章\n后记"
	writeBytes(t, p, []byte(content))
	b, err := Parse(p)
	require.NoError(t, err)
	
	// Should match: 楔子, 第一章, 第１章, 第229章, 第０１章, 第2章, 第 2 章, 第2章, 后记
	require.Len(t, b.Chapters, 9)
	assert.Equal(t, "楔子～开启故事", b.Chapters[0].Title)
	assert.Equal(t, "第一章　龙回故乡", b.Chapters[1].Title)
	assert.Equal(t, "第１章：走马上任", b.Chapters[2].Title)
	assert.Equal(t, "第229章-尾声", b.Chapters[3].Title)
	assert.Equal(t, "第０１章、第一次性交示范课", b.Chapters[4].Title)
	assert.Equal(t, "第2章我成了神手", b.Chapters[5].Title)
	assert.Equal(t, "第 2 章 我成了神手", b.Chapters[6].Title)
	assert.Equal(t, "第2章", b.Chapters[7].Title)
	assert.Equal(t, "后记", b.Chapters[8].Title)
}

func TestTxtTooLargeRejected(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "big.txt")
	f, err := os.Create(p)
	require.NoError(t, err)
	require.NoError(t, f.Truncate(int64(MaxTxtSize + 1)))
	require.NoError(t, f.Close())
	_, err = Parse(p)
	assert.ErrorIs(t, err, ErrTooLarge)
}
