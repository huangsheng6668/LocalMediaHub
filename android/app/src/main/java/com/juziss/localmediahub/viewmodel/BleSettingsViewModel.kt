package com.juziss.localmediahub.viewmodel

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.juziss.localmediahub.R
import com.juziss.localmediahub.ble.BleConnState
import com.juziss.localmediahub.ble.BleController
import com.juziss.localmediahub.data.BleApi
import com.juziss.localmediahub.data.BleDevice
import com.juziss.localmediahub.data.ServerConfigStore
import com.juziss.localmediahub.network.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
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
    val store: ServerConfigStore,
    @Named("bleEnabled") private val bleEnabledFlow: Flow<Boolean>,
) : AndroidViewModel(application) {

    // AndroidViewModel already holds the Application; a short alias keeps the
    // localized BLE error strings readable (getString is not @Composable-safe
    // but ViewModels run outside composition).
    private fun s(res: Int) = getApplication<Application>().getString(res)
    private fun s(res: Int, vararg args: Any) = getApplication<Application>().getString(res, *args)

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

    /**
     * Last connected device MAC address stored in DataStore (null if none).
     */
    val lastConnectedMac: StateFlow<String?> = store.lastConnectedBleAddress
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * 专用 BLE 握手密钥（对应 server 的 ble.token）。空 = 未配置，握手密钥
     * 回退到 authToken（解析规则在 BleController.resolveBleKey，两端对称）。
     */
    val bleToken: StateFlow<String> = store.bleToken
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 保存/清除专用 BLE 密钥。保存后需重新握手才生效。 */
    fun saveBleToken(token: String) {
        viewModelScope.launch {
            store.saveBleToken(token.trim())
        }
    }

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

    private var backgroundConnectJob: Job? = null
    private var manualConnectJob: Job? = null
    private var previousConnState: BleConnState? = null

    init {
        viewModelScope.launch {
            combine(
                bleEnabled,
                store.serverUrl,
                controller.connectionState
            ) { enabled, url, connState ->
                Triple(enabled, url, connState)
            }.collect { (enabled, url, connState) ->
                val wasConnected = previousConnState == BleConnState.CONNECTED
                val isNowDisconnectedOrAdv =
                    connState == BleConnState.ADVERTISING || connState == BleConnState.DISCONNECTED
                val isEligible = enabled && hardwareAvailable() && url.isNotBlank() && isNowDisconnectedOrAdv

                val stateChanged = previousConnState != connState
                previousConnState = connState

                if (!isEligible) {
                    if (connState == BleConnState.CONNECTED || !enabled || url.isBlank()) {
                        backgroundConnectJob?.cancel()
                        backgroundConnectJob = null
                    }
                    return@collect
                }

                if (wasConnected && stateChanged) {
                    // 断线智能退避重连 (3s ➔ 10s ➔ 30s 退避，最多 3 次)
                    backgroundConnectJob?.cancel()
                    backgroundConnectJob = launch {
                        val delays = listOf(3_000L, 10_000L, 30_000L)
                        for (delayMs in delays) {
                            delay(delayMs)
                            if (!isActive) break
                            val success = doAutoConnectOnce(silent = true)
                            if (success) break
                        }
                    }
                } else {
                    // 静默预建联 (0s ➔ 5s ➔ 15s 退避，3 次全部失败后进入 60s 冷却期)
                    if (backgroundConnectJob?.isActive == true) {
                        return@collect
                    }
                    backgroundConnectJob = launch {
                        while (isActive) {
                            val delays = listOf(0L, 5_000L, 15_000L)
                            var connected = false
                            for (delayMs in delays) {
                                if (delayMs > 0) delay(delayMs)
                                if (!isActive) break
                                val success = doAutoConnectOnce(silent = true)
                                if (success) {
                                    connected = true
                                    break
                                }
                            }
                            if (connected) break
                            // 3 次全部失败后进入 60s 冷却期
                            delay(60_000L)
                        }
                    }
                }
            }
        }
    }

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
     * One-click BLE channel connection (manual button). Cancels any ongoing
     * background auto-connect job, resets backoff/cooldown, and executes
     * [doAutoConnectOnce] with silent = false.
     */
    fun autoConnect() {
        backgroundConnectJob?.cancel()
        backgroundConnectJob = null
        manualConnectJob?.cancel()
        manualConnectJob = viewModelScope.launch {
            doAutoConnectOnce(silent = false)
        }
    }

    /**
     * One scan+connect attempt. Returns true if the BLE link reached CONNECTED.
     * Sets _scanning for the duration.
     *
     * @param silent when true, failures do not write to [_errorText], avoiding
     *   polluting the UI during background auto-connect attempts.
     */
    suspend fun doAutoConnectOnce(silent: Boolean = false): Boolean {
        if (!silent) {
            _errorText.value = null
        }
        _scanning.value = true
        try {
            return when (val scanResult = api.scan()) {
                is NetworkResult.Success -> {
                    val discovered = scanResult.data
                    _devices.value = discovered
                    if (discovered.isEmpty()) {
                        if (!silent) {
                            _errorText.value = s(R.string.ble_err_no_advertising)
                        }
                        controller.markDisconnected()
                        return false
                    }
                    val lastMac = store.lastConnectedBleAddress.firstOrNull()
                    val mgr = getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE)
                        as? BluetoothManager
                    val localName = try {
                        mgr?.adapter?.name?.takeIf { it.isNotBlank() }
                    } catch (_: SecurityException) {
                        null
                    } ?: android.os.Build.MODEL

                    // Phase 9 (Task 11 / H-1d): fail-closed 选路。三级匹配
                    // （历史 MAC → 设备名 → RSSI 最强）选不出目标时不再兜底
                    // discovered.first() 去连列表里的任意设备——直接报错并
                    // 放弃本次连接。设备列表本身来自 PC 端 /api/v1/ble/scan，
                    // PC 侧已按完整 128-bit UUID 精确匹配过滤，因此 RSSI 最强
                    // 一档也必然是 UUID 命中的设备。
                    val target = selectBestDevice(discovered, lastMac, localName)
                    if (target == null) {
                        controller.markDisconnected()
                        if (!silent) {
                            _errorText.value = s(R.string.ble_err_no_match)
                        }
                        return false
                    }
                    when (val connResult = api.connect(target.id)) {
                        is NetworkResult.Success -> if (connResult.data) {
                            store.saveLastConnectedBleAddress(target.id)
                            controller.markConnected()
                            return true
                        } else {
                            controller.markDisconnected()
                            if (!silent) {
                                _errorText.value = s(R.string.ble_err_connect_gatt)
                            }
                            return false
                        }
                        is NetworkResult.Error -> {
                            controller.markDisconnected()
                            if (!silent) {
                                _errorText.value = s(R.string.ble_err_connect_fmt, connResult.message)
                            }
                            return false
                        }
                        else -> {
                            controller.markDisconnected()
                            return false
                        }
                    }
                }
                is NetworkResult.Error -> {
                    _devices.value = emptyList()
                    controller.markDisconnected()
                    if (!silent) {
                        val cause = if (scanResult.message.contains("ble unavailable")) {
                            s(R.string.ble_err_server_not_ready)
                        } else scanResult.message
                        _errorText.value = s(R.string.ble_err_connect_cause_fmt, cause)
                    }
                    return false
                }
                else -> {
                    _devices.value = emptyList()
                    controller.markDisconnected()
                    return false
                }
            }
        } finally {
            _scanning.value = false
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
                            _errorText.value = s(R.string.ble_err_scan_none)
                        }
                    }
                    is NetworkResult.Error -> {
                        _devices.value = emptyList()
                        val cause = if (result.message.contains("ble unavailable")) {
                            s(R.string.ble_err_server_not_ready)
                        } else {
                            result.message
                        }
                        _errorText.value = s(R.string.ble_err_scan_fmt, cause)
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
                        store.saveLastConnectedBleAddress(device.id)
                        controller.markConnected()
                    } else {
                        controller.markDisconnected()
                        _errorText.value = s(R.string.ble_err_gatt_connect2)
                    }
                }
                is NetworkResult.Error -> {
                    controller.markDisconnected()
                    _errorText.value = s(R.string.ble_err_connect_fmt, result.message)
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
                        _echoResult.value = s(R.string.ble_send_failed)
                        _errorText.value = s(R.string.ble_err_no_echo)
                        controller.markDisconnected()
                    }
                }
                is NetworkResult.Error -> {
                    _echoResult.value = s(R.string.ble_send_failed)
                    _errorText.value = s(R.string.ble_err_send_fmt, result.message)
                    controller.markDisconnected()
                }
                else -> {
                    _echoResult.value = s(R.string.ble_send_failed)
                    controller.markDisconnected()
                }
            }
        }
    }

    companion object {
        /**
         * 智能选路算法（Phase 9 / Task 11 后无任意设备兜底）：
         * 1. 优先匹配上次成功连接的 MAC 地址（忽略大小写）；
         * 2. 匹配当前设备名称（忽略大小写）；
         * 3. 选择 RSSI 信号最强的设备；
         * 4. 列表为空返回 null。
         *
         * 入参 [discovered] 来自 PC 端 `/api/v1/ble/scan`，PC 侧已按完整
         * 128-bit ServiceUUID 精确匹配过滤——因此第 3 档选出的设备也必然
         * 是 UUID 命中的 LocalMediaHub 设备，不会选中陌生设备。调用方对
         * null 必须 fail-closed（报错并放弃连接），不得再兜底连第一个设备。
         */
        fun selectBestDevice(
            discovered: List<BleDevice>,
            lastConnectedMac: String?,
            localDeviceName: String?
        ): BleDevice? {
            if (discovered.isEmpty()) return null
            if (!lastConnectedMac.isNullOrBlank()) {
                val byMac = discovered.find { it.id.equals(lastConnectedMac, ignoreCase = true) }
                if (byMac != null) return byMac
            }
            if (!localDeviceName.isNullOrBlank()) {
                val byName = discovered.find { it.name.equals(localDeviceName, ignoreCase = true) }
                if (byName != null) return byName
            }
            return discovered.maxByOrNull { it.rssi }
        }
    }
}
