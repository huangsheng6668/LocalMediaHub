# APK 体积优化设计（Round 21 - Batch D）

- **日期**: 2026-07-07
- **范围**: Android 客户端 `app/build.gradle.kts` + `gradle.properties` + Kotlin 图标 import + OkHttp 配置 + FFmpeg 重编
- **策略**: 2 commits — C1 (D1+D2+D3+D4 gradle/代码层) → C2 (D5 FFmpeg native 重编)
- **状态**: 待评审
- **前置**: Round 11（Rust 原生解码器）；Round 19 C3（Retrofit 移除）
- **目标**: APK 从 **7.15 MB → ≤ 6.0 MB**（节省 ≥ 1.15 MB）

---

## 1. 背景与动机

### 1.1 实测体积构成（2026-07-07，当前 master）

`unzip -l app-release.apk` 输出（实际 APK = 7,492,684 bytes = 7.15 MB）：

| 类别 | 解压后大小 | 占比 | 备注 |
|---|---:|---:|---|
| `classes.dex` | 4,760,208 B (4.54 MB) | 50.4% | DEX 主头 |
| `lib/arm64-v8a/libffmpeg.so` | 3,391,296 B (3.23 MB) | 35.9% | 预编译 FFmpeg |
| `lib/arm64-v8a/liblocalmedia_native.so` | 1,123,912 B (1.07 MB) | 11.9% | Rust 解码器 |
| `resources.arsc` | 549,592 B (0.52 MB) | 5.8% | 资源表 |
| `res/` 全部 | ~0.16 MB | 1.7% | PNG/XML/字体 |
| `okhttp3/.../publicsuffixes.gz` | 41,394 B | 0.4% | OkHttp PSL |
| 其它 | ~0.05 MB | 0.5% | META-INF / assets |

**绝对大头**：DEX + native libs，共占 ~95%。`res/` 才 0.16 MB，**不是问题**。

### 1.2 历史上下文

- Round 19 C3 已移除 Retrofit（节省 ~400KB）
- 当前 R8 配置：`isMinifyEnabled = true` + `isShrinkResources = true`，但是 **default mode**（不是 `android.enableR8.fullMode=true`）
- 当前依赖：`material-icons-extended`（5000+ 图标）、`okhttp:4.12.0`、`coil:2.5.0`
- FFmpeg：预编译 `.so` 直接放在 `jniLibs/arm64-v8a/`，未做模块裁剪

---

## 2. 目标与非目标

### 目标
1. **APK ≤ 6.0 MB**（节省 ≥ 1.15 MB）
2. C1（D1-D4）单独交付有价值：DEX 与依赖层瘦身
3. C2（D5）FFmpeg 重编：native 层瘦身
4. 所有功能不退化（运行时反射 / JNI / 转码 / 缩略图）

### 非目标
- APK splits / AAB（项目不走 Play Store）
- 改 `res/`（占比 < 2%，没空间）
- 改依赖到 alpha/beta
- 移除 Hilt / Gson / OkHttp（架构不动）
- 改 R8 默认行为之外的网络/反射配置（除非 D1 报错）
- 改 native Rust 解码器（Round 11 已优化，1.07MB 已合理）

---

## 3. 详细设计

### D1 — 启用 R8 full mode

**改动**：`gradle.properties` 加一行
```
android.enableR8.fullMode=true
```

**机制**：R8 full mode 比 default mode 更激进，会做更严格的反射分析，删除更多死代码。典型 DEX 体积下降 **5-15%**（4.54MB × 5-15% = **220-680KB 节省**）。

**风险**：
- full mode 更严格，可能误删运行时反射访问的类
- 缓解：当前 `proguard-rules.pro` 已有 Gson / Compose / Coil / Media3 / DataStore / JNI 的 keep 规则（见 Round 19 C3），覆盖率应该足够
- 如果 C1 build 后 release 启动崩溃，回退手段：删除该行，重新跑

**验证**：smoke test（见 §5）

### D2 — `material-icons-extended` 精简

**当前**：`build.gradle.kts:211`
```kotlin
implementation("androidx.compose.material:material-icons-extended")
```
这个包包含 ~5000 个图标常量。R8 理论上能 tree-shake（每个图标是 `ImageVector` 静态字段），但实测经常漏（特别是 full mode 之前）。

**改动**：
1. **第一步**：移除 `material-icons-extended` 依赖
2. **第二步**：扫整个 `android/app/src/main/java/` 找所有 `androidx.compose.material.icons.filled.*` / `.outlined.*` / `.rounded.*` 的 import
3. **第三步**：换成 `material-icons-core`（默认随 material3 进来），其中只包含常用 ~100 个 filled 图标。**如果某个图标 core 包没有**，要么换近似图标，要么单独把那个图标的 `ImageVector` 定义拷过来（极端情况）

**预期节省**：100-400KB（取决于 full mode 实际效果）

**风险**：枚举不全 → 编译失败（不是运行时崩溃，所以可接受；CI 会立刻报错）

**验证**：`./gradlew assembleDebug` 编译成功 = D2 完成

### D3 — 移除 OkHttp publicsuffixes.gz

**当前**：`okhttp3/internal/publicsuffix/publicsuffixes.gz` = 41,394 B（约 40KB 解压前；资源表内被算在 OkHttp 类内）。OkHttp 加载到内存后约 **~500KB-1MB heap**，且 R8 一般无法 tree-shake（被 `CookieJar` 默认实现强引用）。

**两个方案**：
- **(a) 接受这个体积**（41KB，占比 < 0.5%，ROI 低）
- **(b) 移除**：自定义 `CookieJar` 接口（不接受 cookie，或简单 `NoOpCookieJar`），让 R8 能 tree-shake 整个 `PublicSuffixDatabase`

**决策**：**采用 (b)**——`OkHttpModule.kt`（Hilt 模块）中当前已有 `OkHttpClient` 构建，加一个 `@Provides` 提供 `CookieJar.NO_COOKIES` 或更激进的 `NoOpCookieJar`，配合 `-assumenosideeffects` ProGuard 规则移除 `PublicSuffixDatabase` 加载。

**预期节省**：~40KB（解压前）+ heap 减少 0.5-1MB（运行时）

**风险**：低（局域网串流场景完全不依赖 cookie 安全策略）

### D4 — Coil 2.5.0 → 2.6.0

**改动**：`build.gradle.kts:230`
```kotlin
implementation("io.coil-kt:coil-compose:2.6.0")
```

**机制**：Coil 2.6 修复了多处死代码（coil-kt/coil#1889 等），DEX 占用更小；同时优化了 Compose lazy list 滚动性能（顺带惠及 Batch B 方向）。

**预期节省**：50-150KB

**风险**：低（API 完全兼容 2.5）

### D5 — FFmpeg 裁剪重编（C2 单独提交）

**当前**：`libffmpeg.so` 3.23 MB（arm64-v8a），未做模块裁剪。来源未知（仓库内是预编译二进制）。

**改动**：
1. 在仓库内（或 `docs/`）记录 FFmpeg 当前 build 配置（`ffmpeg -buildconf`，需要在本机跑）
2. 编写裁剪脚本 `build_ffmpeg.sh`，启用以下最小集：
   - **decoders**：h264, hevc, vp8, vp9, av1, mpeg4, mpeg2video, theora, vc1,wmv3, flv
   - **demuxers**：mov/mp4, matroska, avi, flv, webm, mpegts, asf, ogg
   - **protocols**：file, pipe
   - **filters**：minimal（仅 scale / format）
   - **muxers**：mp4（fragmented，转码用）
   - **encoders**：libx264（已有）+ aac（已有）
   - **disable**：everything else（`--disable-everything` 起步）
3. 重编产出 `libffmpeg.so`，覆盖 `jniLibs/arm64-v8a/libffmpeg.so`
4. 验证转码 + 视频帧抽取仍工作

**预期节省**：1-2MB（裁剪后的 FFmpeg arm64 通常 ~1-1.5MB）

**风险**：中-高
- 编译环境：需要 Android NDK + FFmpeg 源码 + 交叉编译工具链
- 功能回归：误删某个 decoder 会导致某些视频无法播放 / 转码 / 抽帧
- 缓解：先记录"当前能播的视频格式清单"，重编后逐一验证

**降级方案**：如果 D5 因工具链问题无法完成，C1（D1-D4）单独交付仍然有价值；D5 可推迟到下一轮

---

## 4. 实施计划（提交粒度）

### C1: gradle + 代码层瘦身（D1 + D2 + D3 + D4）

**单 commit**，预期省 0.4-1.2 MB（保守估计）

涉及文件：
- `gradle.properties`（新增 `android.enableR8.fullMode=true`）
- `app/build.gradle.kts`（移除 material-icons-extended，升 Coil 2.6）
- `app/proguard-rules.pro`（可能补 keep 规则，如果 full mode 报错）
- `app/src/main/java/.../network/OkHttpModule.kt`（加 CookieJar 配置）
- `app/src/main/java/**`（所有图标 import 替换）

### C2: FFmpeg native 重编（D5）

**单 commit**，预期再省 1-2 MB

涉及文件：
- 新增 `scripts/build_ffmpeg.sh`（FFmpeg 裁剪脚本）
- `android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so`（替换）
- `docs/superpowers/specs/` 或 `docs/` 新增 FFmpeg build 配置记录

---

## 5. 验证方案

### 5.1 每个 commit 后必跑

```bash
# 构建
cd android && ./gradlew assembleRelease

# 单测不退化
cd android && ./gradlew testDebugUnitTest

# APK 体积
unzip -l app/build/outputs/apk/release/app-release.apk | sort -k1,1 -rn | head -15
```

### 5.2 Smoke test（手动，每个 commit 后）

1. 安装 release APK 到真机
2. 启动 App（验证不崩溃 = R8 full mode / Coil 升级没问题）
3. 连接服务端（验证 OkHttp 配置 OK）
4. 浏览大目录（验证图标都正常显示 = D2 没漏）
5. 播放一个视频（验证 Media3 + OkHttp 仍工作）
6. 看一张大图（验证 Coil 2.6 没回归）

### 5.3 C2 (D5) 额外验证

7. 播放需要转码的视频（验证 ffmpeg libx264 + aac 编码仍工作）
8. 查看视频缩略图（验证 ffmpeg `-ss` 抽帧仍工作）
9. 验证各种容器格式（mp4/mkv/avi/flv/mov 至少各一个）

---

## 6. 回滚预案

- C1 回滚：`git revert <C1 commit>`，重新构建
- C2 回滚：还原 `libffmpeg.so`（git 中前一个版本），重新构建

每个 commit 独立可回滚，互不依赖。

---

## 7. 决策点

- **D2 图标替换策略**：先扫描枚举，如果用到的图标 > 30 个且部分 core 包没有，是否接受拷贝图标定义？（影响 D2 工作量）
  - 推荐：是。Coil Material 图标定义是简单的 `ImageVector`，拷贝成本远小于保留整个 extended 包
- **D5 是否本期交付**：
  - 推荐：是，但**允许在工具链不足时单独抽出**到下一轮（C1 单独有价值）
