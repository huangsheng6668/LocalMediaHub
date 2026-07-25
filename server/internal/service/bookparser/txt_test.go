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

// joinTextBlocks concatenates all text-block Values with "\n\n" so existing
// round-trip assertions (which were written against the old ChapterText
// string API) keep working after the migration to ChapterBlocks.
func joinTextBlocks(blocks []Block) string {
	var sb strings.Builder
	for _, blk := range blocks {
		if blk.Type != "text" {
			continue
		}
		if sb.Len() > 0 {
			sb.WriteString("\n\n")
		}
		sb.WriteString(blk.Value)
	}
	return sb.String()
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
	txt, err := b.ChapterBlocks(0)
	require.NoError(t, err)
	joined := joinTextBlocks(txt)
	assert.True(t, utf8.ValidString(joined))
	assert.Contains(t, joined, "开始")
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

func TestTxtChapterBlocksSplit(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "r.txt")
	writeBytes(t, p, []byte("第一章 A\n这是第一章的正文内容\n第二章 B\n这是第二章的正文内容"))
	b, err := Parse(p)
	require.NoError(t, err)
	require.Len(t, b.Chapters, 2)
	c0, err := b.ChapterBlocks(0)
	require.NoError(t, err)
	joined0 := joinTextBlocks(c0)
	assert.Contains(t, joined0, "这是第一章的正文内容")
	assert.NotContains(t, joined0, "第二章")
	c1, err := b.ChapterBlocks(1)
	require.NoError(t, err)
	joined1 := joinTextBlocks(c1)
	assert.Contains(t, joined1, "这是第二章的正文内容")
}

func TestTxtChapterOffsetsRoundTripCRLF(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "r_crlf.txt")
	writeBytes(t, p, []byte("第一章 A\r\n这是第一章的正文内容\r\n第二章 B\r\n这是第二章的正文内容"))
	b, err := Parse(p)
	require.NoError(t, err)
	require.Len(t, b.Chapters, 2)
	c0, err := b.ChapterBlocks(0)
	require.NoError(t, err)
	joined0 := joinTextBlocks(c0)
	assert.Contains(t, joined0, "这是第一章的正文内容")
	assert.NotContains(t, joined0, "第二章")
	c1, err := b.ChapterBlocks(1)
	require.NoError(t, err)
	joined1 := joinTextBlocks(c1)
	assert.Contains(t, joined1, "这是第二章的正文内容")
}

func TestTxtChapterOffsetsRoundTripCRLF_Drift(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "drift.txt")
	content := "第一章 A\r\nline1\r\nline2\r\nline3\r\nline4\r\nline5\r\nline6\r\nline7\r\nline8\r\nline9\r\nline10\r\n第二章 B\r\n这是第二章的正文内容"
	writeBytes(t, p, []byte(content))
	b, err := Parse(p)
	require.NoError(t, err)
	require.Len(t, b.Chapters, 2)
	c1, err := b.ChapterBlocks(1)
	require.NoError(t, err)
	joined1 := joinTextBlocks(c1)

	// Chapter 1 should start exactly with the chapter title "第二章 B", not with "lineX" from Chapter 0.
	assert.True(t, strings.HasPrefix(strings.TrimSpace(joined1), "第二章 B"), "c1 should start with '第二章 B', but got: %q", joined1)
}

func TestTxtChapterRegexComprehensive(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "comprehensive.txt")
	content := "楔子～开启故事\n第一章　龙回故乡\n第２章：走马上任\n第229章-尾声\n第０３章、第一次性交示范课\n第4章我成了神手\n第 5 章 我成了神手\n第6章\n后记"
	writeBytes(t, p, []byte(content))
	b, err := Parse(p)
	require.NoError(t, err)
	
	// Should match: 楔子, 第一章, 第２章, 第229章, 第０３章, 第4章, 第 5 章, 第6章, 后记
	require.Len(t, b.Chapters, 9)
	assert.Equal(t, "楔子～开启故事", b.Chapters[0].Title)
	assert.Equal(t, "第一章　龙回故乡", b.Chapters[1].Title)
	assert.Equal(t, "第２章：走马上任", b.Chapters[2].Title)
	assert.Equal(t, "第229章-尾声", b.Chapters[3].Title)
	assert.Equal(t, "第０３章、第一次性交示范课", b.Chapters[4].Title)
	assert.Equal(t, "第4章我成了神手", b.Chapters[5].Title)
	assert.Equal(t, "第 5 章 我成了神手", b.Chapters[6].Title)
	assert.Equal(t, "第6章", b.Chapters[7].Title)
	assert.Equal(t, "后记", b.Chapters[8].Title)
}

func TestTxtEndMarkerAndDuplicateFilter(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "end_markers.txt")
	content := "第一章 开始\n正文1\n第一章 完\n第二章 生死之交\n作者寄语\n第二章 生死之交 2004/09/29\n正文2\n第二章完评分完成：已经给 接触零距离 加上30银元！"
	writeBytes(t, p, []byte(content))
	b, err := Parse(p)
	require.NoError(t, err)
	require.GreaterOrEqual(t, len(b.Chapters), 2)
	assert.Equal(t, "第一章 开始", b.Chapters[0].Title)
}

func TestParseUserNovel(t *testing.T) {
	p := `H:\IDM_Download\Novel\金鳞岂是池中物_全229章完.txt`
	if _, err := os.Stat(p); os.IsNotExist(err) {
		t.Skip("user file not found")
	}
	b, err := Parse(p)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(b.Chapters), 230)
	assert.Equal(t, "序言", b.Chapters[0].Title)
	assert.Equal(t, "第一章　龙回故乡", b.Chapters[1].Title)
}

func TestParseUserNovel2(t *testing.T) {
	p := `H:\IDM_Download\Novel\母上攻略_1-132.txt`
	if _, err := os.Stat(p); os.IsNotExist(err) {
		t.Skip("user file not found")
	}
	b, err := Parse(p)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(b.Chapters), 132)
	assert.Equal(t, "━━━ 第一章 ━━━", b.Chapters[1].Title)
	assert.Equal(t, "━━━ 第一百三十三章 ━━━", b.Chapters[len(b.Chapters)-1].Title)
}

func TestParseUserNovel3(t *testing.T) {
	p := `H:\IDM_Download\Novel\我的美艳校长妈妈_1-128完结.txt`
	if _, err := os.Stat(p); os.IsNotExist(err) {
		t.Skip("user file not found")
	}
	b, err := Parse(p)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(b.Chapters), 128)
}

func TestParseUserNovel4(t *testing.T) {
	p := `H:\IDM_Download\Novel\肏妈男孩唐飞的故事_1-73+番外.txt`
	if _, err := os.Stat(p); os.IsNotExist(err) {
		t.Skip("user file not found")
	}
	b, err := Parse(p)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(b.Chapters), 74)
	assert.Equal(t, "第一章", b.Chapters[1].Title)
}

func TestParseUserNovel5(t *testing.T) {
	p := `H:\IDM_Download\Novel\高考陪读那三年_全65章完结.txt`
	if _, err := os.Stat(p); os.IsNotExist(err) {
		t.Skip("user file not found")
	}
	b, err := Parse(p)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(b.Chapters), 66)
}

func TestParseUserNovel6(t *testing.T) {
	p := `H:\IDM_Download\Novel\良家人妻系列第一部_1-22完结.txt`
	if _, err := os.Stat(p); os.IsNotExist(err) {
		t.Skip("user file not found")
	}
	b, err := Parse(p)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(b.Chapters), 23)
}

func TestParseUserNovel7(t *testing.T) {
	p := `H:\IDM_Download\Novel\妈妈的大意_1-37.txt`
	if _, err := os.Stat(p); os.IsNotExist(err) {
		t.Skip("user file not found")
	}
	b, err := Parse(p)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(b.Chapters), 37)
}

func TestParseUserNovel8(t *testing.T) {
	p := `H:\IDM_Download\Novel\妈妈林菲菲的一滴泪_1-34完结.txt`
	if _, err := os.Stat(p); os.IsNotExist(err) {
		t.Skip("user file not found")
	}
	b, err := Parse(p)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(b.Chapters), 35)
}

func TestParseUserNovel9(t *testing.T) {
	p := `H:\IDM_Download\Novel\妈妈的欲臀_1-46.txt`
	if _, err := os.Stat(p); os.IsNotExist(err) {
		t.Skip("user file not found")
	}
	b, err := Parse(p)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(b.Chapters), 46)
}

func TestParseUserNovel10(t *testing.T) {
	p := `H:\IDM_Download\Novel\妈妈互助团_1-37.txt`
	if _, err := os.Stat(p); os.IsNotExist(err) {
		t.Skip("user file not found")
	}
	b, err := Parse(p)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(b.Chapters), 37)
}

func TestParseUserNovel11(t *testing.T) {
	p := `H:\IDM_Download\Novel\末日中的母子_1-46.txt`
	if _, err := os.Stat(p); os.IsNotExist(err) {
		t.Skip("user file not found")
	}
	b, err := Parse(p)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(b.Chapters), 46)
}

func TestParseUserNovel12(t *testing.T) {
	p := `H:\IDM_Download\Novel\母亲的针织衫_1-28完加番外.txt`
	if _, err := os.Stat(p); os.IsNotExist(err) {
		t.Skip("user file not found")
	}
	b, err := Parse(p)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(b.Chapters), 22)
}

func TestParseUserNovel13(t *testing.T) {
	p := `H:\IDM_Download\Novel\温柔妈妈坚持用骚穴，唤醒了成为植物人的我_01-20完结.txt`
	if _, err := os.Stat(p); os.IsNotExist(err) {
		t.Skip("user file not found")
	}
	b, err := Parse(p)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(b.Chapters), 20)
}

func TestParseUserNovel14(t *testing.T) {
	p := `H:\IDM_Download\Novel\我的教授母亲_1-86.txt`
	if _, err := os.Stat(p); os.IsNotExist(err) {
		t.Skip("user file not found")
	}
	b, err := Parse(p)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(b.Chapters), 86)
}

func TestParseUserNovel15(t *testing.T) {
	p := `H:\IDM_Download\Novel\我的美母教师_1-43.txt`
	if _, err := os.Stat(p); os.IsNotExist(err) {
		t.Skip("user file not found")
	}
	b, err := Parse(p)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(b.Chapters), 43)
}

func TestParseUserNovel16(t *testing.T) {
	p := `H:\IDM_Download\Novel\我脑中有好感度系统_1-36未完.txt`
	if _, err := os.Stat(p); os.IsNotExist(err) {
		t.Skip("user file not found")
	}
	b, err := Parse(p)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(b.Chapters), 36)
}

func TestParseUserNovel17(t *testing.T) {
	p := `H:\IDM_Download\Novel\艳母的荒唐赌约_1-120完.txt`
	if _, err := os.Stat(p); os.IsNotExist(err) {
		t.Skip("user file not found")
	}
	b, err := Parse(p)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(b.Chapters), 120)
}

func TestParseUserNovel18(t *testing.T) {
	p := `H:\IDM_Download\Novel\韵母攻略_1-101章连载中.txt`
	if _, err := os.Stat(p); os.IsNotExist(err) {
		t.Skip("user file not found")
	}
	b, err := Parse(p)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(b.Chapters), 100)
}

func TestParseUserNovel19(t *testing.T) {
	p := `H:\IDM_Download\Novel\在寡妇村当老师_1.1-13.8.txt`
	if _, err := os.Stat(p); os.IsNotExist(err) {
		t.Skip("user file not found")
	}
	b, err := Parse(p)
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(b.Chapters), 100)
}

func TestParseUserNovel20(t *testing.T) {
	p := `H:\IDM_Download\Novel\在男科工作的美母张文涛同人篇_1-63完.txt`
	if _, err := os.Stat(p); os.IsNotExist(err) {
		t.Skip("user file not found")
	}
	b, err := Parse(p)
	require.NoError(t, err)
	assert.Equal(t, 71, len(b.Chapters), "Should parse 71 chapters (1 preamble + 70 main/sub chapters)")
	assert.Equal(t, "第一章", b.Chapters[1].Title)
	assert.Equal(t, "第六十三章", b.Chapters[len(b.Chapters)-1].Title)
}

func TestParseUserNovel21(t *testing.T) {
	p := `H:\IDM_Download\Novel\妈妈是高级妓女_全.txt`
	if _, err := os.Stat(p); os.IsNotExist(err) {
		t.Skip("user file not found")
	}
	b, err := Parse(p)
	require.NoError(t, err)
	assert.Equal(t, 7, len(b.Chapters), "Should parse 7 chapters (1 preamble + 6 main chapters)")
	assert.Equal(t, "妈妈是高级妓女 一、这就是工作", b.Chapters[1].Title)
	assert.Equal(t, "妈妈是高级妓女 六、妈妈的新爱", b.Chapters[len(b.Chapters)-1].Title)
}





func TestTxtExpandedPatterns(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "expanded.txt")
	content := "(1) 第一节内容\n这是(1)正文\n（二） 第二节内容\n这是（二）正文\n3 第三节内容\n这是3正文\n（ 4 ） 第四节内容\n这是4正文"
	writeBytes(t, p, []byte(content))
	b, err := Parse(p)
	require.NoError(t, err)
	require.Len(t, b.Chapters, 4, "should detect expanded chapter patterns")
	assert.Equal(t, "(1) 第一节内容", b.Chapters[0].Title)
	assert.Equal(t, "（二） 第二节内容", b.Chapters[1].Title)
	assert.Equal(t, "3 第三节内容", b.Chapters[2].Title)
	assert.Equal(t, "（ 4 ） 第四节内容", b.Chapters[3].Title)
}

func TestTxtInlineChapterSuffix(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "inline_chap.txt")
	// Simulates the pattern seen in 女神美母收藏系统: chapter title embedded at end of content line
	content := "第一章\n这是第一章的内容，讲了很多故事。    第二章\n这是第二章的内容，继续讲故事啊。    第三章\n这是第三章的内容。"
	writeBytes(t, p, []byte(content))
	b, err := Parse(p)
	require.NoError(t, err)
	require.Len(t, b.Chapters, 3, "should detect inline chapter suffixes")
	assert.Equal(t, "第一章", b.Chapters[0].Title)
	assert.Equal(t, "第二章", b.Chapters[1].Title)
	assert.Equal(t, "第三章", b.Chapters[2].Title)
	// Verify content split: chapter 0 should contain only its own content
	c0, err := b.ChapterBlocks(0)
	require.NoError(t, err)
	joined0 := joinTextBlocks(c0)
	assert.Contains(t, joined0, "第一章")
	assert.NotContains(t, joined0, "第三章")
}

func TestParseUserNovel22(t *testing.T) {
	p := `H:\IDM_Download\Novel\女神美母收藏系统_1-13.txt`
	if _, err := os.Stat(p); os.IsNotExist(err) {
		t.Skip("user file not found")
	}
	b, err := Parse(p)
	require.NoError(t, err)
	// 13 chapters + 1 preamble ("序言")
	assert.GreaterOrEqual(t, len(b.Chapters), 13, "should detect all 13 chapters")
	assert.Equal(t, "序言", b.Chapters[0].Title)
	assert.Equal(t, "第一章", b.Chapters[1].Title)
}

func TestTxtVolumeHierarchy(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "vol.txt")
	content := "序章 准备\n准备正文\n第一卷 创世纪\n第1章 诞生\n诞生正文\n第2章 崛起\n崛起正文\n第二卷 英雄传\n第3章 征程\n征程正文"
	writeBytes(t, p, []byte(content))

	b, err := Parse(p)
	require.NoError(t, err)
	require.Len(t, b.Chapters, 4)

	assert.Equal(t, "序章 准备", b.Chapters[0].Title)
	assert.Equal(t, "", b.Chapters[0].Volume)
	assert.Equal(t, -1, b.Chapters[0].VolIndex)

	assert.Equal(t, "第1章 诞生", b.Chapters[1].Title)
	assert.Equal(t, "第一卷 创世纪", b.Chapters[1].Volume)
	assert.Equal(t, 0, b.Chapters[1].VolIndex)

	assert.Equal(t, "第2章 崛起", b.Chapters[2].Title)
	assert.Equal(t, "第一卷 创世纪", b.Chapters[2].Volume)
	assert.Equal(t, 0, b.Chapters[2].VolIndex)

	assert.Equal(t, "第3章 征程", b.Chapters[3].Title)
	assert.Equal(t, "第二卷 英雄传", b.Chapters[3].Volume)
	assert.Equal(t, 1, b.Chapters[3].VolIndex)
}


func BenchmarkParseTxt(b *testing.B) {
	content := []byte("第一卷 创世\n第1章 诞生\n测试内容\n第2章 崛起\n测试内容2\n")
	tmpFile := filepath.Join(b.TempDir(), "bench.txt")
	if err := os.WriteFile(tmpFile, content, 0644); err != nil {
		b.Fatal(err)
	}
	info, _ := os.Stat(tmpFile)
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_, _ = parseTxt(tmpFile, info)
	}
}

























