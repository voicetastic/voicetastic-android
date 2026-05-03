package re.chasam.voicetastic.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geeksville.mesh.MeshProtos
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import re.chasam.voicetastic.model.AmrNbBitrate
import re.chasam.voicetastic.model.VoiceConfig
import re.chasam.voicetastic.service.MeshServiceManager

/**
 * ViewModel for the settings/configuration screen.
 * Manages Meshtastic device config (all sections), channels, owner, modules, and app voice config.
 * Observes config flows from MeshServiceManager and populates UI state on connection.
 */
class ConfigViewModel(
    private val meshService: MeshServiceManager,
    private val voiceConfig: MutableStateFlow<VoiceConfig>
) : ViewModel() {

    val connectionState: StateFlow<String> = meshService.connectionState
    val myNodeId: StateFlow<String?> = meshService.myNodeId
    val firmwareVersion: StateFlow<String?> = meshService.firmwareVersion

    // --- Status ---
    private val _configStatus = MutableStateFlow<String?>(null)
    val configStatus: StateFlow<String?> = _configStatus.asStateFlow()

    // ========================  OWNER  ========================

    data class OwnerUiState(
        val longName: String = "",
        val shortName: String = "",
        val isLicensed: Boolean = false
    )

    private val _ownerState = MutableStateFlow(OwnerUiState())
    val ownerState: StateFlow<OwnerUiState> = _ownerState.asStateFlow()

    // ========================  LORA  ========================

    data class LoraUiState(
        val region: String = "UNSET",
        val modemPreset: String = "LONG_FAST",
        val usePreset: Boolean = true,
        val bandwidth: Int = 0,
        val spreadFactor: Int = 0,
        val codingRate: Int = 0,
        val frequencyOffset: Float = 0f,
        val hopLimit: Int = 3,
        val txEnabled: Boolean = true,
        val txPower: Int = 0,
        val channelNum: Int = 0,
        val overrideDutyCycle: Boolean = false,
        val sx126xRxBoostedGain: Boolean = false,
        val overrideFrequency: Float = 0f,
        val ignoreMqtt: Boolean = false
    )

    private val _loraState = MutableStateFlow(LoraUiState())
    val loraState: StateFlow<LoraUiState> = _loraState.asStateFlow()

    val regions = listOf(
        "UNSET", "US", "EU_433", "EU_868", "CN", "JP", "ANZ", "KR",
        "TW", "RU", "IN", "NZ_865", "TH", "LORA_24", "UA_433", "UA_868"
    )

    val modemPresets = listOf(
        "LONG_FAST", "LONG_SLOW", "VERY_LONG_SLOW",
        "MEDIUM_SLOW", "MEDIUM_FAST",
        "SHORT_SLOW", "SHORT_FAST", "LONG_MODERATE", "SHORT_TURBO"
    )

    // ========================  DEVICE  ========================

    data class DeviceUiState(
        val role: String = "CLIENT",
        val serialEnabled: Boolean = false,
        val debugLogEnabled: Boolean = false,
        val buttonGpio: Int = 0,
        val buzzerGpio: Int = 0,
        val rebroadcastMode: String = "ALL",
        val nodeInfoBroadcastSecs: Int = 0,
        val doubleTapAsButtonPress: Boolean = false,
        val isManaged: Boolean = false,
        val disableTripleClick: Boolean = false
    )

    private val _deviceState = MutableStateFlow(DeviceUiState())
    val deviceState: StateFlow<DeviceUiState> = _deviceState.asStateFlow()

    val deviceRoles = listOf(
        "CLIENT", "CLIENT_MUTE", "ROUTER", "ROUTER_CLIENT", "REPEATER",
        "TRACKER", "SENSOR", "TAK", "CLIENT_HIDDEN", "LOST_AND_FOUND", "TAK_TRACKER"
    )

    val rebroadcastModes = listOf("ALL", "ALL_SKIP_DECODING", "LOCAL_ONLY", "KNOWN_ONLY")

    // ========================  POSITION  ========================

    data class PositionUiState(
        val positionBroadcastSecs: Int = 0,
        val positionBroadcastSmartEnabled: Boolean = false,
        val fixedPosition: Boolean = false,
        val gpsEnabled: Boolean = true,
        val gpsUpdateInterval: Int = 0,
        val gpsMode: String = "ENABLED",
        val broadcastSmartMinimumDistance: Int = 0,
        val broadcastSmartMinimumIntervalSecs: Int = 0
    )

    private val _positionState = MutableStateFlow(PositionUiState())
    val positionState: StateFlow<PositionUiState> = _positionState.asStateFlow()

    val gpsModes = listOf("DISABLED", "ENABLED", "NOT_PRESENT")

    // ========================  POWER  ========================

    data class PowerUiState(
        val isPowerSaving: Boolean = false,
        val onBatteryShutdownAfterSecs: Int = 0,
        val adcMultiplierOverride: Float = 0f,
        val waitBluetoothSecs: Int = 0,
        val sdsSecs: Int = 0,
        val lsSecs: Int = 0,
        val minWakeSecs: Int = 0,
        val shutdownOnPowerLoss: Boolean = false
    )

    private val _powerState = MutableStateFlow(PowerUiState())
    val powerState: StateFlow<PowerUiState> = _powerState.asStateFlow()

    // ========================  NETWORK  ========================

    data class NetworkUiState(
        val wifiEnabled: Boolean = false,
        val wifiSsid: String = "",
        val wifiPsk: String = "",
        val ethEnabled: Boolean = false,
        val addressMode: String = "DHCP",
        val ntpServer: String = "",
        val rsyslogServer: String = ""
    )

    private val _networkState = MutableStateFlow(NetworkUiState())
    val networkState: StateFlow<NetworkUiState> = _networkState.asStateFlow()

    val addressModes = listOf("DHCP", "STATIC")

    // ========================  DISPLAY  ========================

    data class DisplayUiState(
        val screenOnSecs: Int = 0,
        val gpsFormat: String = "DEC",
        val autoScreenCarouselSecs: Int = 0,
        val compassNorthTop: Boolean = false,
        val flipScreen: Boolean = false,
        val units: String = "METRIC",
        val oled: String = "OLED_AUTO",
        val displaymode: String = "DEFAULT",
        val headingBold: Boolean = false,
        val wakeOnTapOrMotion: Boolean = false
    )

    private val _displayState = MutableStateFlow(DisplayUiState())
    val displayState: StateFlow<DisplayUiState> = _displayState.asStateFlow()

    val gpsFormats = listOf("DEC", "DMS", "UTM", "MGRS", "OLC", "OSGR")
    val displayUnits = listOf("METRIC", "IMPERIAL")
    val oledTypes = listOf("OLED_AUTO", "OLED_SSD1306", "OLED_SH1106", "OLED_SH1107")
    val displayModes = listOf("DEFAULT", "TWOCOLOR", "INVERTED", "COLOR")

    // ========================  BLUETOOTH  ========================

    data class BluetoothUiState(
        val enabled: Boolean = true,
        val mode: String = "RANDOM_PIN",
        val fixedPin: Int = 0
    )

    private val _bluetoothState = MutableStateFlow(BluetoothUiState())
    val bluetoothState: StateFlow<BluetoothUiState> = _bluetoothState.asStateFlow()

    val pairingModes = listOf("RANDOM_PIN", "FIXED_PIN", "NO_PIN")

    // ========================  CHANNELS  ========================

    data class ChannelUiState(
        val index: Int = 0,
        val role: String = "DISABLED",
        val name: String = "",
        val pskHex: String = "",
        val uplinkEnabled: Boolean = false,
        val downlinkEnabled: Boolean = false
    )

    private val _channelsState = MutableStateFlow<List<ChannelUiState>>(emptyList())
    val channelsState: StateFlow<List<ChannelUiState>> = _channelsState.asStateFlow()

    val channelRoles = listOf("DISABLED", "PRIMARY", "SECONDARY")

    // ========================  VOICE CONFIG  ========================

    val currentVoiceConfig: StateFlow<VoiceConfig> = voiceConfig.asStateFlow()

    // ========================  INIT — FLOW COLLECTION  ========================

    init {
        // When connection state becomes CONNECTED, sync config after a delay
        viewModelScope.launch {
            meshService.connectionState.collect { state ->
                if (state == "CONNECTED") {
                    // Wait for the automatic config request to complete
                    kotlinx.coroutines.delay(2000)
                    syncFromServiceFlows()
                }
            }
        }

        // Also observe each config flow for real-time updates
        viewModelScope.launch {
            meshService.radioConfig.collect { lora -> if (lora != null) updateLoraFromProto(lora) }
        }
        viewModelScope.launch {
            meshService.deviceConfig.collect { dev -> if (dev != null) updateDeviceFromProto(dev) }
        }
        viewModelScope.launch {
            meshService.positionConfig.collect { pos -> if (pos != null) updatePositionFromProto(pos) }
        }
        viewModelScope.launch {
            meshService.powerConfig.collect { pwr -> if (pwr != null) updatePowerFromProto(pwr) }
        }
        viewModelScope.launch {
            meshService.networkConfig.collect { net -> if (net != null) updateNetworkFromProto(net) }
        }
        viewModelScope.launch {
            meshService.displayConfig.collect { dsp -> if (dsp != null) updateDisplayFromProto(dsp) }
        }
        viewModelScope.launch {
            meshService.bluetoothConfig.collect { bt -> if (bt != null) updateBluetoothFromProto(bt) }
        }
        viewModelScope.launch {
            meshService.owner.collect { user ->
                if (user != null) {
                    _ownerState.value = OwnerUiState(
                        longName = user.longName,
                        shortName = user.shortName,
                        isLicensed = user.isLicensed
                    )
                }
            }
        }
        viewModelScope.launch {
            meshService.channels.collect { chList ->
                if (chList.isNotEmpty()) {
                    _channelsState.value = chList.map { ch ->
                        ChannelUiState(
                            index = ch.index,
                            role = ch.role.let {
                                if (it == MeshProtos.Channel.Role.UNRECOGNIZED) "PRIMARY" else it.name
                            },
                            name = if (ch.hasSettings()) ch.settings.name else "",
                            pskHex = if (ch.hasSettings()) ch.settings.psk.toByteArray().toHex() else "",
                            uplinkEnabled = if (ch.hasSettings()) ch.settings.uplinkEnabled else false,
                            downlinkEnabled = if (ch.hasSettings()) ch.settings.downlinkEnabled else false
                        )
                    }
                }
            }
        }
    }

    /**
     * Manually sync all UI state from the current values in the service StateFlows.
     * This ensures fields are populated even if the flow emission was missed.
     */
    private fun syncFromServiceFlows() {
        meshService.radioConfig.value?.let { updateLoraFromProto(it) }
        meshService.deviceConfig.value?.let { updateDeviceFromProto(it) }
        meshService.positionConfig.value?.let { updatePositionFromProto(it) }
        meshService.powerConfig.value?.let { updatePowerFromProto(it) }
        meshService.networkConfig.value?.let { updateNetworkFromProto(it) }
        meshService.displayConfig.value?.let { updateDisplayFromProto(it) }
        meshService.bluetoothConfig.value?.let { updateBluetoothFromProto(it) }
        meshService.owner.value?.let { user ->
            _ownerState.value = OwnerUiState(
                longName = user.longName,
                shortName = user.shortName,
                isLicensed = user.isLicensed
            )
        }
        val chList = meshService.channels.value
        if (chList.isNotEmpty()) {
            _channelsState.value = chList.map { ch ->
                ChannelUiState(
                    index = ch.index,
                    role = ch.role.let {
                        if (it == MeshProtos.Channel.Role.UNRECOGNIZED) "PRIMARY" else it.name
                    },
                    name = if (ch.hasSettings()) ch.settings.name else "",
                    pskHex = if (ch.hasSettings()) ch.settings.psk.toByteArray().toHex() else "",
                    uplinkEnabled = if (ch.hasSettings()) ch.settings.uplinkEnabled else false,
                    downlinkEnabled = if (ch.hasSettings()) ch.settings.downlinkEnabled else false
                )
            }
        }
    }

    private fun updateLoraFromProto(lora: MeshProtos.Config.LoRaConfig) {
        val regionName = lora.region.let {
            if (it == MeshProtos.Config.LoRaConfig.RegionCode.UNRECOGNIZED) "UNSET" else it.name
        }
        val presetName = lora.modemPreset.let {
            if (it == MeshProtos.Config.LoRaConfig.ModemPreset.UNRECOGNIZED) "LONG_FAST" else it.name
        }
        _loraState.value = LoraUiState(
            region = regionName,
            modemPreset = presetName,
            usePreset = lora.usePreset,
            bandwidth = lora.bandwidth,
            spreadFactor = lora.spreadFactor,
            codingRate = lora.codingRate,
            frequencyOffset = lora.frequencyOffset,
            hopLimit = lora.hopLimit,
            txEnabled = lora.txEnabled,
            txPower = lora.txPower,
            channelNum = lora.channelNum,
            overrideDutyCycle = lora.overrideDutyCycle,
            sx126xRxBoostedGain = lora.sx126XRxBoostedGain,
            overrideFrequency = lora.overrideFrequency,
            ignoreMqtt = lora.ignoreMqtt
        )
    }

    private fun updateDeviceFromProto(dev: MeshProtos.Config.DeviceConfig) {
        _deviceState.value = DeviceUiState(
            role = dev.role.let { if (it == MeshProtos.Config.DeviceConfig.Role.UNRECOGNIZED) "CLIENT" else it.name },
            serialEnabled = dev.serialEnabled,
            debugLogEnabled = dev.debugLogEnabled,
            buttonGpio = dev.buttonGpio,
            buzzerGpio = dev.buzzerGpio,
            rebroadcastMode = dev.rebroadcastMode.let { if (it == MeshProtos.Config.DeviceConfig.RebroadcastMode.UNRECOGNIZED) "ALL" else it.name },
            nodeInfoBroadcastSecs = dev.nodeInfoBroadcastSecs,
            doubleTapAsButtonPress = dev.doubleTapAsButtonPress,
            isManaged = dev.isManaged,
            disableTripleClick = dev.disableTripleClick
        )
    }

    private fun updatePositionFromProto(pos: MeshProtos.Config.PositionConfig) {
        _positionState.value = PositionUiState(
            positionBroadcastSecs = pos.positionBroadcastSecs,
            positionBroadcastSmartEnabled = pos.positionBroadcastSmartEnabled,
            fixedPosition = pos.fixedPosition,
            gpsEnabled = pos.gpsEnabled,
            gpsUpdateInterval = pos.gpsUpdateInterval,
            gpsMode = pos.gpsMode.let { if (it == MeshProtos.Config.PositionConfig.GpsMode.UNRECOGNIZED) "ENABLED" else it.name },
            broadcastSmartMinimumDistance = pos.broadcastSmartMinimumDistance,
            broadcastSmartMinimumIntervalSecs = pos.broadcastSmartMinimumIntervalSecs
        )
    }

    private fun updatePowerFromProto(pwr: MeshProtos.Config.PowerConfig) {
        _powerState.value = PowerUiState(
            isPowerSaving = pwr.isPowerSaving,
            onBatteryShutdownAfterSecs = pwr.onBatteryShutdownAfterSecs,
            adcMultiplierOverride = pwr.adcMultiplierOverride,
            waitBluetoothSecs = pwr.waitBluetoothSecs,
            sdsSecs = pwr.sdsSecs,
            lsSecs = pwr.lsSecs,
            minWakeSecs = pwr.minWakeSecs,
            shutdownOnPowerLoss = pwr.shutdownOnPowerLoss
        )
    }

    private fun updateNetworkFromProto(net: MeshProtos.Config.NetworkConfig) {
        _networkState.value = NetworkUiState(
            wifiEnabled = net.wifiEnabled,
            wifiSsid = net.wifiSsid,
            wifiPsk = net.wifiPsk,
            ethEnabled = net.ethEnabled,
            addressMode = net.addressMode.let { if (it == MeshProtos.Config.NetworkConfig.AddressMode.UNRECOGNIZED) "DHCP" else it.name },
            ntpServer = net.ntpServer,
            rsyslogServer = net.rsyslogServer
        )
    }

    private fun updateDisplayFromProto(dsp: MeshProtos.Config.DisplayConfig) {
        _displayState.value = DisplayUiState(
            screenOnSecs = dsp.screenOnSecs,
            gpsFormat = dsp.gpsFormat.let { if (it == MeshProtos.Config.DisplayConfig.GpsCoordinateFormat.UNRECOGNIZED) "DEC" else it.name },
            autoScreenCarouselSecs = dsp.autoScreenCarouselSecs,
            compassNorthTop = dsp.compassNorthTop,
            flipScreen = dsp.flipScreen,
            units = dsp.units.let { if (it == MeshProtos.Config.DisplayConfig.DisplayUnits.UNRECOGNIZED) "METRIC" else it.name },
            oled = dsp.oled.let { if (it == MeshProtos.Config.DisplayConfig.OledType.UNRECOGNIZED) "OLED_AUTO" else it.name },
            displaymode = dsp.displaymode.let { if (it == MeshProtos.Config.DisplayConfig.DisplayMode.UNRECOGNIZED) "DEFAULT" else it.name },
            headingBold = dsp.headingBold,
            wakeOnTapOrMotion = dsp.wakeOnTapOrMotion
        )
    }

    private fun updateBluetoothFromProto(bt: MeshProtos.Config.BluetoothConfig) {
        _bluetoothState.value = BluetoothUiState(
            enabled = bt.enabled,
            mode = bt.mode.let { if (it == MeshProtos.Config.BluetoothConfig.PairingMode.UNRECOGNIZED) "RANDOM_PIN" else it.name },
            fixedPin = bt.fixedPin
        )
    }

    // ========================  SETTERS  ========================

    // --- Owner ---
    fun setOwnerLongName(name: String) { _ownerState.value = _ownerState.value.copy(longName = name) }
    fun setOwnerShortName(name: String) { _ownerState.value = _ownerState.value.copy(shortName = name.take(4)) }
    fun setOwnerIsLicensed(licensed: Boolean) { _ownerState.value = _ownerState.value.copy(isLicensed = licensed) }

    // --- LoRa ---
    fun setLoraRegion(region: String) { _loraState.value = _loraState.value.copy(region = region) }
    fun setLoraModemPreset(preset: String) { _loraState.value = _loraState.value.copy(modemPreset = preset) }
    fun setLoraUsePreset(v: Boolean) { _loraState.value = _loraState.value.copy(usePreset = v) }
    fun setLoraBandwidth(v: Int) { _loraState.value = _loraState.value.copy(bandwidth = v) }
    fun setLoraSpreadFactor(v: Int) { _loraState.value = _loraState.value.copy(spreadFactor = v) }
    fun setLoraCodingRate(v: Int) { _loraState.value = _loraState.value.copy(codingRate = v) }
    fun setLoraFrequencyOffset(v: Float) { _loraState.value = _loraState.value.copy(frequencyOffset = v) }
    fun setLoraHopLimit(v: Int) { _loraState.value = _loraState.value.copy(hopLimit = v.coerceIn(1, 7)) }
    fun setLoraTxEnabled(v: Boolean) { _loraState.value = _loraState.value.copy(txEnabled = v) }
    fun setLoraTxPower(v: Int) { _loraState.value = _loraState.value.copy(txPower = v) }
    fun setLoraChannelNum(v: Int) { _loraState.value = _loraState.value.copy(channelNum = v) }
    fun setLoraOverrideDutyCycle(v: Boolean) { _loraState.value = _loraState.value.copy(overrideDutyCycle = v) }
    fun setLoraSx126xRxBoostedGain(v: Boolean) { _loraState.value = _loraState.value.copy(sx126xRxBoostedGain = v) }
    fun setLoraOverrideFrequency(v: Float) { _loraState.value = _loraState.value.copy(overrideFrequency = v) }
    fun setLoraIgnoreMqtt(v: Boolean) { _loraState.value = _loraState.value.copy(ignoreMqtt = v) }

    // --- Device ---
    fun setDeviceRole(role: String) { _deviceState.value = _deviceState.value.copy(role = role) }
    fun setDeviceSerialEnabled(v: Boolean) { _deviceState.value = _deviceState.value.copy(serialEnabled = v) }
    fun setDeviceDebugLogEnabled(v: Boolean) { _deviceState.value = _deviceState.value.copy(debugLogEnabled = v) }
    fun setDeviceButtonGpio(v: Int) { _deviceState.value = _deviceState.value.copy(buttonGpio = v) }
    fun setDeviceBuzzerGpio(v: Int) { _deviceState.value = _deviceState.value.copy(buzzerGpio = v) }
    fun setDeviceRebroadcastMode(mode: String) { _deviceState.value = _deviceState.value.copy(rebroadcastMode = mode) }
    fun setDeviceNodeInfoBroadcastSecs(v: Int) { _deviceState.value = _deviceState.value.copy(nodeInfoBroadcastSecs = v) }
    fun setDeviceDoubleTapAsButtonPress(v: Boolean) { _deviceState.value = _deviceState.value.copy(doubleTapAsButtonPress = v) }
    fun setDeviceIsManaged(v: Boolean) { _deviceState.value = _deviceState.value.copy(isManaged = v) }
    fun setDeviceDisableTripleClick(v: Boolean) { _deviceState.value = _deviceState.value.copy(disableTripleClick = v) }

    // --- Position ---
    fun setPositionBroadcastSecs(v: Int) { _positionState.value = _positionState.value.copy(positionBroadcastSecs = v) }
    fun setPositionSmartEnabled(v: Boolean) { _positionState.value = _positionState.value.copy(positionBroadcastSmartEnabled = v) }
    fun setPositionFixed(v: Boolean) { _positionState.value = _positionState.value.copy(fixedPosition = v) }
    fun setPositionGpsEnabled(v: Boolean) { _positionState.value = _positionState.value.copy(gpsEnabled = v) }
    fun setPositionGpsUpdateInterval(v: Int) { _positionState.value = _positionState.value.copy(gpsUpdateInterval = v) }
    fun setPositionGpsMode(mode: String) { _positionState.value = _positionState.value.copy(gpsMode = mode) }
    fun setPositionSmartMinDistance(v: Int) { _positionState.value = _positionState.value.copy(broadcastSmartMinimumDistance = v) }
    fun setPositionSmartMinInterval(v: Int) { _positionState.value = _positionState.value.copy(broadcastSmartMinimumIntervalSecs = v) }

    // --- Power ---
    fun setPowerSaving(v: Boolean) { _powerState.value = _powerState.value.copy(isPowerSaving = v) }
    fun setPowerShutdownAfterSecs(v: Int) { _powerState.value = _powerState.value.copy(onBatteryShutdownAfterSecs = v) }
    fun setPowerAdcMultiplier(v: Float) { _powerState.value = _powerState.value.copy(adcMultiplierOverride = v) }
    fun setPowerWaitBluetoothSecs(v: Int) { _powerState.value = _powerState.value.copy(waitBluetoothSecs = v) }
    fun setPowerSdsSecs(v: Int) { _powerState.value = _powerState.value.copy(sdsSecs = v) }
    fun setPowerLsSecs(v: Int) { _powerState.value = _powerState.value.copy(lsSecs = v) }
    fun setPowerMinWakeSecs(v: Int) { _powerState.value = _powerState.value.copy(minWakeSecs = v) }
    fun setPowerShutdownOnPowerLoss(v: Boolean) { _powerState.value = _powerState.value.copy(shutdownOnPowerLoss = v) }

    // --- Network ---
    fun setNetworkWifiEnabled(v: Boolean) { _networkState.value = _networkState.value.copy(wifiEnabled = v) }
    fun setNetworkWifiSsid(v: String) { _networkState.value = _networkState.value.copy(wifiSsid = v) }
    fun setNetworkWifiPsk(v: String) { _networkState.value = _networkState.value.copy(wifiPsk = v) }
    fun setNetworkEthEnabled(v: Boolean) { _networkState.value = _networkState.value.copy(ethEnabled = v) }
    fun setNetworkAddressMode(mode: String) { _networkState.value = _networkState.value.copy(addressMode = mode) }
    fun setNetworkNtpServer(v: String) { _networkState.value = _networkState.value.copy(ntpServer = v) }
    fun setNetworkRsyslogServer(v: String) { _networkState.value = _networkState.value.copy(rsyslogServer = v) }

    // --- Display ---
    fun setDisplayScreenOnSecs(v: Int) { _displayState.value = _displayState.value.copy(screenOnSecs = v) }
    fun setDisplayGpsFormat(v: String) { _displayState.value = _displayState.value.copy(gpsFormat = v) }
    fun setDisplayAutoCarouselSecs(v: Int) { _displayState.value = _displayState.value.copy(autoScreenCarouselSecs = v) }
    fun setDisplayCompassNorthTop(v: Boolean) { _displayState.value = _displayState.value.copy(compassNorthTop = v) }
    fun setDisplayFlipScreen(v: Boolean) { _displayState.value = _displayState.value.copy(flipScreen = v) }
    fun setDisplayUnits(v: String) { _displayState.value = _displayState.value.copy(units = v) }
    fun setDisplayOled(v: String) { _displayState.value = _displayState.value.copy(oled = v) }
    fun setDisplayMode(v: String) { _displayState.value = _displayState.value.copy(displaymode = v) }
    fun setDisplayHeadingBold(v: Boolean) { _displayState.value = _displayState.value.copy(headingBold = v) }
    fun setDisplayWakeOnTapOrMotion(v: Boolean) { _displayState.value = _displayState.value.copy(wakeOnTapOrMotion = v) }

    // --- Bluetooth ---
    fun setBluetoothEnabled(v: Boolean) { _bluetoothState.value = _bluetoothState.value.copy(enabled = v) }
    fun setBluetoothMode(mode: String) { _bluetoothState.value = _bluetoothState.value.copy(mode = mode) }
    fun setBluetoothFixedPin(v: Int) { _bluetoothState.value = _bluetoothState.value.copy(fixedPin = v) }

    // --- Channel ---
    fun setChannelName(index: Int, name: String) {
        _channelsState.value = _channelsState.value.map {
            if (it.index == index) it.copy(name = name) else it
        }
    }
    fun setChannelPskHex(index: Int, hex: String) {
        _channelsState.value = _channelsState.value.map {
            if (it.index == index) it.copy(pskHex = hex) else it
        }
    }
    fun setChannelUplink(index: Int, v: Boolean) {
        _channelsState.value = _channelsState.value.map {
            if (it.index == index) it.copy(uplinkEnabled = v) else it
        }
    }
    fun setChannelDownlink(index: Int, v: Boolean) {
        _channelsState.value = _channelsState.value.map {
            if (it.index == index) it.copy(downlinkEnabled = v) else it
        }
    }

    // --- Voice ---
    fun setVoiceBitrate(bitrate: AmrNbBitrate) {
        voiceConfig.value = voiceConfig.value.copy(bitrate = bitrate)
    }
    fun setMaxDuration(seconds: Int) {
        voiceConfig.value = voiceConfig.value.copy(maxDurationSeconds = seconds.coerceIn(1, 60))
    }
    fun setChunkTimeout(seconds: Int) {
        voiceConfig.value = voiceConfig.value.copy(chunkTimeoutSeconds = seconds.coerceIn(5, 120))
    }
    fun setPartialPlayOnTimeout(enabled: Boolean) {
        voiceConfig.value = voiceConfig.value.copy(partialPlayOnTimeout = enabled)
    }

    // ========================  APPLY METHODS  ========================

    fun applyOwner() {
        if (!meshService.isConnected) { _configStatus.value = "Not connected"; return }
        val s = _ownerState.value
        val user = MeshProtos.User.newBuilder()
            .setLongName(s.longName)
            .setShortName(s.shortName)
            .setIsLicensed(s.isLicensed)
            .build()
        val ok = meshService.writeOwner(user)
        _configStatus.value = if (ok) "Owner config sent" else "Failed to send owner config"
    }

    fun applyLoraConfig() {
        if (!meshService.isConnected) { _configStatus.value = "Not connected"; return }
        val s = _loraState.value
        try {
            val regionEnum = try {
                MeshProtos.Config.LoRaConfig.RegionCode.valueOf(s.region)
            } catch (_: IllegalArgumentException) {
                MeshProtos.Config.LoRaConfig.RegionCode.UNSET
            }
            val presetEnum = try {
                MeshProtos.Config.LoRaConfig.ModemPreset.valueOf(s.modemPreset)
            } catch (_: IllegalArgumentException) {
                MeshProtos.Config.LoRaConfig.ModemPreset.LONG_FAST
            }
            val lora = MeshProtos.Config.LoRaConfig.newBuilder()
                .setUsePreset(s.usePreset)
                .setModemPreset(presetEnum)
                .setBandwidth(s.bandwidth)
                .setSpreadFactor(s.spreadFactor)
                .setCodingRate(s.codingRate)
                .setFrequencyOffset(s.frequencyOffset)
                .setRegion(regionEnum)
                .setHopLimit(s.hopLimit)
                .setTxEnabled(s.txEnabled)
                .setTxPower(s.txPower)
                .setChannelNum(s.channelNum)
                .setOverrideDutyCycle(s.overrideDutyCycle)
                .setSx126XRxBoostedGain(s.sx126xRxBoostedGain)
                .setOverrideFrequency(s.overrideFrequency)
                .setIgnoreMqtt(s.ignoreMqtt)
                .build()
            val config = MeshProtos.Config.newBuilder().setLora(lora).build()
            val ok = meshService.writeConfig(config)
            _configStatus.value = if (ok) "LoRa config sent" else "Failed to send LoRa config"
        } catch (e: Exception) {
            _configStatus.value = "Error: ${e.message}"
        }
    }

    fun applyDeviceConfig() {
        if (!meshService.isConnected) { _configStatus.value = "Not connected"; return }
        val s = _deviceState.value
        val dev = MeshProtos.Config.DeviceConfig.newBuilder()
            .setRole(MeshProtos.Config.DeviceConfig.Role.valueOf(s.role))
            .setSerialEnabled(s.serialEnabled)
            .setDebugLogEnabled(s.debugLogEnabled)
            .setButtonGpio(s.buttonGpio)
            .setBuzzerGpio(s.buzzerGpio)
            .setRebroadcastMode(MeshProtos.Config.DeviceConfig.RebroadcastMode.valueOf(s.rebroadcastMode))
            .setNodeInfoBroadcastSecs(s.nodeInfoBroadcastSecs)
            .setDoubleTapAsButtonPress(s.doubleTapAsButtonPress)
            .setIsManaged(s.isManaged)
            .setDisableTripleClick(s.disableTripleClick)
            .build()
        val config = MeshProtos.Config.newBuilder().setDevice(dev).build()
        val ok = meshService.writeConfig(config)
        _configStatus.value = if (ok) "Device config sent" else "Failed to send device config"
    }

    fun applyPositionConfig() {
        if (!meshService.isConnected) { _configStatus.value = "Not connected"; return }
        val s = _positionState.value
        val pos = MeshProtos.Config.PositionConfig.newBuilder()
            .setPositionBroadcastSecs(s.positionBroadcastSecs)
            .setPositionBroadcastSmartEnabled(s.positionBroadcastSmartEnabled)
            .setFixedPosition(s.fixedPosition)
            .setGpsEnabled(s.gpsEnabled)
            .setGpsUpdateInterval(s.gpsUpdateInterval)
            .setGpsMode(MeshProtos.Config.PositionConfig.GpsMode.valueOf(s.gpsMode))
            .setBroadcastSmartMinimumDistance(s.broadcastSmartMinimumDistance)
            .setBroadcastSmartMinimumIntervalSecs(s.broadcastSmartMinimumIntervalSecs)
            .build()
        val config = MeshProtos.Config.newBuilder().setPosition(pos).build()
        val ok = meshService.writeConfig(config)
        _configStatus.value = if (ok) "Position config sent" else "Failed to send position config"
    }

    fun applyPowerConfig() {
        if (!meshService.isConnected) { _configStatus.value = "Not connected"; return }
        val s = _powerState.value
        val pwr = MeshProtos.Config.PowerConfig.newBuilder()
            .setIsPowerSaving(s.isPowerSaving)
            .setOnBatteryShutdownAfterSecs(s.onBatteryShutdownAfterSecs)
            .setAdcMultiplierOverride(s.adcMultiplierOverride)
            .setWaitBluetoothSecs(s.waitBluetoothSecs)
            .setSdsSecs(s.sdsSecs)
            .setLsSecs(s.lsSecs)
            .setMinWakeSecs(s.minWakeSecs)
            .setShutdownOnPowerLoss(s.shutdownOnPowerLoss)
            .build()
        val config = MeshProtos.Config.newBuilder().setPower(pwr).build()
        val ok = meshService.writeConfig(config)
        _configStatus.value = if (ok) "Power config sent" else "Failed to send power config"
    }

    fun applyNetworkConfig() {
        if (!meshService.isConnected) { _configStatus.value = "Not connected"; return }
        val s = _networkState.value
        val net = MeshProtos.Config.NetworkConfig.newBuilder()
            .setWifiEnabled(s.wifiEnabled)
            .setWifiSsid(s.wifiSsid)
            .setWifiPsk(s.wifiPsk)
            .setEthEnabled(s.ethEnabled)
            .setAddressMode(MeshProtos.Config.NetworkConfig.AddressMode.valueOf(s.addressMode))
            .setNtpServer(s.ntpServer)
            .setRsyslogServer(s.rsyslogServer)
            .build()
        val config = MeshProtos.Config.newBuilder().setNetwork(net).build()
        val ok = meshService.writeConfig(config)
        _configStatus.value = if (ok) "Network config sent" else "Failed to send network config"
    }

    fun applyDisplayConfig() {
        if (!meshService.isConnected) { _configStatus.value = "Not connected"; return }
        val s = _displayState.value
        val dsp = MeshProtos.Config.DisplayConfig.newBuilder()
            .setScreenOnSecs(s.screenOnSecs)
            .setGpsFormat(MeshProtos.Config.DisplayConfig.GpsCoordinateFormat.valueOf(s.gpsFormat))
            .setAutoScreenCarouselSecs(s.autoScreenCarouselSecs)
            .setCompassNorthTop(s.compassNorthTop)
            .setFlipScreen(s.flipScreen)
            .setUnits(MeshProtos.Config.DisplayConfig.DisplayUnits.valueOf(s.units))
            .setOled(MeshProtos.Config.DisplayConfig.OledType.valueOf(s.oled))
            .setDisplaymode(MeshProtos.Config.DisplayConfig.DisplayMode.valueOf(s.displaymode))
            .setHeadingBold(s.headingBold)
            .setWakeOnTapOrMotion(s.wakeOnTapOrMotion)
            .build()
        val config = MeshProtos.Config.newBuilder().setDisplay(dsp).build()
        val ok = meshService.writeConfig(config)
        _configStatus.value = if (ok) "Display config sent" else "Failed to send display config"
    }

    fun applyBluetoothConfig() {
        if (!meshService.isConnected) { _configStatus.value = "Not connected"; return }
        val s = _bluetoothState.value
        val bt = MeshProtos.Config.BluetoothConfig.newBuilder()
            .setEnabled(s.enabled)
            .setMode(MeshProtos.Config.BluetoothConfig.PairingMode.valueOf(s.mode))
            .setFixedPin(s.fixedPin)
            .build()
        val config = MeshProtos.Config.newBuilder().setBluetooth(bt).build()
        val ok = meshService.writeConfig(config)
        _configStatus.value = if (ok) "Bluetooth config sent" else "Failed to send bluetooth config"
    }

    fun applyChannel(index: Int) {
        if (!meshService.isConnected) { _configStatus.value = "Not connected"; return }
        val chUi = _channelsState.value.find { it.index == index } ?: return
        val settings = MeshProtos.ChannelSettings.newBuilder()
            .setName(chUi.name)
            .setPsk(com.google.protobuf.ByteString.copyFrom(chUi.pskHex.hexToBytes()))
            .setUplinkEnabled(chUi.uplinkEnabled)
            .setDownlinkEnabled(chUi.downlinkEnabled)
            .build()
        val channel = MeshProtos.Channel.newBuilder()
            .setIndex(index)
            .setSettings(settings)
            .setRole(MeshProtos.Channel.Role.valueOf(chUi.role))
            .build()
        val ok = meshService.writeChannel(channel)
        _configStatus.value = if (ok) "Channel $index config sent" else "Failed to send channel config"
    }

    // ========================  ACTIONS  ========================

    fun refreshDeviceConfig() {
        if (!meshService.isConnected) { _configStatus.value = "Not connected"; return }
        meshService.refreshConfig()
        _configStatus.value = "Config refresh requested"
        // Sync after allowing time for device to respond
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            syncFromServiceFlows()
            _configStatus.value = "Config refreshed"
        }
    }

    fun rebootDevice() {
        if (!meshService.isConnected) { _configStatus.value = "Not connected"; return }
        meshService.rebootDevice(5)
        _configStatus.value = "Reboot command sent (5s)"
    }

    fun factoryReset() {
        if (!meshService.isConnected) { _configStatus.value = "Not connected"; return }
        meshService.factoryReset()
        _configStatus.value = "Factory reset command sent"
    }

    fun clearStatus() { _configStatus.value = null }

    // ========================  UTILITIES  ========================

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        val clean = this.replace(" ", "").replace("0x", "")
        if (clean.isEmpty()) return byteArrayOf()
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
