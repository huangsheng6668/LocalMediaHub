package com.juziss.localmediahub.ble

/**
 * Abstraction over the Android BluetoothGatt API so [BleController] logic is
 * unit-testable without a real Bluetooth stack. The production implementation
 * (`AndroidBleCentralManager`, Task 8) wires BluetoothManager / BluetoothGatt;
 * this interface is the seam.
 */
interface BleCentralManager {
    fun startScan()
    fun stopScan()
    /** Returns true if the payload was written to the Command characteristic. */
    fun send(payload: ByteArray): Boolean
    var onStateChanged: ((BleConnState) -> Unit)?
    var onPayloadReceived: ((ByteArray) -> Unit)?
}
