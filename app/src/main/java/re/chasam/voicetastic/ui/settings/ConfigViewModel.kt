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

    val regions: List<String> = enumNames(MeshProtos.Config.LoRaConfig.RegionCode.values())
    val modemPresets: List<String> = enumNames(MeshProtos.Config.LoRaConfig.ModemPreset.values())

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

    val deviceRoles: List<String> = enumNames(MeshProtos.Config.DeviceConfig.Role.values())

    val rebroadcastModes: List<String> = enumNames(MeshProtos.Config.DeviceConfig.RebroadcastMode.values())

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

    val gpsModes: List<String> = enumNames(MeshProtos.Config.PositionConfig.GpsMode.values())

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

    val addressModes: List<String> = enumNames(MeshProtos.Config.NetworkConfig.AddressMode.values())

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

    val gpsFormats: List<String> = enumNames(MeshProtos.Config.DisplayConfig.GpsCoordinateFormat.values())
    val displayUnits: List<String> = enumNames(MeshProtos.Config.DisplayConfig.DisplayUnits.values())
    val oledTypes: List<String> = enumNames(MeshProtos.Config.DisplayConfig.OledType.values())
    val displayModes: List<String> = enumNames(MeshProtos.Config.DisplayConfig.DisplayMode.values())

    // ========================  BLUETOOTH  ========================

    data class BluetoothUiState(
        val enabled: Boolean = true,
        val mode: String = "RANDOM_PIN",
        val fixedPin: Int = 0
    )

    private val _bluetoothState = MutableStateFlow(BluetoothUiState())
    val bluetoothState: StateFlow<BluetoothUiState> = _bluetoothState.asStateFlow()

    val pairingModes: List<String> = enumNames(MeshProtos.Config.BluetoothConfig.PairingMode.values())

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

    val channelRoles: List<String> = enumNames(MeshProtos.Channel.Role.values())

    // ========================  VOICE CONFIG  ========================

    val currentVoiceConfig: StateFlow<VoiceConfig> = voiceConfig.asStateFlow()

    // ========================  DIRTY TRACKING  ========================
    // When the user edits a section, we won't overwrite it with subsequent
    // proto pushes from the device until they Apply (or until refreshDeviceConfig()
    // explicitly resets the flag).
    private val dirty: MutableMap<String, Boolean> = mutableMapOf()
    private fun markDirty(section: String) { dirty[section] = true }
    private fun isDirty(section: String) = dirty[section] == true
    private fun clearDirty() { dirty.clear() }

    // ========================  INIT — FLOW COLLECTION  ========================

    init {
        // Re-sync UI when the firmware signals "config burst complete".
        viewModelScope.launch {
            meshService.configComplete.collect {
                clearDirty()
                syncFromServiceFlows()
                _configStatus.value = "Config received"
            }
        }

        // Also do an initial sync once connected, in case configComplete is missed.
        viewModelScope.launch {
            meshService.connectionState.collect { state ->
                if (state == "CONNECTED") {
                    kotlinx.coroutines.delay(3000)
                    syncFromServiceFlows()
                }
            }
        }

        // Per-section flow observers — only apply if the user hasn't dirtied that section.
        viewModelScope.launch {
            meshService.radioConfig.collect { lora -> if (lora != null && !isDirty("lora")) updateLoraFromProto(lora) }
        }
        viewModelScope.launch {
            meshService.deviceConfig.collect { dev -> if (dev != null && !isDirty("device")) updateDeviceFromProto(dev) }
        }
        viewModelScope.launch {
            meshService.positionConfig.collect { pos -> if (pos != null && !isDirty("position")) updatePositionFromProto(pos) }
        }
        viewModelScope.launch {
            meshService.powerConfig.collect { pwr -> if (pwr != null && !isDirty("power")) updatePowerFromProto(pwr) }
        }
        viewModelScope.launch {
            meshService.networkConfig.collect { net -> if (net != null && !isDirty("network")) updateNetworkFromProto(net) }
        }
        viewModelScope.launch {
            meshService.displayConfig.collect { dsp -> if (dsp != null && !isDirty("display")) updateDisplayFromProto(dsp) }
        }
        viewModelScope.launch {
            meshService.bluetoothConfig.collect { bt -> if (bt != null && !isDirty("bluetooth")) updateBluetoothFromProto(bt) }
        }
        viewModelScope.launch {
            meshService.owner.collect { user ->
                if (user != null && !isDirty("owner")) {
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
                if (chList.isNotEmpty() && !isDirty("channels")) {
                    _channelsState.value = chList.map { mapChannel(it) }
                }
            }
        }
    }

    private fun mapChannel(ch: MeshProtos.Channel): ChannelUiState = ChannelUiState(
        index = ch.index,
        role = enumDisplay(ch.role, "PRIMARY"),
        name = if (ch.hasSettings()) ch.settings.name else "",
        pskHex = if (ch.hasSettings()) ch.settings.psk.toByteArray().toHex() else "",
        uplinkEnabled = if (ch.hasSettings()) ch.settings.uplinkEnabled else false,
        downlinkEnabled = if (ch.hasSettings()) ch.settings.downlinkEnabled else false
    )

    /**
     * Manually sync all UI state from the current values in the service StateFlows.
     * This ensures fields are populated even if the flow emission was missed.
     * Respects dirty flags — won't overwrite user edits.
     */
    private fun syncFromServiceFlows() {
        meshService.radioConfig.value?.let { if (!isDirty("lora")) updateLoraFromProto(it) }
        meshService.deviceConfig.value?.let { if (!isDirty("device")) updateDeviceFromProto(it) }
        meshService.positionConfig.value?.let { if (!isDirty("position")) updatePositionFromProto(it) }
        meshService.powerConfig.value?.let { if (!isDirty("power")) updatePowerFromProto(it) }
        meshService.networkConfig.value?.let { if (!isDirty("network")) updateNetworkFromProto(it) }
        meshService.displayConfig.value?.let { if (!isDirty("display")) updateDisplayFromProto(it) }
        meshService.bluetoothConfig.value?.let { if (!isDirty("bluetooth")) updateBluetoothFromProto(it) }
        meshService.owner.value?.let { user ->
            if (!isDirty("owner")) {
                _ownerState.value = OwnerUiState(
                    longName = user.longName,
                    shortName = user.shortName,
                    isLicensed = user.isLicensed
                )
            }
        }
        val chList = meshService.channels.value
        if (chList.isNotEmpty() && !isDirty("channels")) {
            _channelsState.value = chList.map { mapChannel(it) }
        }
    }

    private fun updateLoraFromProto(lora: MeshProtos.Config.LoRaConfig) {
        _loraState.value = LoraUiState(
            region = enumDisplay(lora.region, "UNSET"),
            modemPreset = enumDisplay(lora.modemPreset, "LONG_FAST"),
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
            role = enumDisplay(dev.role, "CLIENT"),
            serialEnabled = dev.serialEnabled,
            debugLogEnabled = dev.debugLogEnabled,
            buttonGpio = dev.buttonGpio,
            buzzerGpio = dev.buzzerGpio,
            rebroadcastMode = enumDisplay(dev.rebroadcastMode, "ALL"),
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
            gpsMode = enumDisplay(pos.gpsMode, "ENABLED"),
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
            addressMode = enumDisplay(net.addressMode, "DHCP"),
            ntpServer = net.ntpServer,
            rsyslogServer = net.rsyslogServer
        )
    }

    private fun updateDisplayFromProto(dsp: MeshProtos.Config.DisplayConfig) {
        _displayState.value = DisplayUiState(
            screenOnSecs = dsp.screenOnSecs,
            gpsFormat = enumDisplay(dsp.gpsFormat, "DEC"),
            autoScreenCarouselSecs = dsp.autoScreenCarouselSecs,
            compassNorthTop = dsp.compassNorthTop,
            flipScreen = dsp.flipScreen,
            units = enumDisplay(dsp.units, "METRIC"),
            oled = enumDisplay(dsp.oled, "OLED_AUTO"),
            displaymode = enumDisplay(dsp.displaymode, "DEFAULT"),
            headingBold = dsp.headingBold,
            wakeOnTapOrMotion = dsp.wakeOnTapOrMotion
        )
    }

    private fun updateBluetoothFromProto(bt: MeshProtos.Config.BluetoothConfig) {
        _bluetoothState.value = BluetoothUiState(
            enabled = bt.enabled,
            mode = enumDisplay(bt.mode, "RANDOM_PIN"),
            fixedPin = bt.fixedPin
        )
    }

    // ========================  SETTERS  ========================
    // Each setter marks its section dirty so subsequent device pushes don't
    // overwrite the user's in-progress edits.

    // --- Owner ---
    fun setOwnerLongName(name: String) { markDirty("owner"); _ownerState.value = _ownerState.value.copy(longName = name) }
    fun setOwnerShortName(name: String) { markDirty("owner"); _ownerState.value = _ownerState.value.copy(shortName = name.take(4)) }
    fun setOwnerIsLicensed(licensed: Boolean) { markDirty("owner"); _ownerState.value = _ownerState.value.copy(isLicensed = licensed) }

    // --- LoRa ---
    fun setLoraRegion(region: String) { markDirty("lora"); _loraState.value = _loraState.value.copy(region = region) }
    fun setLoraModemPreset(preset: String) { markDirty("lora"); _loraState.value = _loraState.value.copy(modemPreset = preset) }
    fun setLoraUsePreset(v: Boolean) { markDirty("lora"); _loraState.value = _loraState.value.copy(usePreset = v) }
    fun setLoraBandwidth(v: Int) { markDirty("lora"); _loraState.value = _loraState.value.copy(bandwidth = v) }
    fun setLoraSpreadFactor(v: Int) { markDirty("lora"); _loraState.value = _loraState.value.copy(spreadFactor = v) }
    fun setLoraCodingRate(v: Int) { markDirty("lora"); _loraState.value = _loraState.value.copy(codingRate = v) }
    fun setLoraFrequencyOffset(v: Float) { markDirty("lora"); _loraState.value = _loraState.value.copy(frequencyOffset = v) }
    fun setLoraHopLimit(v: Int) { markDirty("lora"); _loraState.value = _loraState.value.copy(hopLimit = v.coerceIn(1, 7)) }
    fun setLoraTxEnabled(v: Boolean) { markDirty("lora"); _loraState.value = _loraState.value.copy(txEnabled = v) }
    fun setLoraTxPower(v: Int) { markDirty("lora"); _loraState.value = _loraState.value.copy(txPower = v) }
    fun setLoraChannelNum(v: Int) { markDirty("lora"); _loraState.value = _loraState.value.copy(channelNum = v) }
    fun setLoraOverrideDutyCycle(v: Boolean) { markDirty("lora"); _loraState.value = _loraState.value.copy(overrideDutyCycle = v) }
    fun setLoraSx126xRxBoostedGain(v: Boolean) { markDirty("lora"); _loraState.value = _loraState.value.copy(sx126xRxBoostedGain = v) }
    fun setLoraOverrideFrequency(v: Float) { markDirty("lora"); _loraState.value = _loraState.value.copy(overrideFrequency = v) }
    fun setLoraIgnoreMqtt(v: Boolean) { markDirty("lora"); _loraState.value = _loraState.value.copy(ignoreMqtt = v) }

    // --- Device ---
    fun setDeviceRole(role: String) { markDirty("device"); _deviceState.value = _deviceState.value.copy(role = role) }
    fun setDeviceSerialEnabled(v: Boolean) { markDirty("device"); _deviceState.value = _deviceState.value.copy(serialEnabled = v) }
    fun setDeviceDebugLogEnabled(v: Boolean) { markDirty("device"); _deviceState.value = _deviceState.value.copy(debugLogEnabled = v) }
    fun setDeviceButtonGpio(v: Int) { markDirty("device"); _deviceState.value = _deviceState.value.copy(buttonGpio = v) }
    fun setDeviceBuzzerGpio(v: Int) { markDirty("device"); _deviceState.value = _deviceState.value.copy(buzzerGpio = v) }
    fun setDeviceRebroadcastMode(mode: String) { markDirty("device"); _deviceState.value = _deviceState.value.copy(rebroadcastMode = mode) }
    fun setDeviceNodeInfoBroadcastSecs(v: Int) { markDirty("device"); _deviceState.value = _deviceState.value.copy(nodeInfoBroadcastSecs = v) }
    fun setDeviceDoubleTapAsButtonPress(v: Boolean) { markDirty("device"); _deviceState.value = _deviceState.value.copy(doubleTapAsButtonPress = v) }
    fun setDeviceIsManaged(v: Boolean) { markDirty("device"); _deviceState.value = _deviceState.value.copy(isManaged = v) }
    fun setDeviceDisableTripleClick(v: Boolean) { markDirty("device"); _deviceState.value = _deviceState.value.copy(disableTripleClick = v) }

    // --- Position ---
    fun setPositionBroadcastSecs(v: Int) { markDirty("position"); _positionState.value = _positionState.value.copy(positionBroadcastSecs = v) }
    fun setPositionSmartEnabled(v: Boolean) { markDirty("position"); _positionState.value = _positionState.value.copy(positionBroadcastSmartEnabled = v) }
    fun setPositionFixed(v: Boolean) { markDirty("position"); _positionState.value = _positionState.value.copy(fixedPosition = v) }
    fun setPositionGpsEnabled(v: Boolean) { markDirty("position"); _positionState.value = _positionState.value.copy(gpsEnabled = v) }
    fun setPositionGpsUpdateInterval(v: Int) { markDirty("position"); _positionState.value = _positionState.value.copy(gpsUpdateInterval = v) }
    fun setPositionGpsMode(mode: String) { markDirty("position"); _positionState.value = _positionState.value.copy(gpsMode = mode) }
    fun setPositionSmartMinDistance(v: Int) { markDirty("position"); _positionState.value = _positionState.value.copy(broadcastSmartMinimumDistance = v) }
    fun setPositionSmartMinInterval(v: Int) { markDirty("position"); _positionState.value = _positionState.value.copy(broadcastSmartMinimumIntervalSecs = v) }

    // --- Power ---
    fun setPowerSaving(v: Boolean) { markDirty("power"); _powerState.value = _powerState.value.copy(isPowerSaving = v) }
    fun setPowerShutdownAfterSecs(v: Int) { markDirty("power"); _powerState.value = _powerState.value.copy(onBatteryShutdownAfterSecs = v) }
    fun setPowerAdcMultiplier(v: Float) { markDirty("power"); _powerState.value = _powerState.value.copy(adcMultiplierOverride = v) }
    fun setPowerWaitBluetoothSecs(v: Int) { markDirty("power"); _powerState.value = _powerState.value.copy(waitBluetoothSecs = v) }
    fun setPowerSdsSecs(v: Int) { markDirty("power"); _powerState.value = _powerState.value.copy(sdsSecs = v) }
    fun setPowerLsSecs(v: Int) { markDirty("power"); _powerState.value = _powerState.value.copy(lsSecs = v) }
    fun setPowerMinWakeSecs(v: Int) { markDirty("power"); _powerState.value = _powerState.value.copy(minWakeSecs = v) }
    fun setPowerShutdownOnPowerLoss(v: Boolean) { markDirty("power"); _powerState.value = _powerState.value.copy(shutdownOnPowerLoss = v) }

    // --- Network ---
    fun setNetworkWifiEnabled(v: Boolean) { markDirty("network"); _networkState.value = _networkState.value.copy(wifiEnabled = v) }
    fun setNetworkWifiSsid(v: String) { markDirty("network"); _networkState.value = _networkState.value.copy(wifiSsid = v) }
    fun setNetworkWifiPsk(v: String) { markDirty("network"); _networkState.value = _networkState.value.copy(wifiPsk = v) }
    fun setNetworkEthEnabled(v: Boolean) { markDirty("network"); _networkState.value = _networkState.value.copy(ethEnabled = v) }
    fun setNetworkAddressMode(mode: String) { markDirty("network"); _networkState.value = _networkState.value.copy(addressMode = mode) }
    fun setNetworkNtpServer(v: String) { markDirty("network"); _networkState.value = _networkState.value.copy(ntpServer = v) }
    fun setNetworkRsyslogServer(v: String) { markDirty("network"); _networkState.value = _networkState.value.copy(rsyslogServer = v) }

    // --- Display ---
    fun setDisplayScreenOnSecs(v: Int) { markDirty("display"); _displayState.value = _displayState.value.copy(screenOnSecs = v) }
    fun setDisplayGpsFormat(v: String) { markDirty("display"); _displayState.value = _displayState.value.copy(gpsFormat = v) }
    fun setDisplayAutoCarouselSecs(v: Int) { markDirty("display"); _displayState.value = _displayState.value.copy(autoScreenCarouselSecs = v) }
    fun setDisplayCompassNorthTop(v: Boolean) { markDirty("display"); _displayState.value = _displayState.value.copy(compassNorthTop = v) }
    fun setDisplayFlipScreen(v: Boolean) { markDirty("display"); _displayState.value = _displayState.value.copy(flipScreen = v) }
    fun setDisplayUnits(v: String) { markDirty("display"); _displayState.value = _displayState.value.copy(units = v) }
    fun setDisplayOled(v: String) { markDirty("display"); _displayState.value = _displayState.value.copy(oled = v) }
    fun setDisplayMode(v: String) { markDirty("display"); _displayState.value = _displayState.value.copy(displaymode = v) }
    fun setDisplayHeadingBold(v: Boolean) { markDirty("display"); _displayState.value = _displayState.value.copy(headingBold = v) }
    fun setDisplayWakeOnTapOrMotion(v: Boolean) { markDirty("display"); _displayState.value = _displayState.value.copy(wakeOnTapOrMotion = v) }

    // --- Bluetooth ---
    fun setBluetoothEnabled(v: Boolean) { markDirty("bluetooth"); _bluetoothState.value = _bluetoothState.value.copy(enabled = v) }
    fun setBluetoothMode(mode: String) { markDirty("bluetooth"); _bluetoothState.value = _bluetoothState.value.copy(mode = mode) }
    fun setBluetoothFixedPin(v: Int) { markDirty("bluetooth"); _bluetoothState.value = _bluetoothState.value.copy(fixedPin = v) }

    // --- Channel ---
    fun setChannelName(index: Int, name: String) {
        markDirty("channels")
        _channelsState.value = _channelsState.value.map {
            if (it.index == index) it.copy(name = name) else it
        }
    }
    fun setChannelPskHex(index: Int, hex: String) {
        markDirty("channels")
        _channelsState.value = _channelsState.value.map {
            if (it.index == index) it.copy(pskHex = hex) else it
        }
    }
    fun setChannelUplink(index: Int, v: Boolean) {
        markDirty("channels")
        _channelsState.value = _channelsState.value.map {
            if (it.index == index) it.copy(uplinkEnabled = v) else it
        }
    }
    fun setChannelDownlink(index: Int, v: Boolean) {
        markDirty("channels")
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
        if (meshService.owner.value == null) {
            _configStatus.value = "Owner not yet loaded — refresh first"
            return
        }
        val s = _ownerState.value
        if (s.longName.isBlank() || s.shortName.isBlank()) {
            _configStatus.value = "Long/short name cannot be empty"
            return
        }
        val user = MeshProtos.User.newBuilder()
            .setLongName(s.longName)
            .setShortName(s.shortName)
            .setIsLicensed(s.isLicensed)
            .build()
        val ok = meshService.writeOwner(user)
        if (ok) dirty.remove("owner")
        _configStatus.value = if (ok) "Owner config sent" else "Failed to send owner config"
    }

    fun applyLoraConfig() {
        if (!meshService.isConnected) { _configStatus.value = "Not connected"; return }
        if (meshService.radioConfig.value == null) {
            _configStatus.value = "LoRa config not yet loaded from device — refresh first"
            return
        }
        val s = _loraState.value
        // Refuse obviously dangerous combinations that can crash the radio task
        // on the firmware (and bootloop the device).
        if (s.region.startsWith("UNSET") || s.region.contains("unknown")) {
            _configStatus.value = "Refusing to write: pick a real region first"
            return
        }
        if (!s.usePreset && (s.bandwidth == 0 || s.spreadFactor == 0 || s.codingRate == 0)) {
            _configStatus.value = "Refusing to write: bandwidth / SF / CR cannot be zero when not using a preset"
            return
        }
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
            if (ok) dirty.remove("lora")
            _configStatus.value = if (ok) "LoRa config sent" else "Failed to send LoRa config"
        } catch (e: Exception) {
            _configStatus.value = "Error: ${e.message}"
        }
    }

    fun applyDeviceConfig() {
        if (!meshService.isConnected) { _configStatus.value = "Not connected"; return }
        if (meshService.deviceConfig.value == null) {
            _configStatus.value = "Device config not yet loaded — refresh first"; return
        }
        val s = _deviceState.value
        val dev = MeshProtos.Config.DeviceConfig.newBuilder()
            .setRole(safeEnum(MeshProtos.Config.DeviceConfig.Role::valueOf, s.role, MeshProtos.Config.DeviceConfig.Role.CLIENT))
            .setSerialEnabled(s.serialEnabled)
            .setDebugLogEnabled(s.debugLogEnabled)
            .setButtonGpio(s.buttonGpio)
            .setBuzzerGpio(s.buzzerGpio)
            .setRebroadcastMode(safeEnum(MeshProtos.Config.DeviceConfig.RebroadcastMode::valueOf, s.rebroadcastMode, MeshProtos.Config.DeviceConfig.RebroadcastMode.ALL))
            .setNodeInfoBroadcastSecs(s.nodeInfoBroadcastSecs)
            .setDoubleTapAsButtonPress(s.doubleTapAsButtonPress)
            .setIsManaged(s.isManaged)
            .setDisableTripleClick(s.disableTripleClick)
            .build()
        val config = MeshProtos.Config.newBuilder().setDevice(dev).build()
        val ok = meshService.writeConfig(config)
        if (ok) dirty.remove("device")
        _configStatus.value = if (ok) "Device config sent" else "Failed to send device config"
    }

    fun applyPositionConfig() {
        if (!meshService.isConnected) { _configStatus.value = "Not connected"; return }
        if (meshService.positionConfig.value == null) {
            _configStatus.value = "Position config not yet loaded — refresh first"; return
        }
        val s = _positionState.value
        val pos = MeshProtos.Config.PositionConfig.newBuilder()
            .setPositionBroadcastSecs(s.positionBroadcastSecs)
            .setPositionBroadcastSmartEnabled(s.positionBroadcastSmartEnabled)
            .setFixedPosition(s.fixedPosition)
            .setGpsEnabled(s.gpsEnabled)
            .setGpsUpdateInterval(s.gpsUpdateInterval)
            .setGpsMode(safeEnum(MeshProtos.Config.PositionConfig.GpsMode::valueOf, s.gpsMode, MeshProtos.Config.PositionConfig.GpsMode.ENABLED))
            .setBroadcastSmartMinimumDistance(s.broadcastSmartMinimumDistance)
            .setBroadcastSmartMinimumIntervalSecs(s.broadcastSmartMinimumIntervalSecs)
            .build()
        val config = MeshProtos.Config.newBuilder().setPosition(pos).build()
        val ok = meshService.writeConfig(config)
        if (ok) dirty.remove("position")
        _configStatus.value = if (ok) "Position config sent" else "Failed to send position config"
    }

    fun applyPowerConfig() {
        if (!meshService.isConnected) { _configStatus.value = "Not connected"; return }
        if (meshService.powerConfig.value == null) {
            _configStatus.value = "Power config not yet loaded — refresh first"; return
        }
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
        if (ok) dirty.remove("power")
        _configStatus.value = if (ok) "Power config sent" else "Failed to send power config"
    }

    fun applyNetworkConfig() {
        if (!meshService.isConnected) { _configStatus.value = "Not connected"; return }
        if (meshService.networkConfig.value == null) {
            _configStatus.value = "Network config not yet loaded — refresh first"; return
        }
        val s = _networkState.value
        val net = MeshProtos.Config.NetworkConfig.newBuilder()
            .setWifiEnabled(s.wifiEnabled)
            .setWifiSsid(s.wifiSsid)
            .setWifiPsk(s.wifiPsk)
            .setEthEnabled(s.ethEnabled)
            .setAddressMode(safeEnum(MeshProtos.Config.NetworkConfig.AddressMode::valueOf, s.addressMode, MeshProtos.Config.NetworkConfig.AddressMode.DHCP))
            .setNtpServer(s.ntpServer)
            .setRsyslogServer(s.rsyslogServer)
            .build()
        val config = MeshProtos.Config.newBuilder().setNetwork(net).build()
        val ok = meshService.writeConfig(config)
        if (ok) dirty.remove("network")
        _configStatus.value = if (ok) "Network config sent" else "Failed to send network config"
    }

    fun applyDisplayConfig() {
        if (!meshService.isConnected) { _configStatus.value = "Not connected"; return }
        if (meshService.displayConfig.value == null) {
            _configStatus.value = "Display config not yet loaded — refresh first"; return
        }
        val s = _displayState.value
        val dsp = MeshProtos.Config.DisplayConfig.newBuilder()
            .setScreenOnSecs(s.screenOnSecs)
            .setGpsFormat(safeEnum(MeshProtos.Config.DisplayConfig.GpsCoordinateFormat::valueOf, s.gpsFormat, MeshProtos.Config.DisplayConfig.GpsCoordinateFormat.DEC))
            .setAutoScreenCarouselSecs(s.autoScreenCarouselSecs)
            .setCompassNorthTop(s.compassNorthTop)
            .setFlipScreen(s.flipScreen)
            .setUnits(safeEnum(MeshProtos.Config.DisplayConfig.DisplayUnits::valueOf, s.units, MeshProtos.Config.DisplayConfig.DisplayUnits.METRIC))
            .setOled(safeEnum(MeshProtos.Config.DisplayConfig.OledType::valueOf, s.oled, MeshProtos.Config.DisplayConfig.OledType.OLED_AUTO))
            .setDisplaymode(safeEnum(MeshProtos.Config.DisplayConfig.DisplayMode::valueOf, s.displaymode, MeshProtos.Config.DisplayConfig.DisplayMode.DEFAULT))
            .setHeadingBold(s.headingBold)
            .setWakeOnTapOrMotion(s.wakeOnTapOrMotion)
            .build()
        val config = MeshProtos.Config.newBuilder().setDisplay(dsp).build()
        val ok = meshService.writeConfig(config)
        if (ok) dirty.remove("display")
        _configStatus.value = if (ok) "Display config sent" else "Failed to send display config"
    }

    fun applyBluetoothConfig() {
        if (!meshService.isConnected) { _configStatus.value = "Not connected"; return }
        if (meshService.bluetoothConfig.value == null) {
            _configStatus.value = "Bluetooth config not yet loaded — refresh first"; return
        }
        val s = _bluetoothState.value
        val bt = MeshProtos.Config.BluetoothConfig.newBuilder()
            .setEnabled(s.enabled)
            .setMode(safeEnum(MeshProtos.Config.BluetoothConfig.PairingMode::valueOf, s.mode, MeshProtos.Config.BluetoothConfig.PairingMode.RANDOM_PIN))
            .setFixedPin(s.fixedPin)
            .build()
        val config = MeshProtos.Config.newBuilder().setBluetooth(bt).build()
        val ok = meshService.writeConfig(config)
        if (ok) dirty.remove("bluetooth")
        _configStatus.value = if (ok) "Bluetooth config sent" else "Failed to send bluetooth config"
    }

    fun applyChannel(index: Int) {
        if (!meshService.isConnected) { _configStatus.value = "Not connected"; return }
        if (meshService.channels.value.isEmpty()) {
            _configStatus.value = "Channels not yet loaded — refresh first"; return
        }
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
            .setRole(safeEnum(MeshProtos.Channel.Role::valueOf, chUi.role, MeshProtos.Channel.Role.PRIMARY))
            .build()
        val ok = meshService.writeChannel(channel)
        if (ok) dirty.remove("channels")
        _configStatus.value = if (ok) "Channel $index config sent" else "Failed to send channel config"
    }

    // ========================  ACTIONS  ========================

    fun refreshDeviceConfig() {
        if (!meshService.isConnected) { _configStatus.value = "Not connected"; return }
        clearDirty()
        meshService.refreshConfig()
        _configStatus.value = "Config refresh requested…"
        // syncFromServiceFlows() will be triggered automatically by the
        // configComplete signal from MeshServiceManager.
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

    /** Generic display helper for proto enums: returns the enum name, or
     *  "<fallback> (#<rawNumber>)" if the firmware sent a value our proto doesn't know about. */
    private fun <E : Enum<E>> enumDisplay(value: E, fallback: String): String {
        return if (value.name == "UNRECOGNIZED") {
            // Try to extract the numeric value via reflection (proto3 enums expose .number via getNumber())
            val number = try {
                value.javaClass.getMethod("getNumber").invoke(value) as? Int
            } catch (_: Exception) { null }
            if (number != null) "$fallback (unknown #$number)" else fallback
        } else value.name
    }

    /** Returns the names of all enum values except UNRECOGNIZED. */
    private fun <E : Enum<E>> enumNames(values: Array<E>): List<String> =
        values.filter { it.name != "UNRECOGNIZED" }.map { it.name }

    /** Safely parse an enum name, returning [fallback] if the user has selected the
     *  synthetic "(unknown #N)" placeholder or an unknown name. */
    private fun <E : Enum<E>> safeEnum(parser: (String) -> E, name: String, fallback: E): E =
        try { parser(name) } catch (_: IllegalArgumentException) { fallback }
}
