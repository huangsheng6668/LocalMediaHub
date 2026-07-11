# Security Round 29 — Phase 7: APK Signing Hardening Design

> **日期**：2026-07-10
> **范围**：Android 客户端签名与备份配置
> **威胁模型**：局域网半可信（继承 Round 29 主 spec）
> **依赖**：Phase 1（Bearer Token 鉴权层，已合并于 commit `76b6d51`）；Phase 3（config 默认安全，已合并于 commit `36f9c9a`）
> **审计轮次**：Round 29 Phase 7
> **源 spec**：`docs/superpowers/specs/2026-07-10-security-audit-design.md`（section 5.7）

---

## 0. 摘要

Phase 7 落实主 spec 第 5.7 节的"APK 签名加固"修复，针对两条攻击链：

- **Chain-I（CVSS 7.7 High）**：release 构建签名 fallback 到 `debug.keystore`——任何人能用相同 debug key 重签名发布"官方" APK，用户安装"更新"后设备 RCE。是供应链投毒的根因。
- **Chain-J（CVSS 4.2 Medium）**：`AndroidManifest.xml:15` `allowBackup="true"`——`adb backup` 可提取 `ServerConfigStore`（PC 内网 IP + token）+ 收藏 + 播放进度。

**核心改动**：
1. **签名 fail-fast**：无 `keystore.properties` 时 `assembleRelease` 默认 `throw GradleException`，列出 3 种解决方式。
2. **`-PallowDebugSigning=true` 逃生口**：本地调试时显式 opt-in debug.keystore fallback + 警告横幅。
3. **`allowBackup="false"`**：禁用 `adb backup` + Android 12+ cloud backup + device transfer。
4. **文档强化**：`keystore.properties.example` 加 flag 说明；README 加"release 构建签名指引"小节。

**Why now**：Phase 1 的 token 鉴权 + Phase 3 的 config 默认安全已堵住服务端 LAN 攻击面；Phase 7 转向客户端供应链——Chain-I 是仅次于 Chain-D（libffmpeg RCE）的高危链，且修复成本低（~30 行改动）。

---

## 1. 范围与方法论

### 1.1 范围

仅 Android 客户端签名与备份配置。服务端、Web UI 不动。

### 1.2 方法论

继承 Round 29 主 spec 的"威胁建模 → 代码验证 → 修复"流程。本 spec 只覆盖 Phase 7 的实施细节；威胁清单与攻击链见主 spec。

### 1.3 局限性

本 spec 不覆盖：
- Play Store 发布签名（项目当前未上架；用本地 keystore 即可）
- APK完整性校验（如 V2/V3 signature scheme）——AGP 默认启用，无需改动
- `dataExtractionRules` 精细 backup 控制——`allowBackup="false"` 已一刀切禁用，未来若需部分 backup 再引入

---

## 2. 威胁与缓解对照

| 发现 ID | CVSS | 描述 | Phase 7 缓解 |
|---|---|---|---|
| **T7-01** | 6.1 Medium | release 签名 fallback debug.keystore | 默认 fail-fast；`-PallowDebugSigning` 显式 opt-in |
| **T6-05** | 2.5 Low | `allowBackup="true"` 可被 `adb backup` 提取 | `allowBackup="false"` |

**攻击链缓解**：
- **Chain-I**（CVSS 7.7 High）：debug 签名 → APK 投毒 → 设备 RCE。Phase 7 fail-fast 消除 debug 签名 release APK 的可能性（除非显式 opt-in，且有警告横幅）。**根因消除**。
- **Chain-J**（CVSS 4.2 Medium）：`allowBackup` + 物理访问 → 提取 ServerConfigStore。Phase 7 关闭 backup 后，`adb backup` 拒绝或返回空。**根因消除**。

---

## 3. 设计决策

### 3.1 已确认决策（来自 brainstorming）

| 决策 | 选择 | 理由 |
|---|---|---|
| 签名强制触发条件 | **命令行 flag `-PallowDebugSigning=true`** | 默认安全（fail）；本地调试显式 opt-in；未来加 CI 不传 flag 即 fail |
| `allowBackup` 处理 | **只关 `allowBackup="false"`** | 一行改动覆盖所有 API level；`dataExtractionRules` 在 `allowBackup=false` 时冗余 |
| flag 命名 | **`allowDebugSigning`** | 语义清晰，与 `allowBackup` 命名风格一致 |
| 错误消息引导至 `keystore.properties.example` | **是** | 降低新开发者上手成本（`keytool` 命令已在 example 文件中提供） |
| README 签名指引位置 | **"### 3. 编译 Android 客户端"之后** | 上下文连贯 |

### 3.2 兼容性矩阵

| 现状 | Phase 7 后行为 | 破坏性 |
|---|---|---|
| 开发者已配 `keystore.properties` | 签名正常（用 release key） | 无 |
| 开发者未配 keystore，直接 `assembleRelease` | **构建失败**，错误消息指引配置 | **有**（需配 keystore 或加 flag） |
| 开发者未配 keystore + `-PallowDebugSigning=true` | 用 debug.keystore + 警告横幅 | 无（仅本地调试） |
| 用户 `adb backup` 提取应用数据 | **拒绝或返回空** | 有（用户无法 backup，但个人项目可接受） |
| 用户卸载重装 | DataStore 数据丢失（PC IP / token / 收藏 / 进度） | 有（需重输，README 说明） |

---

## 4. 修改清单

### 4.1 代码

| 文件 | 改动 | Task |
|---|---|---|
| `android/app/build.gradle.kts` | 签名块改造：默认 fail-fast + `-PallowDebugSigning` opt-in | 1 |
| `android/app/src/main/AndroidManifest.xml` | `android:allowBackup="true"` → `"false"` | 2 |

### 4.2 文档

| 文件 | 改动 | Task |
|---|---|---|
| `android/keystore.properties.example` | 加 `-PallowDebugSigning` 说明 + 强调"仅本地调试" | 3 |
| `README.md` | 加"release 构建签名指引"小节（紧跟"### 3. 编译 Android 客户端"） | 3 |

---

## 5. 实施细节

### 5.1 `build.gradle.kts` 签名块改造

替换现有 `signingConfigs { create("release") { ... } }` 块（lines 29-63）。逻辑：

1. 先尝试从 `keystore.properties` 读 `keyAlias` / `storeFile`（不变）。
2. 若 `keyAlias != null && storeFile.exists()` → 用 release key（不变）。
3. 否则：
   - 读 `project.findProperty("allowDebugSigning")`。
   - 若非 `"true"` → `throw GradleException(...)`，消息列出 2 种解决方式 + 安全警告。
   - 若 `"true"` → 保留现有 debug.keystore fallback + warn 横幅。

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

### 5.2 `AndroidManifest.xml` 改动

单行改动：

```xml
<!-- Before -->
<application
    android:name=".LocalMediaHubApplication"
    android:allowBackup="true"
    ...

<!-- After -->
<application
    android:name=".LocalMediaHubApplication"
    android:allowBackup="false"
    ...
```

### 5.3 `keystore.properties.example` 文档强化

在现有内容末尾追加：

```
# Phase 7: release builds fail-fast without a valid keystore.properties.
# For LOCAL TESTING ONLY (e.g. quick release-APK on your own device),
# bypass with: ./gradlew assembleRelease -PallowDebugSigning=true
# Never distribute a debug-signed APK publicly — it can be resigned by anyone.
```

### 5.4 README "release 构建签名指引" 小节

紧跟"### 3. 编译 Android 客户端"之后插入：

```markdown
### 3.1 Release 签名（发布前必读）

Release 构建默认要求有效的 `keystore.properties`，未配置时会**构建失败**（防止误用 debug 签名发布 APK）。

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

---

## 6. 测试方案

### 6.1 自动化测试

Gradle 签名逻辑难以单元测试（涉及 `Project.findProperty` + `GradleException`）。改用集成测试：

| 测试 | 命令 | 期望 |
|---|---|---|
| 无 keystore + 默认 → 构建失败 | `cd android && ./gradlew assembleRelease`（确保无 `keystore.properties`） | BUILD FAILED，错误消息含"Release build requires a valid keystore.properties" |
| 无 keystore + `-PallowDebugSigning=true` → 构建成功 | `./gradlew assembleRelease -PallowDebugSigning=true` | BUILD SUCCESSFUL + warn 横幅 |
| `allowBackup=false` 生效 | `aapt2 dump xmltree --file AndroidManifest.xml app/build/outputs/apk/debug/app-debug.apk \| grep -i allowBackup` 或 `grep allowBackup` merged manifest | 输出含 `allowBackup=false` |

### 6.2 手动验证

| 测试 | 步骤 |
|---|---|
| `adb backup` 拒绝 | `adb backup -f /tmp/test.ab -noapk com.juziss.localmediahub` → 期望"LocalMediaHub" 不在可 backup 列表，或 backup 文件为空 |
| 卸载重装丢数据 | 安装 release APK → 配置 server IP + token + 收藏 → 卸载 → 重装 → 期望配置全空 |

### 6.3 回归测试

- `cd android && ./gradlew testDebugUnitTest assembleDebug` — debug 构建不受签名改动影响，必须仍 green。
- 现有 Phase 1 Android 测试（`ServerConfigStoreAuthTokenTest` / `AuthInterceptorTest` / `OkHttpModuleTest`）必须仍 green。

---

## 7. 风险与权衡

| 风险 | 影响 | 缓解 |
|---|---|---|
| 新开发者首次 clone 无法 `assembleRelease` | 低 | README 文档 + 错误消息列出 3 种解决方式 |
| 现有开发者习惯无 keystore 直接 build | 低（个人项目） | `-PallowDebugSigning` flag 保留逃生口 |
| `allowBackup=false` 导致用户换设备丢失配置 | 中 | 个人项目可接受；README 说明；未来若需 backup 可改用 `dataExtractionRules` 精细控制 |
| CI 环境无 keystore 会导致 release 构建失败 | 低（当前无 CI） | 未来加 CI 时配置 secrets 注入 keystore |
| `adb backup` 测试依赖设备/模拟器 | 低 | 可用 `aapt dump` 替代（纯静态检查 manifest） |

---

## 8. 验证完成标准

- ✅ 无 `keystore.properties` + `./gradlew assembleRelease` → BUILD FAILED + 错误消息含 2 种解决方式（配 keystore / 加 flag）+ 安全警告
- ✅ 无 `keystore.properties` + `./gradlew assembleRelease -PallowDebugSigning=true` → BUILD SUCCESSFUL + warn 横幅
- ✅ 有 `keystore.properties` → BUILD SUCCESSFUL（用 release key，不论 flag）
- ✅ `aapt2 dump xmltree` 或 merged manifest grep 确认 `allowBackup=false`
- ✅ `cd android && ./gradlew testDebugUnitTest assembleDebug` 全 green（debug 不受影响）
- ✅ Phase 1 Android 测试无回归

---

## 9. 后续 Phase 衔接

Phase 7 完成后，剩余 Phase（按主 spec 优先级）：

| Phase | 内容 | 备注 |
|---|---|---|
| Phase 2 | libffmpeg.so 审计 + SBOM | 阻断 Chain-D（唯一 RCE 链） |
| Phase 4 | HTTP 加固（安全头 + 可选 TLS） | 独立 |
| Phase 5 | Web UI XSS 整改（残余风险低） | 主 spec 已审计为"当前字段已覆盖" |
| Phase 6 | 供应链扫描 + 依赖升级 | 独立 |
| Phase 8 | 杂项 P2（rate limit / blockedSegments 扩充 / PUT 校验加强等） | 独立 |

---

## 文档信息

- **创建日期**：2026-07-10
- **审计轮次**：Round 29 Phase 7
- **依赖**：Phase 1（commit `76b6d51`）、Phase 3（commit `36f9c9a`）
- **方法论**：brainstorming skill（澄清 → 方案 → 设计）
- **下一步**：经用户审核后，调用 writing-plans skill 转为实施计划
