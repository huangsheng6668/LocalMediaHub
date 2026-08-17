### Task 12: Android 杂项加固（M-7 / L-6 / L-7）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/VideoPlayerActivity.kt:81-85`；删除 `ui/pip/PipActionReceiver.kt`（死代码）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt`（新增顶层 `encodePathSegments`）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/DownloadWorker.kt:258-304`
- Test: 新建 `android/app/src/test/java/com/juziss/localmediahub/util/PathEncodingTest.kt`；`data/DownloadManagerTest.kt` 追加

**Interfaces:**
- Produces:
  - PiP 接收器统一 `ContextCompat.registerReceiver(this, pipReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)`（所有 API 级别）
  - `internal fun encodePathSegments(path: String): String`：按 `/` 切分逐段 `URLEncoder.encode(seg, "UTF-8")` 重新拼接；`MediaRepository` 中所有把 `relativePath` 拼进 URL 路径段的调用点（列表/详情/缩略图/流地址）改走它
  - DownloadWorker 解压累计上限：`companion object { const val MAX_UNCOMPRESSED_BYTES = 4L * 1024 * 1024 * 1024 }` + 纯函数 `shouldAbortUnzip(extracted: Long, declared: Long): Boolean`（`extracted > maxOf(declared * 2, 64MB)` 或超 4GB），超限中止并删除半成品目录

- [ ] **Step 1: 写失败测试**

```kotlin
// PathEncodingTest.kt
@Test fun encodesEachSegmentButKeepsSlashes() {
    assertEquals("a%20b/c%23d/e.mp4", encodePathSegments("a b/c#d/e.mp4"))
    assertEquals("e.mp4", encodePathSegments("e.mp4"))
    assertEquals("", encodePathSegments(""))
}

// DownloadManagerTest.kt 追加
@Test fun unzipAbortsBeyondDeclaredBudget() {
    assertFalse(shouldAbortUnzip(extracted = 1_000, declared = 10_000))
    assertTrue(shouldAbortUnzip(extracted = 30_000, declared = 10_000)) // 3x declared
    assertTrue(shouldAbortUnzip(extracted = 5L * 1024 * 1024 * 1024, declared = 0)) // 绝对上限
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.util.PathEncodingTest" --tests "com.juziss.localmediahub.data.DownloadManagerTest"`
Expected: 编译错误

- [ ] **Step 3: 实现**

按 Interfaces 逐项落地；解压循环每写一个 entry 累计 `extracted += written` 并检查 `shouldAbortUnzip`，触发即 `file.deleteRecursively()` + `Result.failure(SecurityException("unzip budget exceeded"))`。删除 `PipActionReceiver.kt` 前全仓 grep 确认无引用。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub android/app/src/test/java/com/juziss/localmediahub
git commit -m "fix(android): pip receiver export guard, path encoding and zip cap (Phase 9)"
```

---

