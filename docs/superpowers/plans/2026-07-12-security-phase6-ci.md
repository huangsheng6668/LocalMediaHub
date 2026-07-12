# Security Round 29 — Phase 6: CI + Supply Chain Scanning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish a GitHub Actions CI workflow that runs `govulncheck`, `cargo audit`, `xsscheck`, `go test`, and `./gradlew testDebugUnitTest assembleDebug` on every push/PR to master. Add Gradle `LockMode.STRICT` dependency locking with committed `gradle.lockfile` to prevent transitive dependency substitution.

**Architecture:** Single `.github/workflows/security.yml` with 5 parallel jobs (go-vuln / cargo-audit / xsscheck / go-test / gradle-test). `gradle-test` job implicitly triggers Phase 2's `verifyLibffmpegSha256` via `preBuild` hook (no separate SHA256 job needed). Gradle dependency locking is a single block in `android/app/build.gradle.kts` + a committed `gradle.lockfile`.

**Tech Stack:** GitHub Actions / Ubuntu runners / Go 1.25 / Rust stable / JDK 17 / Android SDK / Gradle 8.x

**Source spec:** `docs/superpowers/specs/2026-07-12-security-phase6-ci-design.md`

**Coverage:** Continuous monitoring for Go/Rust vulns, Web UI XSS regression, libffmpeg SHA256 integrity, Android dependency lock-in

## Global Constraints

- **Workflow triggers on `push` to master AND `pull_request` to master.** Both required. (Spec section 3.1)
- **Any job failure = workflow failure.** No `continue-on-error`. (Spec section 3.1)
- **Jobs run in parallel** (independent runners) for speed. (Spec section 5.1)
- **`gradle-test` job MUST use `--no-daemon`** to avoid daemon state issues in CI. (Spec section 5.1)
- **Gradle dependency locking MUST be `LockMode.STRICT`** — lockfile mismatch fails the build. (Spec section 5.2)
- **`android/app/gradle.lockfile` MUST be committed** (not gitignored). (Spec section 5.2)
- **No `workflow_dispatch`** — only push + PR triggers (manual trigger is a future need, YAGNI).

---

## File Structure

| File | Type | Responsibility |
|---|---|---|
| `.github/workflows/security.yml` | Create | CI workflow: 5 parallel jobs |
| `android/app/build.gradle.kts` | Modify | Add `dependencyLocking` block + activate for all configurations |
| `android/app/gradle.lockfile` | Create | Generated lockfile (committed) |
| `README.md` | Modify | Add "### CI" section documenting workflow + local reproduction |

---

## Task 1: Create GitHub Actions workflow

**Files:**
- Create: `.github/workflows/security.yml`

**Interfaces:**
- Produces: 5-job workflow triggered on push/PR master. Each job is independent (parallel).

- [ ] **Step 1: Create `.github/workflows/` directory + workflow file**

Create `.github/workflows/security.yml`:

```yaml
name: Security

on:
  push:
    branches: [master]
  pull_request:
    branches: [master]

# Cancel in-progress runs for the same ref when new commits are pushed.
# Saves CI minutes when developers push multiple commits in quick succession.
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
      - name: Install Rust toolchain
        uses: dtolnay/rust-toolchain@stable
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

- [ ] **Step 2: Validate YAML syntax**

Run (if you have a YAML linter locally, e.g. `yamllint`):
```bash
yamllint .github/workflows/security.yml
```

Or use Python:
```bash
python -c "import yaml; yaml.safe_load(open('.github/workflows/security.yml'))" && echo "YAML OK"
```

Expected: "YAML OK" (no parse errors). If you don't have Python/yamllint, skip this step — GitHub will validate on first push.

- [ ] **Step 3: Commit (will trigger CI on push)**

```bash
git add .github/workflows/security.yml
git commit -m "ci: add security workflow (govulncheck + cargo audit + xsscheck + tests)"
```

**Note**: Do NOT push yet. Push after Task 2 (dependency locking) + Task 3 (README) are also committed, so the first CI run sees a complete state.

---

## Task 2: Add Gradle dependency locking

**Files:**
- Modify: `android/app/build.gradle.kts`
- Create: `android/app/gradle.lockfile`

**Interfaces:**
- Consumes: existing Android dependencies.
- Produces: `LockMode.STRICT` enforcement; `gradle.lockfile` checked into repo.

- [ ] **Step 1: Read current build.gradle.kts structure**

Read `android/app/build.gradle.kts` to find a sensible insertion point for the `dependencyLocking` block. Recommended location: AFTER the `android { ... }` block closes (around line 240) and BEFORE `dependencies { ... }`. The block goes at the **top level** of the build script.

- [ ] **Step 2: Add dependencyLocking block**

Insert (e.g. right after the `android { ... }` close and before `dependencies { ... }`):

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

**Note**: `LockMode` is `org.gradle.api.artifacts.dsl.LockMode`. Check if it needs import — typically accessible without import inside Kotlin DSL. If compiler complains, add `import org.gradle.api.artifacts.dsl.LockMode` at top of file.

- [ ] **Step 3: Generate the lockfile**

```bash
cd android
./gradlew dependencies --write-locks
```

This resolves all dependencies and writes `android/app/gradle.lockfile`. Expected: file created with entries like:
```
com.squareup.okhttp3:okhttp:4.12.0=runtimeClasspath
com.squareup.okhttp3:logging-interceptor:4.12.0=runtimeClasspath
...
```

If the command fails, read the error — common causes:
- Network issues (cannot reach Maven Central)
- Existing dependency resolution conflict (run with `--stacktrace`)

- [ ] **Step 4: Verify STRICT mode works**

Pick a dependency in `gradle.lockfile` and temporarily change its version (e.g. `4.12.0` → `4.12.1` in the lockfile only, NOT in build.gradle.kts). Then:
```bash
cd android
./gradlew help 2>&1 | tail -10
```
Expected: BUILD FAILED with "Dependency lock state out of date" or similar.

Restore the lockfile:
```bash
git checkout android/app/gradle.lockfile
```

- [ ] **Step 5: Verify normal build still works**

```bash
cd android
./gradlew testDebugUnitTest assembleDebug --no-daemon 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL (lockfile matches build.gradle.kts).

- [ ] **Step 6: Commit**

```bash
git add android/app/build.gradle.kts android/app/gradle.lockfile
git commit -m "feat(android): enable strict dependency locking (Phase 6)"
```

---

## Task 3: Document CI in README

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: Tasks 1-2.
- Produces: User-facing documentation of CI workflow + local reproduction.

- [ ] **Step 1: Read README to find insertion point**

Read `README.md` to find the "### 安全响应头" section (added in Phase 4) or similar location. Insert "### CI（持续集成）" immediately after the security headers section.

- [ ] **Step 2: Insert "### CI（持续集成）" section**

Insert this markdown (adapt heading level to siblings — `###` if siblings are `###`):

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

​```bash
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
​```

**依赖锁定**：Android 使用 Gradle dependency locking（`LockMode.STRICT`）。`android/app/gradle.lockfile` 入库，传递性依赖不可被悄悄替换。升级依赖时：

​```bash
cd android && ./gradlew dependencies --write-locks
git add app/gradle.lockfile
​```
```

**IMPORTANT Markdown escaping**: When writing code fences inside a markdown code block (like this plan), the inner triple-backticks need to be escaped with backticks (``` ``` → use ` ``` ` with zero-width joiner or use 4-backtick fences). In the actual README file, use normal triple-backticks. The implementer should write normal markdown — the README file will have working code fences.

- [ ] **Step 3: Verify markdown renders (optional)**

Visually inspect the inserted section for correct heading level and table/code-fence closure.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: document CI workflow + dependency locking (Phase 6)"
```

---

## Task 4: Push + verify CI runs

**Files:**
- No files modified — verification only.

**Interfaces:**
- Consumes: Tasks 1-3 (all committed, not yet pushed).

- [ ] **Step 1: Push the branch**

```bash
git push origin round-29-security-phase6-ci
```

(Or merge to master first if that's the workflow — but for CI verification, pushing the feature branch is fine. The `on: pull_request` trigger only fires when a PR is opened; `on: push: branches: [master]` only fires on master pushes. **So pushing a feature branch alone will NOT trigger CI.** Two options to verify:)

**Option A** (recommended): Open a PR from the feature branch → `pull_request` trigger fires → CI runs on the PR.

**Option B**: Merge to master first → `push: branches: [master]` trigger fires → CI runs on master.

Choose Option A for first-time verification (CI runs before merge, can catch issues safely).

- [ ] **Step 2: Open PR (Option A)**

```bash
gh pr create --title "feat: round 29 security phase 6 CI + dependency locking" --body "..."
```

Or use GitHub UI.

- [ ] **Step 3: Watch CI run**

Go to GitHub repo → Actions tab (or PR checks UI). Expect:
- 5 jobs start in parallel
- Each job takes 2-10 minutes
- `go-test` + `xsscheck` likely pass first (fastest)
- `gradle-test` slowest (Android SDK setup + Gradle download)

**Possible failures (and how to triage)**:
- `govulncheck` reports vulns → check if vulns are in dependencies we can upgrade; if upgrade is non-trivial, accept as known issue and document
- `cargo audit` reports vulns → same
- `gradle-test` fails on `verifyLibffmpegSha256` → unlikely (`.so` is in repo; hash matches `.sha256` which is also in repo); if it fails, investigate path resolution on Linux runner
- Android SDK setup fails → check `android-actions/setup-android@v3` version; may need to pin

- [ ] **Step 4: Document any known CI failures**

If `govulncheck` or `cargo audit` reports vulns that cannot be fixed in this task (e.g. require dependency upgrade), add a "## Known CI findings" section to the PR description with the vuln IDs + rationale for accepting.

- [ ] **Step 5: Merge PR (Option A)**

Once CI passes (or known failures are accepted), merge the PR via GitHub UI or:
```bash
gh pr merge --merge
```

Or use `--no-ff` if project convention is merge commits (check `git log` for prior merge style).

- [ ] **Step 6: Verify master CI runs**

After merge, GitHub `push: branches: [master]` trigger fires. Check Actions tab to confirm 5 jobs run on master.

---

## Self-Review

**Spec coverage** (against spec section 5):
- ✅ Workflow with 5 parallel jobs (Task 1, spec 5.1)
- ✅ Dependency locking (Task 2, spec 5.2)
- ✅ README documentation (Task 3, spec 5.3)
- ✅ Trigger on push + PR master (spec 3.1)
- ✅ No `continue-on-error` (any job fail = workflow fail, spec 3.1)
- ✅ STRICT lock mode + committed lockfile (spec 5.2)

**Type consistency**:
- Workflow file path `.github/workflows/security.yml` — consistent across Task 1 + Task 3 README
- `gradle.lockfile` location `android/app/gradle.lockfile` — consistent across Task 2 build.gradle.kts + README
- `--write-locks` command — consistent across build.gradle.kts comment + README

**Placeholder scan**: No TBD/TODO/"add error handling" patterns.

**Known implementation risks** (flagged for executor awareness):
1. **Task 2 Step 3 `--write-locks` requires network access** — to resolve all dependencies from Maven Central. If the implementer is offline, this step fails. Workaround: commit `build.gradle.kts` change only; generate lockfile in CI first run (but STRICT mode would fail CI since no lockfile exists). Preferred: ensure network access during implementation.
2. **Task 3 Step 2 markdown code fence escaping** — when writing code fences inside a markdown file (the README), use normal triple-backticks. The plan shows escaped fences (` ``` `) because the plan itself is markdown — don't copy the escapes into the actual README.
3. **Task 4 Step 3 `gradle-test` may fail on first run** — Android SDK setup on Linux runner sometimes has transient failures (network, license acceptance). Re-run the job if transient.
4. **Task 4 Step 3 `govulncheck` may report vulns** — Phase 6 plan does NOT include dependency upgrades (deferred per spec section 1.1). If `govulncheck` reports vulns, the job will fail. The implementer should document the vulns in the PR description and either (a) accept as known failures and create follow-up task, or (b) escalate to controller for "fix-vuln-now" decision. Default: (a).
5. **Task 2 Step 2 `LockMode` import** — Kotlin DSL usually resolves `LockMode` without import (it's in scope from `dependencyLocking` block). If compiler errors, add `import org.gradle.api.artifacts.dsl.LockMode` at top of `build.gradle.kts`.

---

## Plan complete

Plan saved to `docs/superpowers/plans/2026-07-12-security-phase6-ci.md`.

4 tasks. Estimated effort: medium (Task 1 is YAML authoring; Task 2 requires generating lockfile + verifying STRICT; Task 3 is docs; Task 4 is the live CI verification which may surface dependency vulns).

Execution model recommendation:
- Task 1: standard model (YAML + understanding of GitHub Actions semantics)
- Task 2: standard model (Gradle DSL + lockfile generation requires network + judgment)
- Task 3: cheapest model (docs-only)
- Task 4: controller-driven (verification + possible triage of CI findings)
