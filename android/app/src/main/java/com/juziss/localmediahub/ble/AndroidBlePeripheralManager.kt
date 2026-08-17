package com.juziss.localmediahub.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject

/**
 * Outcome of the Task 10 GATT write-request guard ([shouldAcceptWrite]).
 * Mapped onto wire statuses by the callbacks:
 *  - [REJECT_AUTH]          → `BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION`
 *  - [REJECT_NOT_SUPPORTED] → `BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED`
 *  - [ACCEPT]               → `GATT_SUCCESS` (write processed)
 */
enum class WriteDecision { ACCEPT, REJECT_AUTH, REJECT_NOT_SUPPORTED }

/**
 * Task 10 (H-1c) write-request admission policy, extracted as a pure function
 * so it is unit-testable without a Bluetooth stack (BlePeripheralGuardsTest).
 *
 * Only a BONDED peer is admitted: the Command/State characteristics are
 * PERMISSION_*_ENCRYPTED, and the LE stack auto-initiates the Just Works
 * pairing flow when the first encrypted access is refused with
 * GATT_INSUFFICIENT_AUTHENTICATION (one-time per peer, expected UX).
 *
 * Known timing edge (recorded per brief, NOT relaxed): if the link is already
 * encrypted but `bondState` has not yet flipped to BOND_BONDED, this guard
 * still refuses; the peer retries after the bond completes and succeeds.
 * Security errs closed — an unauthenticated frame must never reach the
 * controller's auth gate.
 *
 * Offset (partial) and prepared (long) writes are rejected as
 * GATT_REQUEST_NOT_SUPPORTED: the Command characteristic is a fixed-size
 * one-shot frame channel; reassembly/fragmentation semantics do not exist in
 * this protocol and must not be silently synthesized by the GATT layer.
 */
fun shouldAcceptWrite(bondState: Int, offset: Int, preparedWrite: Boolean): WriteDecision {
    if (bondState != BluetoothDevice.BOND_BONDED) return WriteDecision.REJECT_AUTH
    if (offset != 0 || preparedWrite) return WriteDecision.REJECT_NOT_SUPPORTED
    return WriteDecision.ACCEPT
}

/**
 * Task 10 (L-9): true iff [uuid] is the standard Client Characteristic
 * Configuration Descriptor (0x2902) — the ONLY descriptor whose write may
 * replace the notify subscriber in [AndroidBlePeripheralManager].
 */
fun isCccd(uuid: UUID): Boolean = uuid == UUID.fromString(CCCD_UUID)

/** Client Characteristic Configuration Descriptor UUID (standard 0x2902). */
private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

/**
 * Production [BlePeripheralManager] backed by Android's BluetoothLeAdvertiser
 * + BluetoothGattServer.
 *
 * Lifecycle:
 *   startAdvertising() → opens GATT server with Command (Write) + State (Notify)
 *                        characteristics, begins advertising SERVICE_UUID.
 *   stopAdvertising()  → stops advertising + closes server.
 *
 * Central (PC) writes Command → onCharacteristicWriteRequest → RAW frame
 * pass-through: the exact on-air bytes (v1 handshake frames and v2
 * authenticated frames alike) are handed to the onRawFrameReceived callback
 * with no decoding/re-framing — v1/v2 dispatch lives in BleController
 * .onCommandWrite (Task 10 seam close-out). Central subscribes to State
 * (CCCD) → we hold the subscriber device; notifyPayload writes/notifications
 * go there.
 *
 * Task 10 hardening (H-1c / L-9):
 *  - Command characteristic: PERMISSION_WRITE_ENCRYPTED; State:
 *    PERMISSION_READ_ENCRYPTED. The first encrypted access triggers the
 *    system LE Just Works pairing (one-time per peer, expected).
 *  - onCharacteristicWriteRequest / onDescriptorWriteRequest are guarded by
 *    [shouldAcceptWrite]: unbonded → GATT_INSUFFICIENT_AUTHENTICATION,
 *    offset/prepared → GATT_REQUEST_NOT_SUPPORTED; a rejected write never
 *    reaches the controller callback nor replaces the subscriber.
 *  - Only a CCCD (0x2902) descriptor write ([isCccd]) may replace the notify
 *    subscriber; any other descriptor write is refused with
 *    GATT_REQUEST_NOT_SUPPORTED.
 *
 * NOTE: MTU negotiation remains Central-driven; multi-subscriber fanout and
 * auto-reconnect are intentionally out of scope (YAGNI).
 */
class AndroidBlePeripheralManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : BlePeripheralManager {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiserCallback: AdvertiseCallback? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var stateChar: BluetoothGattCharacteristic? = null
    private var subscriberDevice: BluetoothDevice? = null
    private var onRawFrameReceived: ((ByteArray) -> Unit)? = null
    private var onAdvertisingStarted: ((Boolean) -> Unit)? = null
    private var onPeerConnected: (() -> Unit)? = null
    private var onPeerDisconnected: (() -> Unit)? = null

    override fun isAdapterUsable(): Boolean = adapter?.isEnabled == true

    override fun setOnRawFrameReceived(cb: (ByteArray) -> Unit) {
        onRawFrameReceived = cb
    }

    override fun setOnAdvertisingStarted(cb: (Boolean) -> Unit) {
        onAdvertisingStarted = cb
    }

    override fun setOnPeerConnected(cb: () -> Unit) {
        onPeerConnected = cb
    }

    override fun setOnPeerDisconnected(cb: () -> Unit) {
        onPeerDisconnected = cb
    }

    override fun startAdvertising() {
        val mgr = bluetoothManager ?: return
        val ad = adapter ?: return
        if (gattServer != null) return // already started

        val service = BluetoothGattService(
            UUID.fromString(BleProtocol.SERVICE_UUID),
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        // Task 10 (H-1c): link-layer encrypted access only. First write/read
        // from an unbonded peer is refused with insufficient authentication,
        // which makes the LE stack run the Just Works pairing flow once.
        val cmd = BluetoothGattCharacteristic(
            UUID.fromString(BleProtocol.COMMAND_CHAR_UUID),
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
        )
        val state = BluetoothGattCharacteristic(
            UUID.fromString(BleProtocol.STATE_CHAR_UUID),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
        )
        // CCCD required for Notify subscribers. Kept PERMISSION_WRITE (not
        // encrypted): the bond guard in onDescriptorWriteRequest is the
        // Task 10 gate for unbonded CCCD writes, per the audit design.
        val cccd = BluetoothGattDescriptor(
            UUID.fromString(CCCD_UUID),
            BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        state.addDescriptor(cccd)
        service.addCharacteristic(cmd)
        service.addCharacteristic(state)
        commandChar = cmd
        stateChar = state

        gattServer = mgr.openGattServer(context, gattCallback)
        gattServer?.addService(service)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        // Advertise packet: Keep primary data payload strictly <= 31 bytes by putting
        // device name into scanResponseData, preventing ADVERTISE_FAILED_DATA_TOO_LARGE (errorCode 1).
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(UUID.fromString(BleProtocol.SERVICE_UUID)))
            .build()
        val scanResponseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                android.util.Log.i("BlePeripheral", "advertise onStartSuccess")
                onAdvertisingStarted?.invoke(true)
            }
            override fun onStartFailure(errorCode: Int) {
                android.util.Log.e("BlePeripheral", "advertise onStartFailure errorCode=$errorCode")
                onAdvertisingStarted?.invoke(false)
            }
        }
        advertiserCallback = cb
        android.util.Log.i("BlePeripheral", "startAdvertising: advertiser=${ad.bluetoothLeAdvertiser != null}")
        ad.bluetoothLeAdvertiser?.startAdvertising(settings, data, scanResponseData, cb)
    }

    override fun stopAdvertising() {
        // Null-guard: evaluateAvailability(false) can be called before any
        // startAdvertising() (e.g. initial bleEnabled=false emission from
        // DataStore). BluetoothLeAdvertiser.stopAdvertising throws
        // IllegalArgumentException when passed a null callback.
        advertiserCallback?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) }
        advertiserCallback = null
        gattServer?.close()
        gattServer = null
        subscriberDevice = null
        commandChar = null
        stateChar = null
    }

    override fun disconnectPeer() {
        // Task 10 fatal-path wiring (minimal): BleController calls this when
        // an auth/protocol violation kills the session. cancelConnection is
        // the public GATT-server-side disconnect (BluetoothGattServer
        // .disconnect is hidden); it also cancels a pending inbound connect.
        // The subscriber is dropped immediately so no post-fatal notify can
        // go out even if the link lingers briefly. onConnectionStateChange
        // (STATE_DISCONNECTED) will fire next and re-clear it harmlessly.
        val dev = subscriberDevice ?: return
        android.util.Log.w("BlePeripheral", "disconnectPeer: cancelling GATT link (fatal auth/protocol violation)")
        gattServer?.cancelConnection(dev)
        subscriberDevice = null
    }

    override fun notifyPayload(payload: ByteArray): Boolean {
        val server = gattServer ?: return false
        val dev = subscriberDevice ?: return false
        val state = stateChar ?: return false
        state.value = payload
        // Use notifyCharacteristicChanged (not notify): Kotlin resolves
        // BluetoothGattServer.notify(device, char) ambiguously against the
        // final java.lang.Object.notify() inherited by the framework class,
        // producing "Unresolved reference 'notify'". notifyCharacteristicChanged
        // with confirm=false is the equivalent public API.
        return server.notifyCharacteristicChanged(dev, state, false)
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Existing behavior (known deferred item, deliberately
                // unchanged): the subscriber is set optimistically here; a
                // later bonded CCCD write re-asserts it.
                subscriberDevice = device
                // Phase 9 (C-1): per-connection auth reset fires at GATT-link
                // establishment. At this instant the Central's handshake
                // challenge cannot have arrived yet (the PC writes it only
                // after its Connect() returns from the link layer), so the
                // ordering is inherently race-free — unlike the old
                // markConnected() reset, which landed after the HTTP
                // coordination round-trip and wiped an already-completed
                // handshake.
                onPeerConnected?.invoke()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscriberDevice = null
                // Phase 9 (C-1): link gone — auth state must not survive into
                // the next connection (it must re-handshake).
                onPeerDisconnected?.invoke()
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val server = gattServer ?: return
            // Task 10 guard: bond + write-shape admission. A rejected write
            // is answered (when a response is needed) and DROPPED — the
            // controller callback must not see unauthenticated or malformed
            // writes. REJECT_AUTH doubles as the pairing trigger (the stack
            // initiates LE Just Works when it sees the error).
            when (shouldAcceptWrite(device.bondState, offset, preparedWrite)) {
                WriteDecision.REJECT_AUTH -> {
                    if (responseNeeded) {
                        server.sendResponse(
                            device, requestId,
                            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, 0, null,
                        )
                    }
                    return
                }
                WriteDecision.REJECT_NOT_SUPPORTED -> {
                    if (responseNeeded) {
                        server.sendResponse(
                            device, requestId,
                            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null,
                        )
                    }
                    return
                }
                WriteDecision.ACCEPT -> Unit
            }
            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            }
            if (characteristic.uuid == UUID.fromString(BleProtocol.COMMAND_CHAR_UUID)) {
                // Task 10 raw pass-through: hand the controller the exact
                // on-air bytes — v1 handshake frames AND v2 authenticated
                // frames arrive here undecoded; BleController.onCommandWrite
                // owns the v1/v2 dispatch. (Pre-Task-10 this decoded v1 only,
                // so v2 frames from a real PC never reached the controller.)
                onRawFrameReceived?.invoke(value)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val server = gattServer ?: return
            // Task 10 guard: same bond + write-shape admission as the Command
            // characteristic — an unbonded peer must not subscribe to State.
            when (shouldAcceptWrite(device.bondState, offset, preparedWrite)) {
                WriteDecision.REJECT_AUTH -> {
                    if (responseNeeded) {
                        server.sendResponse(
                            device, requestId,
                            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, 0, null,
                        )
                    }
                    return
                }
                WriteDecision.REJECT_NOT_SUPPORTED -> {
                    if (responseNeeded) {
                        server.sendResponse(
                            device, requestId,
                            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null,
                        )
                    }
                    return
                }
                WriteDecision.ACCEPT -> Unit
            }
            // Task 10 (L-9): ONLY the CCCD (0x2902) write may replace the
            // notify subscriber; every other descriptor write is refused.
            val cccd = isCccd(descriptor.uuid)
            if (responseNeeded) {
                server.sendResponse(
                    device, requestId,
                    if (cccd) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    0, value,
                )
            }
            if (cccd) {
                subscriberDevice = device
            }
        }
    }
}
