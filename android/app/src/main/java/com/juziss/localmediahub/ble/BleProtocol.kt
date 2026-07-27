package com.juziss.localmediahub.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * BLE GATT control-channel protocol. Wire format MUST match server-side
 * `server/internal/ble/protocol.go` exactly:
 *   [0]    version
 *   [1:3]  uint16 payload length (big-endian)
 *   [3:]   payload bytes
 */
object BleProtocol {
    const val SERVICE_UUID = "fa6a3001-8b2c-4e6f-9988-123456789abc"
    const val COMMAND_CHAR_UUID = "fa6a3002-8b2c-4e6f-9988-123456789abc" // Write C -> S
    const val STATE_CHAR_UUID = "fa6a3003-8b2c-4e6f-9988-123456789abc"   // Notify S -> C

    const val FRAME_VERSION: Byte = 0x01
    const val MAX_PAYLOAD_LEN = 244

    data class Frame(val payload: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = payload.contentHashCode()
    }

    fun encodeFrame(payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(3 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(FRAME_VERSION)
        // length as uint16 big-endian
        buf.put(((payload.size shr 8) and 0xFF).toByte())
        buf.put((payload.size and 0xFF).toByte())
        buf.put(payload)
        return buf.array()
    }

    fun decodeFrame(data: ByteArray): Frame? {
        if (data.size < 3) return null
        if (data[0] != FRAME_VERSION) return null
        val length = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        if (length > MAX_PAYLOAD_LEN) return null
        if (data.size < 3 + length) return null
        return Frame(data.copyOfRange(3, 3 + length))
    }
}
