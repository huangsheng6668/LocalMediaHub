package service

import (
	"fmt"
	"math"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// 子进程只允许这两个白名单二进制；自定义安装位置通过 activateToolDir
// 前插 PATH 解析，exec 调用点保持字面量程序名（CWE-78 收敛）。
const (
	ffmpegBin  = "ffmpeg"
	ffprobeBin = "ffprobe"
)

// activateToolDir 把配置指定且通过 sanitizeToolPath 白名单校验的 ffmpeg
// 所在目录前置到当前进程 PATH：后续 exec.Command 用字面量二进制名即可
// 解析到该目录下的工具（Windows 的 .exe 后缀由 LookPath 处理）。目录内
// 缺少 ffprobe 时 LookPath 会继续搜索原 PATH，与旧的 sibling 推导等价。
func activateToolDir(configured string) {
	p := sanitizeToolPath(configured, ffmpegBin)
	if p == ffmpegBin {
		return
	}
	dir := filepath.Dir(p)
	if cur := os.Getenv("PATH"); !strings.Contains(cur, dir) {
		_ = os.Setenv("PATH", dir+string(os.PathListSeparator)+cur)
	}
}

// sanitizeToolPath 是配置来源的 ffmpeg/ffprobe 路径进入 exec 前的净化边界
// （CWE-78）：仅接受指向同名可执行文件的绝对路径，其余形态（空、相对
// 路径、文件名不符、异常扩展名）一律回退到 PATH 上的默认工具名。
func sanitizeToolPath(configured, toolName string) string {
	configured = strings.TrimSpace(configured)
	if configured == "" {
		return toolName
	}
	if !filepath.IsAbs(configured) {
		return toolName
	}
	base := strings.ToLower(filepath.Base(configured))
	ext := strings.ToLower(filepath.Ext(base))
	stem := strings.TrimSuffix(base, ext)
	if stem != strings.ToLower(toolName) {
		return toolName
	}
	if ext != "" && ext != ".exe" {
		return toolName
	}
	return filepath.Clean(configured)
}

// sanitizeMediaArg 是媒体文件路径进入子进程 argv 前的净化边界
// （CWE-78/CWE-22）：要求绝对路径并收敛为 filepath.Clean 的规范形式。
// 调用方上游已通过路径校验三件套，这里是 exec 前的最后一道收敛。
func sanitizeMediaArg(p string) (string, error) {
	if p == "" || !filepath.IsAbs(p) {
		return "", fmt.Errorf("media path must be absolute: %q", p)
	}
	return filepath.Clean(p), nil
}

// sanitizeSeekArg 把 seek 参数收敛为标准十进制数字符串后再进入 exec
// argv（CWE-78），拒绝非数字、负值与 NaN。
func sanitizeSeekArg(seek string) (string, error) {
	v, err := strconv.ParseFloat(strings.TrimSpace(seek), 64)
	if err != nil || math.IsNaN(v) || v < 0 {
		return "", fmt.Errorf("invalid seek value: %q", seek)
	}
	return strconv.FormatFloat(v, 'f', 3, 64), nil
}
