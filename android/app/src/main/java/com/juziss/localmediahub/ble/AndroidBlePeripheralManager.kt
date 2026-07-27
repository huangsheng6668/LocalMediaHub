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
 * Production [BlePeripheralManager] backed by Android's BluetoothLeAdvertiser
 * + BluetoothGattServer.
 *
 * Lifecycle:
 *   startAdvertising() → opens GATT server with Command (Write) + State (Notify)
 *                        characteristics, begins advertising SERVICE_UUID.
 *   stopAdvertising()  → stops advertising + closes server.
 *
 * Central (PC) writes Command → onCharacteristicWriteRequest → decode frame →
 * onPayloadReceived callback. Central subscribes to State (CCCD) → we hold the
 * subscriber device; notifyPayload writes/notifications go there.
 *
 * NOTE: This is a minimal implementation sufficient for the MVP connectivity
 * verification (advertise + serve 2 chars + echo). MTU negotiation, multi-
 * subscriber fanout, and auto-reconnect are intentionally out of scope (YAGNI).
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
    private var onPayloadReceived: ((ByteArray) -> Unit)? = null

    override fun isAdapterUsable(): Boolean = adapter?.isEnabled == true

    override fun setOnPayloadReceived(cb: (ByteArray) -> Unit) {
        onPayloadReceived = cb
    }

    override fun startAdvertising() {
        val mgr = bluetoothManager ?: return
        val ad = adapter ?: return
        if (gattServer != null) return // already started

        val service = BluetoothGattService(
            UUID.fromString(BleProtocol.SERVICE_UUID),
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val cmd = BluetoothGattCharacteristic(
            UUID.fromString(BleProtocol.COMMAND_CHAR_UUID),
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val state = BluetoothGattCharacteristic(
            UUID.fromString(BleProtocol.STATE_CHAR_UUID),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        // CCCD required for Notify subscribers.
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
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(UUID.fromString(BleProtocol.SERVICE_UUID)))
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                android.util.Log.i("BlePeripheral", "advertise onStartSuccess")
            }
            override fun onStartFailure(errorCode: Int) {
                android.util.Log.e("BlePeripheral", "advertise onStartFailure errorCode=$errorCode")
            }
        }
        advertiserCallback = cb
        android.util.Log.i("BlePeripheral", "startAdvertising: advertiser=${ad.bluetoothLeAdvertiser != null}")
        ad.bluetoothLeAdvertiser?.startAdvertising(settings, data, cb)
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
                subscriberDevice = device
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscriberDevice = null
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
            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            }
            if (characteristic.uuid == UUID.fromString(BleProtocol.COMMAND_CHAR_UUID)) {
                val frame = BleProtocol.decodeFrame(value)
                if (frame != null) {
                    onPayloadReceived?.invoke(frame.payload)
                }
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
            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            }
            // CCCD subscription to State characteristic enables notify target.
            subscriberDevice = device
        }
    }

    private companion object {
        /** Client Characteristic Configuration Descriptor UUID (standard 0x2902). */
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    }
}
