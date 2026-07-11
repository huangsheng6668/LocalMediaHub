# Security Round 29 — Phase 2: libffmpeg.so Audit Design

> **日期**：2026-07-11
> **范围**：Android `libffmpeg.so` 来源审计 + SBOM + SHA256 自动校验
> **威胁模型**：局域网半可信（继承 Round 29 主 spec）
> **依赖**：Phase 1（Bearer Token 鉴权层，commit `76b6d51`）
> **审计轮次**：Round 29 Phase 2
> **源 spec**：`docs/superpowers/specs/2026-07-10-security-audit-design.md`（section 5.2）

---

## 0. 摘要

Phase 2 落实主 spec 第 5.2 节的"libffmpeg 审计与文档化"修复。**探索阶段发现**主 spec 的 3 项假设已过时：

| 主 spec 假设 | 实际现状 |
|---|---|
| "libffmpeg.so 来源未记录" | ✅ `BUILD_INSTRUCTIONS.md` 已存在（FFmpeg 6.1.1 + NDK 构建 + 完整 configure flags） |
| "无协议白名单" | ✅ 已配置 `--disable-everything` + 显式 enable + `--disable-network` + `--disable-autodetect` |
| "无 SHA256 校验 + 无 SBOM" | ❌ 仍缺失 |

**所以 Phase 2 实际工作量比主 spec 估的小**——来源已追溯，协议已白名单。剩余工作：

1. **Task 1**：建立 SBOM 文档（`docs/sbom/libffmpeg.md`）+ 哈希文件（`docs/sbom/libffmpeg.sha256`）+ 完成 CVE 快速审计
2. **Task 2**：build.gradle.kts 加 SHA256 自动校验 task（`preBuild` 阶段）

**核心价值**：
- 阻断 Chain-D（CVSS 8.6 High，唯一 Android RCE 链）的"FFmpeg CVE 未知"风险
- 任何 `.so` 替换（开发者重新编译 / 攻击者投毒）必须同步更新 SBOM，强制有意识行为
- CVE 审计把"假设 FFmpeg 有 CVE"转为"已知哪些 CVE 项目实际受影响"

---

## 1. 范围与方法论

### 1.1 范围

仅 Android `libffmpeg.so`。**不含**：
- `liblocalmedia_native.so`（Rust crate 自建，不在 Phase 2 范围；可作 Phase 2.5 follow-up）
- 其他 ABI（x86_64 等，项目当前仅 arm64-v8a）
- 运行时完整性校验（APK 签名 Phase 7 已覆盖）

### 1.2 方法论

继承 Round 29 主 spec 流程。CVE 审计采用"快速检查"策略（澄清问题 3 决策 C）：查 FFmpeg 6.1.1 之后修复的 CVE，对照项目 configure flags 判断是否受影响。

### 1.3 探索阶段已确认的事实

- **文件**：`android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so`，1,575,256 bytes
- **当前 SHA256**：`d6c53cf493b835cde01c857f6063b16b0084988835d7b597d2a755ed8ae3e5b1`
- **来源**：FFmpeg 6.1.1（源码在 `build/ffmpeg-src/ffmpeg-6.1.1/`，99MB 含 LICENSE）
- **构建配置**：`BUILD_INSTRUCTIONS.md` 详细文档化，含安全相关 flags
- **加载方式**：由 Media3 ExoPlayer FFmpeg extension 通过反射加载（项目代码不直接 `loadLibrary("ffmpeg")`）
- **缺失项**：SHA256 自动校验 + SBOM 文档

---

## 2. 威胁与缓解对照

| 发现 ID | CVSS | 描述 | Phase 2 缓解 |
|---|---|---|---|
| **T2-03a** | 8.6 High | `libffmpeg.so` 无来源/SHA256/版本 | SBOM 文档 + SHA256 自动校验 |
| **T2-03b** | 8.6 High | FFmpeg 历史 CVE；毒视频可 RCE Android | CVE 快速审计 + 文档化结果 |
| **T6-04** | 8.0 High | 预编译来源未记录 | 来源已在 BUILD_INSTRUCTIONS.md；SBOM 补充 SHA256 + CVE 状态 |

**攻击链缓解**：
- **Chain-D**（CVSS 8.6 High）：伪 mDNS → Android 连错 server → 下发毒 mp4 → libffmpeg CVE → Android RCE。Phase 2 不直接阻断链（需 Phase 1 mDNS 鉴权），但**显著降低"FFmpeg CVE 未知"不确定性**：
  - CVE 审计列出项目实际受影响的 CVE，攻击者无法依赖"未知 CVE"
  - SHA256 校验确保 `.so` 不被悄悄替换（开发阶段）

---

## 3. 设计决策

### 3.1 已确认决策（来自 brainstorming）

| 决策 | 选择 | 理由 |
|---|---|---|
| SHA256 校验时机 | **构建时静态校验**（`preBuild` 阶段） | 开发者替换 `.so` 立刻发现；CI 自动覆盖 |
| SBOM 文档形态 | **双文件**（`docs/sbom/libffmpeg.md` + `docs/sbom/libffmpeg.sha256`） | 哈希文件纯数据，易被脚本消费；SBOM 文档面向人 |
| CVE 审计策略 | **快速检查**（限 6.1.1 之后修复的 CVE） | 工作量可控（~30 分钟）；聚焦项目实际暴露面 |
| CVE 审计执行时机 | **plan 执行时**（Task 1 内） | brainstorming 聚焦设计；审计是研究工作 |
| `.sha256` 文件格式 | **含文件名后缀**（`hash  path`） | `sha256sum -c` 兼容；path 用 forward slash |
| BUILD_INSTRUCTIONS.md 加交叉引用 | **是**（一行链接到 SBOM） | 文档互联，避免单边视角 |

### 3.2 兼容性

| 现状 | Phase 2 后行为 | 破坏性 |
|---|---|---|
| 开发者构建 Android APK | `preBuild` 自动校验 SHA256（~10ms） | 无 |
| 开发者重新编译 `libffmpeg.so`（如升级 FFmpeg） | 必须更新 `.sha256` 文件 + SBOM.md，否则构建失败 | 有（强制流程，desired behavior） |
| 开发者首次 clone + 直接 build | `.so` 与 `.sha256` 都在仓库，校验通过 | 无 |
| `libffmpeg.so` 缺失（如硬件解码 only 构建） | task skip + 日志提示 | 无 |

---

## 4. 修改清单

| 文件 | 改动 | Task |
|---|---|---|
| `docs/sbom/libffmpeg.md` | 新建：完整 SBOM 文档（版本/来源/configure flags/SHA256/CVE 审计） | 1 |
| `docs/sbom/libffmpeg.sha256` | 新建：纯哈希数据文件（含文件名后缀） | 1 |
| `android/app/src/main/jniLibs/arm64-v8a/BUILD_INSTRUCTIONS.md` | 末尾加交叉引用链接到 SBOM | 1 |
| `android/app/build.gradle.kts` | 新增 `verifyLibffmpegSha256` task + `sha256()` helper + `preBuild` 钩子 | 2 |

---

## 5. 实施细节

### 5.1 Task 1：SBOM 文档 + 哈希文件 + CVE 审计

#### 5.1.1 `docs/sbom/libffmpeg.md` 结构

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

- 审计日期：YYYY-MM-DD（Task 1 执行时填）
- 审计范围：FFmpeg 6.1.1（2023-11）→ 当前审计日期，期间修复的 CVE
- 数据来源：https://ffmpeg.org/security.html
- 审计方法：对照项目 configure flags 启用的 component，判断每个 CVE 是否影响项目

### 审计结果

| CVE | 严重度 | 影响组件 | 项目是否受影响 | 原因 |
|---|---|---|---|---|
| CVE-xxxx-xxxxx | High/Medium/Low | decoder/demuxer 名 | yes/no | 配置启用/未启用 + 攻击路径分析 |

（若审计后无受影响 CVE，标注"无受影响 CVE"+ 审计日期）

## 验证

SHA256 校验由 `android/app/build.gradle.kts:verifyLibffmpegSha256` task 自动执行（preBuild 阶段）。
若 `.so` 哈希与 `docs/sbom/libffmpeg.sha256` 不匹配，构建失败。

## 重新构建流程

当 `.so` 需要更新（如 FFmpeg 版本升级、configure flags 变更）时：
1. 按 `BUILD_INSTRUCTIONS.md` 重新编译，产出新 `libffmpeg.so`
2. 计算新 SHA256：`sha256sum android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so`
3. 更新 `docs/sbom/libffmpeg.sha256`（hash + path 格式）
4. 更新 `docs/sbom/libffmpeg.md` 的"产物"（SHA256）+ "CVE 审计"（重新审计）章节
5. 提交所有变更
```

#### 5.1.2 `docs/sbom/libffmpeg.sha256` 格式

`sha256sum` 标准输出格式（hash + 双空格 + path）：
```
d6c53cf493b835cde01c857f6063b16b0084988835d7b597d2a755ed8ae3e5b1  android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so
```

path 用 forward slash（跨平台）。

#### 5.1.3 `BUILD_INSTRUCTIONS.md` 加交叉引用

文件末尾加：
```markdown
## SBOM 与完整性校验

本库的 SBOM（版本、SHA256、CVE 审计）见 `docs/sbom/libffmpeg.md`。
构建时由 `build.gradle.kts:verifyLibffmpegSha256` 自动校验 SHA256。
```

#### 5.1.4 CVE 审计执行流程

Task 1 implementer 在执行时：

1. 用 WebFetch 访问 `https://ffmpeg.org/security.html`
2. 筛选 FFmpeg 6.1.1（2023-11 发布）之后修复的 CVE
3. 对每个 CVE：
   - 检查 CVE 描述里的 affected component（decoder/demuxer/parser 名称）
   - 对照项目 configure flags 启用的 component 列表（h264/hevc/vp8/vp9/av1/mpeg4/vc1/h263 decoder + mkv/avi/flv/asf/ts/mov demuxer）
   - 若项目启用了 affected component → "受影响"
   - 若项目未启用 → "未受影响"
4. 填入 SBOM.md 的 CVE 审计章节
5. 若 WebFetch 不可用：SBOM.md 标注"待人工审计（日期 YYYY-MM-DD）"+ 列出已知 ffmpeg.org/security.html 链接 + 提供查询指引

### 5.2 Task 2：build.gradle.kts SHA256 校验 task

#### 5.2.1 `verifyLibffmpegSha256` task

```kotlin
// Verify libffmpeg.so integrity against docs/sbom/libffmpeg.sha256.
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

#### 5.2.2 测试方案（集成测试）

Gradle task 单元测试较难，改用集成测试手动验证：

| 测试 | 命令 | 期望 |
|---|---|---|
| 当前 `.so` 与 SBOM 匹配 → 构建成功 | `cd android && ./gradlew verifyLibffmpegSha256` | BUILD SUCCESSFUL，输出含 `verifyLibffmpegSha256: OK` |
| 修改 `.so` 一个字节 → 构建失败 | 备份 `.so` → 修改 → 跑 task → 还原 | BUILD FAILED，错误消息含 `SHA256 mismatch` |
| `.so` 缺失 → skip | 暂时改名 `.so` 跑 task → 还原 | 输出 `libffmpeg.so absent, skipping`，BUILD SUCCESSFUL |
| 哈希文件空 → 构建失败 | 清空 `.sha256` 跑 task → 还原 | BUILD FAILED，错误消息含 `is empty` |

#### 5.2.3 `preBuild` 钩子影响

- 每次构建（`assembleDebug` / `assembleRelease`）自动跑校验
- 1.5MB 文件 SHA256 计算约 10ms，对构建时间无感
- 开发者替换 `.so` 后必须更新 `.sha256` 文件——这是 desired behavior（强制 SBOM 同步）

---

## 6. 测试方案

### 6.1 Task 1 验证

- `docs/sbom/libffmpeg.md` 存在 + 含完整章节（产物/来源/构建配置/CVE 审计/验证/重新构建流程）
- `docs/sbom/libffmpeg.sha256` 内容 = 当前 `.so` 实际 SHA256（验证：`sha256sum android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so` 输出与文件首字段一致）
- `BUILD_INSTRUCTIONS.md` 末尾含 SBOM 交叉引用链接
- CVE 审计章节有实际数据（或"待审计"标注 + 查询指引）

### 6.2 Task 2 验证

见 5.2.2 集成测试矩阵。

### 6.3 回归测试

- `cd android && ./gradlew assembleDebug` — 必须成功（SHA256 校验通过）
- `cd android && ./gradlew testDebugUnitTest` — 全 green（task 不影响单元测试）
- Phase 1 Android 测试无回归

---

## 7. 风险与权衡

| 风险 | 影响 | 缓解 |
|---|---|---|
| CVE 审计需要联网访问 ffmpeg.org | 中 | WebFetch；若失败，标注"待人工审计"+提供查询指引 |
| `preBuild` 钩子让所有构建多 ~10ms | 低 | 可忽略；换取完整性保证 |
| 未来加其他 ABI 需扩展 task | 低 | task 设计为只校验 arm64-v8a；其他 ABI 可复制模式 |
| BUILD_INSTRUCTIONS.md 与 SBOM.md 信息漂移 | 低 | Task 1 加交叉引用；未来审计时检查 |
| CVE 审计误判（项目实际受影响但标"未受影响"） | 中 | 审计方法论文档化（基于 configure flags 启用列表）；未来审计可纠正 |

---

## 8. 验证完成标准

- ✅ `docs/sbom/libffmpeg.md` 存在且内容完整
- ✅ `docs/sbom/libffmpeg.sha256` 内容与当前 `.so` 实际 SHA256 一致
- ✅ `BUILD_INSTRUCTIONS.md` 含 SBOM 交叉引用
- ✅ CVE 审计章节有数据或明确的"待审计"标注
- ✅ `./gradlew verifyLibffmpegSha256` BUILD SUCCESSFUL + 输出 OK
- ✅ 修改 `.so` 后 `./gradlew verifyLibffmpegSha256` BUILD FAILED
- ✅ `./gradlew assembleDebug` 仍 BUILD SUCCESSFUL（preBuild 钩子不影响正常构建）
- ✅ Phase 1 Android 测试无回归

---

## 9. 后续 Phase 衔接

Phase 2 完成后，剩余 Phase（按主 spec 优先级）：

| Phase | 内容 | 备注 |
|---|---|---|
| Phase 5 | Web UI XSS 整改 + 移除 CSP `'unsafe-inline'` | 与 Phase 4 协同 |
| Phase 6 | 供应链扫描 + 依赖升级 + CI | 独立 |

**Phase 2 follow-up**（不在本 spec 范围）：
- `liblocalmedia_native.so`（Rust crate）的 SBOM——可作 Phase 2.5
- 其他 ABI（x86_64）支持——若未来引入

---

## 文档信息

- **创建日期**：2026-07-11
- **审计轮次**：Round 29 Phase 2
- **依赖**：Phase 1（commit `76b6d51`）
- **方法论**：brainstorming skill（澄清 → 方案 → 设计）
- **下一步**：经用户审核后，调用 writing-plans skill 转为实施计划
