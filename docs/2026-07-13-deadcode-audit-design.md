# Round 30 — 死代码 / 冗余代码审计与清理

- **创建日期**: 2026-07-13
- **分支**: master
- **作者**: huangsheng6668 + Claude
- **关联**: 接在 Round 29 八阶段安全审计之后

---

## 1. 目标

对仓库 `E:/github_project/LocalMediaHub` 做一次系统性的死代码与冗余代码审计：

- **A 阶段（审计）**：扫描全部源码层，输出结构化报告，列出疑似死代码 / 冗余项。
- **B 阶段（清理）**：按层分批清理确认后的死代码，每批独立提交并跑该层测试。

不在本轮范围：功能变更、重构、性能优化、新增测试基建。

---

## 2. 范围

### 纳入扫描的源码层

- Go server：`server/cmd/**`、`server/internal/**`
- Android Kotlin：`android/app/src/main/java/**`、`android/app/src/test/java/**`
- Web UI：`server/internal/web/**`（JS + HTML + CSS）
- Rust JNI：`android/app/src/main/rust/src/**`
- 构建脚本/工具：`tools/xsscheck/**`、`scripts/**`、`build.gradle.kts` / `settings.gradle.kts`

### 排除（仅在附录中提示，不主动清理）

- `build/ffmpeg-src/**`（vendored FFmpeg 源码）
- `android/app/src/main/rust/target/**`（cargo 构建产物）
- `android/app/build/**`、`android/build/**`、`server/LocalMediaHub.exe`
- 根目录 `app-debug-*.apk` 旧产物
- `.git/`、`.codegraph/`、`.superpowers/`、`.claude/`

### 审计维度

| 维度 | 覆盖 |
|---|---|
| 文件级死代码 | 是 |
| 函数/类型级死代码 | 是 |
| 语句级不可达分支 | **否**（误报高，收益低） |
| 冗余但非死（重复实现、过度防御） | 是（只进报告，不自动清理） |
| 依赖冗余 | 是 |

---

## 3. 三层执行流水线

### 第一层 · 工具广度扫描

| 栈 | 工具 | 命令 | 关键检查项 |
|---|---|---|---|
| Go | `staticcheck` | `cd server && staticcheck ./...` | `U1000`（unused）、`U1001`（unused receiver）、`SA1019`（deprecated） |
| Go | `go vet` | `cd server && go vet ./...` | 不可达代码、shadow、struct tag |
| Go 依赖 | `go mod tidy -diff` | `cd server && go mod tidy -diff` | 多余的 require |
| Kotlin | `gradlew lint` | `cd android && ./gradlew :app:lintDebug` | `UnusedResources`、`UnusedDeclaration` |
| JS | `knip` | `cd server/internal/web && knip --no-exit-code` | unused exports、unused files、duplicates |
| Rust | `cargo machete` | `cd android/app/src/main/rust && cargo machete` | unused deps（Rust 死代码由 compiler `dead_code` lint 兜底） |

工具未装时，**不自动安装**——缺口记到报告"未执行工具"附录。

### 第二层 · codegraph + grep 精度过滤

对第一层每一条"零调用"命中：

1. `codegraph_explore` 查符号的所有引用者。
2. Grep 关键字确认字符串引用（Echo handler 注册、JNI `RegisterNatives`、Hilt `@Provides`、Compose `@Composable`）。
3. 命中以下任意一条 → 移入"待人工确认"区，不进入清理批次：
   - Go 导出符号（首字母大写）跨包无 import。
   - Kotlin `public`/`internal` 且带 `@Inject` / `@HiltViewModel` / `@Composable` / `@Parcelize` 等运行时注解。
   - JS `export` 且出现在 `index.html` 的 `<script type="module">` import 列表。
   - Rust `pub fn` 且出现在 `jni_bridge` 注册表。

### 第三层 · 人工深度（冗余但非死）

针对"冗余但非死"类别，由人工读源码判断：

- **重复实现**：函数 A 和函数 B 逻辑等价（如两个 `formatTime`、两个 path normalize）。
- **过度防御**：信任边界内的 nil/err 检查。
- **单调用者 helper**：只被一个调用者使用一次、且调用点本身很简单的 helper（仅列出，不主动合并）。

这一层**只进报告、不进自动清理批次**。

---

## 4. 报告结构与判定矩阵

**报告文件**：`docs/2026-07-13-deadcode-audit-report.md`

**顶层分节**

1. 执行摘要（总数、按层/按风险分布、工具运行情况）
2. Go server 发现项
3. Android Kotlin 发现项
4. Web JS 发现项
5. Rust JNI 发现项
6. 构建脚本与工具发现项
7. 依赖冗余（`go.mod` / `build.gradle.kts` / cargo `Cargo.toml`）
8. 附录 A：非源码但占体积的文件清单（不清理）
9. 附录 B：未执行工具及原因

**每条发现项字段**

- `位置`：文件路径:行号 或符号名
- `类型`：文件级 / 符号级 / 冗余非死 / 依赖冗余
- `证据`：工具输出 + codegraph/grep 的二次确认结果
- `风险等级`：低 / 中 / 高
- `建议动作`：删除 / 合并 / 待人工确认

**判定矩阵**

| 证据强度 | 符号可见性 | 动作 |
|---|---|---|
| 工具 + codegraph 双确认零调用 | Go unexported / Kotlin private / JS 非 export | 直接删除（进清理批次） |
| 工具 + codegraph 双确认零调用 | Go exported / Kotlin public/internal / JS export | 进"待人工确认"区 |
| 仅工具报告，codegraph 找到引用 | 任意 | 误报，剔除 |
| 仅人工读出（冗余非死类） | 任意 | 进"冗余建议"区，不自动清理 |
| 工具未运行 | 任意 | 进"未覆盖"区 |

---

## 5. 清理批次与提交粒度

| 批次 | 范围 | 提交信息前缀 |
|---|---|---|
| 1 | Go server 非导出符号 + 死文件 | `chore(server): remove dead code (round 30 audit)` |
| 2 | Android Kotlin private 符号 + 死文件 | `chore(android): remove dead code (round 30 audit)` |
| 3 | Web JS 非 export 符号 + 死文件 | `chore(web): remove dead code (round 30 audit)` |
| 4 | Rust JNI + 构建脚本 | `chore(build): remove dead code (round 30 audit)` |
| 5 | 依赖冗余（go.mod / Cargo.toml / gradle） | `chore(deps): remove unused dependencies (round 30 audit)` |

每批次流程：列出待删项 → 用户确认 → 改代码 → 跑该层测试 → 提交。

---

## 6. 验证策略

| 批次 | 验证命令 | 通过标准 |
|---|---|---|
| 1 Go | `cd server && go build ./... && go vet ./... && go test ./...` | build/vet/test 全绿；staticcheck 重跑后该批次 U1000 命中归零 |
| 2 Android | `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` | assemble/test/lint 全绿（lint 允许已有的非相关 warning） |
| 3 Web | 启动 server 后浏览器手动跑 4 个主路径（dashboard / browse / tags / settings） | 控制台无 `Uncaught Error` / `404 module`；knip 重跑后命中归零 |
| 4 Rust/Build | `cd android/app/src/main/rust && cargo check`；对应 gradle 任务 | cargo check 绿；gradle 任务不报缺符号 |
| 5 依赖 | `cd server && go build ./...`；`cd android && ./gradlew :app:assembleDebug`；`cargo check` | 三栈构建全绿 |

**Web 验证弱项**：JS 没有单元测试，只能靠"浏览器跑一遍 + knip 跑一遍"。无法在不动测试基建的前提下消除。报告会注明。

---

## 7. 安全护栏与回滚

### 护栏

- 每批次删除前确认 `git status` 干净。
- 单个 commit 不跨层。
- 不动任何 `*_test.go` / `*Test.kt` 中的生产代码引用。
- 导出符号（Go 大写、Kotlin public/internal、JS export、Rust pub fn）一律不自动删，进"待人工确认"区。

### 已知风险与对策

| 风险 | 应对 |
|---|---|
| Go 反射导致 staticcheck 漏报 | 第二层 codegraph + grep 覆盖；漏报只影响"少删一点"，不会"误删" |
| Hilt `@Inject constructor` 字段看似未用 | 凡带 DI 注解的符号一律进"待人工确认"区 |
| Compose `@Composable` 被 preview 引用、release 中被剔除 | 不删任何 `@Composable` / `@Preview` 符号 |
| Rust `pub fn` 通过 `RegisterNatives` 字符串映射 | Rust 层只清依赖（cargo machete），不动 `pub fn` |
| staticcheck SA1019 误报 deprecated | SA1019 只进报告、不进清理批次 |
| 跨层引用（Kotlin Retrofit 调 Go API、JS 调 Go handler） | 跨层接口（API path、JSON 字段名、Intent extra key）不在本轮范围 |

### 回滚策略

每批次一个独立 commit。任一批次测试失败：

1. 立即 `git revert <commit>`（不用 `git reset`，保留失败痕迹便于复盘）。
2. 在报告里追加"回滚说明"小节。
3. 与用户确认是否拆得更细重做。

---

## 8. 工作流

```
A 阶段：审计
  1. 跑第一层工具扫描（每栈独立）
  2. 跑第二层 codegraph + grep 过滤
  3. 跑第三层人工冗余判断
  4. 写出 docs/2026-07-13-deadcode-audit-report.md
  5. 用户审阅报告 → 确认哪些进入 B 阶段

B 阶段：清理（每批次循环）
  for batch in [Go, Kotlin, Web, Rust/Build, Deps]:
    1. 从报告抽取本批次待删项
    2. 用户最终确认
    3. 改代码
    4. 跑该层验证
    5. 通过 → 提交；失败 → revert + 报告回滚说明
```
