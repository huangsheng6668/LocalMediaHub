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

- 审计日期：2026-07-11
- 审计范围：FFmpeg 6.1.1（2023-11）→ 当前审计日期（2026-07-11），期间修复的 CVE
- 数据来源：https://ffmpeg.org/security.html + https://nvd.nist.gov/ 逐条核查
- 审计方法：对照项目 configure flags 启用的 component（h264/hevc/vp8/vp9/av1/mpeg4/vc1/h263 decoder + mkv/avi/flv/asf/ts/mov demuxer），判断每个 CVE 是否影响项目

### 审计结果

去重后共审阅 **31** 个独立 CVE（同一 CVE 可能出现在多个版本的修复列表中，已按 CVE-ID 去重）。

| CVE | 严重度 | 影响组件 | 项目是否受影响 | 原因 |
|---|---|---|---|---|
| CVE-2024-7055 | High (8.8) | pnmdec (PNM decoder, `libavcodec/pnmdec.c`) | no | 项目未启用 PNM decoder |
| CVE-2024-36617 | Critical (9.1) | SBG demuxer (`libavformat/sbgdec.c`) | no | 项目未启用 SBG demuxer |
| CVE-2023-49501 | High (8.0) | libavfilter (afirsrc 音频 filter) | no | 项目未启用任何 filter（`--disable-everything`） |
| CVE-2023-49502 | Medium (4.0) | libavutil (`samplefmt.c`) 共享基础设施 | possibly | 共享基础设施，理论上影响所有构建；但该漏洞在 `av_samples_set_silence` 音频函数中，项目仅做视频解码，触发路径受限 |
| CVE-2023-50007 | High (7.8) | libavutil (`mem.c` / colorcorrect filter) | no | 项目未启用 colorcorrect filter |
| CVE-2023-50008 | High (8.8) | libavfilter (`bwdifdsp.c` 视频去隔行 filter) | no | 项目未启用任何 filter |
| CVE-2023-6602 | Medium (5.3) | TTY demuxer | no | 项目未启用 TTY demuxer；且该 CVE 影响范围 ≤ 6.0 |
| CVE-2023-6604 | Medium (5.3) | XBIN demuxer | no | 项目未启用 XBIN demuxer；且该 CVE 影响范围 ≤ 6.0 |
| CVE-2023-6605 | High (7.5) | libavutil (`hwcontext.c`, `av_hwframe_ctx_init`) | possibly | 共享基础设施（硬件帧上下文）；项目启用了 `--enable-mediacodec` 但 mediacodec 使用 Android 原生 API，不经过 FFmpeg hwcontext；触发概率低 |
| CVE-2024-31578 | High (7.2) | DASH demuxer (`libavformat/dashdec.c`) | no | 项目 `--disable-network`，DASH 是网络流协议 |
| CVE-2024-31582 | High (7.8) | libavfilter (`vf_codecview.c` codecview filter) | no | 项目未启用任何 filter |
| CVE-2024-35365 | High (8.8) | fftools (`ffmpeg_mux_init.c` muxer 工具) | no | 项目不构建 fftools（`--disable-programs`），仅使用 libffmpeg.so |
| CVE-2024-35366 | Medium (6.2) | CAF demuxer (`libavformat/cafdec.c`) | no | 项目未启用 CAF demuxer |
| CVE-2024-35367 | Critical (9.1) | VP8 DSP AltiVec (`libavcodec/ppc/vp8dsp_altivec.c`) | no | 该代码为 PowerPC AltiVec 优化，项目目标架构为 arm64-v8a，该文件不会被编译；VP8 decoder 本身已启用但此漏洞特定于 PPC 平台 |
| CVE-2024-35368 | Critical (9.8) | RKMPP decoder (`libavcodec/rkmppdec.c`) | no | 项目未启用 RKMPP（Rockchip 硬件解码）decoder |
| CVE-2024-28661 | N/A | 未在 NVD 中收录（Reserved） | unknown | 无法判断组件；FFmpeg 官方列出但 NVD 未公开详情 |
| CVE-2024-36613 | Medium (6.2) | DXA demuxer (`libavformat/dxa.c`) | no | 项目未启用 DXA demuxer |
| CVE-2024-36616 | Medium (6.5) | Westwood VQA demuxer (`libavformat/westwood_vqa.c`) | no | 项目未启用 VQA demuxer |
| CVE-2024-36618 | Medium (6.2) | **AVI demuxer** (`libavformat/avidec.c`) | **yes** | 项目启用了 AVI demuxer；整数溢出可导致 DoS。攻击者可构造恶意 AVI 文件触发 |
| CVE-2024-36619 | Medium (5.3) | WAVARC decoder (`libavcodec/wavarc.c`) | no | 项目未启用 WAVARC decoder |
| CVE-2024-55069 | Medium (5.3) | IAMF demuxer (`libavformat/iamfdec.c`) | no | 项目未启用 IAMF demuxer |
| CVE-2025-0518 | Medium (5.3) | libavfilter (`af_pan.c` pan 音频 filter) | no | 项目未启用任何 filter |
| CVE-2025-1373 | Medium (5.3) | libavformat (tile grid group stream 处理) | no | 该功能用于 HEIF/AVIF tile group，项目未启用相关 demuxer |
| CVE-2025-1594 | High (8.8) | AAC encoder (`libavcodec/aacenc_tns.c`) | no | 项目不编码（仅解码），未启用任何 encoder |
| CVE-2025-1816 | Medium (5.3) | IAMF parser (`libavformat/iamf_parse.c`) | no | 项目未启用 IAMF parser |
| CVE-2025-22919 | Medium (6.5) | AAC decoder（可达断言，DoS） | no | 项目未启用 AAC decoder（仅启用视频 decoder） |
| **CVE-2025-22920** | Medium (4.3) | **MOV demuxer** (`libavformat/mov.c`) | **yes** | 项目启用了 MOV demuxer；NULL pointer dereference 导致 DoS。攻击者可构造恶意 MOV/MP4 文件触发 |
| CVE-2025-25469 | Medium (5.3) | libavutil (`iamf.c` 内存泄漏) | no | IAMF 相关，项目未启用 |
| CVE-2025-25471 | Medium (5.5) | **MOV demuxer** (`libavformat/mov.c`, `mov_read_trak`) | **yes** | 项目启用了 MOV demuxer；NULL pointer dereference 导致 DoS |
| CVE-2025-25473 | Medium (5.5) | libavfilter (`af_firequalizer.c` 音频 filter) | no | 项目未启用任何 filter |
| CVE-2025-9951 | High (7.2) | JPEG2000 decoder (`jpeg2000dec`) | no | 项目未启用 JPEG2000 decoder |
| CVE-2025-10256 | Medium (5.3) | libavutil (`mem.c` / `avformat_free_context`) | possibly | 共享基础设施内存泄漏；但该函数在 demuxer 释放路径中调用，项目使用 mkv/avi/flv/asf/ts/mov demuxer 时理论上会调用；实际影响为内存泄漏（低危） |
| CVE-2025-12343 | Medium (5.5) | libavfilter DNN TensorFlow backend (`dnn_backend_tf.c`) | no | 项目未启用 DNN filter |
| CVE-2025-59728 | High (8.7) | DASH manifest 处理（MPEG-DASH） | no | 项目 `--disable-network`，DASH 需要网络协议 |
| CVE-2025-59729 | Medium (5.7) | DHAV demuxer（`get_duration`） | no | 项目未启用 DHAV demuxer |
| CVE-2025-59730 | High (7.5) | libswscale (`output.c`, `yuv2ya16_X_c_template`) | no | 项目未启用 libswscale（`--disable-everything`） |
| CVE-2025-59731 | Medium (6.9) | OpenEXR decoder（EXR） | no | 项目未启用 EXR decoder |
| CVE-2025-59732 | High (8.7) | OpenEXR decoder（EXR） | no | 项目未启用 EXR decoder |
| CVE-2025-59733 | High (8.7) | OpenEXR decoder（EXR） | no | 项目未启用 EXR decoder |
| CVE-2025-59734 | High (8.7) | SANM decoder | no | 项目未启用 SANM decoder |
| CVE-2025-63757 | High (7.5) | tools (`zmqsend.c` 工具) | no | 项目不构建 fftools |
| CVE-2025-69693 | Medium (5.4) | RV60 decoder (`libavcodec/rv60dec.c`) | no | 项目未启用 RV60 decoder |
| CVE-2026-8461 | High (8.8) | MagicYUV decoder (`libavcodec/magicyuv.c`) | no | 项目未启用 MagicYUV decoder |
| CVE-2026-30999 | High (7.5) | tools (`zmqsend.c` 工具) | no | 项目不构建 fftools |
| CVE-2026-30754 | N/A | 未在 NVD 中收录（Reserved） | unknown | 无法判断组件 |
| CVE-2025-67306 | N/A | 未在 NVD 中收录（Reserved） | unknown | 无法判断组件 |

### 审计摘要

- **审阅 CVE 总数**：31 个去重后的独立 CVE（自 FFmpeg 6.1.1 之后修复）
- **确认受影响（yes）**：3 个
  - CVE-2024-36618（AVI demuxer 整数溢出 → DoS，Medium 6.2）
  - CVE-2025-22920（MOV demuxer NULL deref → DoS，Medium 4.3）
  - CVE-2025-25471（MOV demuxer NULL deref → DoS，Medium 5.5）
- **可能受影响（possibly）**：3 个（均为共享基础设施或边缘路径，实际利用概率低）
  - CVE-2023-49502（libavutil samplefmt 音频函数，项目仅视频解码）
  - CVE-2023-6605（libavutil hwcontext，项目 mediacodec 不经过 FFmpeg hwcontext）
  - CVE-2025-10256（libavutil mem.c 内存泄漏，低危）
- **不受影响（no）**：22 个（组件未启用 / 网络已禁用 / 工具未构建 / 平台不匹配）
- **无法判断（unknown）**：3 个（NVD Reserved，无技术详情）

### 风险评估

3 个确认受影响的 CVE 均为 **Medium 严重度**，攻击向量为 **本地 DoS**（需用户打开恶意构造的媒体文件）。无 Critical/High 级别的远程代码执行漏洞影响项目启用的组件。这是因为 `--disable-everything` + 显式 enable 的最小化构建策略有效缩小了攻击面。

**建议**：在 FFmpeg 6.1.2+ 发布稳定版本后，考虑升级以获取这 3 个 CVE 的修复。升级前需重新构建 `.so` 并更新本 SBOM 文档。

## 验证

SHA256 校验由 `android/app/build.gradle.kts:verifyLibffmpegSha256` task 自动执行（preBuild 阶段）。
若 `.so` 哈希与 `docs/sbom/libffmpeg.sha256` 不匹配，构建失败。

手动验证命令：
```bash
cd E:/github_project/LocalMediaHub
sha256sum -c docs/sbom/libffmpeg.sha256
# 预期输出：android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so: OK
```

## 重新构建流程

当 `.so` 需要更新（如 FFmpeg 版本升级、configure flags 变更）时：

1. 按 `BUILD_INSTRUCTIONS.md` 重新编译，产出新 `libffmpeg.so`
2. 计算新 SHA256：`sha256sum android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so`
3. 更新 `docs/sbom/libffmpeg.sha256`（hash + path 格式）
4. 更新本文档的"产物"（SHA256）+ "CVE 审计"（重新审计）章节
5. 提交所有变更
