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
    /**
     * Phase 9 (C-1): register callback invoked when a Central establishes the
     * GATT link (production impl: onConnectionStateChange →
     * STATE_CONNECTED). [BleController] resets its per-connection auth state
     * HERE — at GATT-link establishment the peer's handshake challenge has
     * not arrived yet, so the reset can never race the handshake the way the
     * old markConnected()-driven reset did (the HTTP /connect response lands
     * AFTER the PC has already completed the mutual challenge over BLE).
     */
    fun setOnPeerConnected(cb: () -> Unit)
    /**
     * Phase 9 (C-1): register callback invoked when the GATT link to the
     * Central drops (production impl: onConnectionStateChange →
     * STATE_DISCONNECTED). [BleController] clears its auth state here: the
     * next link must re-run the mutual handshake before the data phase
     * reopens.
     */
    fun setOnPeerDisconnected(cb: () -> Unit)
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
