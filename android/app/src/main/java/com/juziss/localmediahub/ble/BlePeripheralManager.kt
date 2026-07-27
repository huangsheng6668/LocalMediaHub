package com.juziss.localmediahub.ble

/**
 * Abstraction over Android's BluetoothLeAdvertiser + BluetoothGattServer so
 * [BleController] is unit-testable without a real Bluetooth stack. The
 * production implementation [AndroidBlePeripheralManager] wires the system APIs.
 *
 * Android acts as the BLE Peripheral: it advertises SERVICE_UUID and serves
 * the Command (Write) + State (Notify) characteristics. The Central (PC server)
 * scans, connects, writes Command payloads, and subscribes to State notify.
 */
interface BlePeripheralManager {
    /** Begin advertising SERVICE_UUID + start the GATT server. */
    fun startAdvertising()
    /** Stop advertising + close the GATT server. */
    fun stopAdvertising()
    /** Register callback invoked when the Central writes to the Command characteristic. */
    fun setOnPayloadReceived(cb: (ByteArray) -> Unit)
    /** Send payload via the State characteristic (Notify). Returns false if no subscriber. */
    fun notifyPayload(payload: ByteArray): Boolean
    /** True iff a Bluetooth adapter exists and is powered on. */
    fun isAdapterUsable(): Boolean
}
