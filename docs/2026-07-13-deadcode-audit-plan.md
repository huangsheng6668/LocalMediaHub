# Round 30 — 死代码审计 A 阶段（审计）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 对 LocalMediaHub 仓库执行系统性死代码 / 冗余代码审计，产出结构化报告 `docs/2026-07-13-deadcode-audit-report.md`，作为 B 阶段（清理）的输入。

**Architecture:** 三层流水线——(1) 工具广度扫描（staticcheck/go vet/gradle lint/knip/cargo machete），(2) codegraph+grep 精度过滤（剔除反射/DI/Composable/JNI 字符串引用误报），(3) 人工深度判断（重复实现、过度防御、单调用者 helper）。每层产出追加到同一份报告。

**Tech Stack:** Go 1.25+ staticcheck、Go vet、Android Gradle lint、knip、cargo machete、codegraph、Grep、Bash。

## Global Constraints

- 工作目录：`E:/github_project/LocalMediaHub`（Windows，bash shell，使用 Unix 路径风格）。
- 当前分支：master；A 阶段**不动任何代码**，只产出报告文档。
- spec 文档：`docs/2026-07-13-deadcode-audit-design.md`（commit `46564ff`）。
- 排除范围：`build/ffmpeg-src/**`、`android/app/src/main/rust/target/**`、`android/app/build/**`、`android/build/**`、`server/LocalMediaHub.exe`、根目录 `*.apk`、`.git/`、`.codegraph/`、`.superpowers/`、`.claude/`。
- 工具未安装时**不自动安装**——记录到附录 B，降级到 codegraph+grep 兜底。
- 报告字段约定：每条发现项必须包含 `位置`、`类型`、`证据`、`风险等级`、`建议动作` 五个字段。
- 风险等级判定矩阵（spec §4）：
  - 工具 + codegraph 双确认零调用 + 非导出 → 直接删除（进 B 阶段清理批次）
  - 工具 + codegraph 双确认零调用 + 导出 → 待人工确认
  - 仅工具报告 + codegraph 找到引用 → 误报，剔除
  - 仅人工读出（冗余非死） → 冗余建议区，不自动清理
  - 工具未运行 → 未覆盖区

---

## Task 1: 报告骨架与执行摘要占位

**Files:**
- Create: `docs/2026-07-13-deadcode-audit-report.md`

**Interfaces:**
- Consumes: spec `docs/2026-07-13-deadcode-audit-design.md` §4 报告分节结构
- Produces: 报告文件骨架，后续 Task 2-7 向对应小节追加内容；Task 8 填附录 A/B；Task 9 回填执行摘要数字

- [ ] **Step 1: 创建报告骨架**

写入 `docs/2026-07-13-deadcode-audit-report.md`：

```markdown
# Round 30 — 死代码 / 冗余代码审计报告

- **创建日期**: 2026-07-13
- **关联 spec**: `docs/2026-07-13-deadcode-audit-design.md`（commit `46564ff`）
- **A 阶段状态**: 进行中
- **B 阶段状态**: 未启动

---

## 1. 执行摘要

- 工具运行情况：[Task 9 填]
- 发现项总数：[Task 9 填]，按层分布：
  - Go server：X
  - Android Kotlin：X
  - Web JS：X
  - Rust JNI：X
  - 构建脚本/工具：X
  - 依赖冗余：X
- 按风险等级分布：低 X / 中 X / 高 X
- 按建议动作分布：直接删除 X / 待人工确认 X / 冗余建议 X / 误报剔除 X

---

## 2. Go server 发现项

[Task 2 追加]

---

## 3. Android Kotlin 发现项

[Task 3 追加]

---

## 4. Web JS 发现项

[Task 4 追加]

---

## 5. Rust JNI 发现项

[Task 5 追加]

---

## 6. 构建脚本与工具发现项

[Task 6 追加]

---

## 7. 依赖冗余

[Task 7 追加]

---

## 8. 附录 A：非源码但占体积的文件清单（不清理）

[Task 8 追加]

---

## 9. 附录 B：未执行工具及原因

[Task 8 追加]
```

- [ ] **Step 2: 验证文件存在**

Run: `ls -la docs/2026-07-13-deadcode-audit-report.md`
Expected: 文件存在，大小非零。

- [ ] **Step 3: 暂不提交**

A 阶段报告在 Task 9 完成后一次性提交。Task 1 仅创建本地文件。

---

## Task 2: Go server 审计

**Files:**
- Modify: `docs/2026-07-13-deadcode-audit-report.md`（第 2 节）

**Interfaces:**
- Consumes: spec §3 第一层 Go 工具表；§3 第二层 codegraph+grep 过滤规则
- Produces: 第 2 节填好的 Go server 发现项清单；附录 B 的 Go 工具运行情况

- [ ] **Step 1: 工具可用性探测**

Run:
```bash
cd server && (staticcheck -version 2>&1 || echo "STATICCHECK_MISSING")
cd server && (go vet ./... 2>&1 | head -5 || echo "VET_FAILED")
```

记录 staticcheck / go vet 是否可用。任一缺失 → 附录 B 记录原因，降级到 codegraph 兜底。

- [ ] **Step 2: 跑 staticcheck（若可用）**

Run: `cd server && staticcheck ./... 2>&1 | tee /tmp/staticcheck.out`
Expected: 输出含 `U1000` / `U1001` / `SA1019` 行（若无则无 Go 符号级死代码）。

把所有 `U1000`（unused）/ `U1001`（unused receiver）行收集为原始候选集 R1。
把所有 `SA1019`（deprecated）行单独收集为 R1-deprecated，**只进报告、不进清理批次**。

- [ ] **Step 3: 跑 go vet（不可达代码）**

Run: `cd server && go vet ./... 2>&1 | tee /tmp/govet.out`

收集所有 `unreachable code` / `declared and not used` 行为 R2。

- [ ] **Step 4: 跑 go mod tidy -diff（依赖冗余）**

Run: `cd server && go mod tidy -diff 2>&1 | tee /tmp/gomodtidy.out`

把 diff 中所有 `^-` 开头（被删除的 require 行）收集为 R3，留给 Task 7。

- [ ] **Step 5: 第二层过滤——codegraph 二次确认 R1**

对 R1 中每一个符号，调用 `codegraph_explore` 查询其引用者：

```
mcp__codegraph__codegraph_explore({ query: "<symbol_name>", mode: "references" })
```

判定规则：
- codegraph 报告"无引用者"或仅自身文件引用 → 保留为强证据候选
- codegraph 找到跨文件/跨包引用 → 标记为误报，剔除
- 符号首字母大写（Go 导出）且跨包无 import → 标记"待人工确认"
- 符号首字母小写（Go 非导出）且仅同包无引用 → 标记"直接删除候选"

- [ ] **Step 6: 第二层过滤——grep 字符串引用兜底**

对 Step 5 标记为"直接删除候选"的每一个符号，再跑一次 grep 兜底反射/字符串引用：

```
Grep({ pattern: "<symbol_name>", path: "server", output_mode: "content", -n: true })
```

判定规则：
- 仅出现在定义处 + 同包测试文件 → 保留
- 出现在 `e.GET`/`e.POST`/`e.Add`/`reflect`/字符串字面量 → 标记"待人工确认"
- 出现在 `*_test.go` 且符号本身是测试 helper → 保留
- 出现在测试文件且符号是生产代码 → 重新评估

- [ ] **Step 7: 第三层人工——冗余但非死**

读 `server/internal/service/*.go`（scanner / streaming / tags / thumbnail / path）和 `server/internal/server/handler/*.go`，找：

- 重复实现（如多个 `normalizePath`、多个 `formatSize`）
- 过度防御（信任边界内的 `if err != nil { return err }` 紧跟已被 return 的 err）
- 单调用者 helper（仅一个调用点、且调用点本身很简单的私有函数）

收集为 R4，**只进报告、不进清理批次**。

- [ ] **Step 8: 写入报告第 2 节**

向 `docs/2026-07-13-deadcode-audit-report.md` 第 2 节追加：

```markdown
### 2.1 工具运行情况

- staticcheck: [可用/缺失，版本号]
- go vet: [可用/缺失]
- go mod tidy -diff: [有输出/无输出]

### 2.2 文件级死代码

| 位置 | 类型 | 证据 | 风险 | 建议 |
|---|---|---|---|---|
[一行一项，来自 R1/R2 中"整个文件无引用"的命中]

### 2.3 符号级死代码（直接删除候选）

[每条按 spec §4 字段约定填表]

### 2.4 符号级死代码（待人工确认）

[每条按 spec §4 字段约定填表]

### 2.5 Deprecated API 使用（SA1019，仅记录）

[R1-deprecated 内容]

### 2.6 冗余但非死（仅记录，不自动清理）

[R4 内容]
```

- [ ] **Step 9: 验证报告写入**

Run: `grep -c "^###" docs/2026-07-13-deadcode-audit-report.md`
Expected: 至少包含 2.1-2.6 六个 `###` 子标题。

- [ ] **Step 10: 暂不提交，等 Task 9 统一提交**

---

## Task 3: Android Kotlin 审计

**Files:**
- Modify: `docs/2026-07-13-deadcode-audit-report.md`（第 3 节）

**Interfaces:**
- Consumes: spec §3 第一层 Kotlin 工具表；§3 第二层注解豁免规则
- Produces: 第 3 节填好的 Android Kotlin 发现项清单

- [ ] **Step 1: 工具可用性探测**

Run:
```bash
cd android && (./gradlew --version 2>&1 | head -3 || echo "GRADLE_MISSING")
cd android && ls app/build.gradle.kts
```

- [ ] **Step 2: 跑 gradle lintDebug**

Run: `cd android && ./gradlew :app:lintDebug 2>&1 | tee /tmp/android-lint.out`

收集所有 `UnusedDeclaration` / `UnusedResources` / `UnusedSymbol` 行为 R1。
**注意**：lint 任务首次执行可能耗时较长（10+ 分钟）。

- [ ] **Step 3: 第二层过滤——注解豁免**

对 R1 中每一个符号，用 Grep 在 `android/app/src/main/java/` 内查找该符号定义处是否带以下注解：

```
Grep({ pattern: "@(Inject|HiltViewModel|Composable|Parcelize|Provides|Binds|Module|EntryPoint|AndroidEntryPoint|HiltAndroidApp)", path: "android/app/src/main/java", output_mode: "content", -B: 1 })
```

判定规则：
- 符号带上述任一注解 → 标记"待人工确认"（Hilt/Compose 运行时引用）
- 符号为 `private` 且无注解 → 标记"直接删除候选"
- 符号为 `public`/`internal` 且无注解 → 标记"待人工确认"

- [ ] **Step 4: 第二层过滤——codegraph 二次确认**

对"直接删除候选"调用 `codegraph_explore`：

```
mcp__codegraph__codegraph_explore({ query: "<symbol_name>" })
```

剔除 codegraph 找到引用的项。

- [ ] **Step 5: 第二层过滤——XML/反射兜底**

Run:
```bash
Grep({ pattern: "<symbol_name>", path: "android/app/src/main", output_mode: "content", -n: true })
```

对每个候选符号，检查是否出现在：
- `res/xml/*.xml`（WorkManager、data-extraction rules）
- `AndroidManifest.xml`（activity/service/receiver 元数据）
- `res/values/strings.xml` 等资源文件

任一命中 → 标记"待人工确认"。

- [ ] **Step 6: 第三层人工——冗余但非死**

读 `android/app/src/main/java/com/juziss/localmediahub/` 下的：
- `viewmodel/Browse*.kt`（delegate 之间是否有重复逻辑）
- `util/*.kt`（`TimeUtil` / `NetUtil` / `CacheCleanup` 是否有未用 helper）
- `data/MediaRepository.kt`（Retrofit 接口是否有未挂载到 ViewModel 的方法）

收集为 R4。

- [ ] **Step 7: 写入报告第 3 节**

向报告第 3 节追加，结构同 Task 2 Step 8（3.1-3.6 子标题，按 Android 字段填表）。

- [ ] **Step 8: 验证报告写入**

Run: `grep -c "^### 3\." docs/2026-07-13-deadcode-audit-report.md`
Expected: 至少 6（3.1-3.6）。

- [ ] **Step 9: 暂不提交**

---

## Task 4: Web JS 审计

**Files:**
- Modify: `docs/2026-07-13-deadcode-audit-report.md`（第 4 节）

**Interfaces:**
- Consumes: spec §3 第一层 knip；§3 第二层 `index.html` import 列表规则
- Produces: 第 4 节填好的 Web JS 发现项清单

- [ ] **Step 1: 工具可用性探测**

Run:
```bash
cd server/internal/web && (npx knip --version 2>&1 || echo "KNIP_MISSING")
```

knip 通过 `npx` 即可运行（无需全局安装）。

- [ ] **Step 2: 跑 knip**

Run: `cd server/internal/web && npx knip --no-exit-code 2>&1 | tee /tmp/knip.out`

收集 knip 报告的：
- Unused files（整个 JS 文件无人 import）
- Unused exports（导出符号无人 import）
- Unused exported values
- Duplicate exports

为 R1。

- [ ] **Step 3: 第二层过滤——index.html import 列表**

读 `server/internal/web/index.html`，提取所有 `<script type="module" src="...">` 标签的 src。

判定规则：
- knip 标为 unused 的 file 但出现在 index.html 的 `<script src>` → 误报，剔除
- knip 标为 unused 的 export 但出现在某个被 index.html 引用的模块的 import 列表 → 误报，剔除
- knip 标为 unused 的 file 且不在 index.html → 标记"直接删除候选"
- knip 标为 unused 的 export 且无 import → 标记"直接删除候选"

- [ ] **Step 4: 第二层过滤——Go embed 兜底**

Web 静态资源通过 Go `embed` 挂载。检查 `server/internal/web/web.go`：

Run: `Grep({ pattern: "go:embed", path: "server/internal/web", output_mode: "content", -A: 2 })`

判定规则：knip 标为 unused 但被 `go:embed` 一次性嵌入的文件（如 `style.css`、`favicon.go` 内的 icon）→ 误报，剔除。

- [ ] **Step 5: 第三层人工——冗余但非死**

读 `server/internal/web/utils.js`、`dom.js`、`state.js`、`toast.js`（通用工具类），找：
- 重复的 DOM 操作 helper
- 重复的格式化函数（与 Android `TimeUtil`、Go service 内部重复的 `formatTime` 等）
- 单次使用的 helper

收集为 R4。

- [ ] **Step 6: 写入报告第 4 节**

向报告第 4 节追加（4.1-4.6 子标题）。

- [ ] **Step 7: 验证报告写入**

Run: `grep -c "^### 4\." docs/2026-07-13-deadcode-audit-report.md`
Expected: 至少 6（4.1-4.6）。

- [ ] **Step 8: 暂不提交**

---

## Task 5: Rust JNI 审计

**Files:**
- Modify: `docs/2026-07-13-deadcode-audit-report.md`（第 5 节）

**Interfaces:**
- Consumes: spec §3 Rust 工具表；spec §7 风险表（Rust pub fn 通过 `RegisterNatives` 字符串映射）
- Produces: 第 5 节填好的 Rust JNI 发现项清单（仅依赖冗余 + 文件级死代码，不动 pub fn）

- [ ] **Step 1: 工具可用性探测**

Run:
```bash
cd android/app/src/main/rust && (cargo machete --version 2>&1 || cargo install --dry-run cargo-machete 2>&1 || echo "MACHETE_MISSING")
```

- [ ] **Step 2: 跑 cargo machete（依赖冗余）**

若 machete 可用：
Run: `cd android/app/src/main/rust && cargo machete 2>&1 | tee /tmp/machete.out`

收集 machete 标记为 unused 的依赖为 R1。

若不可用，降级到人工：
- 读 `Cargo.toml` 的 `[dependencies]` 列表
- 对每个依赖 `Grep({ pattern: "<dep_name>", path: "android/app/src/main/rust/src", output_mode: "content" })`
- 找不到引用 → 标记为 unused 候选

- [ ] **Step 3: 跑 cargo check（编译器 dead_code lint）**

Run: `cd android/app/src/main/rust && cargo check 2>&1 | grep -E "warning:.*never used|warning:.*dead_code" | tee /tmp/cargo-deadcode.out`

收集 dead_code 警告为 R2。**注意**：通过 JNI `RegisterNatives` 注册的 `pub fn` 不会触发 dead_code（因为 `pub`），需配合 Step 4 确认。

- [ ] **Step 4: 第二层过滤——JNI 注册表豁免**

读 `android/app/src/main/rust/src/jni_bridge/` 下的所有文件，提取 `RegisterNatives` / `JNIEnv.register_native_methods` 调用中提到的函数名集合 N。

判定规则：
- cargo dead_code 警告的符号 ∈ N → 误报，剔除
- cargo dead_code 警告的符号 ∉ N 且为非 `pub` → 标记"直接删除候选"
- cargo dead_code 警告的符号 ∉ N 且为 `pub fn` → 标记"待人工确认"（spec §7 不主动删 Rust pub fn）

- [ ] **Step 5: 写入报告第 5 节**

向报告第 5 节追加：

```markdown
### 5.1 工具运行情况

- cargo machete: [可用/缺失]
- cargo check dead_code: [有/无警告]

### 5.2 文件级死代码

[整个 .rs 文件无人引用]

### 5.3 符号级死代码

[非 pub 符号可直接删；pub fn 进待人工确认]

### 5.4 冗余但非死

[R4 内容]
```

- [ ] **Step 6: 验证报告写入**

Run: `grep -c "^### 5\." docs/2026-07-13-deadcode-audit-report.md`
Expected: 至少 4。

- [ ] **Step 7: 暂不提交**

---

## Task 6: 构建脚本与工具审计

**Files:**
- Modify: `docs/2026-07-13-deadcode-audit-report.md`（第 6 节）

**Interfaces:**
- Consumes: spec §1 纳入扫描的"构建脚本/工具"范围
- Produces: 第 6 节填好的构建脚本/工具发现项清单

- [ ] **Step 1: xsscheck 工具审计**

读 `tools/xsscheck/`，检查：
- main 入口是否被 CI / Makefile / 脚本引用
  - Run: `Grep({ pattern: "xsscheck", path: "E:/github_project/LocalMediaHub", output_mode: "files_with_matches", glob: "!**/build/**" })`
- 内部是否有未用的 helper / unused 文件
  - Run: `cd tools/xsscheck && go vet ./... 2>&1`
  - Run: `cd tools/xsscheck && (staticcheck ./... 2>&1 || echo "staticcheck missing")`

收集发现项为 R1。

- [ ] **Step 2: scripts/ 审计**

Run: `ls scripts/`
对每个脚本：
- Run: `Grep({ pattern: "<script_name>", path: "E:/github_project/LocalMediaHub", output_mode: "files_with_matches", glob: "!**/build/**" })`
- 若无引用且非 README 显式提到的入口 → 标记"待人工确认"

- [ ] **Step 3: 根目录 gradle/build 脚本审计**

读根目录 `build.gradle.kts`、`settings.gradle.kts`：
- 是否有未使用的插件声明
- 是否有未使用的 repository 声明

- [ ] **Step 4: 写入报告第 6 节**

```markdown
### 6.1 tools/xsscheck

[R1]

### 6.2 scripts/

[Step 2 结果]

### 6.3 根目录 gradle 配置

[Step 3 结果]
```

- [ ] **Step 5: 验证报告写入**

Run: `grep -c "^### 6\." docs/2026-07-13-deadcode-audit-report.md`
Expected: 至少 3。

- [ ] **Step 6: 暂不提交**

---

## Task 7: 依赖冗余汇总

**Files:**
- Modify: `docs/2026-07-13-deadcode-audit-report.md`（第 7 节）

**Interfaces:**
- Consumes: Task 2 Step 4 收集的 R3（Go `go mod tidy -diff`）；Task 5 Step 2 收集的 R1（cargo machete）；Android 依赖人工扫描
- Produces: 第 7 节填好的依赖冗余清单

- [ ] **Step 1: Go 依赖冗余**

读取 Task 2 Step 4 产出的 `/tmp/gomodtidy.out`，提取所有 `-require` 行。

- [ ] **Step 2: Android 依赖冗余**

Run: `cd android && ./gradlew :app:dependencies --configuration debugRuntimeClasspath 2>&1 | tee /tmp/android-deps.out`

读 `app/build.gradle.kts` 的 `dependencies { ... }` 块，对每个 `implementation`/`api` 依赖：
- `Grep({ pattern: "import <package>", path: "android/app/src/main/java", output_mode: "files_with_matches" })`
- 找不到 import → 标记"待人工确认"（Gradle 依赖往往通过传递方式间接使用，需谨慎）

- [ ] **Step 3: Rust 依赖冗余**

读取 Task 5 Step 2 产出的 `/tmp/machete.out`。

- [ ] **Step 4: 写入报告第 7 节**

```markdown
### 7.1 Go (server/go.mod)

| 依赖 | 证据 | 风险 | 建议 |
|---|---|---|---|

### 7.2 Android (app/build.gradle.kts)

[同结构]

### 7.3 Rust (rust/Cargo.toml)

[同结构]
```

- [ ] **Step 5: 验证报告写入**

Run: `grep -c "^### 7\." docs/2026-07-13-deadcode-audit-report.md`
Expected: 至少 3。

- [ ] **Step 6: 暂不提交**

---

## Task 8: 附录 A/B 填充

**Files:**
- Modify: `docs/2026-07-13-deadcode-audit-report.md`（第 8、9 节）

**Interfaces:**
- Consumes: 各 Task 中"工具缺失"的临时标记
- Produces: 附录 A（体积热点，不清理）+ 附录 B（未执行工具）

- [ ] **Step 1: 附录 A——非源码体积热点**

Run:
```bash
cd "E:/github_project/LocalMediaHub" && \
  du -sh build/ffmpeg-src android/app/src/main/rust/target android/app/build android/build server/LocalMediaHub.exe app-debug-*.apk 2>/dev/null | sort -h
```

写入附录 A 表格：路径、大小、是否在 `.gitignore`、建议（保留 / 评估删除）。

- [ ] **Step 2: 附录 B——未执行工具**

汇总 Task 2-7 中标记为 `*_MISSING` 的工具：

```markdown
### 9. 附录 B：未执行工具及原因

| 工具 | 栈 | 原因 | 降级方案 |
|---|---|---|---|
[每行一个缺失工具]
```

- [ ] **Step 3: 验证写入**

Run: `grep -E "^## (8|9)\." docs/2026-07-13-deadcode-audit-report.md`
Expected: 至少 2 行（附录 A、附录 B）。

- [ ] **Step 4: 暂不提交**

---

## Task 9: 执行摘要回填 + 提交

**Files:**
- Modify: `docs/2026-07-13-deadcode-audit-report.md`（第 1 节）

**Interfaces:**
- Consumes: Task 2-8 已填好的第 2-9 节
- Produces: 完整的审计报告（git 提交）

- [ ] **Step 1: 统计各层发现项数量**

对每一节统计行数/条目数：

Run: `grep -cE "^\| .+ \|.+\|.+\|.+\|.+\|$" docs/2026-07-13-deadcode-audit-report.md`

人工对照各节小标题归类。

- [ ] **Step 2: 回填第 1 节执行摘要**

替换 `[Task 9 填]` 占位为实际数字。把 `A 阶段状态` 改为 `完成`。

- [ ] **Step 3: 自检——占位符扫描**

Run: `grep -nE "\[Task|TBD|TODO|XXX" docs/2026-07-13-deadcode-audit-report.md`
Expected: 无输出（或仅匹配被引用的 spec 标题）。

- [ ] **Step 4: 自检——字段完整性抽查**

抽 3 条发现项，确认都含 `位置` / `类型` / `证据` / `风险等级` / `建议动作` 5 字段。若缺字段，补齐。

- [ ] **Step 5: 检查工作树干净**

Run:
```bash
cd "E:/github_project/LocalMediaHub" && git status
```
Expected: 只有 `docs/2026-07-13-deadcode-audit-report.md` 是新文件（或 untracked）+ 会话开始前已存在的 `android/keystore.properties.example` 删除。

**不要**把 `android/keystore.properties.example` 的删除带进本次提交。

- [ ] **Step 6: 提交**

```bash
cd "E:/github_project/LocalMediaHub" && \
  git add docs/2026-07-13-deadcode-audit-report.md && \
  git commit -m "$(cat <<'EOF'
docs: round 30 dead code audit report (phase A)

Three-tier audit (static tools → codegraph+grep filter → manual
redundancy review) across Go server, Android Kotlin, Web JS, Rust
JNI, build scripts, and dependencies. Per-spec §4 field convention;
B-phase cleanup batches to follow after user review.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 7: 验证提交**

Run: `git log --oneline -1`
Expected: 最新 commit 信息为 `docs: round 30 dead code audit report (phase A)`。

- [ ] **Step 8: 通知用户审核报告**

输出："A 阶段审计完成，报告已提交到 `docs/2026-07-13-deadcode-audit-report.md`（commit `<hash>`）。请审阅，确认进入 B 阶段（清理）的批次顺序和待删项。"

---

## 自检（plan 自身）

**Spec 覆盖**：spec §1 范围 → Task 2-7 各栈 + Task 8 附录 A；spec §2 排除项 → Task 8 附录 A；spec §3 三层流水线 → 每个 Task 内 Step 1-7 体现；spec §4 报告结构与字段 → Task 1 骨架 + 各 Task 写入步骤；spec §5 批次拆分 → 属于 B 阶段（不在本计划）；spec §6 验证 → 属于 B 阶段；spec §7 护栏 → 本计划不动代码，无需回滚。

**Placeholder 扫描**：无 TBD；所有 Step 都有具体 Run/Expected 或可执行动作。

**Type 一致性**：所有 Task 产出的"清单/表格"结构一致（位置/类型/证据/风险/建议 5 字段），后续 B 阶段清理批次可直接消费。
