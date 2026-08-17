package com.juziss.localmediahub.ble

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Task 10 (H-1c / L-9): the GATT write-request admission policy is extracted
 * into pure top-level functions ([shouldAcceptWrite] / [isCccd], plus the
 * [WriteDecision] enum) in `AndroidBlePeripheralManager.kt` so the guards are
 * unit-testable without a Bluetooth stack.
 *
 * `BluetoothDevice.BOND_*` are compile-time int constants, so they resolve in
 * plain JVM tests; the CCCD check takes a plain `java.util.UUID` (exactly what
 * `BluetoothGattDescriptor.getUuid()` returns in the production callback).
 */
class BlePeripheralGuardsTest {

    @Test
    fun writeGuardRejectsUnbondedOffsetAndPrepared() {
        // Encrypted mode (requireBond=true): unbonded (or mid-bonding)
        // writers get GATT_INSUFFICIENT_AUTHENTICATION.
        assertEquals(
            WriteDecision.REJECT_AUTH,
            shouldAcceptWrite(BluetoothDevice.BOND_NONE, 0, false, requireBond = true),
        )
        assertEquals(
            WriteDecision.REJECT_AUTH,
            shouldAcceptWrite(BluetoothDevice.BOND_BONDING, 0, false, requireBond = true),
        )
        // Offset (partial) writes and prepared (long) writes are refused —
        // the Command characteristic is a fixed-size one-shot frame channel.
        assertEquals(
            WriteDecision.REJECT_NOT_SUPPORTED,
            shouldAcceptWrite(BluetoothDevice.BOND_BONDED, 4, false),
        )
        assertEquals(
            WriteDecision.REJECT_NOT_SUPPORTED,
            shouldAcceptWrite(BluetoothDevice.BOND_BONDED, 0, true),
        )
        // The admissible shape: bonded peer, single write at offset 0.
        assertEquals(
            WriteDecision.ACCEPT,
            shouldAcceptWrite(BluetoothDevice.BOND_BONDED, 0, false),
        )
    }

    @Test
    fun writeGuardDefaultModeAcceptsUnbondedButKeepsShapeChecks() {
        // Default mode (REQUIRE_ENCRYPTED_LINK=false, per the real-device
        // pairing findings): bond state is NOT enforced — authentication is
        // the controller's HMAC handshake — but malformed write shapes are
        // still rejected at the GATT layer.
        assertEquals(
            WriteDecision.ACCEPT,
            shouldAcceptWrite(BluetoothDevice.BOND_NONE, 0, false),
        )
        assertEquals(
            WriteDecision.REJECT_NOT_SUPPORTED,
            shouldAcceptWrite(BluetoothDevice.BOND_NONE, 4, false),
        )
        assertEquals(
            WriteDecision.REJECT_NOT_SUPPORTED,
            shouldAcceptWrite(BluetoothDevice.BOND_BONDED, 0, true),
        )
    }

    @Test
    fun onlyCccdReplacesSubscriber() {
        assertTrue(isCccd(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")))
        // Characteristic User Description (0x2901) and any custom UUID must
        // never be treated as a Notify subscription.
        assertFalse(isCccd(UUID.fromString("00002901-0000-1000-8000-00805f9b34fb")))
        assertFalse(isCccd(UUID.fromString("fa6a3002-8b2c-4e6f-9988-123456789abc")))
    }

    @Test
    fun bondRequestedOnlyForUnbondedPeersInEncryptedMode() {
        // Encrypted mode: only a BOND_NONE peer needs the Peripheral to kick
        // off Just Works pairing on connection.
        assertTrue(shouldRequestBond(BluetoothDevice.BOND_NONE, requireEncryption = true))
        assertFalse(shouldRequestBond(BluetoothDevice.BOND_BONDED, requireEncryption = true))
        // Bonding already in flight — createBond() must not be re-issued.
        assertFalse(shouldRequestBond(BluetoothDevice.BOND_BONDING, requireEncryption = true))
        // Default mode (encryption optional): pairing is never initiated —
        // OS-level pairing proved unestablishable in practice, and the HMAC
        // handshake carries the authentication load alone.
        assertFalse(shouldRequestBond(BluetoothDevice.BOND_NONE))
    }
}
