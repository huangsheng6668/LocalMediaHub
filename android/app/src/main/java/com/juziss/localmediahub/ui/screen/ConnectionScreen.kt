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
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
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
    onBrowseOffline: () -> Unit = {},
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
                        Text("连接到服务器", fontWeight = FontWeight.Bold)
                        Text(
                            text = "配对并连接您的 PC 媒体服务器",
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
                        title = "正在检测服务器状态",
                        message = "正在尝试连接 LocalMediaHub，连接成功后将自动保存该服务器。",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                is ConnectionState.Connected -> {
                    StatusCard(
                        icon = Icons.Filled.CheckCircle,
                        title = "连接已准备就绪",
                        message = "已成功连接至 ${(connectionState as ConnectionState.Connected).serverUrl}",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                is ConnectionState.Error -> {
                    StatusCard(
                        icon = Icons.Filled.Error,
                        title = "连接失败",
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
                        title = "未发现可用服务器",
                        message = "局域网扫描结束，未发现运行中的 LocalMediaHub 服务。您仍然可以使用手动输入 IP 地址进行连接。",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
                is DiscoveryState.Error -> {
                    StatusCard(
                        icon = Icons.Filled.Error,
                        title = "自动发现服务遇到问题",
                        message = state.message,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                is DiscoveryState.FoundMultiple -> {
                    StatusCard(
                        icon = Icons.Filled.CheckCircle,
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
 
            ElevatedCard(
                onClick = onBrowseOffline,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(16.dp)
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(28.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "浏览离线下载内容",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "无网状态下直接播放已下载的视频或浏览图片。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
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
                text = "选择服务器",
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
                Text("取消")
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
                text = "将您的手机与电脑连接至同一局域网，LocalMediaHub 将自动完成其余工作。",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = if (savedIp.isNotBlank()) {
                    "我们将优先尝试连接上次使用的服务器，您也可以随时进行局域网扫描或手动输入。"
                } else {
                    "推荐使用自动搜索连接，或在下方手动输入服务器地址。"
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
                            Icons.Filled.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column {
                            Text(
                                text = "上次使用的服务器",
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
                    text = "正在尝试自动连接保存的服务器...",
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
                text = "局域网自动搜索",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "自动扫描当前局域网以寻找运行中的 LocalMediaHub 服务端并自动完成配对。",
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
                        Text("正在扫描...")
                    } else {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("扫描局域网")
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
    connectionState: ConnectionState,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
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
                text = "手动输入连接",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "若自动扫描未能发现，请输入您电脑的局域网 IP 地址和 LocalMediaHub 运行端口。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            OutlinedTextField(
                value = ip,
                onValueChange = onIpChange,
                label = { Text("服务器 IP 地址") },
                placeholder = { Text("192.168.1.100") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = connectionState is ConnectionState.Error,
                supportingText = {
                    Text("通常为运行 LocalMediaHub 服务端的电脑局域网 IPv4 地址。")
                },
                shape = RoundedCornerShape(10.dp)
            )
            OutlinedTextField(
                value = port,
                onValueChange = onPortChange,
                label = { Text("运行端口") },
                placeholder = { Text("8000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = {
                    Text("若未修改服务端配置文件，请保持默认的 8000 端口。")
                },
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
                    Text("正在连接...")
                } else {
                    Text("手动连接服务器")
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
            Icon(icon, contentDescription = null, tint = contentColor)
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
