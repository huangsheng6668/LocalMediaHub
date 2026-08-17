package com.juziss.localmediahub.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BleConnState {
    DISABLED,     // Bluetooth off / not authorized / no hardware
    IDLE,         // Ready but not advertising
    ADVERTISING,  // Peripheral advertising, waiting to be scanned by Central
    CONNECTING,
    CONNECTED,
    DISCONNECTED // transient; immediately treated as IDLE
}

/**
 * Pure-logic BLE connection state machine. No Android Bluetooth API calls —
 * fully unit-testable. The hardware-facing [com.juziss.localmediahub.ble.BlePeripheralManager]
 * drives this machine via the `on*` transition methods. Android acts as the
 * BLE Peripheral (advertising); the Central (PC server) scans + connects.
 */
class BleConnectionStateMachine {
    private val _state = MutableStateFlow(BleConnState.IDLE)
    val state: StateFlow<BleConnState> = _state.asStateFlow()

    fun onBleDisabled() {
        _state.value = BleConnState.DISABLED
    }

    fun onStartAdvertising() {
        _state.value = BleConnState.ADVERTISING
    }

    fun onConnecting() {
        if (_state.value == BleConnState.DISABLED) return
        _state.value = BleConnState.CONNECTING
    }

    fun onConnected() {
        if (_state.value == BleConnState.DISABLED) return
        _state.value = BleConnState.CONNECTED
    }

    /**
     * Central (PC) disconnected, or a /connect attempt failed. As a Peripheral
     * we resume advertising so the Central can rediscover + reconnect, rather
     * than going silent (IDLE). The machine's transitions guard DISABLED so a
     * disabled device never silently re-arms advertising.
     */
    fun onDisconnected() {
        if (_state.value == BleConnState.DISABLED) return
        _state.value = BleConnState.ADVERTISING
    }

    /**
     * Phase 9 (Task 9, H-1b): fatal BLE authentication / protocol violation
     * (handshake MAC mismatch, pre-auth data command, post-auth v2 frame that
     * fails MAC/structure, or seq rollback/replay). Fail closed: unlike
     * [onDisconnected] the drop is surfaced as DISCONNECTED — a distinct
     * terminal signal for observers — rather than immediately ADVERTISING.
     * The next explicit transition (HTTP-coordination markConnected /
     * markDisconnected, or an availability re-evaluation) moves the machine
     * on; a re-connect must re-run the mutual-challenge handshake before any
     * data phase. Mirrors Go Central's `failConnection` (drop + auth reset).
     */
    fun onAuthFailure() {
        if (_state.value == BleConnState.DISABLED) return
        _state.value = BleConnState.DISCONNECTED
    }

    fun onError() {
        if (_state.value == BleConnState.DISABLED) return
        _state.value = BleConnState.IDLE
    }
}
