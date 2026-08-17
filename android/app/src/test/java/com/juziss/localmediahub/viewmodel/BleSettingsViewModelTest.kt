package com.juziss.localmediahub.viewmodel

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.juziss.localmediahub.ble.BleConnState
import com.juziss.localmediahub.ble.BleController
import com.juziss.localmediahub.ble.BlePeripheralManager
import com.juziss.localmediahub.ble.BleTransportFallback
import com.juziss.localmediahub.data.BleApi
import com.juziss.localmediahub.data.BleDevice
import com.juziss.localmediahub.data.ServerConfigStore
import com.juziss.localmediahub.network.NetworkResult
import com.juziss.localmediahub.network.ServerConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Unit tests for the BLE scan/connect/sendTest flows (Task 9) and the auto-connect /
 * silent pre-connect and reconnect backoff flows (Task 3).
 *
 * Each VM is constructed with:
 *  - a real [BleController] backed by a [FakePeripheralManager], so the
 *    `advertisingStarted` SharedFlow (Task 1 signal) and `connectionState`
 *    StateFlow behave exactly like production;
 *  - a mockk [BleApi] whose scan()/connect()/send() answers are scripted per
 *    test (mockk 1.13.12 mocks the concrete `@Singleton BleApi` final class
 *    via its agent — same pattern the original Task 9 tests used);
 *  - lightweight mutable-flow fakes for [ServerConfigStore] / [ServerConfig]
 *    so each test seeds `serverConfigured` and `bleEnabled` directly.
 *
 * `Dispatchers.setMain(StandardTestDispatcher())` puts `viewModelScope.launch`
 * under runTest's controlled scheduler; simple tests call [runCurrent] to flush.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleSettingsViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    // --- Existing manual flows (Task 9) -----------------------------------

    @Test
    fun scan_populatesDevices() = runTest {
        val api = fakeApi(scanFailCount = 0)
        val vm = buildVm(api = api, serverConfigured = false, bleEnabled = true)
        vm.scan(); runCurrent()

        assertEquals(1, vm.devices.value.size)
        assertEquals("AA:BB", vm.devices.value[0].id)
    }

    @Test
    fun connect_marksControllerConnected() = runTest {
        val api = fakeApi(scanFailCount = 0)
        val vm = buildVm(api = api, serverConfigured = false, bleEnabled = true)
        vm.connect(BleDevice("AA:BB", "Pixel", -45)); runCurrent()

        assertEquals(BleConnState.CONNECTED, vm.connectionState.value)
    }

    @Test
    fun sendTest_updatesEchoResult() = runTest {
        val api = fakeApi(scanFailCount = 0)
        val vm = buildVm(api = api, serverConfigured = false, bleEnabled = true)
        vm.sendTest(); runCurrent()

        assertEquals("pong", vm.echoResult.value)
    }

    // --- Manual autoConnect flow -------------------------------------------

    @Test
    fun autoConnect_manualCall_triggersSingleScanAndConnect() = runTest {
        val api = fakeApi(scanFailCount = 0)
        val vm = buildVm(api = api, serverConfigured = false, bleEnabled = true)
        runCurrent()

        vm.autoConnect(); runCurrent()

        assertEquals(1, api.scanCallCount)
        assertEquals(BleConnState.CONNECTED, vm.connectionState.value)
    }

    @Test
    fun autoConnect_manualCall_handlesScanFailureCleanly() = runTest {
        val api = fakeApi(scanFailCount = 1)
        val vm = buildVm(api = api, serverConfigured = false, bleEnabled = true)
        runCurrent()

        vm.autoConnect(); runCurrent()

        assertEquals(1, api.scanCallCount)
        assertEquals(BleConnState.ADVERTISING, vm.connectionState.value)
        // The error text is now resolved through the mocked Application's
        // getString stub, which echoes the format argument — so the assertion
        // checks that the API failure cause reaches the user-facing text.
        assertTrue(vm.errorText.value?.contains("scan error") == true)
    }

    // --- Silent Pre-connect & Auto-reconnect Backoff (Task 3) ---------------

    @Test
    fun silentAutoConnect_onFailure_doesNotPolluteErrorText() = runTest {
        val api = fakeApi(scanFailCount = 1)
        val vm = buildVm(api = api, serverConfigured = false, bleEnabled = true)
        runCurrent()

        val success = vm.doAutoConnectOnce(silent = true)
        runCurrent()

        assertFalse(success)
        assertNull(vm.errorText.value) // 静默模式下错误文本保持 null
    }

    @Test
    fun manualAutoConnect_resetsBackoffAndSetsErrorTextOnFailure() = runTest {
        val api = fakeApi(scanFailCount = 10)
        val vm = buildVm(api = api, serverConfigured = true, bleEnabled = true)
        runCurrent()

        vm.autoConnect()
        runCurrent()

        assertTrue(vm.errorText.value != null) // 手动模式下更新 errorText
    }

    @Test
    fun silentAutoConnect_triggersAutomaticallyOnStartupWhenConfigured() = runTest {
        val api = fakeApi(scanFailCount = 0)
        val vm = buildVm(api = api, serverConfigured = true, bleEnabled = true)
        runCurrent()

        // 验证初始启动已触发静默预建联并成功连接
        assertEquals(1, api.scanCallCount)
        assertEquals(BleConnState.CONNECTED, vm.connectionState.value)
    }

    @Test
    fun silentAutoConnect_entersCooldownAfterThreeFailures() = runTest {
        val api = fakeApi(scanFailCount = 10) // 总是失败
        val vm = buildVm(api = api, serverConfigured = true, bleEnabled = true)
        runCurrent()
        advanceTimeBy(30_000); runCurrent()

        // 最多尝试 3 次退避，随后进入冷却期
        assertEquals(3, api.scanCallCount)
        assertNull(vm.errorText.value) // 静默模式下不污染 errorText
    }

    @Test
    fun silentAutoConnect_doesNotTrigger_whenBleDisabled() = runTest {
        val api = fakeApi(scanFailCount = 0)
        val vm = buildVm(api = api, serverConfigured = true, bleEnabled = false)
        runCurrent()
        advanceTimeBy(30_000); runCurrent()

        assertEquals(0, api.scanCallCount)
    }

    @Test
    fun silentAutoConnect_doesNotTrigger_whenServerNotConfigured() = runTest {
        val api = fakeApi(scanFailCount = 0)
        val vm = buildVm(api = api, serverConfigured = false, bleEnabled = true)
        runCurrent()
        advanceTimeBy(30_000); runCurrent()

        assertEquals(0, api.scanCallCount)
    }

    @Test
    fun autoReconnectOnDisconnect_triggersBackoffWhenDisconnected() = runTest {
        val api = fakeApi(scanFailCount = 0)
        val vm = buildVm(api = api, serverConfigured = true, bleEnabled = true)
        runCurrent()

        assertEquals(1, api.scanCallCount)
        assertEquals(BleConnState.CONNECTED, vm.connectionState.value)

        // 触发服务端断开连接
        vm.fakeController.markDisconnected()
        runCurrent()

        // 3s 退避延迟前不应触发扫描
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, api.scanCallCount)

        // 到达 3s 触发第 1 次重连扫描
        advanceTimeBy(2_000); runCurrent()
        assertEquals(2, api.scanCallCount)
        assertEquals(BleConnState.CONNECTED, vm.connectionState.value)
    }

    // --- selectBestDevice and MAC memory (Task 2) ---------------------------

    @Test
    fun selectBestDevice_prioritizesLastConnectedMac() {
        val devices = listOf(
            BleDevice("11:22:33:44:55:66", "Pixel 8", -40),
            BleDevice("AA:BB:CC:DD:EE:FF", "Pixel 7", -60),
        )
        val best = BleSettingsViewModel.selectBestDevice(
            discovered = devices,
            lastConnectedMac = "AA:BB:CC:DD:EE:FF",
            localDeviceName = "Pixel 8"
        )
        assertEquals("AA:BB:CC:DD:EE:FF", best?.id)
    }

    @Test
    fun selectBestDevice_fallsBackToDeviceNameMatch_whenNoMacMatch() {
        val devices = listOf(
            BleDevice("11:22:33:44:55:66", "Unknown", -40),
            BleDevice("77:88:99:AA:BB:CC", "My Phone", -70),
        )
        val best = BleSettingsViewModel.selectBestDevice(
            discovered = devices,
            lastConnectedMac = "FF:EE:DD:CC:BB:AA",
            localDeviceName = "My Phone"
        )
        assertEquals("77:88:99:AA:BB:CC", best?.id)
    }

    @Test
    fun selectBestDevice_fallsBackToMaxRssi_whenNoMacOrNameMatch() {
        val devices = listOf(
            BleDevice("11:22:33:44:55:66", "Device A", -80),
            BleDevice("77:88:99:AA:BB:CC", "Device B", -45),
        )
        val best = BleSettingsViewModel.selectBestDevice(
            discovered = devices,
            lastConnectedMac = null,
            localDeviceName = "My Phone"
        )
        assertEquals("77:88:99:AA:BB:CC", best?.id)
    }

    @Test
    fun autoConnect_savesLastConnectedMacOnSuccess() = runTest {
        val api = fakeApi(scanFailCount = 0)
        val vm = buildVm(api = api, serverConfigured = false, bleEnabled = true)
        runCurrent()

        vm.autoConnect(); runCurrent()

        assertEquals("AA:BB", vm.store.lastConnectedBleAddress.first())
    }

    // --- Test helpers ------------------------------------------------------

    /**
     * Builds a mockk [BleApi] whose `scan()` fails the first [scanFailCount]
     * calls then returns one device; `connect()` succeeds unless [connectFails]
     * is true, in which case it returns Success(false) on every call (drives
     * the retry loop through all 3 attempts). `send` echoes "pong". Each scan
     * call increments [scanCallCount] so tests can assert how many attempts the
     * retry loop made.
     */
    private fun fakeApi(scanFailCount: Int, connectFails: Boolean = false): FakeApi =
        FakeApi(scanFailCount, connectFails)

    /** Wrapper around a mockk BleApi that counts scan() calls. */
    private class FakeApi(scanFailCount: Int, connectFails: Boolean) {
        val api: BleApi = mockk()
        var scanCallCount: Int = 0; private set
        private val failRemaining = scanFailCount

        init {
            coEvery { api.scan() } answers {
                scanCallCount++
                if (failRemaining > 0 && scanCallCount <= failRemaining) {
                    NetworkResult.Error("scan error (attempt $scanCallCount)")
                } else {
                    NetworkResult.Success(listOf(BleDevice("AA:BB", "Pixel", -50)))
                }
            }
            coEvery { api.connect(any()) } returns
                if (connectFails) NetworkResult.Success(false)
                else NetworkResult.Success(true)
            coEvery { api.send(any()) } returns NetworkResult.Success("pong")
        }

        // Delegate the BleApi-shaped operations the VM uses.
        suspend fun scan(): NetworkResult<List<BleDevice>> = api.scan()
        suspend fun connect(id: String): NetworkResult<Boolean> = api.connect(id)
        suspend fun send(payload: String): NetworkResult<String?> = api.send(payload)
    }

    /** A [BlePeripheralManager] that just records the advertising callback. */
    private class FakePeripheralManager : BlePeripheralManager {
        var advertising = false
        private var onAdvertisingStarted: ((Boolean) -> Unit)? = null

        override fun startAdvertising() { advertising = true }
        override fun stopAdvertising() { advertising = false }
        override fun setOnPayloadReceived(cb: (ByteArray) -> Unit) = Unit
        override fun setOnAdvertisingStarted(cb: (Boolean) -> Unit) {
            onAdvertisingStarted = cb
        }
        override fun notifyPayload(payload: ByteArray): Boolean = false
        override fun isAdapterUsable(): Boolean = true

        fun fireAdvertisingStarted(success: Boolean) {
            onAdvertisingStarted?.invoke(success)
        }
    }

    /**
     * Bundle of the live fakes backing a test VM, stashed so test hooks
     * ([BleSettingsViewModelTest.fireAdvertisingReady]) can reach the
     * controller and its peripheral.
     */
    private class VmFixtures(
        val fakeController: BleController,
        val peripheral: FakePeripheralManager,
        val serverUrlFlow: MutableStateFlow<String>,
        val serverConfig: ServerConfig,
    )

    /**
     * Builds a [BleSettingsViewModel] wired to fakes appropriate for the
     * auto-connect tests: real [BleController] (so `advertisingStarted` and
     * `connectionState` are live), mutable-flow [ServerConfigStore] /
     * [ServerConfig] (so tests seed [serverConfigured] / [bleEnabled]), and
     * either a [FakeApi] or a plain mockk [BleApi].
     */
    private fun buildVm(
        api: Any,
        serverConfigured: Boolean,
        bleEnabled: Boolean,
    ): BleSettingsViewModel {
        val peripheral = FakePeripheralManager()
        val bleEnabledFlow: MutableStateFlow<Boolean> = MutableStateFlow(bleEnabled)
        val fakeController = BleController(
            peripheralManager = peripheral,
            bleTransportFallback = BleTransportFallback(),
            bleEnabledFlow = bleEnabledFlow,
            bleHardwareAvailable = { true },
            saveBleEnabled = {},
        )
        // Drive the controller into ADVERTISING so markConnected/markDisconnected
        // actually flip connectionState (the state machine no-ops in DISABLED).
        fakeController.evaluateAvailability(enabled = true)

        val serverConfig = ServerConfig().apply {
            if (serverConfigured) setBaseUrl("http://192.168.1.10:8000")
        }
        val serverUrlFlow: MutableStateFlow<String> =
            MutableStateFlow(if (serverConfigured) "http://192.168.1.10:8000" else "")
        val lastConnectedBleAddressFlow: MutableStateFlow<String?> = MutableStateFlow(null)
        val store = object : ServerConfigStore(mockk(relaxed = true)) {
            override val serverUrl: Flow<String> = serverUrlFlow
            override val bleEnabled: Flow<Boolean> = bleEnabledFlow
            override val lastConnectedBleAddress: Flow<String?> = lastConnectedBleAddressFlow
            override suspend fun saveLastConnectedBleAddress(address: String) {
                lastConnectedBleAddressFlow.value = address
            }
            override suspend fun clearLastConnectedBleAddress() {
                lastConnectedBleAddressFlow.value = null
            }
        }
        val bleApi: BleApi = when (api) {
            is FakeApi -> api.api
            is BleApi -> api
            else -> error("unsupported api fixture: ${api::class}")
        }
        // The ViewModel resolves error strings via Application.getString;
        // stub both overloads to echo the format argument so tests can assert
        // that API failure causes surface in the user-facing text. The vararg
        // overload is matched by its JVM shape (Int, Array<Any>) because
        // mockk's anyVararg matcher does not bind to Kotlin vararg calls.
        val application = mockk<Application>(relaxed = true)
        val bluetoothManager = mockk<BluetoothManager>(relaxed = true)
        val bluetoothAdapter = mockk<BluetoothAdapter>(relaxed = true)
        every { bluetoothAdapter.isEnabled } returns true
        every { bluetoothManager.adapter } returns bluetoothAdapter
        every { application.getSystemService(Context.BLUETOOTH_SERVICE) } returns bluetoothManager
        every { application.getString(any<Int>()) } returns "ble"
        every { application.getString(any<Int>(), any()) } answers {
            "ble:" + secondArg<Array<Any>>().joinToString(",")
        }

        val vm = BleSettingsViewModel(
            application = application,
            controller = fakeController,
            api = bleApi,
            store = store,
            serverConfig = serverConfig,
            bleEnabledFlow = bleEnabledFlow,
        )
        lastFixtures = VmFixtures(fakeController, peripheral, serverUrlFlow, serverConfig)
        return vm
    }

    private var lastFixtures: VmFixtures? = null

    /** Pushes an `advertisingStarted` emission into the fake controller. */
    private fun BleSettingsViewModel.fireAdvertisingReady(success: Boolean) {
        lastFixtures?.peripheral?.fireAdvertisingStarted(success)
    }

    /** The fake controller backing this VM (for state-machine drives in tests). */
    private val BleSettingsViewModel.fakeController: BleController
        get() = lastFixtures!!.fakeController

    /**
     * Simulates the user connecting to a server AFTER the VM was already built
     * (serverUrl + baseUrl both populated). Used to test the "BLE toggle was on
     * before connecting server" ordering — the regression that motivated
     * folding advertisingStarted into a sticky StateFlow.
     */
    private fun BleSettingsViewModel.connectServerPostHoc() {
        val f = lastFixtures!!
        val url = "http://192.168.1.10:8000"
        f.serverUrlFlow.value = url
        f.serverConfig.setBaseUrl(url)
    }
}
