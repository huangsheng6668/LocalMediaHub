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
    /**
     * Register callback invoked when advertising actually starts
     * (AdvertiseCallback.onStartSuccess → true) or fails
     * (AdvertiseCallback.onStartFailure → false). Used by [BleController] to
     * surface a "broadcast ready" signal so callers do not attempt BLE
     * connect before the peripheral is discoverable (spec method B).
     */
    fun setOnAdvertisingStarted(cb: (success: Boolean) -> Unit)
    /**
     * Register callback invoked with the RAW frame bytes the Central wrote to
     * the Command characteristic (Task 10 raw pass-through): the exact
     * on-air bytes — v1 handshake frames and v2 authenticated frames alike —
     * with NO decoding or re-framing at this seam. The controller's
     * [BleController.onCommandWrite] owns v1/v2 dispatch and authentication.
     */
    fun setOnRawFrameReceived(cb: (rawFrame: ByteArray) -> Unit)
    /** Send payload via the State characteristic (Notify). Returns false if no subscriber. */
    fun notifyPayload(payload: ByteArray): Boolean
    /**
     * Task 10 fatal-path wiring: proactively drop the GATT link to the
     * connected Central (production impl: BluetoothGattServer.cancelConnection).
     * Called by [BleController] when an auth/protocol violation kills the
     * session. No-op when no peer is tracked.
     */
    fun disconnectPeer()
    /** True iff a Bluetooth adapter exists and is powered on. */
    fun isAdapterUsable(): Boolean
}
