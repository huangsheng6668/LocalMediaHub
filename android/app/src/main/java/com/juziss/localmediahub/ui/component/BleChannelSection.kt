package com.juziss.localmediahub.ui.component

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.juziss.localmediahub.R
import com.juziss.localmediahub.ble.BleConnState
import com.juziss.localmediahub.ble.BleToggleRule
import com.juziss.localmediahub.data.BleDevice
import com.juziss.localmediahub.viewmodel.BleSettingsViewModel

/**
 * Self-contained BLE control-channel section: experimental toggle card plus,
 * when enabled, the scan / connect / send-test card.
 *
 * Designed to be embedded in any screen that is reachable WHILE the Wi-Fi
 * connection to the server is up (BLE scan/connect/send is proxied through
 * the server over HTTP, so it requires the server online). Collects its own
 * [BleSettingsViewModel] state and owns the runtime-permission launcher, so
 * the host screen does not need to know anything about BLE.
 */
@Composable
fun BleChannelSection(
    bleViewModel: BleSettingsViewModel? = null,
) {
    val context = LocalContext.current
    val isHiltAvailable = remember(context) {
        context is dagger.hilt.internal.GeneratedComponentManager<*> ||
        context is dagger.hilt.internal.GeneratedComponent ||
        (context as? android.app.Activity) is dagger.hilt.internal.GeneratedComponentManager<*> ||
        (context as? android.app.Activity) is dagger.hilt.internal.GeneratedComponent
    }

    val viewModel: BleSettingsViewModel = when {
        bleViewModel != null -> bleViewModel
        isHiltAvailable -> androidx.hilt.navigation.compose.hiltViewModel()
        else -> return
    }

    // Experimental BLE channel toggle. Collected here so the card reflects
    // the persisted setting + live connection state from BleController.
    val bleEnabled by viewModel.bleEnabled.collectAsState()
    val bleConnState by viewModel.connectionState.collectAsState()
    val bleHardwareAvailable by remember { mutableStateOf(viewModel.hardwareAvailable()) }
    val bleDevices by viewModel.devices.collectAsState()
    val bleScanning by viewModel.scanning.collectAsState()
    val bleEchoResult by viewModel.echoResult.collectAsState()
    val bleErrorText by viewModel.errorText.collectAsState()

    // Runtime permission launcher for BLE. On Android 12+ both BLUETOOTH_SCAN
    // and BLUETOOTH_CONNECT are runtime permissions; on older API levels they
    // are install-time (the manifest declares them with maxSdkVersion guards)
    // and the contract auto-grants, so the same launcher works for all levels.
    // The launcher must be registered at the composable body level; the
    // Switch's onCheckedChange calls it instead of touching the VM directly.
    val blePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        // Treat as granted only if every requested permission was granted
        // (or considered already-granted, which appears as `true` here).
        val granted = result.values.all { it }
        if (granted) {
            viewModel.onBleToggle(requested = true)
        } else if (!bleEnabled) {
            // User denied and the switch was off: make sure we don't leave
            // the persisted setting on. Calling with false is a no-op when
            // already off but keeps state honest if it was on.
            viewModel.onBleToggle(requested = false)
        }
    }

    BleExperimentalToggleCard(
        enabled = bleEnabled,
        connectionState = bleConnState,
        hardwareAvailable = bleHardwareAvailable,
        onCheckedChange = { requested ->
            if (!requested) {
                viewModel.onBleToggle(requested = false)
                return@BleExperimentalToggleCard
            }
            val hasPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            if (hasPermissions) {
                viewModel.onBleToggle(requested = true)
            } else {
                val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                    )
                } else {
                    arrayOf(
                        Manifest.permission.BLUETOOTH,
                        Manifest.permission.BLUETOOTH_ADMIN,
                    )
                }
                blePermissionLauncher.launch(perms)
            }
        },
    )

    // BLE device scan + connect + echo card. Only shown once the user
    // has opted in to the experimental BLE channel; the inner buttons
    // are further gated on the live connection state.
    if (bleEnabled) {
        BleDeviceScanCard(
            devices = bleDevices,
            scanning = bleScanning,
            connectionState = bleConnState,
            echoResult = bleEchoResult,
            errorText = bleErrorText,
            onScan = { viewModel.scan() },
            onConnect = { viewModel.connect(it) },
            onAutoConnect = { viewModel.autoConnect() },
            onSendTest = { viewModel.sendTest() },
        )
    }
}

/**
 * Experimental BLE control-channel toggle + status indicator.
 *
 * Renders a Switch bound to the persisted `bleEnabled` setting and a one-line
 * status text derived from the controller's [BleConnState]. The switch is
 * greyed out (non-interactive) on devices without usable Bluetooth hardware —
 * see [BleToggleRule].
 *
 * MVP scope: flipping the switch to ON requests BLUETOOTH_SCAN/BLUETOOTH_CONNECT
 * runtime permission and then calls [BleSettingsViewModel.onBleToggle]; the
 * actual advertising/GATT wiring is backed by `AndroidBlePeripheralManager`
 * (Android now acts as the BLE Peripheral). Wi-Fi/HTTP behavior is entirely
 * unaffected regardless of this switch's state.
 */
@Composable
internal fun BleExperimentalToggleCard(
    enabled: Boolean,
    connectionState: BleConnState,
    hardwareAvailable: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val canToggle = BleToggleRule.canToggle(hardwareAvailable = hardwareAvailable)

    val statusText = when (connectionState) {
        BleConnState.DISABLED -> if (hardwareAvailable) "关闭" else "此设备不支持"
        BleConnState.IDLE -> "待机"
        BleConnState.ADVERTISING -> "广播中…"
        BleConnState.CONNECTING -> "连接中…"
        BleConnState.CONNECTED -> "已连接"
        BleConnState.DISCONNECTED -> "已断开"
    }

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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "蓝牙稳定通道（实验性）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "可选的低功耗蓝牙控制通道；不可用时自动退回 Wi-Fi。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "状态：$statusText",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (connectionState == BleConnState.CONNECTED)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                enabled = canToggle,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

/**
 * Scan / connect / send-test card for the experimental BLE channel (Task 9).
 *
 * Surfaces three interactions, gated on the live [connectionState]:
 *  - 扫描设备 button: asks the PC server's BLE Central to scan; disabled
 *    while a scan is in flight or while already connected.
 *  - Device list: each discovered device is a clickable row → [onConnect].
 *  - 发送测试 button: only shown when CONNECTED; sends "ping" and shows the
 *    echoed reply in [echoResult] (null until the first send completes).
 *
 * MVP scope (YAGNI): no auto-scan, no retry, no disconnect button.
 */
@Composable
internal fun BleDeviceScanCard(
    devices: List<BleDevice>,
    scanning: Boolean,
    connectionState: BleConnState,
    echoResult: String?,
    errorText: String? = null,
    onScan: () -> Unit,
    onConnect: (BleDevice) -> Unit,
    onAutoConnect: () -> Unit,
    onSendTest: () -> Unit,
) {
    val isConnected = connectionState == BleConnState.CONNECTED

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
                text = "BLE 控制通道",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "通过 PC 服务端建立双向低延迟蓝牙 GATT 控制通道。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = onAutoConnect,
                enabled = !scanning && !isConnected,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) {
                if (scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("建立 BLE 通道中…")
                } else if (isConnected) {
                    Text("BLE 通道已建立")
                } else {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("一键建立 BLE 控制通道")
                }
            }

            if (!errorText.isNullOrBlank()) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
            }

            if (isConnected) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Button(
                    onClick = onSendTest,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("发送测试 (Ping / Pong)")
                }
                if (echoResult != null) {
                    Text(
                        text = "收到回声：$echoResult",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
