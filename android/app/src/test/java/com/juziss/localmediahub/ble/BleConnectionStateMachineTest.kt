package com.juziss.localmediahub.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class BleConnectionStateMachineTest {

    @Test
    fun startsAtIdle() {
        val sm = BleConnectionStateMachine()
        assertEquals(BleConnState.IDLE, sm.state.value)
    }

    @Test
    fun idle_toAdvertising_toConnecting_toConnected() {
        val sm = BleConnectionStateMachine()
        sm.onStartAdvertising()
        assertEquals(BleConnState.ADVERTISING, sm.state.value)
        sm.onConnecting()
        assertEquals(BleConnState.CONNECTING, sm.state.value)
        sm.onConnected()
        assertEquals(BleConnState.CONNECTED, sm.state.value)
    }

    @Test
    fun connected_toDisconnected_returnsToIdle() {
        val sm = BleConnectionStateMachine()
        sm.onStartAdvertising()
        sm.onConnecting()
        sm.onConnected()
        sm.onDisconnected()
        assertEquals(BleConnState.IDLE, sm.state.value)
    }

    @Test
    fun onBleDisabled_overridesToDisabled() {
        val sm = BleConnectionStateMachine()
        sm.onStartAdvertising()
        sm.onBleDisabled()
        assertEquals(BleConnState.DISABLED, sm.state.value)
    }

    @Test
    fun onError_fromAnyState_returnsToIdle() {
        val sm = BleConnectionStateMachine()
        sm.onStartAdvertising()
        sm.onConnecting()
        sm.onError()
        assertEquals(BleConnState.IDLE, sm.state.value)
    }
}
