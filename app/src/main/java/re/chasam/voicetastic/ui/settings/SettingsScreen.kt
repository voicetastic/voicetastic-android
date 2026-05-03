package re.chasam.voicetastic.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import re.chasam.voicetastic.model.AmrNbBitrate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: ConfigViewModel) {
    val connectionState by viewModel.connectionState.collectAsState()
    val configStatus by viewModel.configStatus.collectAsState()
    val myNodeId by viewModel.myNodeId.collectAsState()
    val firmwareVersion by viewModel.firmwareVersion.collectAsState()

    val ownerState by viewModel.ownerState.collectAsState()
    val loraState by viewModel.loraState.collectAsState()
    val deviceState by viewModel.deviceState.collectAsState()
    val positionState by viewModel.positionState.collectAsState()
    val powerState by viewModel.powerState.collectAsState()
    val networkState by viewModel.networkState.collectAsState()
    val displayState by viewModel.displayState.collectAsState()
    val bluetoothState by viewModel.bluetoothState.collectAsState()
    val channelsState by viewModel.channelsState.collectAsState()
    val voiceConfig by viewModel.currentVoiceConfig.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ===== Connection Status =====
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when (connectionState) {
                        "CONNECTED" -> MaterialTheme.colorScheme.primaryContainer
                        "CONNECTING" -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    }
                )
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Meshtastic: $connectionState", style = MaterialTheme.typography.titleMedium)
                            if (connectionState == "CONNECTED") {
                                myNodeId?.let { Text("Node: $it", style = MaterialTheme.typography.bodySmall) }
                                firmwareVersion?.let { Text("Firmware: $it", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        IconButton(onClick = { viewModel.refreshDeviceConfig() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh config")
                        }
                    }
                }
            }
        }

        // ===== Status message =====
        item {
            configStatus?.let { status ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(status, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { viewModel.clearStatus() }) { Text("Dismiss") }
                    }
                }
            }
        }

        // ===== Owner =====
        item {
            ExpandableConfigCard(title = "User / Owner", icon = Icons.Default.Person) {
                OutlinedTextField(
                    value = ownerState.longName,
                    onValueChange = { viewModel.setOwnerLongName(it) },
                    label = { Text("Long Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ownerState.shortName,
                    onValueChange = { viewModel.setOwnerShortName(it) },
                    label = { Text("Short Name (max 4)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Licensed (HAM)", ownerState.isLicensed) { viewModel.setOwnerIsLicensed(it) }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.applyOwner() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Apply Owner")
                }
            }
        }

        // ===== LoRa =====
        item {
            ExpandableConfigCard(title = "LoRa / Radio", icon = Icons.Default.Settings) {
                EnumDropdownSetting("Region", viewModel.regions, loraState.region) { viewModel.setLoraRegion(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Use Preset", loraState.usePreset) { viewModel.setLoraUsePreset(it) }
                if (loraState.usePreset) {
                    Spacer(Modifier.height(8.dp))
                    EnumDropdownSetting("Modem Preset", viewModel.modemPresets, loraState.modemPreset) { viewModel.setLoraModemPreset(it) }
                } else {
                    Spacer(Modifier.height(8.dp))
                    NumberFieldSetting("Bandwidth (kHz)", loraState.bandwidth) { viewModel.setLoraBandwidth(it) }
                    Spacer(Modifier.height(8.dp))
                    NumberFieldSetting("Spread Factor", loraState.spreadFactor) { viewModel.setLoraSpreadFactor(it) }
                    Spacer(Modifier.height(8.dp))
                    NumberFieldSetting("Coding Rate", loraState.codingRate) { viewModel.setLoraCodingRate(it) }
                }
                Spacer(Modifier.height(8.dp))
                NumberFieldSetting("Hop Limit (1–7)", loraState.hopLimit) { viewModel.setLoraHopLimit(it) }
                Spacer(Modifier.height(8.dp))
                NumberFieldSetting("Tx Power (dBm)", loraState.txPower) { viewModel.setLoraTxPower(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Tx Enabled", loraState.txEnabled) { viewModel.setLoraTxEnabled(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Override Duty Cycle", loraState.overrideDutyCycle) { viewModel.setLoraOverrideDutyCycle(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("SX126x Rx Boosted Gain", loraState.sx126xRxBoostedGain) { viewModel.setLoraSx126xRxBoostedGain(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Ignore MQTT", loraState.ignoreMqtt) { viewModel.setLoraIgnoreMqtt(it) }
                Spacer(Modifier.height(8.dp))
                NumberFieldSetting("Channel Num", loraState.channelNum) { viewModel.setLoraChannelNum(it) }
                Spacer(Modifier.height(8.dp))
                FloatFieldSetting("Frequency Offset (MHz)", loraState.frequencyOffset) { viewModel.setLoraFrequencyOffset(it) }
                Spacer(Modifier.height(8.dp))
                FloatFieldSetting("Override Frequency (MHz)", loraState.overrideFrequency) { viewModel.setLoraOverrideFrequency(it) }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { viewModel.applyLoraConfig() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Apply LoRa Config")
                }
            }
        }

        // ===== Device =====
        item {
            ExpandableConfigCard(title = "Device", icon = Icons.Default.PhoneAndroid) {
                EnumDropdownSetting("Role", viewModel.deviceRoles, deviceState.role) { viewModel.setDeviceRole(it) }
                Spacer(Modifier.height(8.dp))
                EnumDropdownSetting("Rebroadcast Mode", viewModel.rebroadcastModes, deviceState.rebroadcastMode) { viewModel.setDeviceRebroadcastMode(it) }
                Spacer(Modifier.height(8.dp))
                NumberFieldSetting("NodeInfo Broadcast (s)", deviceState.nodeInfoBroadcastSecs) { viewModel.setDeviceNodeInfoBroadcastSecs(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Serial Enabled", deviceState.serialEnabled) { viewModel.setDeviceSerialEnabled(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Debug Log Enabled", deviceState.debugLogEnabled) { viewModel.setDeviceDebugLogEnabled(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Double Tap as Button", deviceState.doubleTapAsButtonPress) { viewModel.setDeviceDoubleTapAsButtonPress(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Disable Triple Click", deviceState.disableTripleClick) { viewModel.setDeviceDisableTripleClick(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Managed Mode", deviceState.isManaged) { viewModel.setDeviceIsManaged(it) }
                Spacer(Modifier.height(8.dp))
                NumberFieldSetting("Button GPIO", deviceState.buttonGpio) { viewModel.setDeviceButtonGpio(it) }
                Spacer(Modifier.height(8.dp))
                NumberFieldSetting("Buzzer GPIO", deviceState.buzzerGpio) { viewModel.setDeviceBuzzerGpio(it) }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { viewModel.applyDeviceConfig() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Apply Device Config")
                }
            }
        }

        // ===== Position =====
        item {
            ExpandableConfigCard(title = "Position", icon = Icons.Default.LocationOn) {
                EnumDropdownSetting("GPS Mode", viewModel.gpsModes, positionState.gpsMode) { viewModel.setPositionGpsMode(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("GPS Enabled", positionState.gpsEnabled) { viewModel.setPositionGpsEnabled(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Fixed Position", positionState.fixedPosition) { viewModel.setPositionFixed(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Smart Broadcast", positionState.positionBroadcastSmartEnabled) { viewModel.setPositionSmartEnabled(it) }
                Spacer(Modifier.height(8.dp))
                NumberFieldSetting("Broadcast Interval (s)", positionState.positionBroadcastSecs) { viewModel.setPositionBroadcastSecs(it) }
                Spacer(Modifier.height(8.dp))
                NumberFieldSetting("GPS Update Interval (s)", positionState.gpsUpdateInterval) { viewModel.setPositionGpsUpdateInterval(it) }
                Spacer(Modifier.height(8.dp))
                NumberFieldSetting("Smart Min Distance (m)", positionState.broadcastSmartMinimumDistance) { viewModel.setPositionSmartMinDistance(it) }
                Spacer(Modifier.height(8.dp))
                NumberFieldSetting("Smart Min Interval (s)", positionState.broadcastSmartMinimumIntervalSecs) { viewModel.setPositionSmartMinInterval(it) }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { viewModel.applyPositionConfig() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Apply Position Config")
                }
            }
        }

        // ===== Power =====
        item {
            ExpandableConfigCard(title = "Power", icon = Icons.Default.BatteryChargingFull) {
                SwitchSetting("Power Saving", powerState.isPowerSaving) { viewModel.setPowerSaving(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Shutdown on Power Loss", powerState.shutdownOnPowerLoss) { viewModel.setPowerShutdownOnPowerLoss(it) }
                Spacer(Modifier.height(8.dp))
                NumberFieldSetting("Shutdown After (s, on battery)", powerState.onBatteryShutdownAfterSecs) { viewModel.setPowerShutdownAfterSecs(it) }
                Spacer(Modifier.height(8.dp))
                NumberFieldSetting("Wait Bluetooth (s)", powerState.waitBluetoothSecs) { viewModel.setPowerWaitBluetoothSecs(it) }
                Spacer(Modifier.height(8.dp))
                NumberFieldSetting("SDS Secs", powerState.sdsSecs) { viewModel.setPowerSdsSecs(it) }
                Spacer(Modifier.height(8.dp))
                NumberFieldSetting("LS Secs", powerState.lsSecs) { viewModel.setPowerLsSecs(it) }
                Spacer(Modifier.height(8.dp))
                NumberFieldSetting("Min Wake Secs", powerState.minWakeSecs) { viewModel.setPowerMinWakeSecs(it) }
                Spacer(Modifier.height(8.dp))
                FloatFieldSetting("ADC Multiplier Override", powerState.adcMultiplierOverride) { viewModel.setPowerAdcMultiplier(it) }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { viewModel.applyPowerConfig() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Apply Power Config")
                }
            }
        }

        // ===== Network =====
        item {
            ExpandableConfigCard(title = "Network", icon = Icons.Default.Wifi) {
                SwitchSetting("WiFi Enabled", networkState.wifiEnabled) { viewModel.setNetworkWifiEnabled(it) }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = networkState.wifiSsid,
                    onValueChange = { viewModel.setNetworkWifiSsid(it) },
                    label = { Text("WiFi SSID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                SecretFieldSetting("WiFi PSK", networkState.wifiPsk) { viewModel.setNetworkWifiPsk(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Ethernet Enabled", networkState.ethEnabled) { viewModel.setNetworkEthEnabled(it) }
                Spacer(Modifier.height(8.dp))
                EnumDropdownSetting("Address Mode", viewModel.addressModes, networkState.addressMode) { viewModel.setNetworkAddressMode(it) }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = networkState.ntpServer,
                    onValueChange = { viewModel.setNetworkNtpServer(it) },
                    label = { Text("NTP Server") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = networkState.rsyslogServer,
                    onValueChange = { viewModel.setNetworkRsyslogServer(it) },
                    label = { Text("Rsyslog Server") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { viewModel.applyNetworkConfig() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Apply Network Config")
                }
            }
        }

        // ===== Display =====
        item {
            ExpandableConfigCard(title = "Display", icon = Icons.Default.Tv) {
                NumberFieldSetting("Screen On (s)", displayState.screenOnSecs) { viewModel.setDisplayScreenOnSecs(it) }
                Spacer(Modifier.height(8.dp))
                NumberFieldSetting("Auto Carousel (s)", displayState.autoScreenCarouselSecs) { viewModel.setDisplayAutoCarouselSecs(it) }
                Spacer(Modifier.height(8.dp))
                EnumDropdownSetting("GPS Format", viewModel.gpsFormats, displayState.gpsFormat) { viewModel.setDisplayGpsFormat(it) }
                Spacer(Modifier.height(8.dp))
                EnumDropdownSetting("Units", viewModel.displayUnits, displayState.units) { viewModel.setDisplayUnits(it) }
                Spacer(Modifier.height(8.dp))
                EnumDropdownSetting("OLED Type", viewModel.oledTypes, displayState.oled) { viewModel.setDisplayOled(it) }
                Spacer(Modifier.height(8.dp))
                EnumDropdownSetting("Display Mode", viewModel.displayModes, displayState.displaymode) { viewModel.setDisplayMode(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Compass North Top", displayState.compassNorthTop) { viewModel.setDisplayCompassNorthTop(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Flip Screen", displayState.flipScreen) { viewModel.setDisplayFlipScreen(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Heading Bold", displayState.headingBold) { viewModel.setDisplayHeadingBold(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Wake on Tap/Motion", displayState.wakeOnTapOrMotion) { viewModel.setDisplayWakeOnTapOrMotion(it) }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { viewModel.applyDisplayConfig() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Apply Display Config")
                }
            }
        }

        // ===== Bluetooth =====
        item {
            ExpandableConfigCard(title = "Bluetooth", icon = Icons.Default.Bluetooth) {
                SwitchSetting("Enabled", bluetoothState.enabled) { viewModel.setBluetoothEnabled(it) }
                Spacer(Modifier.height(8.dp))
                EnumDropdownSetting("Pairing Mode", viewModel.pairingModes, bluetoothState.mode) { viewModel.setBluetoothMode(it) }
                Spacer(Modifier.height(8.dp))
                SecretNumberFieldSetting("Fixed PIN", bluetoothState.fixedPin) { viewModel.setBluetoothFixedPin(it) }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { viewModel.applyBluetoothConfig() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Apply Bluetooth Config")
                }
            }
        }

        // ===== Channels =====
        item {
            ExpandableConfigCard(title = "Channels", icon = Icons.Default.Forum) {
                if (channelsState.isEmpty()) {
                    Text("No channels received yet", style = MaterialTheme.typography.bodyMedium)
                }
                channelsState.forEach { ch ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Channel ${ch.index} (${ch.role})", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = ch.name,
                                onValueChange = { viewModel.setChannelName(ch.index, it) },
                                label = { Text("Name") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(Modifier.height(4.dp))
                            SecretFieldSetting("PSK (hex)", ch.pskHex) { viewModel.setChannelPskHex(ch.index, it) }
                            Spacer(Modifier.height(4.dp))
                            SwitchSetting("Uplink", ch.uplinkEnabled) { viewModel.setChannelUplink(ch.index, it) }
                            SwitchSetting("Downlink", ch.downlinkEnabled) { viewModel.setChannelDownlink(ch.index, it) }
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(onClick = { viewModel.applyChannel(ch.index) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Apply Channel ${ch.index}")
                            }
                        }
                    }
                }
            }
        }

        // ===== Voice Config =====
        item {
            ExpandableConfigCard(title = "Voice", icon = Icons.Default.Mic) {
                EnumDropdownSetting(
                    label = "AMR-NB Bitrate",
                    options = AmrNbBitrate.entries.map { it.label },
                    selected = voiceConfig.bitrate.label,
                    onSelected = { label ->
                        AmrNbBitrate.entries.find { it.label == label }?.let { viewModel.setVoiceBitrate(it) }
                    }
                )
                Spacer(Modifier.height(8.dp))
                Text("Max Recording Duration: ${voiceConfig.maxDurationSeconds}s")
                Slider(
                    value = voiceConfig.maxDurationSeconds.toFloat(),
                    onValueChange = { viewModel.setMaxDuration(it.toInt()) },
                    valueRange = 1f..60f,
                    steps = 58
                )
                Text("Chunk Receive Timeout: ${voiceConfig.chunkTimeoutSeconds}s")
                Slider(
                    value = voiceConfig.chunkTimeoutSeconds.toFloat(),
                    onValueChange = { viewModel.setChunkTimeout(it.toInt()) },
                    valueRange = 5f..120f,
                    steps = 22
                )
                SwitchSetting("Partial Play on Timeout", voiceConfig.partialPlayOnTimeout) { viewModel.setPartialPlayOnTimeout(it) }
            }
        }

        // ===== Device Actions =====
        item {
            ExpandableConfigCard(title = "Device Actions", icon = Icons.Default.Warning) {
                OutlinedButton(onClick = { viewModel.rebootDevice() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Reboot Device")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.factoryReset() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Factory Reset")
                }
            }
        }
    }
}

// ========================  REUSABLE COMPOSABLES  ========================

@Composable
private fun ExpandableConfigCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun SwitchSetting(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun NumberFieldSetting(label: String, value: Int, onValueChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { onValueChange(it.toIntOrNull() ?: 0) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
private fun FloatFieldSetting(label: String, value: Float, onValueChange: (Float) -> Unit) {
    OutlinedTextField(
        value = if (value == 0f) "0" else value.toString(),
        onValueChange = { onValueChange(it.toFloatOrNull() ?: 0f) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

@Composable
private fun SecretFieldSetting(label: String, value: String, onValueChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "Hide" else "Show"
                )
            }
        }
    )
}

@Composable
private fun SecretNumberFieldSetting(label: String, value: Int, onValueChange: (Int) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { onValueChange(it.toIntOrNull() ?: 0) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "Hide" else "Show"
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnumDropdownSetting(
    label: String,
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

