package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.ble.BleConnState
import com.juziss.localmediahub.ble.BleController
import com.juziss.localmediahub.data.BleApi
import com.juziss.localmediahub.data.BleDevice
import com.juziss.localmediahub.network.NetworkResult
import io.mockk.coEvery
import android.app.Application
import com.juziss.localmediahub.data.ServerConfigStore
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the BLE scan/connect/sendTest flows added in Task 9.
 *
 * Uses mockk to fake the final concrete classes [BleController] and [BleApi]
 * (mockk 1.13.12 supports Kotlin 2.x final-class mocking via its agent, which
 * is already on the test classpath). `Dispatchers.setMain(UnconfinedTestDispatcher())`
 * makes viewModelScope.launch run synchronously inside runTest, so the test
 * can assert post-launch state directly.
 */
class BleSettingsViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun scan_populatesDevices() = runTest {
        val ctrl = mockk<BleController>(relaxed = true)
        every { ctrl.connectionState } returns MutableStateFlow(BleConnState.DISABLED)
        val api = mockk<BleApi>()
        coEvery { api.scan() } returns
            NetworkResult.Success(listOf(BleDevice("AA:BB", "Pixel", -45)))

        val store = mockk<ServerConfigStore>(); every { store.bleEnabled } returns emptyFlow(); val vm = BleSettingsViewModel(mockk<Application>(), ctrl, api, store)
        vm.scan()

        assertEquals(1, vm.devices.value.size)
        assertEquals("AA:BB", vm.devices.value[0].id)
    }

    @Test
    fun connect_marksControllerConnected() = runTest {
        val ctrl = mockk<BleController>(relaxed = true)
        every { ctrl.connectionState } returns MutableStateFlow(BleConnState.DISABLED)
        val api = mockk<BleApi>()
        coEvery { api.connect(any()) } returns NetworkResult.Success(true)

        val store = mockk<ServerConfigStore>(); every { store.bleEnabled } returns emptyFlow(); val vm = BleSettingsViewModel(mockk<Application>(), ctrl, api, store)
        vm.connect(BleDevice("AA:BB", "Pixel", -45))

        coVerify { api.connect("AA:BB") }
        verify { ctrl.markConnected() }
    }

    @Test
    fun sendTest_updatesEchoResult() = runTest {
        val ctrl = mockk<BleController>(relaxed = true)
        every { ctrl.connectionState } returns MutableStateFlow(BleConnState.DISABLED)
        val api = mockk<BleApi>()
        coEvery { api.send(any()) } returns NetworkResult.Success("pong")

        val store = mockk<ServerConfigStore>(); every { store.bleEnabled } returns emptyFlow(); val vm = BleSettingsViewModel(mockk<Application>(), ctrl, api, store)
        vm.sendTest()

        assertEquals("pong", vm.echoResult.value)
    }
}
