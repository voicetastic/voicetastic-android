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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hoho.android.usbserial.driver.UsbSerialDriver
import re.chasam.voicetastic.R
import re.chasam.voicetastic.service.MeshServiceManager
import re.chasam.voicetastic.service.NetworkDevice

/**
 * One screen, one list. USB and Bluetooth Meshtastic devices appear in the
 * same "Available Devices" list — the user picks whichever is closest to
 * hand. The active transport is reported in the status card at the top.
 */
@SuppressLint("MissingPermission")
@Composable
fun DeviceScreen(meshServiceManager: MeshServiceManager) {
    val bleDevices by meshServiceManager.discoveredDevices.collectAsState()
    val isScanning by meshServiceManager.isScanning.collectAsState()
    val connectionState by meshServiceManager.connectionState.collectAsState()
    val nodes by meshServiceManager.nodes.collectAsState()
    val activeTransport by meshServiceManager.activeTransport.collectAsState()
    val usbDeviceConnected by meshServiceManager.usbConnectedDevice.collectAsState()
    val isNodeScanning by meshServiceManager.isNodeScanInProgress.collectAsState()
    val networkDevices by meshServiceManager.discoveredNetworkDevices.collectAsState()
    val isNetworkScanning by meshServiceManager.isNetworkScanning.collectAsState()

    // USB hot-plug events arrive through the OS broadcast pipeline, but we
    // also re-enumerate whenever something interesting happens on screen so
    // the list stays accurate even if the broadcast is missed.
    var usbDrivers by remember { mutableStateOf<List<UsbSerialDriver>>(emptyList()) }
    LaunchedEffect(usbDeviceConnected, connectionState, isScanning) {
        usbDrivers = meshServiceManager.discoverUsbDevices()
    }

    // Build the unified entry list (USB first — when a cable is plugged in
    // the user's intent is usually "talk to that one").
    val entries: List<DeviceEntry> = remember(usbDrivers, bleDevices, networkDevices) {
        buildList {
            usbDrivers.forEach { add(DeviceEntry.Usb(it)) }
            bleDevices.forEach { add(DeviceEntry.Ble(it)) }
            networkDevices.forEach { add(DeviceEntry.Network(it)) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // ===== Unified device list =====
        if (connectionState != "CONNECTED") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.device_available_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (isScanning || isNetworkScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        meshServiceManager.stopScan()
                        meshServiceManager.stopNetworkScan()
                    }) { Text(stringResource(R.string.device_stop)) }
                }
            }

            Spacer(Modifier.height(8.dp))

            // One button per transport: Bluetooth, Serial (USB), Network (mDNS).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScanButton(
                    icon = Icons.Default.Bluetooth,
                    label = stringResource(R.string.device_scan_bluetooth),
                    enabled = !isScanning,
                    modifier = Modifier.weight(1f),
                ) { meshServiceManager.startScan() }
                ScanButton(
                    icon = Icons.Default.Usb,
                    label = stringResource(R.string.device_scan_serial),
                    enabled = true,
                    modifier = Modifier.weight(1f),
                ) { usbDrivers = meshServiceManager.discoverUsbDevices() }
                ScanButton(
                    icon = Icons.Default.Wifi,
                    label = stringResource(R.string.device_scan_network),
                    enabled = !isNetworkScanning,
                    modifier = Modifier.weight(1f),
                ) { meshServiceManager.startNetworkScan() }
            }

            Spacer(Modifier.height(8.dp))

            if (entries.isEmpty() && !isScanning) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.device_no_devices),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries, key = { it.key }) { entry ->
                    UnifiedDeviceCard(
                        entry = entry,
                        onClick = { connect(meshServiceManager, entry) }
                    )
                }
            }
        }

        // ===== Mesh nodes (after connection) =====
        if (connectionState == "CONNECTED") {
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.device_mesh_nodes),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (isNodeScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.device_scan_nodes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    FilledTonalButton(onClick = { meshServiceManager.requestNodeInfo() }) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.device_scan_nodes))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (nodes.isEmpty()) {
                Text(
                    stringResource(R.string.device_no_nodes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
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
}

// =============================================================================
//  Unified device entry model
// =============================================================================

private sealed class DeviceEntry {
    abstract val key: String
    abstract val title: String
    abstract val subtitle: String
    abstract val icon: ImageVector
    abstract val transportLabel: String

    data class Usb(val driver: UsbSerialDriver) : DeviceEntry() {
        private val device = driver.device
        override val key: String = "usb-${device.deviceName}"
        override val title: String = device.productName ?: device.deviceName
        override val subtitle: String =
            "VID:%04X PID:%04X · %s".format(
                device.vendorId, device.productId, driver.javaClass.simpleName
            )
        override val icon: ImageVector = Icons.Default.Usb
        override val transportLabel: String = "USB"
    }

    data class Ble(val device: BluetoothDevice) : DeviceEntry() {
        @SuppressLint("MissingPermission")
        override val key: String = "ble-${device.address}"
        @SuppressLint("MissingPermission")
        override val title: String = device.name ?: "Unknown Device"
        @SuppressLint("MissingPermission")
        override val subtitle: String = device.address
        override val icon: ImageVector = Icons.Default.Bluetooth
        override val transportLabel: String = "BLE"
    }

    data class Network(val device: NetworkDevice) : DeviceEntry() {
        override val key: String = device.key
        override val title: String = device.name
        override val subtitle: String = "${device.host}:${device.port}"
        override val icon: ImageVector = Icons.Default.Wifi
        override val transportLabel: String = "NET"
    }
}

@SuppressLint("MissingPermission")
private fun connect(meshServiceManager: MeshServiceManager, entry: DeviceEntry) {
    when (entry) {
        is DeviceEntry.Ble -> meshServiceManager.connect(entry.device)
        is DeviceEntry.Usb -> {
            val device = entry.driver.device
            if (meshServiceManager.usbHasPermission(device)) {
                meshServiceManager.connectUsb(entry.driver)
            } else {
                meshServiceManager.requestUsbPermission(device) { granted ->
                    if (granted) meshServiceManager.connectUsb(entry.driver)
                }
            }
        }
        is DeviceEntry.Network -> meshServiceManager.connectTcp(entry.device.host, entry.device.port)
    }
}

@Composable
private fun ScanButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun UnifiedDeviceCard(entry: DeviceEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(entry.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    AssistChip(onClick = onClick, label = { Text(entry.transportLabel) })
                }
                Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.device_connect))
        }
    }
}
