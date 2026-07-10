package com.juziss.localmediahub.ui.screen
 
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.juziss.localmediahub.viewmodel.ConnectionState
import androidx.compose.ui.res.stringResource
import com.juziss.localmediahub.R
import com.juziss.localmediahub.viewmodel.ConnectionViewModel
import com.juziss.localmediahub.viewmodel.DiscoveredServer
import com.juziss.localmediahub.viewmodel.DiscoveryState
import com.juziss.localmediahub.viewmodel.shouldAttemptAutoConnect
 
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    onConnected: () -> Unit,
    onBrowseOffline: () -> Unit = {},
    viewModel: ConnectionViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8000") }
    var tokenInput by remember { mutableStateOf("") }
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
                        Text(stringResource(R.string.conn_title), fontWeight = FontWeight.Bold)
                        Text(
                            text = stringResource(R.string.conn_subtitle),
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
                tokenInput = tokenInput,
                connectionState = connectionState,
                onIpChange = { ip = it },
                onPortChange = { port = it },
                onTokenChange = { tokenInput = it },
                onConnect = {
                    viewModel.saveToken(tokenInput)
                    viewModel.testConnection(ip, port)
                },
            )
 
            when (connectionState) {
                is ConnectionState.Testing -> {
                    StatusCard(
                        icon = painterResource(R.drawable.ic_storage),
                        title = stringResource(R.string.conn_detecting),
                        message = stringResource(R.string.conn_detecting_desc),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                is ConnectionState.Connected -> {
                    StatusCard(
                        icon = rememberVectorPainter(Icons.Filled.CheckCircle),
                        title = stringResource(R.string.conn_ready),
                        message = "已成功连接至 ${(connectionState as ConnectionState.Connected).serverUrl}",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                is ConnectionState.Error -> {
                    StatusCard(
                        icon = painterResource(R.drawable.ic_error),
                        title = stringResource(R.string.conn_failed),
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
                        icon = rememberVectorPainter(Icons.Filled.Search),
                        title = stringResource(R.string.conn_not_found),
                        message = stringResource(R.string.conn_not_found_desc),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
                is DiscoveryState.Error -> {
                    StatusCard(
                        icon = painterResource(R.drawable.ic_error),
                        title = stringResource(R.string.conn_error),
                        message = state.message,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                is DiscoveryState.FoundMultiple -> {
                    StatusCard(
                        icon = rememberVectorPainter(Icons.Filled.CheckCircle),
                        title = "发现 ${state.servers.size} 个可用服务器",
                        message = if (state.servers.size == 1) {
                            "在 ${state.servers.first().ip}:${state.servers.first().port} 发现 LocalMediaHub 服务器。正在连接..."
                        } else {
                            "发现 ${state.servers.size} 个服务器。点击 \"查看服务器\" 选择一个连接。"
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                DiscoveryState.Idle,
                DiscoveryState.Scanning -> Unit
            }
 
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(4.dp))
 
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onBrowseOffline() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_folder),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.conn_offline_btn),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.conn_offline_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
                text = stringResource(R.string.conn_select_server),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "在当前局域网中发现 ${servers.size} 个 LocalMediaHub 服务器。",
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
                            painterResource(R.drawable.ic_storage),
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
                Text(stringResource(R.string.cancel))
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
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                shape = RoundedCornerShape(24.dp)
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                        )
                    )
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.conn_hint_same_lan),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = if (savedIp.isNotBlank()) {
                    stringResource(R.string.conn_hint_reconnect)
                } else {
                    stringResource(R.string.conn_hint_recommend)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
 
            if (savedIp.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_history),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.conn_last_server),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
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
                    text = stringResource(R.string.conn_auto_connecting),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        modifier = Modifier.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
            shape = RoundedCornerShape(16.dp)
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.conn_lan_scan),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.conn_lan_scan_desc),
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
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (discoveryState is DiscoveryState.Scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(stringResource(R.string.conn_scanning))
                    } else {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(stringResource(R.string.conn_scan_btn))
                    }
                }
 
                if (discoveredCount > 1) {
                    Button(
                        onClick = onViewServers,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("查看可用服务器 ($discoveredCount)")
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
                            text = "已检查 ${scanProgress.first} / ${scanProgress.second} 个 IP 地址",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (discoveredCount > 0) {
                            Text(
                                text = "已发现 $discoveredCount 个",
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
    tokenInput: String,
    connectionState: ConnectionState,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onConnect: () -> Unit,
) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        modifier = Modifier.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
            shape = RoundedCornerShape(16.dp)
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.conn_manual_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.conn_manual_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            OutlinedTextField(
                value = ip,
                onValueChange = onIpChange,
                label = { Text(stringResource(R.string.conn_ip_label)) },
                placeholder = { Text("192.168.1.100") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = connectionState is ConnectionState.Error,
                supportingText = {
                    val error = connectionState as? ConnectionState.Error
                    Text(
                        text = error?.message ?: stringResource(R.string.conn_ip_hint),
                        color = if (error != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                shape = RoundedCornerShape(10.dp)
            )
            OutlinedTextField(
                value = port,
                onValueChange = onPortChange,
                label = { Text(stringResource(R.string.conn_port_label)) },
                placeholder = { Text("8000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = {
                    Text(stringResource(R.string.conn_port_hint))
                },
                shape = RoundedCornerShape(10.dp)
            )
            OutlinedTextField(
                value = tokenInput,
                onValueChange = onTokenChange,
                label = { Text(stringResource(R.string.auth_token_label)) },
                placeholder = { Text(stringResource(R.string.auth_token_placeholder)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                enabled = connectionState !is ConnectionState.Testing,
                shape = RoundedCornerShape(10.dp)
            ) {
                if (connectionState is ConnectionState.Testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.conn_connecting))
                } else {
                    Text(stringResource(R.string.conn_connect_btn))
                }
            }
        }
    }
}
 
@Composable
private fun StatusCard(
    icon: androidx.compose.ui.graphics.painter.Painter,
    title: String,
    message: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    ElevatedCard(
        shape = RoundedCornerShape(12.dp),
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
            Icon(painter = icon, contentDescription = null, tint = contentColor)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
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
