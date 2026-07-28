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
import com.juziss.localmediahub.network.ServerConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

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
    private val store: ServerConfigStore,
    private val serverConfig: ServerConfig,
    @Named("bleEnabled") private val bleEnabledFlow: Flow<Boolean>,
) : AndroidViewModel(application) {

    private val _optimisticBleEnabled = MutableStateFlow<Boolean?>(null)

    val bleEnabled: StateFlow<Boolean> = combine(
        store.bleEnabled,
        _optimisticBleEnabled
    ) { persisted, optimistic ->
        optimistic ?: persisted
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly, false
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

    private val _errorText = MutableStateFlow<String?>(null)
    /** Human-readable error message from the last operation (null if clean). */
    val errorText: StateFlow<String?> = _errorText.asStateFlow()

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
            _optimisticBleEnabled.value = requested
            _errorText.value = null
            try {
                controller.setEnabled(requested)
            } finally {
                _optimisticBleEnabled.value = null
            }
        }
    }

    /**
     * One-click BLE channel connection (manual button). Single-call wrapper
     * around [doAutoConnectOnce]; the automatic path uses [autoConnectWithRetry].
     */
    fun autoConnect() {
        viewModelScope.launch { doAutoConnectOnce() }
    }

    /**
     * One scan+connect attempt. Returns true if the BLE link reached CONNECTED.
     * Shared by the manual button ([autoConnect], single call) and the automatic
     * path ([autoConnectWithRetry], 3 attempts). Sets _scanning for the duration.
     */
    private suspend fun doAutoConnectOnce(): Boolean {
        _errorText.value = null
        _scanning.value = true
        try {
            return when (val scanResult = api.scan()) {
                is NetworkResult.Success -> {
                    val discovered = scanResult.data
                    _devices.value = discovered
                    if (discovered.isEmpty()) {
                        _errorText.value = "未发现当前手机的 BLE 广播（请确保手机蓝牙已开启且距离电脑较近）"
                        controller.markDisconnected()
                        return false
                    }
                    val target = discovered.first()
                    when (val connResult = api.connect(target.id)) {
                        is NetworkResult.Success -> if (connResult.data) {
                            controller.markConnected(); return true
                        } else {
                            controller.markDisconnected()
                            _errorText.value = "连接失败：服务端未能建立 BLE GATT 连接"; return false
                        }
                        is NetworkResult.Error -> {
                            controller.markDisconnected()
                            _errorText.value = "连接失败: ${connResult.message}"; return false
                        }
                        else -> { controller.markDisconnected(); return false }
                    }
                }
                is NetworkResult.Error -> {
                    _devices.value = emptyList(); controller.markDisconnected()
                    val cause = if (scanResult.message.contains("ble unavailable")) {
                        "服务端蓝牙未就绪（请确认 PC 已配有蓝牙且服务端使用 go build -tags bluetooth 编译）"
                    } else scanResult.message
                    _errorText.value = "建立连接失败: $cause"; return false
                }
                else -> { _devices.value = emptyList(); controller.markDisconnected(); return false }
            }
        } finally {
            _scanning.value = false
        }
    }

    // --- Task 2: automatic connect trigger + 3x retry ---------------------

    /**
     * Dedup flag for [autoConnectWithRetry]. Set true when a retry loop starts;
     * reset to false when a precondition drops (server URL blanked, BLE toggle
     * off) or when the connection leaves CONNECTED, so a subsequent
     * `advertisingStarted == true` can re-arm a fresh retry loop.
     */
    private var autoConnectArmed: Boolean = false

    /**
     * Runs [doAutoConnectOnce] up to 3 times, 3s apart, stopping early on
     * CONNECTED. Deduped by [autoConnectArmed]; cleared by the precondition
     * and connection-state collectors in [init].
     */
    private fun autoConnectWithRetry() {
        if (autoConnectArmed) return
        autoConnectArmed = true
        viewModelScope.launch {
            repeat(3) {
                if (controller.connectionState.value == BleConnState.CONNECTED) return@launch
                if (doAutoConnectOnce()) return@launch
                delay(3_000)
            }
            // Retries exhausted without a connection: surface a soft hint so the
            // user knows the degraded channel is not yet usable and can retry.
            if (controller.connectionState.value != BleConnState.CONNECTED) {
                _errorText.value = "BLE 自动连接失败，降级通道暂不可用（可手动重试）"
            }
        }
    }

    init {
        // Three-signal auto-connect (spec §1.1): fires only when (server
        // configured) AND (BLE toggle on) AND (advertising started) all hold.
        // The combine below watches the *precondition* signals so a drop resets
        // autoConnectArmed; the advertisingStarted collector fires the actual
        // retry loop. We deliberately do NOT run an HTTP healthCheck here —
        // Wi-Fi may be down (the whole point of the BLE failover channel).
        viewModelScope.launch {
            val serverConfigured = combine(
                store.serverUrl,
                serverConfig.baseUrl,
            ) { url, base -> url.isNotBlank() && base.isNotBlank() }
            combine(serverConfigured, bleEnabledFlow) { srv, ble -> srv to ble }
                .distinctUntilChanged()
                .collect { (srv, ble) ->
                    if (!srv || !ble) {
                        // A precondition dropped: allow the next advertising
                        // burst to start a fresh retry loop.
                        autoConnectArmed = false
                    }
                }
        }
        viewModelScope.launch {
            controller.advertisingStarted.collect { started ->
                if (started &&
                    store.serverUrl.first().isNotBlank() &&
                    serverConfig.baseUrl.value.isNotBlank() &&
                    bleEnabledFlow.first()
                ) {
                    autoConnectWithRetry()
                }
            }
        }
        viewModelScope.launch {
            controller.connectionState.collect { st ->
                if (st != BleConnState.CONNECTED) autoConnectArmed = false
            }
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
            _errorText.value = null
            _scanning.value = true
            try {
                when (val result = api.scan()) {
                    is NetworkResult.Success -> {
                        _devices.value = result.data
                        if (result.data.isEmpty()) {
                            _errorText.value = "未扫描到可连接的 BLE 设备（请确保 PC 蓝牙已开启，并且 PC 服务端以 -tags bluetooth 编译启动）"
                        }
                    }
                    is NetworkResult.Error -> {
                        _devices.value = emptyList()
                        val cause = if (result.message.contains("ble unavailable")) {
                            "服务端蓝牙未就绪（请确认 PC 已配有蓝牙且服务端使用 go build -tags bluetooth 编译）"
                        } else {
                            result.message
                        }
                        _errorText.value = "扫描失败: $cause"
                    }
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
            _errorText.value = null
            when (val result = api.connect(device.id)) {
                is NetworkResult.Success -> {
                    if (result.data) {
                        controller.markConnected()
                    } else {
                        controller.markDisconnected()
                        _errorText.value = "连接失败：PC 服务端未能与设备建立 BLE GATT 连接"
                    }
                }
                is NetworkResult.Error -> {
                    controller.markDisconnected()
                    _errorText.value = "连接失败: ${result.message}"
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
            _errorText.value = null
            when (val result = api.send("ping")) {
                is NetworkResult.Success -> {
                    if (result.data != null) {
                        _echoResult.value = result.data
                    } else {
                        _echoResult.value = "发送失败"
                        _errorText.value = "未收到 BLE GATT Echo 回声响应"
                        controller.markDisconnected()
                    }
                }
                is NetworkResult.Error -> {
                    _echoResult.value = "发送失败"
                    _errorText.value = "发送失败: ${result.message}"
                    controller.markDisconnected()
                }
                else -> {
                    _echoResult.value = "发送失败"
                    controller.markDisconnected()
                }
            }
        }
    }
}
