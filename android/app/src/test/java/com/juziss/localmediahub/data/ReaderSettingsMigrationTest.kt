package com.juziss.localmediahub.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.juziss.localmediahub.ui.component.reader.ReaderFontFamily
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * V1 → V2 reader_settings migration tests.
 *
 * Verifies that JSON written by an older app version (with `fontSize` /
 * `lineHeight` as enum-name strings) is correctly rewritten to V2 numeric
 * shape before Gson deserialization, and that all other fields (theme,
 * autoScrollSpeed) survive the migration rather than being lost to a Gson
 * fallback.
 *
 * Strategy: inject raw V1 JSON directly into the DataStore key via the
 * test-only [RecentActivityStore.injectRawReaderSettingsForTest] API,
 * then read [RecentActivityStore.readerSettingsFlow] and assert.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ReaderSettingsMigrationTest {

    private lateinit var store: RecentActivityStore

    @Before
    fun setUp() {
        store = RecentActivityStore(ApplicationProvider.getApplicationContext())
        runBlocking { store.clearAllReaderSettings() }
    }

    /** 把任意 JSON 字符串直接注入 DataStore 的 reader_settings key。 */
    private suspend fun injectRawSettings(raw: String) {
        store.injectRawReaderSettingsForTest(raw)
    }

    @Test
    fun v1_medium_migrates_to_16() = runBlocking {
        injectRawSettings("""{"fontSize":"MEDIUM","lineHeight":"STANDARD","theme":"DAY","autoScrollSpeed":5}""")
        val s = store.readerSettingsFlow.first()
        assertEquals(16, s.fontSizeSp)
        assertEquals(1.8f, s.lineHeightMultiplier, 0.0001f)
        assertEquals(ReaderTheme.DAY, s.theme)
        assertEquals(5, s.autoScrollSpeed)
    }

    @Test
    fun v1_small_large_xlarge_migrate_correctly() = runBlocking {
        injectRawSettings("""{"fontSize":"SMALL"}""")
        assertEquals(14, store.readerSettingsFlow.first().fontSizeSp)
        injectRawSettings("""{"fontSize":"LARGE"}""")
        assertEquals(18, store.readerSettingsFlow.first().fontSizeSp)
        injectRawSettings("""{"fontSize":"XLARGE"}""")
        assertEquals(20, store.readerSettingsFlow.first().fontSizeSp)
    }

    @Test
    fun v1_line_height_compact_loose_migrate_correctly() = runBlocking {
        injectRawSettings("""{"lineHeight":"COMPACT"}""")
        assertEquals(1.4f, store.readerSettingsFlow.first().lineHeightMultiplier, 0.0001f)
        injectRawSettings("""{"lineHeight":"LOOSE"}""")
        assertEquals(2.2f, store.readerSettingsFlow.first().lineHeightMultiplier, 0.0001f)
    }

    @Test
    fun v1_unknown_enum_falls_back_to_default() = runBlocking {
        injectRawSettings("""{"fontSize":"BOGUS","lineHeight":"WEIRD"}""")
        val s = store.readerSettingsFlow.first()
        assertEquals(16, s.fontSizeSp)  // migrate 函数找不到映射时保留原字符串 -> Gson 抛异常 -> 整体默认
        assertEquals(1.8f, s.lineHeightMultiplier, 0.0001f)
    }

    @Test
    fun v1_corrupt_json_falls_back_to_default() = runBlocking {
        injectRawSettings("""{this is not json""")
        assertEquals(ReaderSettings(), store.readerSettingsFlow.first())
    }

    @Test
    fun v1_partial_keeps_other_fields() = runBlocking {
        injectRawSettings("""{"fontSize":"LARGE","theme":"NIGHT","autoScrollSpeed":9}""")
        val s = store.readerSettingsFlow.first()
        assertEquals(18, s.fontSizeSp)
        assertEquals(ReaderTheme.NIGHT, s.theme)  // theme 没丢
        assertEquals(9, s.autoScrollSpeed)        // autoScrollSpeed 没丢
    }

    @Test
    fun v2_round_trip() = runBlocking {
        val original = ReaderSettings(
            fontFamily = ReaderFontFamily.SERIF,
            fontSizeSp = 22,
            lineHeightMultiplier = 2.0f,
            contentWidthDp = 680,
            firstLineIndent = false,
            paragraphSpacing = true,
            theme = ReaderTheme.NIGHT_BLACK,
            immersiveMode = true,
            autoScrollSpeed = 7,
        )
        store.saveReaderSettings(original)
        assertEquals(original, store.readerSettingsFlow.first())
    }
}


