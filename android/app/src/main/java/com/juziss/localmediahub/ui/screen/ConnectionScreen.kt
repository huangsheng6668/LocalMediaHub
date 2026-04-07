package com.juziss.localmediahub.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.juziss.localmediahub.viewmodel.ConnectionState
import com.juziss.localmediahub.viewmodel.ConnectionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    onConnected: () -> Unit,
    viewModel: ConnectionViewModel = viewModel(),
) {
    var ip by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8000") }

    // Collect saved values
    val savedIp by viewModel.savedIp.collectAsState()
    val savedPort by viewModel.savedPort.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    // Initialize with saved values
    LaunchedEffect(savedIp, savedPort) {
        if (ip.isEmpty() && savedIp.isNotEmpty()) {
            ip = savedIp
            port = savedPort
        }
    }

    // Navigate on successful connection
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionState.Connected) {
            onConnected()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect to Server") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "LocalMediaHub",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Enter your PC server address",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(32.dp))

            // IP Address input
            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it },
                label = { Text("Server IP Address") },
                placeholder = { Text("192.168.1.100") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = connectionState is ConnectionState.Error,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Port input
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("Port") },
                placeholder = { Text("8000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Connect button
            Button(
                onClick = { viewModel.testConnection(ip, port) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = connectionState !is ConnectionState.Testing,
            ) {
                if (connectionState is ConnectionState.Testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connecting...")
                } else {
                    Text("Connect")
                }
            }

            // Status message
            Spacer(modifier = Modifier.height(24.dp))

            when (val state = connectionState) {
                is ConnectionState.Connected -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Connected to ${state.serverUrl}",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                is ConnectionState.Error -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            state.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                else -> { /* Idle or Loading - no extra UI */ }
            }
        }
    }
}
