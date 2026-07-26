package com.juziss.localmediahub.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Production [BleCentralManager] backed by Android's BluetoothGatt API.
 *
 * NOTE: This is a scaffolding implementation for the MVP. The actual GATT
 * connect/services/characteristics wiring is completed during manual
 * hardware verification (the protocol + state machine are already proven
 * by unit tests). Until then, scan/connect are no-ops that do not affect
 * Wi-Fi/HTTP behavior.
 */
class AndroidBleCentralManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : BleCentralManager {

    private val adapter by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE)
            ?.let { it as? BluetoothManager }?.adapter
    }

    override var onStateChanged: ((BleConnState) -> Unit)? = null
    override var onPayloadReceived: ((ByteArray) -> Unit)? = null

    override fun startScan() {
        // TODO(hardware-integration): BluetoothLeScanner.startScan with a
        // filter on BleProtocol.SERVICE_UUID. No-op until verified on hardware.
    }

    override fun stopScan() {
        // TODO(hardware-integration): BluetoothLeScanner.stopScan.
    }

    override fun send(payload: ByteArray): Boolean {
        // TODO(hardware-integration): write to COMMAND_CHAR_UUID characteristic.
        return false
    }

    /** True iff a Bluetooth adapter exists and is powered on. */
    fun isAdapterUsable(): Boolean = adapter?.isEnabled == true
}
