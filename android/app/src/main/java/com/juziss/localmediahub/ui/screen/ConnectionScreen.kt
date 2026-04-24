package com.juziss.localmediahub.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.juziss.localmediahub.viewmodel.ConnectionState
import com.juziss.localmediahub.viewmodel.ConnectionViewModel
import com.juziss.localmediahub.viewmodel.DiscoveredServer
import com.juziss.localmediahub.viewmodel.DiscoveryState
import com.juziss.localmediahub.viewmodel.shouldAttemptAutoConnect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    onConnected: () -> Unit,
    viewModel: ConnectionViewModel = viewModel(),
) {
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8000") }
    var autoConnectAttempted by rememberSaveable { mutableStateOf(false) }
    var showServerSelection by remember { mutableStateOf(false) }

    val savedIp by viewModel.savedIp.collectAsState()
    val savedPort by viewModel.savedPort.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val discoveryState by viewModel.discoveryState.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val discoveredServers by viewModel.discoveredServers.collectAsState()

    LaunchedEffect(savedIp, savedPort, connectionState) {
        if (ip.isEmpty() && savedIp.isNotEmpty()) {
            ip = savedIp
            port = savedPort
        }

        if (shouldAttemptAutoConnect(savedIp, autoConnectAttempted, connectionState)) {
            autoConnectAttempted = true
            viewModel.tryAutoConnect()
        }
    }

    LaunchedEffect(discoveryState) {
        val state = discoveryState
        if (state is DiscoveryState.FoundMultiple) {
            if (state.servers.size == 1) {
                viewModel.connectToDiscovered(state.servers.first())
            } else {
                showServerSelection = true
            }
        }
    }

    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) {
            onConnected()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Connect")
                        Text(
                            text = "Pair with your PC media server",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            ConnectionHeroCard(
                savedIp = savedIp,
                savedPort = savedPort,
                isAutoConnecting = autoConnectAttempted && connectionState is ConnectionState.Testing,
            )

            DiscoveryCard(
                discoveryState = discoveryState,
                discoveredCount = discoveredServers.size,
                scanProgress = scanProgress,
                onStartDiscovery = { viewModel.startDiscovery() },
                onViewServers = { showServerSelection = true },
            )

            ManualConnectionCard(
                ip = ip,
                port = port,
                connectionState = connectionState,
                onIpChange = { ip = it },
                onPortChange = { port = it },
                onConnect = { viewModel.testConnection(ip, port) },
            )

            when (connectionState) {
                is ConnectionState.Testing -> {
                    StatusCard(
                        icon = Icons.Filled.Storage,
                        title = "Checking server health",
                        message = "Trying to reach LocalMediaHub and save this server for next time.",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                is ConnectionState.Connected -> {
                    StatusCard(
                        icon = Icons.Filled.CheckCircle,
                        title = "Connection ready",
                        message = "Connected to ${(connectionState as ConnectionState.Connected).serverUrl}",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                is ConnectionState.Error -> {
                    StatusCard(
                        icon = Icons.Filled.Error,
                        title = "Connection failed",
                        message = (connectionState as ConnectionState.Error).message,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                ConnectionState.Idle -> Unit
            }

            when (val state = discoveryState) {
                is DiscoveryState.NotFound -> {
                    StatusCard(
                        icon = Icons.Filled.Search,
                        title = "No server found",
                        message = "The scan finished without finding LocalMediaHub on this network. You can still connect manually.",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
                is DiscoveryState.Error -> {
                    StatusCard(
                        icon = Icons.Filled.Error,
                        title = "Discovery hit a problem",
                        message = state.message,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                is DiscoveryState.FoundMultiple -> {
                    StatusCard(
                        icon = Icons.Filled.CheckCircle,
                        title = "${state.servers.size} server(s) found",
                        message = if (state.servers.size == 1) {
                            "Found LocalMediaHub at ${state.servers.first().ip}:${state.servers.first().port}. Connecting..."
                        } else {
                            "Found ${state.servers.size} servers. Tap \"View servers\" to select one."
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                DiscoveryState.Idle,
                DiscoveryState.Scanning -> Unit
            }
        }

        if (showServerSelection && discoveredServers.isNotEmpty()) {
            ServerSelectionSheet(
                servers = discoveredServers,
                onSelect = { server ->
                    showServerSelection = false
                    ip = server.ip
                    port = server.port.toString()
                    viewModel.connectToDiscovered(server)
                },
                onDismiss = { showServerSelection = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSelectionSheet(
    servers: List<DiscoveredServer>,
    onSelect: (DiscoveredServer) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Select Server",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "${servers.size} LocalMediaHub server(s) found on this network.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            servers.forEach { server ->
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(server) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Filled.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${server.ip}:${server.port}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "LocalMediaHub",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun ConnectionHeroCard(
    savedIp: String,
    savedPort: String,
    isAutoConnecting: Boolean,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Bring your phone onto the same network as your PC and LocalMediaHub will do the rest.",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = if (savedIp.isNotBlank()) {
                    "We will try your last server first, and you can always scan or edit the address below."
                } else {
                    "Start with auto-discovery for the smooth path, or enter the server address manually."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (savedIp.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Filled.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column {
                            Text(
                                text = "Last used server",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = "$savedIp:$savedPort",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (isAutoConnecting) {
                Text(
                    text = "Auto-connect is in progress using your saved server.",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun DiscoveryCard(
    discoveryState: DiscoveryState,
    discoveredCount: Int,
    scanProgress: Pair<Int, Int>,
    onStartDiscovery: () -> Unit,
    onViewServers: () -> Unit,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Find on this network",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Scan your LAN for LocalMediaHub servers and fill the address automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onStartDiscovery,
                    modifier = Modifier.weight(1f),
                    enabled = discoveryState !is DiscoveryState.Scanning,
                ) {
                    if (discoveryState is DiscoveryState.Scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Scanning...")
                    } else {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Scan network")
                    }
                }

                if (discoveredCount > 1) {
                    Button(
                        onClick = onViewServers,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("View servers ($discoveredCount)")
                    }
                }
            }

            if (discoveryState is DiscoveryState.Scanning) {
                val total = scanProgress.second.coerceAtLeast(1)
                val progress = scanProgress.first.toFloat() / total.toFloat()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Checked ${scanProgress.first} of ${scanProgress.second} addresses",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (discoveredCount > 0) {
                            Text(
                                text = "$discoveredCount found",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualConnectionCard(
    ip: String,
    port: String,
    connectionState: ConnectionState,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Manual connection",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Use your PC's LAN IP and the LocalMediaHub port if discovery misses it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            OutlinedTextField(
                value = ip,
                onValueChange = onIpChange,
                label = { Text("Server IP address") },
                placeholder = { Text("192.168.1.100") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = connectionState is ConnectionState.Error,
                supportingText = {
                    Text("Usually the IPv4 address of the PC running LocalMediaHub.")
                },
            )
            OutlinedTextField(
                value = port,
                onValueChange = onPortChange,
                label = { Text("Port") },
                placeholder = { Text("8000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = {
                    Text("Leave this as 8000 unless you changed the server config.")
                },
            )
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                enabled = connectionState !is ConnectionState.Testing,
            ) {
                if (connectionState is ConnectionState.Testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Connecting...")
                } else {
                    Text("Connect to server")
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    icon: ImageVector,
    title: String,
    message: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = containerColor,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, tint = contentColor)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                )
            }
        }
    }
}
