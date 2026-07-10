# Security Round 29 — Phase 7: APK Signing Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent accidental release distribution with debug signing key (Chain-I) by failing fast without `keystore.properties`, and disable `adb backup` data extraction (Chain-J) by setting `allowBackup="false"`.

**Architecture:** Android-only. `build.gradle.kts` release signing block gains `-PallowDebugSigning` project property check: default `throw GradleException` with remediation guidance, opt-in fallback to debug.keystore with warn banner. `AndroidManifest.xml` flips `allowBackup` to `false`. `keystore.properties.example` + `README.md` document the new flag and signing workflow.

**Tech Stack:** Gradle Kotlin DSL / Android Gradle Plugin 8.x / AndroidManifest XML

**Source spec:** `docs/superpowers/specs/2026-07-10-security-phase7-apk-signing-design.md`

**Coverage:** T7-01 (Medium 6.1), T6-05 (Low 2.5) + mitigates Chain-I (High 7.7) and Chain-J (Medium 4.2)

## Global Constraints

- **No `keystore.properties` + no `-PallowDebugSigning=true` flag → `assembleRelease` MUST throw GradleException.** Default is fail-fast. (Spec section 5.1)
- **`-PallowDebugSigning=true` flag MUST be the ONLY escape hatch.** No env var detection (project has no CI), no auto-fallback. (Spec section 3.1)
- **`allowBackup="false"` in AndroidManifest.** No `dataExtractionRules` (redundant when allowBackup is false). (Spec section 3.1)
- **Debug builds (`assembleDebug`) MUST be unaffected.** The signing change is in `signingConfigs.release` only; debug uses its own config. (Spec section 6.3)
- **Existing Phase 1 Android tests MUST stay green.** `ServerConfigStoreAuthTokenTest`, `AuthInterceptorTest`, `OkHttpModuleTest` run on debug builds — unaffected by release signing changes.
- **No new third-party dependencies.**

---

## File Structure

| File | Type | Responsibility |
|---|---|---|
| `android/app/build.gradle.kts` | Modify | Release signing block: fail-fast + `-PallowDebugSigning` opt-in |
| `android/app/src/main/AndroidManifest.xml` | Modify | `android:allowBackup="true"` → `"false"` |
| `android/keystore.properties.example` | Modify | Document `-PallowDebugSigning` flag + warning |
| `README.md` | Modify | Add "### 3.1 Release 签名" section after "### 3. 编译 Android 客户端" |

---

## Task 1: Release signing fail-fast + opt-in flag

**Files:**
- Modify: `android/app/build.gradle.kts` (signing block, lines 29-63)

**Interfaces:**
- Produces: `-PallowDebugSigning=true` project property escape hatch; default `GradleException` on missing keystore.

- [ ] **Step 1: Read current signing block**

Read `android/app/build.gradle.kts` lines 14-63 to confirm the current `signingConfigs { create("release") { ... } }` structure matches the plan's assumption. Note the `keystoreProperties` loading at top (lines 14-18) stays unchanged.

- [ ] **Step 2: Replace the `else` branch of the signing block**

The current `else` branch (lines 41-61) unconditionally falls back to debug.keystore with a warn. Replace it with the fail-fast + opt-in logic. The full replacement block (lines 29-63 become):

```kotlin
    signingConfigs {
        create("release") {
            val keyAliasVal = keystoreProperties["keyAlias"] as String?
            val keyPasswordVal = keystoreProperties["keyPassword"] as String?
            val storeFileVal = keystoreProperties["storeFile"]?.let { rootProject.file(it) }
            val storePasswordVal = keystoreProperties["storePassword"] as String?

            if (keyAliasVal != null && storeFileVal?.exists() == true) {
                keyAlias = keyAliasVal
                keyPassword = keyPasswordVal
                storeFile = storeFileVal
                storePassword = storePasswordVal
            } else {
                // Phase 7: default fail-fast. Debug signing fallback is opt-in via
                // -PallowDebugSigning=true to prevent accidental release distribution
                // with the debug key (Chain-I: debug-signed APK can be resigned by
                // anyone, enabling supply-chain attacks).
                val allowDebugSigning = (project.findProperty("allowDebugSigning") as String?) == "true"
                if (!allowDebugSigning) {
                    throw GradleException(
                        "Release build requires a valid keystore.properties at the project root.\n" +
                        "To create one, copy keystore.properties.example to keystore.properties and fill in your release signing details.\n" +
                        "For LOCAL TESTING ONLY, run: ./gradlew assembleRelease -PallowDebugSigning=true\n" +
                        "Do NOT distribute a debug-signed APK publicly — it can be resigned by anyone."
                    )
                }
                val logger = org.gradle.api.logging.Logging.getLogger("LocalMediaHubSigning")
                logger.warn("==============================================================")
                logger.warn(" RELEASE BUILD IS USING THE DEBUG SIGNING KEY (explicitly opted in).")
                logger.warn(" Do NOT distribute this APK publicly.")
                logger.warn("==============================================================")
                keyAlias = "androiddebugkey"
                keyPassword = "android"
                storePassword = "android"
                storeFile = JFile("${System.getProperty("user.home")}/.android/debug.keystore")
            }
        }
    }
```

**Note**: The `if (keyAliasVal != null && storeFileVal?.exists() == true)` branch (lines 36-40) is UNCHANGED — only the `else` branch changes. Use Edit tool to replace just the `else { ... }` block.

- [ ] **Step 3: Verify compilation**

Run: `cd android && ./gradlew :app:help` (or any lightweight Gradle task that evaluates the build script)
Expected: No script evaluation errors. If `GradleException` is unresolved, add the import `import org.gradle.api.GradleException` at the top of the file (check existing imports first — `JFile` is already aliased).

- [ ] **Step 4: Verify fail-fast behavior (no keystore, no flag)**

First ensure no `keystore.properties` exists (it's gitignored, so likely absent):
```powershell
if (Test-Path android/keystore.properties) { Write-Host "present (rename to test)" } else { Write-Host "absent (expected)" }
```

Run: `cd android && ./gradlew assembleRelease 2>&1 | Select-Object -Last 20`
Expected: BUILD FAILED, output contains `Release build requires a valid keystore.properties` + 2 remediation options (configure keystore / use flag) + security warning.

- [ ] **Step 5: Verify opt-in behavior (no keystore + flag)**

Run: `cd android && ./gradlew assembleRelease -PallowDebugSigning=true 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL (may take 1-2 min), output contains `RELEASE BUILD IS USING THE DEBUG SIGNING KEY (explicitly opted in)` warn banner. APK produced at `app/build/outputs/apk/release/app-release.apk`.

- [ ] **Step 6: Verify debug build unaffected**

Run: `cd android && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL. Debug build does not evaluate the release signing block.

- [ ] **Step 7: Run unit tests to verify no regression**

Run: `cd android && ./gradlew testDebugUnitTest 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 8: Commit**

```bash
git add android/app/build.gradle.kts
git commit -m "feat(android): fail-fast release signing without keystore (Phase 7)"
```

---

## Task 2: Disable allowBackup

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml` (line 15)

**Interfaces:**
- Produces: `android:allowBackup="false"` — disables adb backup + cloud backup + device transfer.

- [ ] **Step 1: Modify AndroidManifest.xml**

Change line 15 from `android:allowBackup="true"` to `android:allowBackup="false"`:

```xml
<!-- Before -->
    <application
        android:name=".LocalMediaHubApplication"
        android:allowBackup="true"

<!-- After -->
    <application
        android:name=".LocalMediaHubApplication"
        android:allowBackup="false"
```

- [ ] **Step 2: Build to verify manifest is valid**

Run: `cd android && ./gradlew :app:assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL. Invalid manifest attribute would fail the build.

- [ ] **Step 3: Verify allowBackup=false in built APK**

Run:
```powershell
cd android
# Grep the merged manifest for allowBackup:
Select-String -Path "app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml" -Pattern "allowBackup"
```

Alternative — use `aapt2 dump xmltree`:
```powershell
cd android
& "$env:ANDROID_HOME\build-tools\$(Get-ChildItem $env:ANDROID_HOME\build-tools | Select-Object -Last 1)\aapt2.exe" dump xmltree --file AndroidManifest.xml app/build/outputs/apk/debug/app-debug.apk | Select-String -Pattern "allowBackup"
```

Expected: `android:allowBackup="false"`.

- [ ] **Step 4: Run unit tests (no regression)**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/AndroidManifest.xml
git commit -m "feat(android): disable allowBackup to prevent adb backup extraction (Phase 7)"
```

---

## Task 3: Document signing workflow + flag

**Files:**
- Modify: `android/keystore.properties.example`
- Modify: `README.md`

**Interfaces:**
- Consumes: Tasks 1-2.
- Produces: User-facing documentation for the new signing fail-fast + `-PallowDebugSigning` flag.

- [ ] **Step 1: Append to `keystore.properties.example`**

Read current `android/keystore.properties.example` (13 lines). Append at the end:

```
# Phase 7: release builds fail-fast without a valid keystore.properties.
# For LOCAL TESTING ONLY (e.g. quick release-APK on your own device),
# bypass with: ./gradlew assembleRelease -PallowDebugSigning=true
# Never distribute a debug-signed APK publicly — it can be resigned by anyone.
```

- [ ] **Step 2: Add "### 3.1 Release 签名" section to README.md**

Read `README.md` to find "### 3. 编译 Android 客户端" section (around line 195-205). Insert the new subsection IMMEDIATELY AFTER it (before "### 4. 连接" or the next `###` heading):

```markdown
### 3.1 Release 签名（发布前必读）

Release 构建默认要求有效的 `keystore.properties`，未配置时会**构建失败**（防止误用 debug 签名发布 APK，避免供应链攻击）。

**首次配置**：

1. 生成 keystore（一次性）：
   ```bash
   keytool -genkeypair -v -keystore localmediahub.keystore -alias localmediahub \
     -keyalg RSA -keysize 2048 -validity 10000
   ```

2. 复制示例配置并填入你的签名信息：
   ```bash
   cp android/keystore.properties.example android/keystore.properties
   # 编辑 android/keystore.properties，填入 storeFile/storePassword/keyAlias/keyPassword
   ```

3. 正常构建：
   ```bash
   cd android && ./gradlew assembleRelease
   ```

**仅本地调试**（不配 keystore，用 debug key）：

```bash
./gradlew assembleRelease -PallowDebugSigning=true
```

⚠️ **切勿公开分发 debug 签名的 APK**——任何人都能用相同 debug key 重签名发布"官方" APK（Chain-I 供应链攻击）。
```

- [ ] **Step 3: Verify README markdown renders (optional)**

If you have a markdown linter, run it. Otherwise, visually inspect the inserted section for correct heading level (`###` matches sibling sections) and code fence closure.

- [ ] **Step 4: Full regression — Android build + tests**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL. Docs-only changes shouldn't affect build, but verify.

- [ ] **Step 5: Commit**

```bash
git add android/keystore.properties.example README.md
git commit -m "docs: document release signing workflow and -PallowDebugSigning flag (Phase 7)"
```

---

## Self-Review

**Spec coverage** (against spec section 5):
- ✅ Fail-fast release signing (Task 1, spec 5.1)
- ✅ `-PallowDebugSigning` opt-in flag (Task 1, spec 5.1)
- ✅ `allowBackup="false"` (Task 2, spec 5.2)
- ✅ `keystore.properties.example` documentation (Task 3, spec 5.3)
- ✅ README "### 3.1 Release 签名" section (Task 3, spec 5.4)
- ✅ Debug builds unaffected (Task 1 Step 6, Task 2 Step 4)

**Type consistency**:
- `-PallowDebugSigning` flag name — consistent across Tasks 1, 3
- `allowDebugSigning` project property name — consistent in build.gradle.kts
- Error message text — consistent between Task 1 implementation and Task 3 README guidance

**Placeholder scan**: No TBD/TODO/"add error handling"/"similar to Task N" patterns. Every step contains complete code or exact commands.

**Known implementation risks** (flagged for executor awareness):
1. **Task 1 Step 4-5 requires no `keystore.properties` to exist** — the implementer must verify it's absent (gitignored, likely absent). If the developer has one locally, the fail-fast test won't trigger — temporarily rename it to test, then restore.
2. **Task 2 Step 3 `aapt` path** — the `ANDROID_HOME` env var may not be set. The fallback grep on `intermediates/merged_manifest/` is more reliable. If neither works, skip the static check and rely on the manifest edit being a single-character change (`true` → `false`) that's hard to get wrong.
3. **Task 3 Step 2 README insertion point** — the implementer must find the exact line after "### 3. 编译 Android 客户端" section's code block ends. Read the surrounding context to avoid inserting mid-code-fence.

---

## Plan complete

Plan saved to `docs/superpowers/plans/2026-07-10-security-phase7-apk-signing.md`.

Three tasks, Android-only. Estimated total effort: small (Task 1 is the core logic change ~30 lines; Tasks 2-3 are single-line + docs).

Execution model recommendation:
- Task 1: standard model (Gradle DSL logic + manual integration tests + potential GradleException import)
- Task 2: cheapest model (single-character manifest change + verification)
- Task 3: cheapest model (docs-only)
