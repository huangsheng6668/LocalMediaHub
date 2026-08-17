# SDD ledger — plan: docs/superpowers/plans/2026-08-17-security-phase9-tri-end-audit.md
BASELINE EXCEPTION: internal/service/bookparser TestParseUserNovel 在干净 master 上即 FAIL（Phase 9 前遗留，与本 plan 所有任务无关；后续任务验收时排除该用例）
Task 1: minor (deferred): RequestURI 重建用解码后 URL.Path，编码路径下日志保真度降低（不影响脱敏目标）
Task 1: minor (deferred): ?TOKEN= 大小写变体不在脱敏范围（Round 32 既有语义）
Task 1: complete (commits f245136..4e68a5a, review clean)
Task 2: minor (deferred): 测试断言未区分拒绝原因，Linux 上空真（M-4 为 Windows 特有漏洞）
Task 2: minor (deferred): == 与 EqualFold 冗余（brief 原文形态）
Task 2: minor (deferred): Linux 仅大小写不同的双根配置会被误拒（fail-closed 方向安全）
Task 2: minor (deferred): 测试 os.MkdirAll 返回值未检查
Task 2: complete (commits 4e68a5a..39bfe82, review clean)
Task 3: minor (deferred): readerPrefs.test.mjs 存在对同一模块的两条 import 可合并
Task 3: complete (commits 39bfe82..0623fc7, review clean)
Task 4: minor (deferred): 带 token 正向断言仅覆盖 /folders，wildcard 无正向断言（authMw 单实例，风险趋零）
Task 4: observation: /tags 读端点与 /books/image(sig-only)、/health 保持公开（与 brief 范围一致，最终审查再评估）
Task 4: complete (commits 0623fc7..0b99fe7, review clean)
Task 5: minor (deferred): 413 测试仅覆盖 ContentLength 快速路径，chunked 流式路径未测
Task 5: complete (commits 0b99fe7..62ad74b, review clean)
Task 6: minor (deferred): 测试缺口——并发压力/evictLocked 4096 容量路径/空token+limiter 组合无断言（后续小 commit 可补）
Task 6: minor (deferred): 与 ratelimit.go 两处淘汰细节差异（brief 字面规格内，内部自洽）
Task 6: complete (commits 62ad74b..845c129, review clean)
Task 7: minor (deferred): 双 walk 使 system 缩略图 total 计双份（保守方向，cap 上界未被突破；后续删第二个 root 即精确）
Task 7: minor (deferred): thumb-tmp-*.jpg 临时文件不在清扫排除范围（低概率误删瞬时自愈；建议过滤器跳过该前缀）
Task 7: observation: /images/* 60/min 含 /original，大目录快速滚动可能瞬时 429（brief 选定阈值）
Task 7: complete (commits 845c129..9bb6e97, review clean)
Task 8: fix round 1/5 (2 addressed, 0 open — I-1 seq写序竞态(sendMu串行化) + M-3 三条安全门测试; commits 31edce3..eb252fd)
Task 8: minor (deferred): handshaking 标志轮询分支不可达; pre-auth 垃圾帧 drop 不断开策略不对称; 测试死代码 wrong:=DecodeAuthedFrame; Send 失败路径未复用 failConnection; seq 种子未绑定 nonce 的跨连接重放理论窗口
Task 8: complete (commits 9bb6e97..eb252fd, review clean after fix round 1)
Task 9: minor (deferred): echo 路径 notify 失败静默; onAuthFailure 无直接单测; declared 超限路径默认 cap 下生产不可达(仅 accumulated 可达); DI token 冷启动空窗口 fail-closed; authLock 内 notify+重试 sleep 最坏持锁 ~100ms
Task 9: complete (commits eb252fd..752fa9d, review clean; Go 向量锁 7/7 实跑验证)
CARRYOVER Task10: manager 仍 v1 解码后回调(v2 真机到不了 onCommandWrite, raw 接缝已预留 onCommandWrite(rawFrame)) + fatal 主动断链 + authErrorText UI 暴露 + PC requestMtu(247)
Task 10: minor (deferred): CCCD 写值语义未校验(0x0000 取消订阅也被记为 subscriber); onConnectionStateChange 无条件设 subscriberDevice 为既有行为(单 peer MVP 假设)
Task 10: complete (commits 752fa9d..77bcc7a, review clean; MTU 偏离裁定通过)
CARRYOVER 收尾: 真机联调清单——Just Works 配对弹窗/加密首访问/bond 后完整握手/MTU 协商值日志确认
Task 11: minor (deferred): matchUUIDPrefix 名称与现语义不符(纯可读性); scan 回调无锁计数为既有模式
Task 11: plan-defect-note: brief Step1 测试输入(大小写变体 must-NOT-match)与 Interfaces(lowercase 归一化)矛盾，审查裁定按 Interfaces 处理正确(RFC 4122 语义)
Task 11: complete (commits 77bcc7a..e359a13, review clean)
Task 12: minor (deferred): abort 清理不删空目录; 同名覆盖为既有语义; PipControllerStore 两方法沦为死代码; +→%20 替换无直接单测
Task 12: plan-defect-note: brief 公式自相矛盾(唯一自洽解=实施公式)/contentLength 恒 chunked 需 tempFile 兜底/删除半成品目录字面会删历史下载/pip 路径笔误——四项偏离均裁定正确
Task 12: complete (commits e359a13..3a2bfd6, review clean)
Task 13: minor (deferred): lastSuccessAt 字段只写不读; 开放模式非私网 403 分支无测试; LIKE 混合转义用例可再补
Task 13: plan-defect-note: brief LIKE 字面(先转义后拼 sep)在 Windows 静默失效、cooldown 裸循环无法精确封顶 2h——两项纠偏均实证正确; 跨进程冷却为既有 session 永久禁用(比退避更严)
Task 13: complete (commits 3a2bfd6..4bcfee0, review clean)
Task 14: minor (deferred): book.go 前缀匹配只认小写(变体经 manifest 反查兜底等价置空); lightbox 单图模式 DOM 属性赋值无注入面
Task 14: complete (commits 4bcfee0..99a8132, review clean)
Task 15: observation: docs/INDEX.md 顶部 API 端点表"需 Token"列仍显示 folders/videos/images=否，与 Phase 9 挂 authMw 不一致（空 token 透传的条件式语义，最终审查处理）
Task 15: complete (commits 99a8132..c546a19, review clean; Important 观察项=INDEX.md API 表需Token列过时，留给最终审查)
FINAL REVIEW (f12b6f3..c546a19): C-1 BLE markConnected 时序缺陷(生产不可用) + I-1 L-8漏修 + I-2 INDEX口径 + I-3 tags泄露面 → 进入唯一 fix wave；递延 Minor 分诊表见最终审查报告(全部递延成立)
