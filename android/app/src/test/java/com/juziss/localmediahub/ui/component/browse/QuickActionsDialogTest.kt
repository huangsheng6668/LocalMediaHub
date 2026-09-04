package com.juziss.localmediahub.ui.component.browse

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.ReadingStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * spec/plan Task 17：文本文件长按菜单必须有第 4 个"清除手动标记"动作
 * （onMarkStatus(item, null)）——否则手动状态一旦设置就永远无法回到自动派生。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickActionsDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val textFile = MediaFile(
        name = "a.txt", path = "/m/a.txt", relativePath = "a.txt",
        size = 1, modifiedTime = "2026-01-01", mediaType = "text", extension = ".txt",
    )

    @Test
    fun clearManualStatusActionInvokesNullStatusForTextFiles() {
        val marks = mutableListOf<ReadingStatus?>()
        composeRule.setContent {
            QuickActionsDialog(
                item = textFile,
                onEditTags = {},
                onDownloadFile = {},
                onDeleteFile = {},
                onDownloadFolder = {},
                onDeleteFolder = {},
                onDismiss = {},
                onMarkStatus = { _, status -> marks.add(status) },
            )
        }

        composeRule.onNodeWithText("清除手动标记").performClick()

        assertEquals(listOf<ReadingStatus?>(null), marks)
    }

    @Test
    fun clearManualStatusActionHiddenForNonTextFiles() {
        val video = MediaFile(
            name = "v.mp4", path = "/m/v.mp4", relativePath = "v.mp4",
            size = 1, modifiedTime = "2026-01-01", mediaType = "video", extension = ".mp4",
        )
        val marks = mutableListOf<ReadingStatus?>()
        composeRule.setContent {
            QuickActionsDialog(
                item = video,
                onEditTags = {},
                onDownloadFile = {},
                onDeleteFile = {},
                onDownloadFolder = {},
                onDeleteFolder = {},
                onDismiss = {},
                onMarkStatus = { _, status -> marks.add(status) },
            )
        }

        composeRule.onNodeWithText("清除手动标记").assertDoesNotExist()
    }
}
