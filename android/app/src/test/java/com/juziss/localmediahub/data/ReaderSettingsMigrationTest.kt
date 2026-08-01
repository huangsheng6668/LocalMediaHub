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

    @Test
    fun v2_without_new_fields_falls_back_to_defaults() = runBlocking {
        injectRawSettings("""{"theme":"NIGHT"}""")
        val s = store.readerSettingsFlow.first()
        assertEquals(0f, s.letterSpacing, 0.0001f)
        assertEquals(null, s.customBg)
        assertEquals(null, s.customFg)
        assertEquals(null, s.customMuted)
    }

    @Test
    fun v2_with_new_fields_reads_correctly() = runBlocking {
        injectRawSettings("""{"letterSpacing":0.25,"customBg":"#ABCDEF","customFg":"#112233","customMuted":"#445566"}""")
        val s = store.readerSettingsFlow.first()
        assertEquals(0.25f, s.letterSpacing, 0.0001f)
        assertEquals("#ABCDEF", s.customBg)
        assertEquals("#112233", s.customFg)
        assertEquals("#445566", s.customMuted)
    }

    @Test
    fun v2_without_pageTurnStyle_falls_back_to_none() = runBlocking {
        injectRawSettings("""{"theme":"NIGHT"}""")
        assertEquals(PageTurnStyle.NONE, store.readerSettingsFlow.first().pageTurnStyle)
    }

    @Test
    fun v2_with_pageTurnStyle_reads_correctly() = runBlocking {
        injectRawSettings("""{"pageTurnStyle":"DRAG"}""")
        assertEquals(PageTurnStyle.DRAG, store.readerSettingsFlow.first().pageTurnStyle)
    }

    @Test
    fun v2_with_invalid_pageTurnStyle_falls_back_to_none() = runBlocking {
        injectRawSettings("""{"pageTurnStyle":"BOGUS"}""")
        // Global Gson enum-default TypeAdapterFactory maps unknown enum names to
        // the enum's first declared value (NONE for PageTurnStyle).
        assertEquals(PageTurnStyle.NONE, store.readerSettingsFlow.first().pageTurnStyle)
    }

    /**
     * Latent-bug regression: Gson's default behavior sets a non-nullable Kotlin enum
     * field to `null` when it encounters an unknown enum-name string (no exception).
     * This affects ALL enum fields on [ReaderSettings] (theme/readingMode/pageTurnStyle),
     * causing NPEs at first access. The global TypeAdapterFactory must map unknown
     * enum names to the enum's first declared value, fixing all three fields uniformly.
     */
    @Test
    fun theme_bogus_falls_back_to_default_via_global_adapter() = runBlocking {
        injectRawSettings("""{"theme":"BOGUS"}""")
        assertEquals(ReaderTheme.DAY, store.readerSettingsFlow.first().theme)
    }

    @Test
    fun readingMode_bogus_falls_back_to_default_via_global_adapter() = runBlocking {
        injectRawSettings("""{"readingMode":"BOGUS"}""")
        assertEquals(ReadingMode.CHAPTER, store.readerSettingsFlow.first().readingMode)
    }
}


