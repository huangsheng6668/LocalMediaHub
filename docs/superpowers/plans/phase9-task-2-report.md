# Task 2 Report: ValidateDeletion 根比较大小写不敏感（M-4）

**Status:** DONE
**Commit:** `39bfe82` — `fix(security): case-insensitive delete-root guard on Windows (Phase 9)`
**Branch:** master（基于 `4e68a5a`）
**Files changed:** `server/internal/service/path.go` / `server/internal/service/path_test.go`（共 +17/-1）

## 做了什么

### 漏洞分析（实现前确认）

`ValidateDeletion` 的调用链：`ResolveWithinRoots` → `resolveWithin` → `relPathWithin`。其中 `relPathWithin`
在 Windows 上**已经是大小写不敏感匹配**（`strings.EqualFold` + lowercase 前缀比较），所以用户提交
`d:\MEDIA`（根配置为 `D:\Media`）时会以 `rel == "."`（根自身）通过 within-roots 校验；但
`resolveWithin` 返回的是**保留用户提交大小写的词法清洗路径**（`NormalizePath` 只归一盘符大小写）。
随后 `ValidateDeletion` 的禁删守卫 `if resolved == absRoot` 是**大小写敏感**的 `==` 比较，
`D:\MEDIA != D:\Media` → 不命中 → 允许删除库根本身。这就是 M-4 的绕过路径。

### 实施步骤（TDD，按 brief 原文）

1. **Step 1 — 失败测试**：在 `TestValidateDeletionRejectsRootItself` 之后新增
   `TestValidateDeletionRejectsRootItselfCaseInsensitive`（brief Step 1 代码逐字使用）。测试用
   `os.TempDir()/LMH-CaseRoot` 构造真实目录，对 `strings.ToUpper(root)` 与 `strings.ToLower(root)`
   两个变体断言 `ValidateDeletion` 必须拒绝。
   - 前置验证：先确认 Go 进程视角的 `os.TempDir()` = `C:\Users\juziss\AppData\Local\Temp`，
     其路径段（users/appdata/local/temp）不命中 blockedSegments（"users" 被 blocklist 显式排除），
     保证测试拒绝的原因是禁删守卫而非 blocklist 误伤。
2. **Step 2 — 确认失败**：`go test ./internal/service/ -run TestValidateDeletionRejectsRootItselfCaseInsensitive -v`
   → FAIL，`ValidateDeletion("C:\\USERS\\...\\LMH-CASEROOT") should reject the root itself`。
   漏洞复现。（输出末尾的 `go: unlinkat ... service.test.exe` 是 Windows 下 go-build 缓存清理噪音，非测试失败。）
3. **Step 3 — 最小实现**：`path.go` 禁删守卫改为
   `if resolved == absRoot || strings.EqualFold(resolved, absRoot) {`（brief 原文），
   并在既有守卫注释块中追加 3 行说明 EqualFold 的安全动机（Windows FS 大小写不敏感 + 词法清洗保留用户大小写）。
4. **Step 4 — 确认通过**：`-run TestValidateDeletion -v` 3 个测试全 PASS（含新测试与既有
   `RejectsRootItself` / `AllowsChildFile`）。
5. **Step 5 — 提交**：`git add` 两个文件，commit message 用 brief 原文。

### Coordinator 消歧项的处理

- **"所有 `== absRoot` 形态的比较"**：grep 全 server 代码确认 `ValidateDeletion` 内只有一处根相等比较
  （循环内 `resolved == absRoot`），`internal/` 下无其它 `== absRoot` / `resolved ==` 形态的比较点，
  无需辅助函数统一。删除路径的 within-roots 匹配（`relPathWithin`）在 Windows 上本就 case-insensitive，
  修复后删除校验链路（within-roots → 禁删守卫）语义端到端一致。
- **测试路径形态与生产一致**：测试目录真实存在（`os.MkdirAll`）且在 roots 数组内（roots 语义 =
  "允许删除的根集合"，与生产 `deletePath`/`deletePaths` 调用一致）；`ValidateDeletion` 对根自身
  不做存在性 stat（resolveWithin 是纯词法校验），建目录是保持 fixture 真实性的卫生做法。

## 测试结果

| 命令 | 结果 |
|---|---|
| `go test ./internal/service/ -run TestValidateDeletionRejectsRootItselfCaseInsensitive -v`（修复前） | **FAIL**（漏洞复现，符合 Step 2 预期） |
| `go test ./internal/service/ -run TestValidateDeletion -v`（修复后） | **PASS** ×3 |
| `go test ./internal/service/` | **ok**（全包通过，14.8s） |
| `go test ./internal/service/... ./internal/server/...` | 仅 `internal/service/bookparser` `TestParseUserNovel` 失败 |

- `TestParseUserNovel` 基线例外已实证确认：`git stash` 暂存本任务改动后在干净 master 上同样失败
  （章节规则期望 `第一章　龙回故乡` 实得 `第一章，谢了。`），与本任务无关。
- `internal/server/...` 无失败。

## Self-review 发现

1. **`resolved == absRoot` 与 `EqualFold` 冗余**：`EqualFold(a,b)` 在 `a==b` 时恒为 true，前半冗余。
   保留 brief 原文写法（含短路 `||`），语义不变、可读性无碍，不做"优化"。
2. **非 Windows 平台影响**：Linux 上 `resolveWithin` 大小写敏感，大小写变体根本进不了比较（直接
   "path outside allowed directories"），新测试在 Linux 上以"拒绝"通过（不会假阳性）。唯一理论边缘：
   同一 roots 配置里存在仅大小写不同的两个根（Linux 上是两个不同目录）时，删除其中之一会被
   EqualFold 误拒——这是 fail-closed 方向的保守行为，安全上正确，接受。
3. **测试健壮性**：`os.MkdirAll` 返回值未检查（brief 原文保留）；若 Mkdir 失败，`ValidateDeletion`
   对不存在路径仍会走词法校验并命中禁删守卫（修复后），测试结论不受影响，可接受。
4. **改动范围**：仅 `path.go` + `path_test.go`，符合约束；工作区中 `docs/superpowers/reviews/`、
   `tools/reformat_novels.py` 为他人未跟踪文件，未触碰、未入提交。
5. **grep 交叉确认**：`grep -rn "== absRoot\|absRoot ==\|== resolved\|resolved ==" internal/ --include="*.go"`
   仅命中修复后的那一行，无遗漏比较点。
