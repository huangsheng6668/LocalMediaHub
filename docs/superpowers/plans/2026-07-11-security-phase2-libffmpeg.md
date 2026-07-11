# Security Round 29 — Phase 2: libffmpeg.so Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish SBOM documentation for `libffmpeg.so` (version, SHA256, configure flags, CVE audit) and add automated SHA256 verification at build time so any `.so` replacement requires a deliberate SBOM update.

**Architecture:** Android-only. Task 1 creates `docs/sbom/libffmpeg.md` + `docs/sbom/libffmpeg.sha256` + cross-references `BUILD_INSTRUCTIONS.md`, and performs a rapid CVE audit (FFmpeg 6.1.1 → current). Task 2 adds `verifyLibffmpegSha256` Gradle task hooked into `preBuild` that fails the build on hash mismatch.

**Tech Stack:** Gradle Kotlin DSL / FFmpeg 6.1.1 / `java.security.MessageDigest`

**Source spec:** `docs/superpowers/specs/2026-07-11-security-phase2-libffmpeg-design.md`

**Coverage:** T2-03a (High 8.6), T2-03b (High 8.6), T6-04 (High 8.0) + reduces Chain-D (High 8.6) uncertainty

## Global Constraints

- **SHA256 in `docs/sbom/libffmpeg.sha256` MUST match the actual `.so` hash** at commit time (else Task 2 fails). (Spec section 5.1.2)
- **`.sha256` file format MUST be `hash  path`** (sha256sum standard, two spaces, forward-slash path). (Spec section 5.1.2)
- **`verifyLibffmpegSha256` task MUST run at `preBuild` phase** so every Android build verifies integrity. (Spec section 5.2.1)
- **`.so` absent → task skips (not fails)** — hardware-decode-only builds are valid. (Spec section 5.2.1)
- **CVE audit MUST cover FFmpeg 6.1.1 (2023-11) → current** — list each CVE with project-affected yes/no + reason. (Spec section 5.1.4)
- **No new third-party dependencies.** SHA256 uses `java.security.MessageDigest` (JDK stdlib).

---

## File Structure

| File | Type | Responsibility |
|---|---|---|
| `docs/sbom/libffmpeg.md` | Create | SBOM document (product / source / build config / CVE audit / verify / rebuild flow) |
| `docs/sbom/libffmpeg.sha256` | Create | Single-line `hash  path` data file consumed by build.gradle.kts |
| `android/app/src/main/jniLibs/arm64-v8a/BUILD_INSTRUCTIONS.md` | Modify | Append cross-reference to SBOM |
| `android/app/build.gradle.kts` | Modify | Add `verifyLibffmpegSha256` task + `sha256()` helper + `preBuild` hook |

---

## Task 1: SBOM document + hash file + CVE audit

**Files:**
- Create: `docs/sbom/libffmpeg.md`
- Create: `docs/sbom/libffmpeg.sha256`
- Modify: `android/app/src/main/jniLibs/arm64-v8a/BUILD_INSTRUCTIONS.md`

**Interfaces:**
- Produces: SBOM document (human-readable) + hash file (machine-readable). Task 2's Gradle task will consume the hash file.

- [ ] **Step 1: Compute the actual SHA256 of the current `.so`**

Run from project root:
```bash
sha256sum android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so
```
Expected output (first field is the hash):
```
d6c53cf493b835cde01c857f6063b16b0084988835d7b597d2a755ed8ae3e5b1 *android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so
```
**Save the hash** — you'll need it for both `libffmpeg.sha256` and `libffmpeg.md`. If the hash differs from above (e.g. the `.so` was rebuilt since spec was written), use the actual hash.

- [ ] **Step 2: Create `docs/sbom/` directory and `libffmpeg.sha256`**

Create directory + file:
```bash
mkdir -p docs/sbom
```

Create `docs/sbom/libffmpeg.sha256` with the exact format (hash + two spaces + forward-slash path):
```
d6c53cf493b835cde01c857f6063b16b0084988835d7b597d2a755ed8ae3e5b1  android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so
```

Replace the hash with the actual value from Step 1 if different.

- [ ] **Step 3: Create `docs/sbom/libffmpeg.md`**

Create the SBOM document with these sections (fill in actual values from Step 1 + CVE audit in Step 5):

```markdown
# SBOM: libffmpeg.so

## 产物

- 文件：`android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so`
- 大小：1,575,256 bytes（~1.5 MB）
- SHA256：`d6c53cf493b835cde01c857f6063b16b0084988835d7b597d2a755ed8ae3e5b1`
- 架构：arm64-v8a（仅此架构；其他 ABI 不带此库）

## 上游来源

- 项目：FFmpeg
- 版本：6.1.1（2023-11 发布）
- 源码位置：`build/ffmpeg-src/ffmpeg-6.1.1/`
- 官方下载：https://ffmpeg.org/releases/ffmpeg-6.1.1.tar.xz
- 官方安全公告：https://ffmpeg.org/security.html
- 许可证：LGPL v2.1+（详见 `build/ffmpeg-src/ffmpeg-6.1.1/COPYING.LGPLv2.1`）

## 构建配置

完整 configure flags 见 `android/app/src/main/jniLibs/arm64-v8a/BUILD_INSTRUCTIONS.md`。

关键安全相关 flags：
- `--disable-everything` + 显式 enable 指定 decoder/demuxer/parser
- `--disable-network`：禁用所有网络协议（http/rtmp/ftp 等）
- `--disable-autodetect`：禁用自动检测外部库
- `--enable-jni` + `--enable-mediacodec`：Android 集成

启用的 decoder：h264, hevc, vp8, vp9, av1, mpeg4, vc1, h263
启用的 demuxer：mkv, avi, flv, asf, ts, mov
启用的 parser：h264, hevc, vp8, vp9, av1, mpeg4video, vc1, h263

## CVE 审计

- 审计日期：<YYYY-MM-DD，填实际日期>
- 审计范围：FFmpeg 6.1.1（2023-11）→ 当前审计日期，期间修复的 CVE
- 数据来源：https://ffmpeg.org/security.html
- 审计方法：对照项目 configure flags 启用的 component（h264/hevc/vp8/vp9/av1/mpeg4/vc1/h263 decoder + mkv/avi/flv/asf/ts/mov demuxer），判断每个 CVE 是否影响项目

### 审计结果

<Fill in from Step 5 CVE audit. Format:>

| CVE | 严重度 | 影响组件 | 项目是否受影响 | 原因 |
|---|---|---|---|---|
| CVE-xxxx-xxxxx | High/Medium/Low | decoder/demuxer 名 | yes/no | 配置启用/未启用 + 攻击路径分析 |

</Fill in>

## 验证

SHA256 校验由 `android/app/build.gradle.kts:verifyLibffmpegSha256` task 自动执行（preBuild 阶段）。
若 `.so` 哈希与 `docs/sbom/libffmpeg.sha256` 不匹配，构建失败。

## 重新构建流程

当 `.so` 需要更新（如 FFmpeg 版本升级、configure flags 变更）时：

1. 按 `BUILD_INSTRUCTIONS.md` 重新编译，产出新 `libffmpeg.so`
2. 计算新 SHA256：`sha256sum android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so`
3. 更新 `docs/sbom/libffmpeg.sha256`（hash + path 格式）
4. 更新本文档的"产物"（SHA256）+ "CVE 审计"（重新审计）章节
5. 提交所有变更
```

- [ ] **Step 4: Append SBOM cross-reference to BUILD_INSTRUCTIONS.md**

Read `android/app/src/main/jniLibs/arm64-v8a/BUILD_INSTRUCTIONS.md` (92 lines currently). Append at the end:

```markdown

## SBOM 与完整性校验

本库的 SBOM（版本、SHA256、CVE 审计）见 `docs/sbom/libffmpeg.md`。
构建时由 `build.gradle.kts:verifyLibffmpegSha256` 自动校验 SHA256。
```

- [ ] **Step 5: CVE rapid audit**

Use WebFetch to access `https://ffmpeg.org/security.html`. Identify CVEs fixed in FFmpeg versions **after** 6.1.1 (i.e., fixed in 6.1.1.x patch releases or 6.1.2+ / 7.x — these are CVEs that 6.1.1 does NOT have a fix for).

For each CVE, determine project impact:
1. Read the CVE description to identify the affected component (decoder / demuxer / parser / protocol / filter name).
2. Cross-reference against the project's enabled components list:
   - **Decoders enabled**: h264, hevc, vp8, vp9, av1, mpeg4, vc1, h263
   - **Demuxers enabled**: mkv, avi, flv, asf, ts, mov
   - **Parsers enabled**: h264, hevc, vp8, vp9, av1, mpeg4video, vc1, h263
   - **Network protocols**: NONE (`--disable-network`)
3. If the CVE affects an enabled component → "yes" (project impacted)
4. If the CVE affects a component NOT in the enabled list → "no" (project not impacted)
5. Special cases:
   - CVE in libavutil / libswscale (shared infrastructure) → "yes" (likely impacts all builds)
   - CVE requires network protocol → "no" (project disabled network)
   - CVE in encoder → "no" (project only decodes)

Fill the "审计结果" table in `docs/sbom/libffmpeg.md`:

| CVE | 严重度 | 影响组件 | 项目是否受影响 | 原因 |
|---|---|---|---|---|
| (CVE ID) | (High/Med/Low from NVD) | (decoder/demux/etc.) | (yes/no) | (one-sentence rationale) |

**Fallback if WebFetch fails**: replace the table with:
```markdown
### 审计结果

**状态：待人工审计**

WebFetch 未能获取 https://ffmpeg.org/security.html。请手动执行：
1. 访问 https://ffmpeg.org/security.html
2. 筛选 6.1.1 之后修复的 CVE
3. 对照项目启用的 component 列表（见"构建配置"章节）判断是否受影响
4. 回填本表

最后更新：<YYYY-MM-DD>
```

- [ ] **Step 6: Verify hash file matches actual `.so`**

Run:
```bash
cd E:/github_project/LocalMediaHub
sha256sum -c docs/sbom/libffmpeg.sha256
```
Expected: `android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so: OK`

If FAIL, the hash in `.sha256` is wrong — recompute via Step 1 and fix.

- [ ] **Step 7: Commit**

```bash
git add docs/sbom/libffmpeg.md docs/sbom/libffmpeg.sha256 \
        android/app/src/main/jniLibs/arm64-v8a/BUILD_INSTRUCTIONS.md
git commit -m "docs(sbom): establish libffmpeg.so SBOM + CVE audit (Phase 2)"
```

---

## Task 2: SHA256 verification Gradle task

**Files:**
- Modify: `android/app/build.gradle.kts`

**Interfaces:**
- Consumes: `docs/sbom/libffmpeg.sha256` (from Task 1).
- Produces: `verifyLibffmpegSha256` task that runs at `preBuild` and fails on hash mismatch.

- [ ] **Step 1: Read current build.gradle.kts to find insertion point**

Read `android/app/build.gradle.kts`. Find a sensible location for the new task — typically after `signingConfigs { ... }` block (around line 63) or near the existing `android { ... }` close. The task definition goes at the **top level** of the build script (NOT inside `android { }`).

- [ ] **Step 2: Add `verifyLibffmpegSha256` task + `sha256()` helper**

Insert the following at the top level of `android/app/build.gradle.kts` (e.g. after the `android { ... }` block ends, before `dependencies { ... }`):

```kotlin
// Phase 2: Verify libffmpeg.so integrity against docs/sbom/libffmpeg.sha256.
// Runs at preBuild phase; fails the build if the .so was replaced without
// updating the SBOM. To update: rebuild .so per BUILD_INSTRUCTIONS.md,
// recompute sha256, update docs/sbom/libffmpeg.sha256 + docs/sbom/libffmpeg.md.
tasks.register("verifyLibffmpegSha256") {
    group = "verification"
    description = "Verify libffmpeg.so SHA256 matches docs/sbom/libffmpeg.sha256"

    val soFile = file("${projectDir}/src/main/jniLibs/arm64-v8a/libffmpeg.so")
    val hashFile = rootProject.file("docs/sbom/libffmpeg.sha256")

    // Skip if .so absent (e.g. building without ffmpeg support — hardware decode only).
    if (!soFile.exists()) {
        logger.lifecycle("verifyLibffmpegSha256: libffmpeg.so absent, skipping")
        return@register
    }

    doLast {
        val actualHash = sha256(soFile)
        val expectedLine = hashFile.readText().trim().lines().firstOrNull()
            ?: throw GradleException("docs/sbom/libffmpeg.sha256 is empty")
        val expectedHash = expectedLine.split(Regex("\\s+"))[0]

        if (!actualHash.equals(expectedHash, ignoreCase = true)) {
            throw GradleException(
                "libffmpeg.so SHA256 mismatch!\n" +
                "  actual:   $actualHash\n" +
                "  expected: $expectedHash\n" +
                "If you intentionally rebuilt libffmpeg.so, update docs/sbom/libffmpeg.sha256 " +
                "and docs/sbom/libffmpeg.md per BUILD_INSTRUCTIONS.md."
            )
        }
        logger.lifecycle("verifyLibffmpegSha256: OK ($actualHash)")
    }
}

// Helper: compute SHA256 of a file using java.security.MessageDigest.
fun sha256(file: File): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

// Hook into preBuild so the check runs before every Android build.
project.tasks.named("preBuild").configure {
    dependsOn("verifyLibffmpegSha256")
}
```

**Note**: `File`, `ByteArray` are Kotlin built-ins (no import needed). `java.security.MessageDigest` is fully qualified (no import). `GradleException` — check if it's imported; if not, add `import org.gradle.api.GradleException` at the top of the file (alongside existing imports).

- [ ] **Step 3: Verify the task runs successfully against current `.so`**

Run:
```bash
cd android
./gradlew verifyLibffmpegSha256
```
Expected: BUILD SUCCESSFUL, output contains `verifyLibffmpegSha256: OK (<hash>)`.

If FAIL with "hash mismatch", Task 1's `.sha256` is stale — recompute and update.

- [ ] **Step 4: Integration test — modify `.so` and verify build fails**

Back up the `.so`, append a byte, run task, expect failure, restore:

```bash
cd E:/github_project/LocalMediaHub/android/app/src/main/jniLibs/arm64-v8a
cp libffmpeg.so libffmpeg.so.bak
echo "x" >> libffmpeg.so   # append one byte to change hash
cd E:/github_project/LocalMediaHub/android
./gradlew verifyLibffmpegSha256 2>&1 | tail -10
# Expected: BUILD FAILED with "SHA256 mismatch" + actual/expected hashes
cd app/src/main/jniLibs/arm64-v8a
mv libffmpeg.so.bak libffmpeg.so   # restore
```

If `tail` is unavailable on Windows bash, omit it and read full output.

- [ ] **Step 5: Verify preBuild hook actually runs the task**

Run a normal build and confirm `verifyLibffmpegSha256` appears in task list:
```bash
cd android
./gradlew assembleDebug --dry-run 2>&1 | grep verifyLibffmpegSha256
```
Expected: line like `:app:verifyLibffmpegSha256` in the dry-run task graph.

Then run actual build:
```bash
./gradlew assembleDebug 2>&1 | grep -i "verifyLibffmpeg\|SHA256"
```
Expected: `verifyLibffmpegSha256: OK (<hash>)` line appears during build.

- [ ] **Step 6: Verify `.so` absent case (skip, not fail)**

```bash
cd E:/github_project/LocalMediaHub/android/app/src/main/jniLibs/arm64-v8a
mv libffmpeg.so libffmpeg.so.tmp
cd E:/github_project/LocalMediaHub/android
./gradlew verifyLibffmpegSha256 2>&1 | tail -5
# Expected: "libffmpeg.so absent, skipping" + BUILD SUCCESSFUL
cd app/src/main/jniLibs/arm64-v8a
mv libffmpeg.so.tmp libffmpeg.so   # restore
```

- [ ] **Step 7: Run full Android test suite + build**

```bash
cd android
./gradlew testDebugUnitTest assembleDebug 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 8: Commit**

```bash
git add android/app/build.gradle.kts
git commit -m "feat(android): verify libffmpeg.so SHA256 at preBuild (Phase 2)"
```

---

## Self-Review

**Spec coverage** (against spec section 5):
- ✅ Task 1: SBOM doc + hash file + CVE audit + BUILD_INSTRUCTIONS cross-ref (spec 5.1)
- ✅ Task 2: verifyLibffmpegSha256 task + preBuild hook (spec 5.2)
- ✅ Hash file format `hash  path` (spec 5.1.2)
- ✅ `.so` absent → skip not fail (spec 5.2.1)
- ✅ CVE audit covers 6.1.1 → current (spec 5.1.4)

**Type consistency**:
- `verifyLibffmpegSha256` task name — consistent across Task 2 + BUILD_INSTRUCTIONS.md cross-ref
- `docs/sbom/libffmpeg.sha256` path — consistent across Task 1 (creates) + Task 2 (reads)
- Hash format identical in `.sha256` file and `.md` document

**Placeholder scan**: No TBD/TODO/"add error handling" patterns in code. The `<YYYY-MM-DD>` and `<fill in from Step 5>` placeholders in Task 1 Step 3 are intentional — the implementer fills them during execution (CVE audit + date). These are not plan placeholders.

**Known implementation risks** (flagged for executor awareness):
1. **Task 1 Step 1 SHA256 value**: The spec was written with hash `d6c53cf493b835cde01c857f6063b16b0084988835d7b597d2a755ed8ae3e5b1`. If the `.so` has been rebuilt since, the actual hash differs. Step 1 re-computes — use the actual value, not the spec's.
2. **Task 1 Step 5 WebFetch failure**: ffmpeg.org may be unreachable or the page structure may not parse cleanly. The fallback "待人工审计" template is provided — use it if WebFetch fails.
3. **Task 2 Step 2 `GradleException` import**: Check existing imports in `build.gradle.kts` (around lines 1-13). If `org.gradle.api.GradleException` is not imported, add it.
4. **Task 2 Step 4 Windows bash**: `cp`/`mv`/`echo >>` work in Git Bash. On PowerShell, use `Copy-Item`/`Move-Item`/`Add-Content`.
5. **Task 2 Step 5 dry-run output**: `--dry-run` lists tasks that WOULD run. If `verifyLibffmpegSha256` doesn't appear, the `preBuild` dependency wasn't wired correctly — re-check the `project.tasks.named("preBuild").configure { dependsOn(...) }` block.

---

## Plan complete

Plan saved to `docs/superpowers/plans/2026-07-11-security-phase2-libffmpeg.md`.

Two tasks. Estimated total effort: medium (Task 1 is research-heavy: CVE audit via WebFetch + document writing; Task 2 is Gradle DSL + integration tests on Windows).

Execution model recommendation:
- Task 1: standard model (CVE audit requires judgment + cross-referencing configure flags; WebFetch interpretation)
- Task 2: standard model (Gradle Kotlin DSL + integration test execution + Windows bash quirks)
