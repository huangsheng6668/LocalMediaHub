package com.juziss.localmediahub.viewmodel

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.juziss.localmediahub.ble.BleConnState
import com.juziss.localmediahub.ble.BleController
import com.juziss.localmediahub.data.ServerConfigStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
}
