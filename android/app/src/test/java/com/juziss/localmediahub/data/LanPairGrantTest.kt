package com.juziss.localmediahub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * POST /api/v1/pair 响应解析 —— server 侧 handler/pair.go 的三种载荷形态：
 * token 模式（仅 token）、开放模式 + 专属 BLE 密钥（仅 ble_token）、
 * 双密钥（两者皆有）。
 */
class LanPairGrantTest {

    @Test
    fun parsesTokenOnly() {
        val g = MediaRepository.LanPairGrant.parse("""{"token":"sekrit"}""")!!
        assertEquals("sekrit", g.token)
        assertNull(g.bleToken)
    }

    @Test
    fun parsesBleTokenOnly() {
        val g = MediaRepository.LanPairGrant.parse("""{"ble_token":"blekey"}""")!!
        assertNull(g.token)
        assertEquals("blekey", g.bleToken)
    }

    @Test
    fun parsesBothTokens() {
        val g = MediaRepository.LanPairGrant.parse("""{"token":"sekrit","ble_token":"blekey"}""")!!
        assertEquals("sekrit", g.token)
        assertEquals("blekey", g.bleToken)
    }

    @Test
    fun malformedOrEmptyBodyReturnsNull() {
        assertNull(MediaRepository.LanPairGrant.parse(""))
        assertNull(MediaRepository.LanPairGrant.parse("not json"))
        assertNull(MediaRepository.LanPairGrant.parse("null"))
    }
}
