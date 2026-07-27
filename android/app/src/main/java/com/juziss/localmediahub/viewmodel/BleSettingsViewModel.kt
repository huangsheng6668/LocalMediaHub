package com.juziss.localmediahub.viewmodel

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.juziss.localmediahub.ble.BleConnState
import com.juziss.localmediahub.ble.BleController
import com.juziss.localmediahub.data.BleApi
import com.juziss.localmediahub.data.BleDevice
import com.juziss.localmediahub.data.ServerConfigStore
import com.juziss.localmediahub.network.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel backing the experimental BLE toggle in the connection/settings
 * screen. Mirrors the [ConnectionViewModel] pattern: a Hilt-injected
 * AndroidViewModel that surfaces DataStore state as `StateFlow` for Compose
 * to collect, and exposes a single intent method for the toggle.
 *
 * Why a dedicated VM (not extending ConnectionViewModel):
 *   - BLE is opt-in and experimental; keeping its state separate avoids
 *     polluting the connection-screen VM with BLE fields the rest of the
 *     UI does not care about.
 *   - The toggle only needs (a) the persisted setting, (b) the controller's
 *     connection state, (c) a hardware-availability snapshot. All three are
 *     app-scoped singletons, so the VM is cheap to construct.
 *
 * @param controller app-scoped singleton (Task 7/8) holding the connection
 *   state machine and the `setEnabled` API.
 * @param store DataStore-backed; provides the persisted `bleEnabled` flag.
 */
@HiltViewModel
class BleSettingsViewModel @Inject constructor(
    application: Application,
    private val controller: BleController,
    private val api: BleApi,
    store: ServerConfigStore,
) : AndroidViewModel(application) {

    /**
     * The persisted user setting. Default `false` (zero-regression: BLE is
     * off until the user explicitly opts in).
     */
    val bleEnabled: StateFlow<Boolean> = store.bleEnabled.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), false
    )

    /**
     * Live BLE connection state from the controller's state machine.
     */
    val connectionState: StateFlow<BleConnState> = controller.connectionState

    // --- Task 9: scan / connect / send echo state ---------------------------

    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    /** Devices returned by the last successful scan. Empty until [scan] runs. */
    val devices: StateFlow<List<BleDevice>> = _devices.asStateFlow()

    private val _echoResult = MutableStateFlow<String?>(null)
    /** Last echo payload received from the server (null before first send). */
    val echoResult: StateFlow<String?> = _echoResult.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    /** True while a scan is in flight (UI shows a loading indicator). */
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /**
     * True iff the device has a powered, present Bluetooth adapter. Re-read
     * on each call rather than cached so a user enabling Bluetooth in system
     * settings is reflected on the next recomposition that reads this.
     *
     * NOTE: This is a polling-style snapshot. We deliberately do NOT register
     * a BluetoothAdapter state listener for the MVP (YAGNI per the plan); the
     * value is refreshed when the user re-enters the screen or flips the
     * toggle. Matches the hardwareAvailable lambda used by the controller.
     */
    fun hardwareAvailable(): Boolean {
        val mgr = getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE)
            as? BluetoothManager
        return mgr?.adapter?.isEnabled == true
    }

    /**
     * Toggle handler invoked by the UI switch. Persists the setting and lets
     * the controller re-evaluate scan/idle/disabled. The UI is responsible
     * for obtaining runtime permissions BEFORE calling this with `true`; if
     * the permission is denied the UI should not call this (or call with
     * `false`).
     */
    fun onBleToggle(requested: Boolean) {
        viewModelScope.launch {
            controller.setEnabled(requested)
        }
    }

    /**
     * Ask the PC server (Central) to scan for BLE peripherals advertising the
     * shared SERVICE_UUID. On success, populates [devices]; on any error the
     * list is cleared so the UI doesn't show stale results. [scanning] is set
     * for the duration of the call so the UI can show a loading indicator.
     */
    fun scan() {
        viewModelScope.launch {
            _scanning.value = true
            try {
                when (val result = api.scan()) {
                    is NetworkResult.Success -> _devices.value = result.data
                    else -> _devices.value = emptyList()
                }
            } finally {
                _scanning.value = false
            }
        }
    }

    /**
     * Ask the PC server (Central) to connect to [device]. Drives the local
     * controller's state machine: a confirmed `connected:true` →
     * [BleController.markConnected]; anything else (failure or connected:false)
     * → [BleController.markDisconnected] to keep the UI honest.
     */
    fun connect(device: BleDevice) {
        viewModelScope.launch {
            when (val result = api.connect(device.id)) {
                is NetworkResult.Success -> {
                    if (result.data) controller.markConnected()
                    else controller.markDisconnected()
                }
                else -> controller.markDisconnected()
            }
        }
    }

    /**
     * Send a fixed "ping" payload through the PC server's BLE Central and
     * surface the echoed reply in [echoResult]. On any failure shows the
     * localized "发送失败" sentinel so the user knows the round-trip broke.
     */
    fun sendTest() {
        viewModelScope.launch {
            when (val result = api.send("ping")) {
                is NetworkResult.Success -> _echoResult.value = result.data
                else -> _echoResult.value = "发送失败"
            }
        }
    }
}
