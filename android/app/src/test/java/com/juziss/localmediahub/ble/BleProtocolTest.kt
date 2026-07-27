package com.juziss.localmediahub.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleProtocolTest {

    @Test
    fun roundTrip_preservesPayload() {
        val payload = "hello-ble".toByteArray()
        val encoded = BleProtocol.encodeFrame(payload)
        val frame = BleProtocol.decodeFrame(encoded)
        assertArrayEquals(payload, frame?.payload)
    }

    @Test
    fun decode_returnsNullForTruncatedInput() {
        // Header only, no payload.
        val headerOnly = byteArrayOf(BleProtocol.FRAME_VERSION, 0x05, 0x00)
        assertNull(BleProtocol.decodeFrame(headerOnly))
    }

    @Test
    fun uuids_areDistinctAndMatchServerContract() {
        assertEquals("fa6a3001-8b2c-4e6f-9988-123456789abc", BleProtocol.SERVICE_UUID)
        assertEquals("fa6a3002-8b2c-4e6f-9988-123456789abc", BleProtocol.COMMAND_CHAR_UUID)
        assertEquals("fa6a3003-8b2c-4e6f-9988-123456789abc", BleProtocol.STATE_CHAR_UUID)
    }
}
