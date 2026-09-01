package com.juziss.localmediahub.data

/**
 * TXT 分章解析 —— server/internal/service/bookparser（rules.go + txt.go 的
 * splitChapters）的 Kotlin 1:1 移植，让离线下载的本地书籍与 server 在线解析
 * 产出完全一致的章节划分。
 *
 * 约束：修改任一端规则/流水线时必须同步另一端，同步的回归用例见
 * TxtChapterParserTest 与 server 的 bookparser/txt_test.go。
 *
 * 偏移量约定：与 Kotlin String.substring 一致使用 UTF-16 code unit；
 * server 侧按 rune 计数（BMP 内等价）。入口处统一 "\r\n" -> "\n"，
 * 保证 charStart/charEnd 与调用方的切片文本一致。
 */
internal object TxtChapterParser {

    // 对应 rules.go volumeRules（卷标题）
    private val volumeRules = listOf(
        Regex("""^[^\p{L}\p{N}]*第\s*[一二三四五六七八九十百千零0-9０-９]+\s*卷\s*.*"""),
        Regex("""^(?:Volume|Vol\.)\s*\d+.*"""),
        Regex("""^[^\p{L}\p{N}]*卷\s*[一二三四五六七八九十0-9]+\s*.*"""),
        Regex("""^[^\p{L}\p{N}]*(?:上|中|下|前|后)[卷篇部](?:[\s　：:～~、，,;；\-_—]|$).*"""),
        Regex("""^[^\p{L}\p{N}]*(?:上|中|下|前篇|后篇)\s*[^\p{L}\p{N}]*$"""),
        // ━━━ 第1-5章 ━━━ 这类区间标记是分卷分组，不是单章
        Regex("""^(?:={3,}|-{3,}|\*{3,}|━{3,})\s*第?\s*[一二三四五六七八九十0-9０-９]+\s*[-~～至到—–—]\s*[一二三四五六七八九十0-9０-９]+\s*[章节回]?\s*(?:={3,}|-{3,}|\*{3,}|━{3,})?"""),
    )

    // 对应 rules.go chapterRules（章节标题）
    private val chapterRules = listOf(
        // 带「第」前缀保持宽松：紧凑标题（如 "第4章我成了神手"）常见
        Regex("""^[^\p{L}\p{N}]*第\s*[一二三四五六七八九十百千零0-9０-９]+(?:\s*[-~～至到—–—]\s*[一二三四五六七八九十百千零0-9０-９]+)?\s*完?\s*[章节回卷集部篇]"""),
        // 无「第」前缀时，数字+量词后必须跟分隔符或行尾，否则 "一回到家…"
        // "一部分记忆…" 这类正文行会被误判成章节标题
        Regex("""^[^\p{L}\p{N}]*[一二三四五六七八九十百千零0-9０-９]+(?:\s*[-~～至到—–—]\s*[一二三四五六七八九十百千零0-9０-９]+)?\s*完?\s*[章节回卷集部篇](?:$|[\s　：:～~、，,;；_—【\[（()（）\-])"""),
        // 括号编号前允许装饰前缀（如 ＊＊＊（３））
        Regex("""^[^\p{L}\p{N}]*[【\[（(]\s*第?\s*[一二三四五六七八九十百千零0-9０-９\s]+\s*[章节回]?\s*[】\]）)]\s*.*"""),
        Regex("""^(?:={3,}|-{3,}|\*{3,}|━{3,})\s*第?\s*[一二三四五六七八九十0-9]+.*"""),
        Regex("""^\d{1,4}\.\s+.*"""),
        Regex("""^\d{1,4}\s+[^\s\d].*"""),
        Regex("""^[一二三四五六七八九十]+[、\.]\s*.*"""),
        // 中文数字 + 空格 + 标题（如 一 邂逅）
        Regex("""^[一二三四五六七八九十]+\s+\S.*"""),
        Regex("""^(?:Chapter|Section|Volume|Book)\s+\d+"""),
        Regex("""^楔子($|[\s　：:～~、，,;；])"""),
        Regex("""^序[章言]($|[\s　：:～~、，,;；])"""),
        Regex("""^尾声($|[\s　：:～~、，,;；])"""),
        Regex("""^前言($|[\s　：:～~、，,;；])"""),
        Regex("""^后记($|[\s　：:～~、，,;；])"""),
        Regex("""^终章($|[\s　：:～~、，,;；])"""),
        Regex("""^[^\p{L}\p{N}]*番外(?:篇|章|[\s　：:～~、，,;；\-_—\d一二三四五六七八九十0-9０-９]|$)"""),
        // 【上篇：...】, 【中篇：...】, 【下篇：...】, 【序言：...】
        Regex("""^【(?:上篇|中篇|下篇|前篇|后篇|序言|番外|尾声)[：:][^】]+】"""),
        Regex("""^[^\s\d一二三四五六七八九十]+\s+[一二三四五六七八九十0-9０-９]{1,4}[、\.].*"""),
    )

    // 对应 txt.go 的章节号提取与防误判正则
    private val chapChapRegex = Regex("""(?:第\s*)?([一二三四五六七八九十百千零0-9０-９]+)(?:\s*[-~～至到—–—]\s*[一二三四五六七八九十百千零0-9０-９]+)?\s*完?\s*章""")
    private val chapNumRegex = Regex("""(?:第\s*)?([一二三四五六七八九十百千零0-9０-９]+)(?:\s*[-~～至到—–—]\s*[一二三四五六七八九十百千零0-9０-９]+)?\s*[章节回卷集部篇]""")
    private val chapParenRegex = Regex("""^[^\p{L}\p{N}]*[【\[（(]\s*第?\s*([一二三四五六七八九十百千零0-9０-９]+)\s*[章节回]?\s*[】\]）)]""")
    private val chapDotRegex = Regex("""([一二三四五六七八九十0-9０-９]{1,4})[、\.]""")
    private val chapBareRegex = Regex("""^(\d{1,4})\s+""")
    private val chapCnBareRegex = Regex("""^([一二三四五六七八九十]+)\s+""")
    private val chapEngRegex = Regex("""(?i)^(?:Chapter|Section|Volume|Book)\s+(\d+)""")
    private val endMarkerRegex = Regex("""[章节回卷集部篇]\s*完($|[\s　：:～~、，,;；]|评分|【|（|\()""")
    private val authorNoteRegex = Regex("""^(前言|后记|序言|序章|编者按|作者的话)[：:]""")
    private val pagePrefixRegex = Regex("""^[^\p{L}\p{N}]*第\s*[0-9一二三四五六七八九十]+\s*页[\s　]+""")
    private val inlineChapterSuffixRegex = Regex("""^(.+?)((?:第\s*)?[一二三四五六七八九十百千零0-9０-９]{1,4}\s*[章节回])\s*$""")

    // 同一行同时携带卷号与章号（如 "第一卷 第1章"、"【第二卷 第1章】"）。
    // 若无此判定，下方卷识别分支会整行吞掉这类标题，导致全书章节全部丢失。
    private val compoundVolChapRegex = Regex("""^[【\[\s　]*(第\s*[一二三四五六七八九十百千零0-9０-９]+\s*卷)[\s　]*[】\]]?[\s　]*[:：]?[\s　]*(?:第\s*)?[一二三四五六七八九十百千零0-9０-９]+\s*完?\s*[章节回]""")

    // 卷标题的 "第X卷" 核心 token，用于判断复合标题是否进入了新卷
    private val volCoreRegex = Regex("""第\s*[一二三四五六七八九十百千零0-9０-９]+\s*卷""")

    /** 命中卷标题时返回该行（去除首尾空白后），否则 null。对应 rules.go IsVolumeHeader。 */
    fun isVolumeHeader(trim: String): String? {
        if (trim.codePointCount(0, trim.length) > 100) return null
        for (re in volumeRules) {
            if (re.containsMatchIn(trim)) return trim
        }
        return null
    }

    /** 对应 rules.go IsChapterHeader。 */
    fun isChapterHeader(trim: String): Boolean =
        chapterRules.any { it.containsMatchIn(trim) }

    /**
     * 对应 txt.go isEndMarker：正文行防误判过滤。注意顺序语义——
     * 章节标题优先（先判 IsChapterHeader），其次超长行 / "X章完" 等结尾标记。
     */
    fun isEndMarker(trim: String): Boolean {
        if (isChapterHeader(trim)) return false
        if (trim.codePointCount(0, trim.length) > 80) return true
        if (authorNoteRegex.containsMatchIn(trim) && trim.codePointCount(0, trim.length) > 20) return true
        if (endMarkerRegex.containsMatchIn(trim)) return true
        if (trim.contains("本章完") || trim.contains("全书完") || trim.contains("全剧终") || trim.contains("评分完成")) return true
        if (trim.contains("加") && (trim.contains("银元") || trim.contains("金币"))) return true
        if (trim.endsWith("。") || trim.endsWith("！") || trim.endsWith("？") || trim.endsWith("!") || trim.endsWith("?")) return true
        if (trim.contains("节课") || trim.contains("；")) return true
        return false
    }

    /** 对应 txt.go chineseToNum：阿拉伯数字直取，中文数字按位权折算。 */
    private fun chineseToNum(s: String): Int {
        val sb = StringBuilder()
        for (c in s) {
            sb.append(if (c in '０'..'９') ('0' + (c - '０')) else c)
        }
        val norm = sb.toString()
        // 与 Go fmt.Sscanf("%d") 对齐：只解析前导十进制数字
        val leading = leadingIntRegex.find(norm)?.value
        if (leading != null) {
            val v = leading.toInt()
            if (v > 0) return v
        }

        val cnMap = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        )
        val unitMap = mapOf('十' to 10, '百' to 100, '千' to 1000, '万' to 10000)
        var total = 0
        var num = 0
        for (c in norm) {
            when {
                cnMap.containsKey(c) -> num = cnMap.getValue(c)
                unitMap.containsKey(c) -> {
                    val u = unitMap.getValue(c)
                    if (num == 0 && u == 10) num = 1
                    total += num * u
                    num = 0
                }
                c in '0'..'9' -> num = c - '0'
            }
        }
        return total + num
    }

    private val leadingIntRegex = Regex("""^\d+""")

    /** 对应 txt.go extractChapKey：提取归一化章节号用于去重比较，失败返回 ""。 */
    fun extractChapKey(trimRaw: String): String {
        val trim = pagePrefixRegex.replace(trimRaw, "")
        val m = chapChapRegex.find(trim)
            ?: chapNumRegex.find(trim)
            ?: chapParenRegex.find(trim)
            ?: chapDotRegex.find(trim)
            ?: chapBareRegex.find(trim)
            ?: chapCnBareRegex.find(trim)
        if (m != null) {
            val s = goTrim(m.groupValues[1])
            val n = chineseToNum(s)
            return if (n > 0) n.toString() else s
        }
        return chapEngRegex.find(trim)?.groupValues?.get(1) ?: ""
    }

    /**
     * 对应 txt.go splitChapters 的完整流水线：卷识别 → 行尾内嵌章节名 →
     * 防误判过滤 → 章节标题（含同号去重替换）→ 卷提升 → 序言前置章。
     */
    fun splitChapters(rawText: String, fallbackTitle: String): List<BookChapter> {
        val text = rawText.replace("\r\n", "\n")

        data class Mark(
            val title: String,
            val start: Int,
            val chapKey: String,
            val volume: String,
            val volIdx: Int,
        )

        val marks = mutableListOf<Mark>()
        val volMarks = mutableListOf<Pair<String, Int>>()
        var currentVolume = ""
        var currentVolIdx = -1
        var off = 0

        for (line in text.split('\n')) {
            val trim = goTrim(line).replace(pagePrefixRegex, "")
            val advance = { off += line.length + 1 }

            // "第一卷 第1章" / "【第二卷 第1章】"：所属卷的章节标题而非卷标记。
            // 更新卷跟踪后落入下方常规章节标题处理（advance 在那里执行），
            // 保证同号去重替换逻辑只有一份。
            val compound = compoundVolChapRegex.find(trim)
            if (compound != null && trim.codePointCount(0, trim.length) <= 100) {
                val vol = goTrim(compound.groupValues[1])
                val curCore = cleanTitleForComparison(volCoreRegex.find(currentVolume)?.value ?: "")
                if (curCore != cleanTitleForComparison(vol)) {
                    currentVolIdx++
                }
                currentVolume = vol
            } else {
                val volTitle = isVolumeHeader(trim)
                if (volTitle != null) {
                    volMarks.add(volTitle to off)
                    currentVolume = volTitle
                    currentVolIdx++
                    advance()
                    continue
                }
            }

            // 行尾内嵌章节名：正文句末跟着 "第X章"（如 "……正式开始。    第三章"）。
            // 必须在 isEndMarker 之前判定，否则长行/带句号的行会先被正文过滤掉。
            if (!isChapterHeader(trim)) {
                val sub = inlineChapterSuffixRegex.find(trim)
                if (sub != null) {
                    val prefix = goTrim(sub.groupValues[1])
                    if (prefix.codePointCount(0, prefix.length) >= 3) {
                        val chapTitle = goTrim(sub.groupValues[2])
                        // 章节标记落在章节 token 起始处，而非行首
                        val chapOff = off + sub.groupValues[1].length
                        val key = extractChapKey(chapTitle)
                        val m = Mark(chapTitle, chapOff, key, currentVolume, currentVolIdx)
                        var replaced = false
                        if (marks.isNotEmpty() && key.isNotEmpty()) {
                            val prev = marks.last()
                            if (key == prev.chapKey && chapOff - prev.start < 3000) {
                                val c1 = cleanTitleForComparison(prev.title)
                                val c2 = cleanTitleForComparison(chapTitle)
                                if (c1.startsWith(c2) || c2.startsWith(c1) || chapTitle.length >= prev.title.length) {
                                    marks[marks.lastIndex] = m
                                    replaced = true
                                }
                            }
                        }
                        if (!replaced) marks.add(m)
                        advance()
                        continue
                    }
                }
            }

            if (isEndMarker(trim)) {
                advance()
                continue
            }

            if (isChapterHeader(trim)) {
                val key = extractChapKey(trim)
                var t = trim
                val wwwIdx = t.indexOf("www.")
                if (wwwIdx > 0) {
                    t = goTrim(t.substring(0, wwwIdx))
                }
                if (t.codePointCount(0, t.length) > 50) {
                    t = t.substring(0, t.offsetByCodePoints(0, 50))
                }
                val m = Mark(t, off, key, currentVolume, currentVolIdx)
                var replaced = false
                if (marks.isNotEmpty() && key.isNotEmpty()) {
                    val prev = marks.last()
                    if (key == prev.chapKey && off - prev.start < 3000) {
                        val c1 = cleanTitleForComparison(prev.title)
                        val c2 = cleanTitleForComparison(trim)
                        val prevIsRange = prev.title.any { it in RANGE_MARKERS }
                        val currIsRange = trim.any { it in RANGE_MARKERS }
                        if ((prevIsRange && !currIsRange) || c1.startsWith(c2) || c2.startsWith(c1) || trim.length >= prev.title.length) {
                            marks[marks.lastIndex] = m
                            replaced = true
                        }
                    }
                }
                if (!replaced) marks.add(m)
            }
            advance()
        }

        // 无章节标记但有卷标记时，把卷提升为章节
        if (marks.isEmpty() && volMarks.isNotEmpty()) {
            for ((title, start) in volMarks) {
                marks.add(Mark(title, start, "", "", -1))
            }
        }

        if (marks.isEmpty()) {
            return listOf(BookChapter(index = 0, title = fallbackTitle, charStart = 0, charEnd = text.length))
        }

        val chapters = mutableListOf<BookChapter>()
        if (marks[0].start > 0) {
            val preamble = text.substring(0, marks[0].start).trimEnd('\n')
            if (goTrim(preamble).isNotEmpty()) {
                chapters.add(
                    BookChapter(
                        index = chapters.size,
                        title = "序言",
                        charStart = 0,
                        charEnd = marks[0].start,
                        volume = "",
                        volIndex = -1,
                    )
                )
            }
        }
        for ((i, m) in marks.withIndex()) {
            val end = if (i + 1 < marks.size) marks[i + 1].start else text.length
            chapters.add(
                BookChapter(
                    index = chapters.size,
                    title = m.title,
                    charStart = m.start,
                    charEnd = end,
                    volume = m.volume,
                    volIndex = m.volIdx,
                )
            )
        }
        return chapters
    }

    private val RANGE_MARKERS = "-~～至到—–—"

    private fun cleanTitleForComparison(title: String): String =
        title.replace(" ", "").replace("　", "").replace("\t", "")

    /** 复刻 Go strings.TrimSpace 的空白集合（含 U+3000 / NBSP 等）。 */
    private fun goTrim(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && isGoSpace(s[start])) start++
        while (end > start && isGoSpace(s[end - 1])) end--
        return if (start == 0 && end == s.length) s else s.substring(start, end)
    }

    private fun isGoSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u0085', '\u00A0',
        '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000' -> true
        else -> c in '\u2000'..'\u200A'
    }
}
