import os, sys, re, shutil

sys.stdout.reconfigure(encoding='utf-8')

NOVEL_DIR = r'H:\IDM_Download\Novel'
BACKUP_DIR = r'H:\IDM_Download\Novel_Backup'

VOL_RE = re.compile(r'^(?:={3,}|-{3,}|\*{3,}|━{3,})\s*第?\s*[一二三四五六七八九十0-9０-９]+.*')
DIVIDER_RE = re.compile(r'^(?:={3,}|-{3,}|\*{3,}|━{3,})$')
CHAP_RE = re.compile(r'^(?:第\s*[0-9一二三四五六七八九十百千零]+(?:\s*[-~～至到—–—]\s*[0-9一二三四五六七八九十百千零]+)?\s*完?\s*[章节回卷集部篇]|[0-9一二三四五六七八九十]+[章回卷节]\s*.*|[0-9]{1,4}\s+\S.*|[【\[（(]\s*第?\s*[0-9一二三四五六七八九十\s]+\s*[章节回]\s*[】\]）)].*|^楔子($|[\s　：:])|^序[章言]($|[\s　：:])|^尾声($|[\s　：:])|^前言($|[\s　：:])|^后记($|[\s　：:])|^终章($|[\s　：:])|^番外)')

def is_chapter_or_header(line):
    trim = line.strip()
    if not trim:
        return False
    if len(trim) > 60:
        return False
    if DIVIDER_RE.match(trim) or VOL_RE.match(trim) or CHAP_RE.match(trim):
        return True
    if trim.startswith(('作者：', '字数：', '来源：', 'TXT包：', '【') ) and len(trim) < 40:
        return True
    return False

def get_text(fp):
    raw = open(fp, 'rb').read()
    if raw.startswith(b'\xef\xbb\xbf'):
        return raw[3:].decode('utf-8', errors='replace')
    try:
        return raw.decode('gb18030')
    except Exception:
        return raw.decode('utf-8', errors='ignore')

def split_line_by_tabs_spaces_and_u3000(line):
    trim = line.strip()
    if not trim:
        return []
        
    if is_chapter_or_header(trim):
        return [trim]

    # Handle inline chapter headers at end of line (e.g. "...笑纳了！’第十章" or "...策略。”    第四章")
    m = re.search(r'(.+?)[\s　\t]*(第\s*[0-9一二三四五六七八九十百千零]{1,4}\s*[章节回].*)$', line)
    if m and len(m.group(1).strip()) >= 3:
        p1 = m.group(1).strip()
        p2 = m.group(2).strip()
        return [p1, p2]
        
    parts = re.split(r'[\t]+|[\s\u3000　]{2,}', line)
    return [p.strip() for p in parts if p.strip()]

def reformat_text(raw_text):
    raw_lines = raw_text.splitlines()
    
    expanded_lines = []
    for line in raw_lines:
        sub_parts = split_line_by_tabs_spaces_and_u3000(line)
        for part in sub_parts:
            expanded_lines.append(part)
            
    paragraphs = []
    current_para = []
    
    for line in expanded_lines:
        trimmed = line.strip()
        if not trimmed:
            if current_para:
                paragraphs.append("".join(current_para))
                current_para = []
            continue
            
        is_hdr = is_chapter_or_header(trimmed)
        
        if is_hdr:
            if current_para:
                paragraphs.append("".join(current_para))
                current_para = []
            paragraphs.append(trimmed)
            continue
            
        if current_para:
            prev_str = current_para[-1]
            if prev_str and prev_str[-1] not in '。！？!?……”"\'':
                current_para.append(trimmed)
            else:
                paragraphs.append("".join(current_para))
                current_para = [trimmed]
        else:
            current_para = [trimmed]
            
    if current_para:
        paragraphs.append("".join(current_para))

    formatted_lines = []
    for para in paragraphs:
        p = para.strip()
        if not p:
            continue
        if is_chapter_or_header(p):
            formatted_lines.append("")
            formatted_lines.append(p)
            formatted_lines.append("")
        else:
            formatted_lines.append("　　" + p)
            
    result = []
    prev_empty = False
    for line in formatted_lines:
        if line == "":
            if not prev_empty:
                result.append(line)
                prev_empty = True
        else:
            result.append(line)
            prev_empty = False
            
    return "\n\n".join(result).strip() + "\n"

def main():
    if not os.path.exists(BACKUP_DIR):
        print(f"Error: Backup directory {BACKUP_DIR} does not exist.")
        sys.exit(1)
        
    files = [f for f in os.listdir(BACKUP_DIR) if f.endswith('.txt')]
    print(f"Found {len(files)} txt files in backup directory {BACKUP_DIR}")
    
    processed = 0
    for i, f in enumerate(files, 1):
        # 路径净化（CWE-22）：只允许纯文件名，杜绝 ../ 等目录逃逸
        safe_name = os.path.basename(f)
        if safe_name != f or safe_name in (".", ".."):
            raise ValueError(f"unexpected entry name: {f}")
        backup_fp = os.path.join(BACKUP_DIR, safe_name)
        target_fp = os.path.join(NOVEL_DIR, safe_name)
        try:
            raw_text = get_text(backup_fp)
            orig_lines_cnt = len(raw_text.splitlines())
            formatted_text = reformat_text(raw_text)
            new_lines_cnt = len(formatted_text.splitlines())
            
            with open(target_fp, 'w', encoding='utf-8') as out:
                out.write(formatted_text)
            processed += 1
            if i % 20 == 0 or i == len(files):
                print(f"[{i}/{len(files)}] Processed: {f} ({orig_lines_cnt} -> {new_lines_cnt} lines)")
        except Exception as e:
            print(f"Error processing {f}: {e}")
            
    print(f"\nAll done! Successfully reformatted {processed}/{len(files)} files.")

if __name__ == '__main__':
    main()
