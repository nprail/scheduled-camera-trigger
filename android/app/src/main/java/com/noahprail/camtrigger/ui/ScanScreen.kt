@file:OptIn(ExperimentalMaterial3Api::class)

package com.noahprail.camtrigger.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularAlt1Bar
import androidx.compose.material.icons.filled.SignalCellularAlt2Bar
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.noahprail.camtrigger.AppModel
import com.noahprail.camtrigger.BuildConfig
import com.noahprail.camtrigger.applyDemo
import com.noahprail.camtrigger.demoScenes
import com.noahprail.camtrigger.ble.BluetoothState
import com.noahprail.camtrigger.ble.DiscoveredDevice
import com.noahprail.camtrigger.ble.bluetoothPermissions

@Composable
fun ScanScreen(model: AppModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var asked by remember { mutableStateOf(false) }
    var showDemoMenu by remember { mutableStateOf(false) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.refreshBluetooth()
        model.startScanning()
    }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.refreshBluetooth()
        model.startScanning()
    }

    // ask once, the first time we find out the permission is missing
    LaunchedEffect(model.bluetooth) {
        if (model.bluetooth == BluetoothState.UNAUTHORIZED && !asked && !model.demo) {
            asked = true
            permissions.launch(bluetoothPermissions())
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    model.refreshBluetooth()
                    model.startScanning()
                }
                Lifecycle.Event.ON_PAUSE -> model.stopScanning()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    if (showDemoMenu) DemoMenu(model, onDismiss = { showDemoMenu = false })

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    // debug builds: tap the title 7 times for the demo menu (fixtures instead of Bluetooth)
                    var taps by remember { mutableIntStateOf(0) }
                    Text(
                        "Camera Triggers",
                        modifier = if (BuildConfig.DEBUG) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                taps += 1
                                if (taps >= 7) {
                                    taps = 0
                                    showDemoMenu = true
                                }
                            }
                        } else {
                            Modifier
                        },
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (model.bluetooth) {
                BluetoothState.POWERED_ON -> {
                    Section(
                        title = null,
                        footer = "Stay within a few metres. The first time, you'll pair with the button on the device.",
                    ) {
                        if (model.devices.isEmpty()) {
                            ListItem(
                                leadingContent = { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp) },
                                headlineContent = { Text("Looking for camera triggers…") },
                                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            )
                        }
                        model.devices.forEachIndexed { index, device ->
                            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            DeviceRow(device, paired = model.isPaired(device), onClick = { model.connect(device) })
                        }
                    }
                }
                BluetoothState.UNAUTHORIZED -> Unavailable(
                    Icons.Filled.PanTool,
                    "Bluetooth is off for this app",
                    "Allow Nearby devices access for Camera Trigger in Settings.",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { permissions.launch(bluetoothPermissions()) }) { Text("Allow") }
                        Button(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        }) { Text("Open Settings") }
                    }
                }
                BluetoothState.POWERED_OFF -> Unavailable(
                    Icons.Filled.BluetoothDisabled,
                    "Bluetooth is off",
                    "Turn on Bluetooth to find your camera triggers.",
                ) {
                    Button(onClick = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) {
                        Text("Turn on")
                    }
                }
                BluetoothState.UNSUPPORTED -> Unavailable(
                    Icons.Filled.Block,
                    "Bluetooth isn't available",
                    "This device doesn't support Bluetooth Low Energy.",
                )
                BluetoothState.UNKNOWN -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun DemoMenu(model: AppModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Demo mode") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Show a screen with sample data instead of using Bluetooth. Debug builds only.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                demoScenes.forEach { scene ->
                    ListItem(
                        modifier = Modifier.clickable {
                            onDismiss()
                            model.applyDemo(scene)
                        },
                        headlineContent = { Text(scene) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    )
                }
            }
        },
        confirmButton = {
            if (model.demo) {
                TextButton(onClick = { onDismiss(); model.exitDemo() }) { Text("Exit demo") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DeviceRow(device: DiscoveredDevice, paired: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(device.name, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(if (paired) "Paired" else "Not paired") },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    if (paired) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = if (paired) "Paired" else "Not paired",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    signalIcon(device.rssi),
                    contentDescription = "Signal strength",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}

/** Map RSSI (about -95 ... -45 dBm) onto three bars. */
private fun signalIcon(rssi: Int) = when (((rssi + 95) / 50.0).coerceIn(0.0, 1.0)) {
    in 0.0..0.34 -> Icons.Filled.SignalCellularAlt1Bar
    in 0.34..0.67 -> Icons.Filled.SignalCellularAlt2Bar
    else -> Icons.Filled.SignalCellularAlt
}

@Composable
fun ConnectingScreen(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(Modifier.size(48.dp))
            Text("Connecting to $name…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PairingScreen(model: AppModel, reason: String?) {
    Scaffold(
        topBar = {
            androidx.compose.material3.CenterAlignedTopAppBar(
                title = { Text(model.info?.name ?: "Pair") },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.TouchApp,
                null,
                Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(16.dp))
            Text("Pair with this device", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.size(12.dp))
            Text(
                "Press the button on the camera trigger, then tap Pair within two minutes. This phone will then be the only one that can control it.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (reason != null) {
                Spacer(Modifier.size(12.dp))
                Text(
                    reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.size(24.dp))
            Button(
                onClick = { model.pair() },
                enabled = !model.working,
                modifier = Modifier.fillMaxWidth(0.7f),
            ) {
                if (model.working) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Pair")
                }
            }
            androidx.compose.material3.TextButton(onClick = { model.disconnect() }) { Text("Cancel") }
        }
    }
}
