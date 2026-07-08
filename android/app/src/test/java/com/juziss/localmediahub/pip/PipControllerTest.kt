package com.juziss.localmediahub.pip

import android.content.Context
import android.util.Rational
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 验证 PipController.buildParams 构造的 PictureInPictureParams：
 *  - 宽高比（含 16:9 fallback）
 *  - 至少 1 个 RemoteAction
 *
 * 注意：framework `PictureInPictureParams` 公开 getter 在 API 35/36 才暴露
 * `getAspectRatio()` / `getActions()`。compileSdk=36 下 Kotlin 属性名为
 * `aspectRatio`（来自 getAspectRatio()）。brief 草稿写的 `params.rational`
 * 并非公开 API（rational 是包私有字段），故改用 `aspectRatio`。
 */
@RunWith(RobolectricTestRunner::class)
class PipControllerTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `buildParams 16by9 landscape video returns 16 to 9 ratio`() {
        val params = PipController.buildParams(ctx, width = 1920, height = 1080, isPlaying = true)
        val ratio = params.aspectRatio
        assertNotNull("aspect ratio must be set", ratio)
        assertEquals(Rational(16, 9), ratio)
    }

    @Test
    fun `buildParams unknown or zero size falls back to 16 to 9`() {
        val params = PipController.buildParams(ctx, width = 0, height = 0, isPlaying = false)
        assertEquals(Rational(16, 9), params.aspectRatio)
    }

    @Test
    fun `buildParams vertical video returns 9 to 16`() {
        val params = PipController.buildParams(ctx, width = 720, height = 1280, isPlaying = true)
        assertEquals(Rational(9, 16), params.aspectRatio)
    }

    @Test
    fun `buildParams always returns non-null params with at least one action`() {
        val params = PipController.buildParams(ctx, width = 1920, height = 1080, isPlaying = true)
        assertNotNull(params)
        assertNotNull(params.aspectRatio)
        // getActions() 是 API 35+ 公开 getter；Robolectric 在 API 36 下应返回我们塞入的列表。
        val actions = params.actions
        assertNotNull("actions list must be set", actions)
        assertTrue("expected at least 1 RemoteAction, got ${actions?.size}",
            actions!!.isNotEmpty())
    }

    @Test
    fun `buildParams plays or pauses is encoded via isPlaying into returned params`() {
        // 两个 isPlaying 状态都应成功构造、不抛异常，且共享同一宽高比；
        // 此外每个 params 都应携带恰好 1 个 RemoteAction。
        val playing = PipController.buildParams(ctx, 1920, 1080, isPlaying = true)
        val paused  = PipController.buildParams(ctx, 1920, 1080, isPlaying = false)
        assertNotNull(playing)
        assertNotNull(paused)
        assertEquals(playing.aspectRatio, paused.aspectRatio)
        assertEquals(1, playing.actions?.size)
        assertEquals(1, paused.actions?.size)
        assertFalse("params should be distinct objects", playing === paused)
    }
}
