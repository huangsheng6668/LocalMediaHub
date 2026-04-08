package com.juziss.localmediahub.viewmodel

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
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

    private val _discoveryState = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

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

    /**
     * Start mDNS service discovery for LocalMediaHub.
     */
    fun startDiscovery() {
        if (discoveryState.value is DiscoveryState.Scanning) return

        val context = getApplication<Application>()
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        _discoveryState.value = DiscoveryState.Scanning

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                _discoveryState.value = DiscoveryState.Scanning
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        _discoveryState.value = DiscoveryState.Error(
                            "Failed to resolve service (error $errorCode)"
                        )
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host.hostAddress ?: return
                        val port = serviceInfo.port
                        _discoveryState.value = DiscoveryState.Found(host, port)
                        stopDiscovery()
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // No action needed
            }

            override fun onDiscoveryStopped(serviceType: String) {
                if (_discoveryState.value is DiscoveryState.Scanning) {
                    _discoveryState.value = DiscoveryState.NotFound
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _discoveryState.value = DiscoveryState.Error(
                    "Discovery failed to start (error $errorCode)"
                )
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                // No action needed
            }
        }

        try {
            nsdManager?.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        } catch (e: Exception) {
            _discoveryState.value = DiscoveryState.Error(
                e.message ?: "Discovery failed"
            )
        }
    }

    /**
     * Stop an ongoing mDNS service discovery.
     */
    fun stopDiscovery() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {
            // Already stopped or not started
        }
        if (_discoveryState.value is DiscoveryState.Scanning) {
            _discoveryState.value = DiscoveryState.Idle
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopDiscovery()
    }

    companion object {
        private const val SERVICE_TYPE = "_localmediahub._tcp."
    }
}

sealed class ConnectionState {
    data object Idle : ConnectionState()
    data object Testing : ConnectionState()
    data class Connected(val serverUrl: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

sealed class DiscoveryState {
    data object Idle : DiscoveryState()
    data object Scanning : DiscoveryState()
    data class Found(val host: String, val port: Int) : DiscoveryState()
    data object NotFound : DiscoveryState()
    data class Error(val message: String) : DiscoveryState()
}
