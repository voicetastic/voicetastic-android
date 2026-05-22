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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import re.chasam.voicetastic.R
import re.chasam.voicetastic.model.AmrNbBitrate
import re.chasam.voicetastic.model.Codec2Mode
import re.chasam.voicetastic.model.ThemePreference
import re.chasam.voicetastic.model.VoiceCodecChoice

/**
 * Destructive device-side actions that must be confirmed before firing.
 * Held in a single `rememberSaveable` state so a rotation while the
 * dialog is open doesn't dismiss it (and doesn't accidentally re-fire
 * the action either).
 */
private enum class PendingDeviceAction { Reboot, FactoryReset }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ConfigViewModel,
    themePreference: ThemePreference,
    onThemePreferenceChange: (ThemePreference) -> Unit
) {
    var pendingAction by rememberSaveable { mutableStateOf<PendingDeviceAction?>(null) }
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
                            Text(stringResource(R.string.settings_status_format, connectionState), style = MaterialTheme.typography.titleMedium)
                            if (connectionState == "CONNECTED") {
                                myNodeId?.let { Text(stringResource(R.string.settings_node_id, it), style = MaterialTheme.typography.bodySmall) }
                                firmwareVersion?.let { Text(stringResource(R.string.settings_firmware, it), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        IconButton(onClick = { viewModel.refreshDeviceConfig() }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.settings_refresh_config))
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
                        TextButton(onClick = { viewModel.clearStatus() }) { Text(stringResource(R.string.settings_dismiss)) }
                    }
                }
            }
        }

        // ===== Appearance =====
        item {
            Card {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Palette, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.settings_appearance),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ThemePreference.entries.forEachIndexed { index, pref ->
                            SegmentedButton(
                                selected = themePreference == pref,
                                onClick = { onThemePreferenceChange(pref) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = ThemePreference.entries.size
                                )
                            ) {
                                Text(stringResource(pref.labelRes))
                            }
                        }
                    }
                }
            }
        }

        // ===== Meshtastic (device-side config) =====
        item {
            ExpandableSection(
                title = stringResource(R.string.settings_meshtastic),
                icon = Icons.Default.Hub
            ) {
                ExpandableConfigCard(title = stringResource(R.string.settings_user_owner), icon = Icons.Default.Person) {
                OutlinedTextField(
                    value = ownerState.longName,
                    onValueChange = { viewModel.setOwnerLongName(it) },
                    label = { Text(stringResource(R.string.settings_long_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ownerState.shortName,
                    // Cap at 4 chars at the UI layer so the user sees the
                    // limit (the firmware also rejects > 4) instead of
                    // typing freely and discovering on Apply that the
                    // ViewModel silently truncated their input.
                    onValueChange = { viewModel.setOwnerShortName(it.take(4)) },
                    label = { Text(stringResource(R.string.settings_short_name)) },
                    supportingText = { Text("${ownerState.shortName.length}/4") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                SwitchSetting(stringResource(R.string.settings_licensed), ownerState.isLicensed) { viewModel.setOwnerIsLicensed(it) }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.applyOwner() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_apply_owner))
                }
            }

            // ===== LoRa =====
            ExpandableConfigCard(title = stringResource(R.string.settings_lora_radio), icon = Icons.Default.Settings) {
                EnumDropdownSetting(stringResource(R.string.settings_region), viewModel.regions, loraState.region) { viewModel.setLoraRegion(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting(stringResource(R.string.settings_use_preset), loraState.usePreset) { viewModel.setLoraUsePreset(it) }
                if (loraState.usePreset) {
                    Spacer(Modifier.height(8.dp))
                    EnumDropdownSetting(stringResource(R.string.settings_modem_preset), viewModel.modemPresets, loraState.modemPreset) { viewModel.setLoraModemPreset(it) }
                } else {
                    Spacer(Modifier.height(8.dp))
                    NumberFieldSetting(stringResource(R.string.settings_bandwidth), loraState.bandwidth) { viewModel.setLoraBandwidth(it) }
                    Spacer(Modifier.height(8.dp))
                    NumberFieldSetting(stringResource(R.string.settings_spread_factor), loraState.spreadFactor) { viewModel.setLoraSpreadFactor(it) }
                    Spacer(Modifier.height(8.dp))
                    NumberFieldSetting(stringResource(R.string.settings_coding_rate), loraState.codingRate) { viewModel.setLoraCodingRate(it) }
                }
                Spacer(Modifier.height(8.dp))
                NumberFieldSetting(stringResource(R.string.settings_hop_limit), loraState.hopLimit) { viewModel.setLoraHopLimit(it) }
                Spacer(Modifier.height(8.dp))
                NumberFieldSetting(stringResource(R.string.settings_tx_power), loraState.txPower) { viewModel.setLoraTxPower(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting(stringResource(R.string.settings_tx_enabled), loraState.txEnabled) { viewModel.setLoraTxEnabled(it) }
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
                    Text(stringResource(R.string.settings_apply_lora))
                }
            }

            // ===== Device =====
            ExpandableConfigCard(title = stringResource(R.string.settings_device), icon = Icons.Default.PhoneAndroid) {
                EnumDropdownSetting(stringResource(R.string.settings_role), viewModel.deviceRoles, deviceState.role) { viewModel.setDeviceRole(it) }
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
                    Text(stringResource(R.string.settings_apply_device))
                }
            }

            // ===== Position =====
            ExpandableConfigCard(title = stringResource(R.string.settings_position), icon = Icons.Default.LocationOn) {
                EnumDropdownSetting("GPS Mode", viewModel.gpsModes, positionState.gpsMode) { viewModel.setPositionGpsMode(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("GPS Enabled", positionState.gpsEnabled) { viewModel.setPositionGpsEnabled(it) }
                Spacer(Modifier.height(8.dp))
                SwitchSetting("Fixed Position", positionState.fixedPosition) { viewModel.setPositionFixed(it) }
                if (positionState.fixedPosition) {
                    Spacer(Modifier.height(8.dp))
                    DoubleFieldSetting("Latitude (°)", positionState.fixedLatitude) { viewModel.setPositionFixedLatitude(it) }
                    Spacer(Modifier.height(8.dp))
                    DoubleFieldSetting("Longitude (°)", positionState.fixedLongitude) { viewModel.setPositionFixedLongitude(it) }
                    Spacer(Modifier.height(8.dp))
                    NumberFieldSetting("Altitude (m)", positionState.fixedAltitude) { viewModel.setPositionFixedAltitude(it) }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Button(onClick = { viewModel.applyFixedPosition() }, modifier = Modifier.weight(1f)) {
                            Text("Apply Fixed Position")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { viewModel.clearFixedPosition() }, modifier = Modifier.weight(1f)) {
                            Text("Clear")
                        }
                    }
                }
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
                    Text(stringResource(R.string.settings_apply_position))
                }
            }

            // ===== Power =====
            ExpandableConfigCard(title = stringResource(R.string.settings_power), icon = Icons.Default.BatteryChargingFull) {
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
                    Text(stringResource(R.string.settings_apply_power))
                }
            }

            // ===== Network =====
            ExpandableConfigCard(title = stringResource(R.string.settings_network), icon = Icons.Default.Wifi) {
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
                    Text(stringResource(R.string.settings_apply_network))
                }
            }

            // ===== Display =====
            ExpandableConfigCard(title = stringResource(R.string.settings_display), icon = Icons.Default.Tv) {
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
                    Text(stringResource(R.string.settings_apply_display))
                }
            }

            // ===== Bluetooth =====
            ExpandableConfigCard(title = stringResource(R.string.settings_bluetooth), icon = Icons.Default.Bluetooth) {
                SwitchSetting("Enabled", bluetoothState.enabled) { viewModel.setBluetoothEnabled(it) }
                Spacer(Modifier.height(8.dp))
                EnumDropdownSetting("Pairing Mode", viewModel.pairingModes, bluetoothState.mode) { viewModel.setBluetoothMode(it) }
                Spacer(Modifier.height(8.dp))
                SecretNumberFieldSetting("Fixed PIN", bluetoothState.fixedPin) { viewModel.setBluetoothFixedPin(it) }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { viewModel.applyBluetoothConfig() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_apply_bluetooth))
                }
            }

            // ===== Channels =====
            ExpandableConfigCard(title = stringResource(R.string.settings_channels), icon = Icons.Default.Forum) {
                if (channelsState.isEmpty()) {
                    Text(stringResource(R.string.settings_no_channels), style = MaterialTheme.typography.bodyMedium)
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

            // ===== Device Actions =====
            ExpandableConfigCard(title = stringResource(R.string.settings_device_actions), icon = Icons.Default.Warning) {
                OutlinedButton(
                    onClick = { pendingAction = PendingDeviceAction.Reboot },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_reboot))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { pendingAction = PendingDeviceAction.FactoryReset },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.settings_factory_reset))
                }
            }
            }
        }

        // ===== Voice Config =====
        item {
            ExpandableSection(title = stringResource(R.string.settings_voice), icon = Icons.Default.Mic) {
                EnumDropdownSetting(
                    label = "Codec",
                    options = listOf("AMR-NB", "Opus", "Codec2"),
                    selected = when (voiceConfig.codec) {
                        VoiceCodecChoice.AmrNb -> "AMR-NB"
                        VoiceCodecChoice.Opus -> "Opus"
                        VoiceCodecChoice.Codec2 -> "Codec2"
                    },
                    onSelected = { label ->
                        val codec = when (label) {
                            "Opus" -> VoiceCodecChoice.Opus
                            "Codec2" -> VoiceCodecChoice.Codec2
                            else -> VoiceCodecChoice.AmrNb
                        }
                        viewModel.setVoiceCodec(codec)
                    }
                )
                Spacer(Modifier.height(8.dp))

                // AMR-NB settings
                if (voiceConfig.codec == VoiceCodecChoice.AmrNb) {
                    EnumDropdownSetting(
                        label = "AMR-NB Bitrate",
                        options = AmrNbBitrate.entries.map { it.label },
                        selected = voiceConfig.bitrate.label,
                        onSelected = { label ->
                            AmrNbBitrate.entries.find { it.label == label }?.let { viewModel.setVoiceBitrate(it) }
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // Opus settings
                if (voiceConfig.codec == VoiceCodecChoice.Opus) {
                    Text("Opus Bitrate: ${voiceConfig.opusBitrateKbps} kbps")
                    Slider(
                        value = voiceConfig.opusBitrateKbps.toFloat(),
                        onValueChange = { viewModel.setOpusBitrateKbps(it.toInt()) },
                        valueRange = 6f..16f,
                        steps = 9
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // Codec2 settings
                if (voiceConfig.codec == VoiceCodecChoice.Codec2) {
                    EnumDropdownSetting(
                        label = "Codec2 Mode",
                        options = Codec2Mode.entries.map { it.label },
                        selected = voiceConfig.codec2Mode.label,
                        onSelected = { label ->
                            Codec2Mode.entries.find { it.label == label }?.let { viewModel.setCodec2Mode(it) }
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                }

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
                SwitchSetting(
                    "Noise Suppression",
                    voiceConfig.noiseSuppressionEnabled
                ) { viewModel.setNoiseSuppressionEnabled(it) }
            }
        }

    }

    pendingAction?.let { action ->
        val (titleRes, messageRes) = when (action) {
            PendingDeviceAction.Reboot ->
                R.string.settings_reboot_confirm_title to R.string.settings_reboot_confirm_message
            PendingDeviceAction.FactoryReset ->
                R.string.settings_factory_reset_confirm_title to R.string.settings_factory_reset_confirm_message
        }
        val destructive = action == PendingDeviceAction.FactoryReset
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(stringResource(titleRes)) },
            text = { Text(stringResource(messageRes)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (action) {
                            PendingDeviceAction.Reboot -> viewModel.rebootDevice()
                            PendingDeviceAction.FactoryReset -> viewModel.factoryReset()
                        }
                        pendingAction = null
                    },
                    colors = if (destructive) {
                        ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                ) {
                    Text(stringResource(R.string.settings_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

// ========================  REUSABLE COMPOSABLES  ========================

@Composable
private fun ExpandableSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) stringResource(R.string.settings_collapse) else stringResource(R.string.settings_expand)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        }
    }
}

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
                        contentDescription = if (expanded) stringResource(R.string.settings_collapse) else stringResource(R.string.settings_expand)
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

/**
 * Free-form decimal field backed by a `Double`. Used for high-precision
 * coordinates (latitude / longitude) where Float lacks the resolution
 * needed for sub-metre accuracy.
 *
 * Holds a local string so the user can type intermediate states like "-",
 * "1.", or "1.0" without the value being clobbered to 0 mid-typing.
 */
@Composable
private fun DoubleFieldSetting(label: String, value: Double, onValueChange: (Double) -> Unit) {
    var text by remember(value) {
        mutableStateOf(if (value == 0.0) "" else value.toString())
    }
    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            newText.toDoubleOrNull()?.let(onValueChange)
                ?: if (newText.isBlank()) onValueChange(0.0) else Unit
        },
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
                    contentDescription = if (visible) stringResource(R.string.settings_hide) else stringResource(R.string.settings_show)
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

