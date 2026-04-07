package com.juziss.localmediahub.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.data.ServerConfig
import com.juziss.localmediahub.network.NetworkResult
import com.juziss.localmediahub.network.RetrofitClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the server connection screen.
 */
class ConnectionViewModel(application: Application) : AndroidViewModel(application) {

    private val serverConfig = ServerConfig(application)
    private val repository = MediaRepository()

    // Saved IP & Port
    val savedIp = serverConfig.serverIp.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), ""
    )
    val savedPort = serverConfig.serverPort.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), "8000"
    )

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    fun testConnection(ip: String, port: String) {
        if (ip.isBlank()) {
            _connectionState.value = ConnectionState.Error("IP address cannot be empty")
            return
        }
        val portInt = port.toIntOrNull()
        if (portInt == null || portInt !in 1..65535) {
            _connectionState.value = ConnectionState.Error("Port must be 1-65535")
            return
        }

        viewModelScope.launch {
            _connectionState.value = ConnectionState.Testing
            try {
                val url = "http://$ip:$port"
                RetrofitClient.initialize(url)
                when (val result = repository.healthCheck()) {
                    is NetworkResult.Success -> {
                        serverConfig.saveServerConfig(ip, port)
                        _connectionState.value = ConnectionState.Connected(url)
                    }
                    is NetworkResult.Error -> {
                        _connectionState.value = ConnectionState.Error(result.message)
                    }
                    is NetworkResult.Loading -> {}
                }
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error(
                    e.message ?: "Unknown error"
                )
            }
        }
    }

    /** Try to auto-connect using saved config. */
    fun tryAutoConnect() {
        viewModelScope.launch {
            val ip = savedIp.value
            val port = savedPort.value
            if (ip.isNotBlank()) {
                testConnection(ip, port)
            }
        }
    }
}

sealed class ConnectionState {
    data object Idle : ConnectionState()
    data object Testing : ConnectionState()
    data class Connected(val serverUrl: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
