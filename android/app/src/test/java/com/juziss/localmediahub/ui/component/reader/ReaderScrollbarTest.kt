package com.juziss.localmediahub.ui.component.reader

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.test.click
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderScrollbarTest {

    // 注:Robolectric 下 pointerInput 的 awaitPointerEvent 事件注入不稳定,
    // 此测试以"组件可渲染 + progress clamp"为最低保障;pointer 序列由真机手动验证(Task 5 Step 8)。
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun seek_callbacks_fire_in_correct_order() {
        val start = java.util.concurrent.atomic.AtomicInteger(0)
        val seekVals = java.util.concurrent.CopyOnWriteArrayList<Float>()
        val endVals = java.util.concurrent.CopyOnWriteArrayList<Float>()

        composeRule.setContent {
            ReaderScrollbar(
                progress = 0.2f,
                onSeekStart = { start.incrementAndGet() },
                onSeek = { seekVals.add(it) },
                onSeekEnd = { endVals.add(it) },
                modifier = Modifier,
                testTag = "scrubber",
            )
        }

        composeRule.onNodeWithTag("scrubber").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 200f))
            up()
        }
        composeRule.waitForIdle()

        org.junit.Assert.assertEquals(1, start.get())
        org.junit.Assert.assertTrue("onSeek should fire during drag", seekVals.isNotEmpty())
        org.junit.Assert.assertEquals(1, endVals.size)
        // 松手 progress 在 [0,1]
        val endP = endVals.first()
        org.junit.Assert.assertTrue(endP in 0f..1f)
    }
}
