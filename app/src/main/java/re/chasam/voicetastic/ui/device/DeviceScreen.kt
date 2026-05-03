package re.chasam.voicetastic.ui.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import re.chasam.voicetastic.service.MeshServiceManager

@SuppressLint("MissingPermission")
@Composable
fun DeviceScreen(meshServiceManager: MeshServiceManager) {
    val devices by meshServiceManager.discoveredDevices.collectAsState()
    val isScanning by meshServiceManager.isScanning.collectAsState()
    val connectionState by meshServiceManager.connectionState.collectAsState()
    val myNodeId by meshServiceManager.myNodeId.collectAsState()
    val nodes by meshServiceManager.nodes.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Connection status
        Card(
            colors = CardDefaults.cardColors(
                containerColor = when (connectionState) {
                    "CONNECTED" -> MaterialTheme.colorScheme.primaryContainer
                    "CONNECTING" -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.errorContainer
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = when (connectionState) {
                        "CONNECTED" -> "✅ Connected to Meshtastic"
                        "CONNECTING" -> "🔄 Connecting…"
                        else -> "❌ Not connected"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                if (connectionState == "CONNECTED") {
                    myNodeId?.let { Text("My ID: $it", style = MaterialTheme.typography.bodySmall) }
                    Text("${nodes.size} nodes discovered", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { meshServiceManager.disconnect() }) {
                        Icon(Icons.Default.BluetoothDisabled, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Disconnect")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Scan controls
        if (connectionState != "CONNECTED") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Nearby Meshtastic Devices",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { meshServiceManager.stopScan() }) {
                        Text("Stop")
                    }
                } else {
                    FilledTonalButton(onClick = { meshServiceManager.startScan() }) {
                        Icon(Icons.Default.BluetoothSearching, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Scan")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (devices.isEmpty() && !isScanning) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No devices found.\nMake sure your Meshtastic device is powered on\nand Bluetooth is enabled.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices) { device ->
                    DeviceCard(device = device, onClick = {
                        meshServiceManager.connect(device)
                    })
                }
            }
        }

        // Show connected nodes
        if (connectionState == "CONNECTED" && nodes.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Mesh Nodes", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(nodes) { node ->
                    ListItem(
                        headlineContent = { Text(node.longName) },
                        supportingContent = { Text(node.nodeId) },
                        leadingContent = {
                            Text(node.shortName, style = MaterialTheme.typography.titleMedium)
                        },
                        trailingContent = {
                            node.batteryLevel?.let { Text("🔋$it%") }
                        }
                    )
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DeviceCard(device: BluetoothDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name ?: "Unknown Device",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = "Connect")
        }
    }
}

