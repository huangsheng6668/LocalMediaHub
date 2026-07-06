package com.juziss.localmediahub.viewmodel

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.data.ServerConfigStore
import com.juziss.localmediahub.network.NetworkResult
import com.juziss.localmediahub.network.ServerConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
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
import javax.inject.Inject
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class DiscoveredServer(
    val ip: String,
    val port: Int,
)

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    application: Application,
    private val serverConfigStore: ServerConfigStore,
    private val serverConfig: ServerConfig,
    private val repository: MediaRepository,
    private val httpClient: OkHttpClient,
) : AndroidViewModel(application) {

    val savedIp = serverConfigStore.serverIp.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), ""
    )
    val savedPort = serverConfigStore.serverPort.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), "8000"
    )

    val knownServers = serverConfigStore.knownServers.stateIn(
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
    private var multicastLock: WifiManager.MulticastLock? = null
    // NSD resolve must be serialized: Android's NsdManager allows only ONE
    // active resolve at a time. Calling resolveService() concurrently fails with
    // FAILURE_ALREADY_ACTIVE and can crash the system NSD service on older OS
    // versions. We queue found services here and drain them one at a time from
    // resolveWorkerJob.
    private var resolveQueue: Channel<NsdServiceInfo>? = null
    private var resolveWorkerJob: Job? = null

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                val context = getApplication<Application>()
                val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifiManager.createMulticastLock("LocalMediaHubMulticastLock").apply {
                    setReferenceCounted(false)
                }
            }
            multicastLock?.acquire()
        } catch (_: Exception) {}
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (_: Exception) {}
    }

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
                serverConfig.setBaseUrl(url)
                when (val result = repository.healthCheck()) {
                    is NetworkResult.Success -> {
                        serverConfigStore.saveServerConfig(ip, port)
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

        acquireMulticastLock()
        _discoveredServers.value = emptyList()
        _discoveryState.value = DiscoveryState.Scanning
        startNsdDiscovery()
        startHttpScan()
    }

    fun stopDiscovery() {
        stopNsdDiscovery()
        releaseMulticastLock()
        if (_discoveryState.value is DiscoveryState.Scanning) {
            _discoveryState.value = DiscoveryState.Idle
        }
    }

    // ── mDNS Discovery ──────────────────────────────────────────────────

    private fun startNsdDiscovery() {
        try {
            val context = getApplication<Application>()
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

            // Buffer for found services; resolve worker drains this one at a time.
            resolveQueue = Channel(capacity = Channel.UNLIMITED)
            resolveWorkerJob = viewModelScope.launch(Dispatchers.IO) {
                resolveNsdQueue()
            }

            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    // Don't resolve here directly — queue it so the worker can
                    // guarantee only one in-flight resolve at a time.
                    resolveQueue?.trySend(serviceInfo)
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

    /**
     * Resolves queued NSD services strictly one at a time. Each resolve is
     * bridged to a [CompletableDeferred] so the next service only starts after
     * the previous one's callback (success or failure) has fired, avoiding
     * NsdManager's FAILURE_ALREADY_ACTIVE error and related crashes.
     */
    private suspend fun resolveNsdQueue() {
        val queue = resolveQueue ?: return
        for (serviceInfo in queue) {
            val manager = nsdManager ?: return
            val done = CompletableDeferred<Unit>()
            try {
                manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        done.complete(Unit)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.hostAddress
                        val port = serviceInfo.port
                        if (host != null) {
                            onServerFound(host, port)
                        }
                        done.complete(Unit)
                    }
                })
            } catch (_: Exception) {
                // resolveService can throw if the manager is already busy or
                // stopping; mark done so the loop can continue.
                done.complete(Unit)
            }
            // Block until this resolve's callback fires before starting the next.
            done.await()
        }
    }

    private fun stopNsdDiscovery() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {}
        discoveryListener = null
        // Tear down the resolve worker and its queue.
        resolveWorkerJob?.cancel()
        resolveWorkerJob = null
        resolveQueue?.close()
        resolveQueue = null
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

            // Candidate ports to probe per host. The saved port is tried first
            // (most likely to be the user's actual server), followed by common
            // defaults, so a server running on a non-default port is still found.
            val candidatePorts = linkedSetOf(
                savedPort.value.takeIf { it.isNotBlank() }?.toIntOrNull(),
                8000,
                8080,
                8888,
                9000,
            ).filterNotNull().toIntArray()

            // Derive a short-timeout client that shares the singleton's connection
            // pool. newBuilder() copies config + pool; we override only the
            // timeouts needed for fast LAN IP probing (Round 17 C3).
            val scanClient = httpClient.newBuilder()
                .connectTimeout(250, TimeUnit.MILLISECONDS)
                .readTimeout(250, TimeUnit.MILLISECONDS)
                .build()

            val total = 255
            _scanProgress.value = 0 to total
            val concurrencyLimit = Semaphore(12)

            coroutineScope {
                (1..255).map { i ->
                    async(Dispatchers.IO) {
                        concurrencyLimit.withPermit {
                            try {
                                if (_discoveryState.value !is DiscoveryState.Scanning) {
                                    return@withPermit
                                }

                                val ip = "$subnet.$i"
                                probeHostPorts(ip, candidatePorts, scanClient)
                            } catch (_: Exception) {
                                // Host unreachable.
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

    /**
     * Probes a single host on each candidate port in order, stopping at the
     * first one that responds with the LocalMediaHub signature. Extracted into
     * its own suspend function so we can use early `return` instead of `break`,
     * which Kotlin forbids inside inline lambdas (withPermit).
     */
    private suspend fun probeHostPorts(
        ip: String,
        ports: IntArray,
        client: OkHttpClient,
    ) = supervisorScope {
        ports.map { port ->
            async(Dispatchers.IO) {
                if (_discoveryState.value !is DiscoveryState.Scanning) return@async
                val request = Request.Builder()
                    .url("http://$ip:$port/")
                    .get()
                    .build()
                try {
                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (body.contains("LocalMediaHub")) {
                            withContext(Dispatchers.Main) { onServerFound(ip, port) }
                        }
                    }
                } catch (_: Exception) {
                    // Port closed or not our server
                }
            }
        }.awaitAll()
    }

    private fun onServerFound(host: String, port: Int) {
        if (_discoveryState.value !is DiscoveryState.Scanning) return
        val current = _discoveredServers.value
        if (current.any { it.ip == host && it.port == port }) return
        _discoveredServers.value = current + DiscoveredServer(host, port)
    }

    private fun getOwnLanIp(): String? =
        com.juziss.localmediahub.util.NetUtil.getLanIp()

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
