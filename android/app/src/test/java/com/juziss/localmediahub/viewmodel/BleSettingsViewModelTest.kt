package com.juziss.localmediahub.viewmodel

import android.app.Application
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
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Unit tests for the BLE scan/connect/sendTest flows (Task 9) and the manual
 * connection flow (`autoConnect()`).
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
        val vm = buildVm(api = api, serverConfigured = true, bleEnabled = true)
        vm.scan(); runCurrent()

        assertEquals(1, vm.devices.value.size)
        assertEquals("AA:BB", vm.devices.value[0].id)
    }

    @Test
    fun connect_marksControllerConnected() = runTest {
        val api = fakeApi(scanFailCount = 0)
        val vm = buildVm(api = api, serverConfigured = true, bleEnabled = true)
        vm.connect(BleDevice("AA:BB", "Pixel", -45)); runCurrent()

        assertEquals(BleConnState.CONNECTED, vm.connectionState.value)
    }

    @Test
    fun sendTest_updatesEchoResult() = runTest {
        val api = mockk<BleApi>()
        coEvery { api.send(any()) } returns NetworkResult.Success("pong")
        val vm = buildVm(api = api, serverConfigured = true, bleEnabled = true)
        vm.sendTest(); runCurrent()

        assertEquals("pong", vm.echoResult.value)
    }

    // --- Manual autoConnect flow -------------------------------------------

    @Test
    fun bleEnabled_doesNotTriggerAutoConnect() = runTest {
        val api = fakeApi(scanFailCount = 0)
        val vm = buildVm(api = api, serverConfigured = true, bleEnabled = true)
        runCurrent()
        vm.fireAdvertisingReady(true); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(0, api.scanCallCount)
    }

    @Test
    fun autoConnect_manualCall_triggersSingleScanAndConnect() = runTest {
        val api = fakeApi(scanFailCount = 0)
        val vm = buildVm(api = api, serverConfigured = true, bleEnabled = true)
        runCurrent()

        vm.autoConnect(); runCurrent()

        assertEquals(1, api.scanCallCount)
        assertEquals(BleConnState.CONNECTED, vm.connectionState.value)
    }

    @Test
    fun autoConnect_manualCall_handlesScanFailureCleanly() = runTest {
        val api = fakeApi(scanFailCount = 1)
        val vm = buildVm(api = api, serverConfigured = true, bleEnabled = true)
        runCurrent()

        vm.autoConnect(); runCurrent()

        assertEquals(1, api.scanCallCount)
        assertEquals(BleConnState.ADVERTISING, vm.connectionState.value)
        assertTrue(vm.errorText.value?.contains("建立连接失败") == true)
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
        val store = object : ServerConfigStore(mockk(relaxed = true)) {
            override val serverUrl: Flow<String> = serverUrlFlow
            override val bleEnabled: Flow<Boolean> = bleEnabledFlow
        }
        val bleApi: BleApi = when (api) {
            is FakeApi -> api.api
            is BleApi -> api
            else -> error("unsupported api fixture: ${api::class}")
        }
        val vm = BleSettingsViewModel(
            application = mockk(relaxed = true),
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
