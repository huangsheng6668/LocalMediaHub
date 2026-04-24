package com.juziss.localmediahub.viewmodel

internal fun shouldAttemptAutoConnect(
    savedIp: String,
    autoConnectAttempted: Boolean,
    connectionState: ConnectionState,
): Boolean {
    return savedIp.isNotBlank() &&
        !autoConnectAttempted &&
        connectionState is ConnectionState.Idle
}
