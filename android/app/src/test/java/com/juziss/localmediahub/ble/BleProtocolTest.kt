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
        assertEquals("0000fc01-0000-1000-8000-00805f9b34fb", BleProtocol.SERVICE_UUID)
        assertEquals("0000fc02-0000-1000-8000-00805f9b34fb", BleProtocol.COMMAND_CHAR_UUID)
        assertEquals("0000fc03-0000-1000-8000-00805f9b34fb", BleProtocol.STATE_CHAR_UUID)
    }
}
