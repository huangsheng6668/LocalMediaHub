# Security Round 29 — Phase 6: CI + Supply Chain Scanning Design

> **日期**：2026-07-12
> **范围**：GitHub Actions CI workflow + Gradle dependency locking
> **威胁模型**：继承 Round 29 主 spec；本 Phase 是"CI 放大器"，让 Phase 2/5 工具 + 已有扫描在每次 push/PR 自动跑
> **依赖**：Phase 2（`verifyLibffmpegSha256` task）、Phase 5（`tools/xsscheck`）；都已在 master
> **审计轮次**：Round 29 Phase 6（最后一个未实施的 Phase）
> **源 spec**：`docs/superpowers/specs/2026-07-10-security-audit-design.md`（section 5.6）

---

## 0. 摘要

Phase 6 落实主 spec 第 5.6 节的"供应链扫描与依赖锁定"修复（最小可行范围）。项目当前**无 CI**（`.github/` 目录不存在），Phase 6 建立 GitHub Actions workflow，让以下工具在每次 push master / PR 时自动运行：

1. **`govulncheck`**：Go 漏洞扫描（`server/`）
2. **`cargo audit`**：Rust 漏洞扫描（`android/app/src/main/rust/`）
3. **`tools/xsscheck`**：Web UI XSS 静态分析（Phase 5 follow-up）
4. **`./gradlew testDebugUnitTest assembleDebug`**：Android 构建 + 单元测试 + 隐式触发 `verifyLibffmpegSha256`（Phase 2）+ 触发 Phase 7 签名 fail-fast（debug 不受影响）
5. **`go test ./...`**：服务端单元测试

加上 **Gradle dependency locking**（`LockMode.STRICT` + `gradle.lockfile` 入库），防止 Android 依赖被传递性替换。

**核心价值**：
- 把 Phase 2/5 已建工具变成"持续监控"，而非"开发者记得手动跑"
- `govulncheck` / `cargo audit` 自动发现新 CVE
- dependency locking 防 supply chain 投毒（如 malicious transitive dep）
- PR 级保护：CI 失败 = 不能 merge

**Why now**：Phase 1/2/3/4/5/7/8 都已合并，安全工具齐备但缺自动化。Phase 6 是 Round 29 的收尾——让所有安全工作"自运行"。

---

## 1. 范围与方法论

### 1.1 范围（最小可行 CI + dependency locking）

**包含**：
- `.github/workflows/security.yml`：1 个 workflow，4-5 个并行 job
- `android/app/build.gradle.kts`：加 `dependencyLocking` 配置
- `android/gradle.lockfile`：首次生成的锁文件，入库

**不含**（留作未来 follow-up）：
- OWASP dependencyCheck（Gradle plugin；工作量大，`govulncheck` + `cargo audit` 已覆盖核心）
- 依赖升级（okhttp 4.12.0 → 4.12.1 等；主 spec 5.6 列出但每个需独立测试）
- Dockerfile（构建环境可重现；LAN-only 个人项目过度）
- 镜像签名 / SLSA Level（个人项目过度）
- 自动化依赖更新 bot（Dependabot 可作未来 follow-up）

### 1.2 方法论

继承 Round 29 主 spec 流程。

### 1.3 探索阶段已确认的事实

- **`.github/` 不存在**：项目零 CI
- **`server/go.mod`**：`github.com/localmediahub/server`，go 1.25.0
- **`android/app/src/main/rust/Cargo.toml`** + **`Cargo.lock`**：已入库
- **`android/app/build.gradle.kts`**：无 `dependencyLocking` 配置
- **`tools/xsscheck/`**：独立 Go module（Phase 5 follow-up）
- **`android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so`**：在仓库（CI 可访问）
- **现有 `verifyLibffmpegSha256` task**：挂在 `preBuild`（Phase 2）
- **GitHub repo**：`github.com/huangsheng6668/LocalMediaHub`

---

## 2. 威胁与缓解对照

| 风险 | Phase 6 缓解 |
|---|---|
| **新 Go vuln 未被发现** | `govulncheck` 每次 push/PR 跑，新 CVE 数据库更新后立即报警 |
| **新 Rust vuln 未被发现** | `cargo audit` 同上 |
| **Web UI 引入未转义 innerHTML**（Phase 5 工具存在但无人跑） | CI 自动跑 `tools/xsscheck` |
| **`libffmpeg.so` 被悄悄替换**（Phase 2 工具存在但只在本地 `preBuild` 跑） | CI 跑 `assembleDebug` 触发 `preBuild` → 自动校验 |
| **传递性依赖被投毒**（如 npm-style "left-pad" 事件） | Gradle `LockMode.STRICT` + `gradle.lockfile` |
| **PR 引入 regression** | CI 全套 job，任一失败阻断 merge |

**攻击链影响**：Phase 6 不直接缓解攻击链，但**放大 Phase 2/5/8 的保护**——让"开发期工具"变成"持续监控"。

---

## 3. 设计决策

### 3.1 已确认决策（来自 brainstorming）

| 决策 | 选择 | 理由 |
|---|---|---|
| CI 平台 | **GitHub Actions** | 与 GitHub 仓库原生集成；免费额度对个人项目够用 |
| 扫描范围 | **最小可行**：govulncheck + cargo audit + xsscheck + Android build + go test | 覆盖 Phase 2/5 工具 + 主语言；不含 OWASP / 依赖升级 / Dockerfile |
| 触发时机 | **push master + PR master** | 双触发确保 master 始终被检查；PR 级保护 |
| 失败策略 | **任一 job 失败则 workflow 失败** | CI 核心价值；否则只是"建议" |
| Dependency locking | **`LockMode.STRICT` + `gradle.lockfile` 入库** | 防 transitive 替换；STRICT 模式下 lockfile 不匹配则构建失败 |

### 3.2 隐式覆盖

`gradle-test` job 跑 `./gradlew assembleDebug` 会触发：
- `preBuild` → `verifyLibffmpegSha256`（Phase 2 SHA256 校验）
- `preBuild` → `buildRustNative`（若配置）
- 所有单元测试（含 Phase 1 `ServerConfigStoreAuthTokenTest` / Phase 5 `TestScanRealWebUI` 等）

所以**不需要单独的 `libffmpeg-sha256` job**——`gradle-test` 隐式覆盖。

### 3.3 兼容性矩阵

| 现状 | Phase 6 后行为 | 破坏性 |
|---|---|---|
| 开发者本地构建 | 不变（CI 不影响本地） | 无 |
| 开发者本地跑 `./gradlew assembleDebug` | STRICT 模式下，若依赖更新未在 lockfile 中 → 失败 | **有**（需先 `--write-locks`） |
| PR 提交 | 自动跑 CI；失败阻止 merge | 有（desired behavior） |
| 直接 push master | 自动跑 CI；失败 GitHub UI 显示红色 ❌ | 无（不阻止 push，只标记） |

---

## 4. 修改清单

| 文件 | 改动 | Task |
|---|---|---|
| `.github/workflows/security.yml` | 新建：CI workflow（5 jobs） | 1 |
| `android/app/build.gradle.kts` | 加 `dependencyLocking` 配置块 | 2 |
| `android/app/gradle.lockfile` | 新建：首次锁文件（`./gradlew dependencies --write-locks` 生成） | 2 |
| `README.md` | 加"### CI" 小节：说明 workflow 触发 + 本地复现命令 | 3 |

---

## 5. 实施细节

### 5.1 `.github/workflows/security.yml`

```yaml
name: Security

on:
  push:
    branches: [master]
  pull_request:
    branches: [master]

# Cancel in-progress runs for the same ref when new commits are pushed.
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  go-vuln:
    name: Go vulncheck
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-go@v5
        with:
          go-version: '1.25'
      - name: Install govulncheck
        run: go install golang.org/x/vuln/cmd/govulncheck@latest
      - name: Run govulncheck
        working-directory: server
        run: govulncheck ./...

  cargo-audit:
    name: Rust cargo audit
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Install cargo-audit
        run: cargo install cargo-audit
      - name: Run cargo audit
        working-directory: android/app/src/main/rust
        run: cargo audit

  xsscheck:
    name: XSS lint (Web UI)
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-go@v5
        with:
          go-version: '1.25'
      - name: Run xsscheck tests
        working-directory: tools/xsscheck
        run: go test ./...
      - name: Run xsscheck on real Web UI
        working-directory: tools/xsscheck
        run: go run .

  go-test:
    name: Go unit tests
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-go@v5
        with:
          go-version: '1.25'
      - name: Run server tests
        working-directory: server
        run: go test ./...

  gradle-test:
    name: Android build + tests + SHA256 verify
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'
      - uses: android-actions/setup-android@v3
      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v3
      - name: Run Android unit tests + debug build
        working-directory: android
        run: ./gradlew testDebugUnitTest assembleDebug --no-daemon
```

**注**：
- `concurrency` 块取消同一 ref 的旧 run（避免新 push 时旧 run 浪费）
- 每个 job 独立 runner（并行）
- `gradle-test` 隐式触发 `preBuild` → `verifyLibffmpegSha256`（Phase 2）
- Java 17 是 Android Gradle Plugin 8.x 的要求

### 5.2 `android/app/build.gradle.kts` dependency locking

在 `android { ... }` 块**之外**（顶层）加：

```kotlin
// Phase 6: strict dependency locking. Prevents transitive deps from being
// silently replaced (supply-chain attack). Lock file is committed at
// android/app/gradle.lockfile. To update deps: run
//   ./gradlew dependencies --write-locks
// then commit the lockfile change.
dependencyLocking {
    lockMode = LockMode.STRICT
    lockFile = file("$projectDir/gradle.lockfile")
}

configurations.all {
    resolutionStrategy.activateDependencyLocking()
}
```

**首次生成 lockfile**：
```bash
cd android
./gradlew dependencies --write-locks
git add app/gradle.lockfile
```

`LockMode.STRICT` 含义：lockfile 中没有的依赖 → 构建失败（防止传递性依赖变化）。

### 5.3 `README.md` "### CI" 小节

紧跟"### 安全响应头"或类似位置插入：

```markdown
### CI（持续集成）

GitHub Actions workflow `.github/workflows/security.yml` 在每次 push master / PR master 时自动运行：

| Job | 工具 | 覆盖 |
|---|---|---|
| `go-vuln` | `govulncheck` | Go 已知漏洞（`server/`） |
| `cargo-audit` | `cargo audit` | Rust 已知漏洞（`android/app/src/main/rust/`） |
| `xsscheck` | `tools/xsscheck` | Web UI XSS 静态分析（`server/internal/web/`） |
| `go-test` | `go test` | 服务端单元测试 |
| `gradle-test` | `./gradlew testDebugUnitTest assembleDebug` | Android 单元测试 + 构建（含 `verifyLibffmpegSha256` SHA256 校验） |

任一 job 失败 → workflow 失败 → PR 阻止 merge。

**本地复现**：

```bash
# Go 漏洞
cd server && govulncheck ./...

# Rust 漏洞
cd android/app/src/main/rust && cargo audit

# XSS lint
cd tools/xsscheck && go test ./... && go run .

# 服务端测试
cd server && go test ./...

# Android 测试 + 构建
cd android && ./gradlew testDebugUnitTest assembleDebug
```

**依赖锁定**：Android 使用 Gradle dependency locking（`LockMode.STRICT`）。`android/app/gradle.lockfile` 入库，传递性依赖不可被悄悄替换。升级依赖时：
```bash
cd android && ./gradlew dependencies --write-locks
git add app/gradle.lockfile
```
```

---

## 6. 测试方案

### 6.1 Workflow 验证

| 测试 | 方法 |
|---|---|
| Workflow YAML 语法正确 | 推送到 GitHub，查看 Actions tab 是否解析 workflow |
| 每个 job 都能启动 | 触发首次 run（push 或 PR），观察 5 个 job 都启动 |
| `go-vuln` 跑通 | job 完成且非 fail（若 `govulncheck` 报 vuln，记入 backlog） |
| `cargo-audit` 跑通 | 同上 |
| `xsscheck` 跑通 | 同上（应输出 `OK: no unescaped innerHTML variables in 14 file(s)`） |
| `go-test` 跑通 | 所有 server 测试 pass |
| `gradle-test` 跑通 | `assembleDebug` 成功（隐式触发 SHA256 校验） |

### 6.2 Dependency locking 验证

| 测试 | 方法 |
|---|---|
| lockfile 生成 | `./gradlew dependencies --write-locks` 产出 `android/app/gradle.lockfile` |
| STRICT 模式生效 | 修改某依赖版本（不更新 lockfile）→ `./gradlew assembleDebug` 失败 |
| 解锁命令工作 | `./gradlew dependencies --write-locks` 后构建恢复 |

### 6.3 回归测试

- 本地 `cd server && go test ./...` 全 green
- 本地 `cd android && ./gradlew testDebugUnitTest assembleDebug` 全 green
- CI 首次 run 无 fail

---

## 7. 风险与权衡

| 风险 | 影响 | 缓解 |
|---|---|---|
| `govulncheck` 报已知 vuln（依赖未升级） | CI 红 | 接受短期红 CI；记入 backlog 升级依赖 |
| `cargo audit` 同上 | 同上 | 同上 |
| Dependency locking 让本地首次构建失败 | 中 | README 加 `--write-locks` 指引；首次 lockfile 已 commit |
| CI 成本（5 jobs × 每分钟） | 低 | 个人项目免费额度（2000 分钟/月）；`concurrency` 取消旧 run |
| Linux runner 无 Windows 特定路径 | 低 | 项目跨平台代码不依赖；CI 仅 debug build |
| `cargo-audit` 数据库可能滞后 | 低 | RustSec advisory DB 持续更新；CI 跑 `cargo audit` 自动拉最新 |
| Android SDK setup 失败 | 中 | `android-actions/setup-android@v3` 是社区维护，但广泛使用 |

---

## 8. 验证完成标准

- ✅ `.github/workflows/security.yml` 存在 + YAML 语法正确
- ✅ 首次 push 后 GitHub Actions tab 显示 5 个 job 都启动
- ✅ `android/app/build.gradle.kts` 含 `dependencyLocking` 配置
- ✅ `android/app/gradle.lockfile` 入库
- ✅ STRICT 模式：改 dep 版本不更新 lockfile → 构建失败
- ✅ README 含"### CI" 小节 + 本地复现命令
- ✅ 本地 server / Android 测试无回归

---

## 9. 后续衔接

Phase 6 是 Round 29 最后一个 Phase。完成后 Round 29 全部 8 阶段 + Phase 5 follow-up + Phase 2 backlog 全部落地。

**未来 follow-up**（不在本 spec 范围）：
- 依赖升级（主 spec 5.6 列出的 okhttp/gson/media3）
- OWASP dependencyCheck（Gradle plugin）
- Dependabot（自动依赖更新）
- Dockerfile（构建环境可重现）
- SLSA Level（高级供应链保证）

---

## 文档信息

- **创建日期**：2026-07-12
- **审计轮次**：Round 29 Phase 6
- **依赖**：Phase 2（`verifyLibffmpegSha256`）、Phase 5（`tools/xsscheck`）
- **方法论**：brainstorming skill（澄清 → 方案 → 设计）
- **下一步**：经用户审核后，调用 writing-plans skill 转为实施计划
