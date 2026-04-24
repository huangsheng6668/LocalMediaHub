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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

data class DiscoveredServer(
    val ip: String,
    val port: Int,
)

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {

    private val serverConfig = ServerConfig(application)
    private val repository = MediaRepository()

    val savedIp = serverConfig.serverIp.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), ""
    )
    val savedPort = serverConfig.serverPort.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), "8000"
    )

    val knownServers = serverConfig.knownServers.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveryState = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()

    private val _scanProgress = MutableStateFlow(0 to 0)
    val scanProgress: StateFlow<Pair<Int, Int>> = _scanProgress.asStateFlow()

    private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers.asStateFlow()

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

    fun tryAutoConnect() {
        if (_connectionState.value is ConnectionState.Testing || _connectionState.value is ConnectionState.Connected) {
            return
        }

        viewModelScope.launch {
            val ip = savedIp.value
            val port = savedPort.value
            if (ip.isNotBlank()) {
                testConnection(ip, port)
            }
        }
    }

    fun connectToDiscovered(server: DiscoveredServer) {
        testConnection(server.ip, server.port.toString())
    }

    fun startDiscovery() {
        if (discoveryState.value is DiscoveryState.Scanning) return

        _discoveredServers.value = emptyList()
        _discoveryState.value = DiscoveryState.Scanning
        startNsdDiscovery()
        startHttpScan()
    }

    fun stopDiscovery() {
        stopNsdDiscovery()
        if (_discoveryState.value is DiscoveryState.Scanning) {
            _discoveryState.value = DiscoveryState.Idle
        }
    }

    // ── mDNS Discovery ──────────────────────────────────────────────────

    private fun startNsdDiscovery() {
        try {
            val context = getApplication<Application>()
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host.hostAddress ?: return
                            val port = serviceInfo.port
                            onServerFound(host, port)
                        }
                    })
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    stopNsdDiscovery()
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }

            nsdManager?.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        } catch (_: Exception) {
            // mDNS not available, HTTP scan will handle it
        }
    }

    private fun stopNsdDiscovery() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {}
        discoveryListener = null
    }

    // ── HTTP LAN Scan ───────────────────────────────────────────────────

    private fun startHttpScan() {
        viewModelScope.launch {
            val ownIp = getOwnLanIp() ?: run {
                if (_discoveryState.value is DiscoveryState.Scanning) {
                    _discoveryState.value = DiscoveryState.Error("Cannot determine local IP")
                }
                return@launch
            }

            val parts = ownIp.split(".")
            if (parts.size != 4) {
                if (_discoveryState.value is DiscoveryState.Scanning) {
                    _discoveryState.value = DiscoveryState.Error("Invalid local IP: $ownIp")
                }
                return@launch
            }
            val subnet = "${parts[0]}.${parts[1]}.${parts[2]}"

            val scanClient = OkHttpClient.Builder()
                .connectTimeout(800, TimeUnit.MILLISECONDS)
                .readTimeout(800, TimeUnit.MILLISECONDS)
                .build()

            val total = 255
            _scanProgress.value = 0 to total
            val concurrencyLimit = Semaphore(24)

            coroutineScope {
                (1..255).map { i ->
                    async(Dispatchers.IO) {
                        concurrencyLimit.withPermit {
                            try {
                                if (_discoveryState.value !is DiscoveryState.Scanning) {
                                    return@withPermit
                                }

                                val ip = "$subnet.$i"
                                val request = Request.Builder()
                                    .url("http://$ip:8000/")
                                    .get()
                                    .build()

                                scanClient.newCall(request).execute().use { response ->
                                    val body = response.body?.string().orEmpty()
                                    if (body.contains("LocalMediaHub")) {
                                        withContext(Dispatchers.Main) {
                                            onServerFound(ip, 8000)
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                                // Not our server or unreachable.
                            } finally {
                                withContext(Dispatchers.Main) {
                                    val current = _scanProgress.value
                                    _scanProgress.value = (current.first + 1) to total
                                }
                            }
                        }
                    }
                }.awaitAll()
            }

            // Scan complete — finalize state with all discovered servers
            if (_discoveryState.value is DiscoveryState.Scanning) {
                val servers = _discoveredServers.value
                _discoveryState.value = when {
                    servers.isEmpty() -> DiscoveryState.NotFound
                    else -> DiscoveryState.FoundMultiple(servers)
                }
                stopNsdDiscovery()
            }
        }
    }

    private fun onServerFound(host: String, port: Int) {
        if (_discoveryState.value !is DiscoveryState.Scanning) return
        val current = _discoveredServers.value
        if (current.any { it.ip == host && it.port == port }) return
        _discoveredServers.value = current + DiscoveredServer(host, port)
    }

    private fun getOwnLanIp(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (intf in interfaces) {
            for (addr in intf.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val ip = addr.hostAddress ?: continue
                    if (ip.startsWith("192.168.") || ip.startsWith("10.") ||
                        ip.startsWith("172.16.") || ip.startsWith("172.17.") ||
                        ip.startsWith("172.18.") || ip.startsWith("172.19.") ||
                        ip.startsWith("172.2") || ip.startsWith("172.3")
                    ) {
                        return ip
                    }
                }
            }
        }
        // Fallback: return any non-loopback IPv4
        for (intf in NetworkInterface.getNetworkInterfaces() ?: return null) {
            for (addr in intf.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    return addr.hostAddress
                }
            }
        }
        return null
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
    data class FoundMultiple(val servers: List<DiscoveredServer>) : DiscoveryState()
    data object NotFound : DiscoveryState()
    data class Error(val message: String) : DiscoveryState()
}
