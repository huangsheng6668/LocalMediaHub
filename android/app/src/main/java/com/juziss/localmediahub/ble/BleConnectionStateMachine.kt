package com.juziss.localmediahub.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BleConnState {
    DISABLED,    // Bluetooth off / not authorized / no hardware
    IDLE,        // Ready but not scanning
    SCANNING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED // transient; immediately treated as IDLE
}

/**
 * Pure-logic BLE connection state machine. No Android Bluetooth API calls —
 * fully unit-testable. The hardware-facing [BleCentralManager] drives this
 * machine via the `on*` transition methods.
 */
class BleConnectionStateMachine {
    private val _state = MutableStateFlow(BleConnState.IDLE)
    val state: StateFlow<BleConnState> = _state.asStateFlow()

    fun onBleDisabled() {
        _state.value = BleConnState.DISABLED
    }

    fun onStartScan() {
        if (_state.value == BleConnState.DISABLED) return
        _state.value = BleConnState.SCANNING
    }

    fun onConnecting() {
        if (_state.value == BleConnState.DISABLED) return
        _state.value = BleConnState.CONNECTING
    }

    fun onConnected() {
        if (_state.value == BleConnState.DISABLED) return
        _state.value = BleConnState.CONNECTED
    }

    fun onDisconnected() {
        if (_state.value == BleConnState.DISABLED) return
        _state.value = BleConnState.IDLE
    }

    fun onError() {
        if (_state.value == BleConnState.DISABLED) return
        _state.value = BleConnState.IDLE
    }
}
