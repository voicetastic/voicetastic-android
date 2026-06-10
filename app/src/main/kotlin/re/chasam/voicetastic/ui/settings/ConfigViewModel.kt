package re.chasam.voicetastic.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geeksville.mesh.MeshProtos
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import re.chasam.voicetastic.model.AmrNbBitrate
import re.chasam.voicetastic.model.Codec2Mode
import re.chasam.voicetastic.model.latDegrees
import re.chasam.voicetastic.model.lonDegrees
import re.chasam.voicetastic.model.VoiceCodecChoice
import re.chasam.voicetastic.model.VoiceConfig
import re.chasam.voicetastic.service.MeshFacade
import re.chasam.voicetastic.service.PhoneLocationProvider

/**
 * ViewModel for the settings/configuration screen.
 * Manages Meshtastic device config (all sections), channels, owner, modules, and app voice config.
 * Observes config flows from MeshFacade and populates UI state on connection.
 */
class ConfigViewModel(
    private val meshService: MeshFacade,
    private val voiceConfig: MutableStateFlow<VoiceConfig>,
    private val phoneLocation: PhoneLocationProvider? = null
) : ViewModel() {

    val connectionState: StateFlow<String> = meshService.connectionState
    val myNodeId: StateFlow<String?> = meshService.myNodeId
    val firmwareVersion: StateFlow<String?> = meshService.firmwareVersion

    // --- Status ---
    val configStatus: StateFlow<String?>
        field = MutableStateFlow<String?>(null)

    // ========================  OWNER  ========================

    data class OwnerUiState(
        val longName: String = "",
        val shortName: String = "",
        val isLicensed: Boolean = false
    )

    val ownerState: StateFlow<OwnerUiState>
        field = MutableStateFlow(OwnerUiState())

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

    val loraState: StateFlow<LoraUiState>
        field = MutableStateFlow(LoraUiState())

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

    val deviceState: StateFlow<DeviceUiState>
        field = MutableStateFlow(DeviceUiState())

    val deviceRoles: List<String> = enumNames(MeshProtos.Config.DeviceConfig.Role.values())

    val rebroadcastModes: List<String> = enumNames(MeshProtos.Config.DeviceConfig.RebroadcastMode.values())

    // ========================  POSITION  ========================

    /**
     * Where the node's live position comes from while GPS is enabled:
     *  - [DEVICE] the node's own onboard GPS (normal Meshtastic behaviour)
     *  - [PHONE]  this smartphone's GPS, broadcast to the mesh by the app, so
     *             a GPS-less node still reports a position.
     * App-side only; not part of the device's PositionConfig proto.
     */
    enum class GpsSource { DEVICE, PHONE }

    data class PositionUiState(
        val positionBroadcastSecs: Int = 0,
        val positionBroadcastSmartEnabled: Boolean = false,
        val fixedPosition: Boolean = false,
        val gpsEnabled: Boolean = true,
        val gpsSource: GpsSource = GpsSource.DEVICE,
        val gpsUpdateInterval: Int = 0,
        val gpsMode: String = "ENABLED",
        val broadcastSmartMinimumDistance: Int = 0,
        val broadcastSmartMinimumIntervalSecs: Int = 0,
        // Manually-fixed coordinates (only meaningful when fixedPosition=true).
        // Stored in human-readable degrees / metres; converted to the
        // proto's scaled integer representation on apply.
        val fixedLatitude: Double = 0.0,
        val fixedLongitude: Double = 0.0,
        val fixedAltitude: Int = 0
    )

    val positionState: StateFlow<PositionUiState>
        field = MutableStateFlow(PositionUiState())

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

    val powerState: StateFlow<PowerUiState>
        field = MutableStateFlow(PowerUiState())

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

    val networkState: StateFlow<NetworkUiState>
        field = MutableStateFlow(NetworkUiState())

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

    val displayState: StateFlow<DisplayUiState>
        field = MutableStateFlow(DisplayUiState())

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

    val bluetoothState: StateFlow<BluetoothUiState>
        field = MutableStateFlow(BluetoothUiState())

    val pairingModes: List<String> = enumNames(MeshProtos.Config.BluetoothConfig.PairingMode.values())

    // ========================  MQTT MODULE  ========================

    data class MqttUiState(
        val enabled: Boolean = false,
        val address: String = "",
        val username: String = "",
        val password: String = "",
        val root: String = "",
        val encryptionEnabled: Boolean = true,
        val jsonEnabled: Boolean = false,
        val tlsEnabled: Boolean = false,
        val proxyToClientEnabled: Boolean = false,
        val mapReportingEnabled: Boolean = false,
        val mapPublishIntervalSecs: Int = 0,
        val mapPositionPrecision: Int = 0,
        val mapShouldReportLocation: Boolean = false,
    )

    val mqttState: StateFlow<MqttUiState>
        field = MutableStateFlow(MqttUiState())

    // ========================  CHANNELS  ========================

    data class ChannelUiState(
        val index: Int = 0,
        val role: String = "DISABLED",
        val name: String = "",
        val pskHex: String = "",
        val uplinkEnabled: Boolean = false,
        val downlinkEnabled: Boolean = false
    )

    val channelsState: StateFlow<List<ChannelUiState>>
        field = MutableStateFlow<List<ChannelUiState>>(emptyList())

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
                configStatus.value = "Config received"
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
            meshService.myPosition.collect { p -> if (p != null) updateFixedPositionFromMyPosition(p) }
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
            meshService.mqttConfig.collect { m -> if (m != null && !isDirty("mqtt")) updateMqttFromProto(m) }
        }
        viewModelScope.launch {
            meshService.owner.collect { user ->
                if (user != null && !isDirty("owner")) {
                    ownerState.value = OwnerUiState(
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
                    channelsState.value = chList.map { mapChannel(it) }
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
        meshService.myPosition.value?.let { updateFixedPositionFromMyPosition(it) }
        meshService.powerConfig.value?.let { if (!isDirty("power")) updatePowerFromProto(it) }
        meshService.networkConfig.value?.let { if (!isDirty("network")) updateNetworkFromProto(it) }
        meshService.displayConfig.value?.let { if (!isDirty("display")) updateDisplayFromProto(it) }
        meshService.bluetoothConfig.value?.let { if (!isDirty("bluetooth")) updateBluetoothFromProto(it) }
        meshService.mqttConfig.value?.let { if (!isDirty("mqtt")) updateMqttFromProto(it) }
        meshService.owner.value?.let { user ->
            if (!isDirty("owner")) {
                ownerState.value = OwnerUiState(
                    longName = user.longName,
                    shortName = user.shortName,
                    isLicensed = user.isLicensed
                )
            }
        }
        val chList = meshService.channels.value
        if (chList.isNotEmpty() && !isDirty("channels")) {
            channelsState.value = chList.map { mapChannel(it) }
        }
    }

    private fun updateLoraFromProto(lora: MeshProtos.Config.LoRaConfig) {
        loraState.value = LoraUiState(
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
        deviceState.value = DeviceUiState(
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
        val current = positionState.value
        positionState.value = current.copy(
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

    /**
     * Pre-fill the fixed-position lat/lon/alt fields from the device's own
     * reported position so the user has a sensible starting point rather than
     * zeros.
     *
     * Seeds only while the fields are still empty (all zero), which is enough
     * to avoid clobbering anything the user has typed. Intentionally NOT gated
     * on the section's dirty flag: enabling the "Fixed Position" toggle marks
     * the section dirty, yet the lat/lon should still pre-fill from the device.
     */
    private fun updateFixedPositionFromMyPosition(p: MeshProtos.Position) {
        // A (0, 0) payload means "no fix" - nothing useful to seed.
        if (p.latitudeI == 0 && p.longitudeI == 0) return
        val cur = positionState.value
        if (cur.fixedLatitude == 0.0 && cur.fixedLongitude == 0.0 && cur.fixedAltitude == 0) {
            positionState.value = cur.copy(
                fixedLatitude = p.latDegrees,
                fixedLongitude = p.lonDegrees,
                fixedAltitude = p.altitude
            )
        }
    }

    private fun updatePowerFromProto(pwr: MeshProtos.Config.PowerConfig) {
        powerState.value = PowerUiState(
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
        networkState.value = NetworkUiState(
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
        displayState.value = DisplayUiState(
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
        bluetoothState.value = BluetoothUiState(
            enabled = bt.enabled,
            mode = enumDisplay(bt.mode, "RANDOM_PIN"),
            fixedPin = bt.fixedPin
        )
    }

    private fun updateMqttFromProto(m: MeshProtos.ModuleConfig.MQTTConfig) {
        val map = if (m.hasMapReportSettings()) m.mapReportSettings else null
        mqttState.value = MqttUiState(
            enabled = m.enabled,
            address = m.address,
            username = m.username,
            password = m.password,
            root = m.root,
            encryptionEnabled = m.encryptionEnabled,
            jsonEnabled = m.jsonEnabled,
            tlsEnabled = m.tlsEnabled,
            proxyToClientEnabled = m.proxyToClientEnabled,
            mapReportingEnabled = m.mapReportingEnabled,
            mapPublishIntervalSecs = map?.publishIntervalSecs ?: 0,
            mapPositionPrecision = map?.positionPrecision ?: 0,
            mapShouldReportLocation = map?.shouldReportLocation ?: false,
        )
    }

    // ========================  SETTERS  ========================
    // Each setter marks its section dirty so subsequent device pushes don't
    // overwrite the user's in-progress edits.

    // --- Owner ---
    fun setOwnerLongName(name: String) { markDirty("owner"); ownerState.value = ownerState.value.copy(longName = name) }
    fun setOwnerShortName(name: String) { markDirty("owner"); ownerState.value = ownerState.value.copy(shortName = name) }
    fun setOwnerIsLicensed(licensed: Boolean) { markDirty("owner"); ownerState.value = ownerState.value.copy(isLicensed = licensed) }

    // --- LoRa ---
    fun setLoraRegion(region: String) { markDirty("lora"); loraState.value = loraState.value.copy(region = region) }
    fun setLoraModemPreset(preset: String) { markDirty("lora"); loraState.value = loraState.value.copy(modemPreset = preset) }
    fun setLoraUsePreset(v: Boolean) { markDirty("lora"); loraState.value = loraState.value.copy(usePreset = v) }
    fun setLoraBandwidth(v: Int) { markDirty("lora"); loraState.value = loraState.value.copy(bandwidth = v) }
    fun setLoraSpreadFactor(v: Int) { markDirty("lora"); loraState.value = loraState.value.copy(spreadFactor = v) }
    fun setLoraCodingRate(v: Int) { markDirty("lora"); loraState.value = loraState.value.copy(codingRate = v) }
    fun setLoraFrequencyOffset(v: Float) { markDirty("lora"); loraState.value = loraState.value.copy(frequencyOffset = v) }
    fun setLoraHopLimit(v: Int) { markDirty("lora"); loraState.value = loraState.value.copy(hopLimit = v.coerceIn(1, 7)) }
    fun setLoraTxEnabled(v: Boolean) { markDirty("lora"); loraState.value = loraState.value.copy(txEnabled = v) }
    fun setLoraTxPower(v: Int) { markDirty("lora"); loraState.value = loraState.value.copy(txPower = v) }
    fun setLoraChannelNum(v: Int) { markDirty("lora"); loraState.value = loraState.value.copy(channelNum = v) }
    fun setLoraOverrideDutyCycle(v: Boolean) { markDirty("lora"); loraState.value = loraState.value.copy(overrideDutyCycle = v) }
    fun setLoraSx126xRxBoostedGain(v: Boolean) { markDirty("lora"); loraState.value = loraState.value.copy(sx126xRxBoostedGain = v) }
    fun setLoraOverrideFrequency(v: Float) { markDirty("lora"); loraState.value = loraState.value.copy(overrideFrequency = v) }
    fun setLoraIgnoreMqtt(v: Boolean) { markDirty("lora"); loraState.value = loraState.value.copy(ignoreMqtt = v) }

    // --- Device ---
    fun setDeviceRole(role: String) { markDirty("device"); deviceState.value = deviceState.value.copy(role = role) }
    fun setDeviceSerialEnabled(v: Boolean) { markDirty("device"); deviceState.value = deviceState.value.copy(serialEnabled = v) }
    fun setDeviceDebugLogEnabled(v: Boolean) { markDirty("device"); deviceState.value = deviceState.value.copy(debugLogEnabled = v) }
    fun setDeviceButtonGpio(v: Int) { markDirty("device"); deviceState.value = deviceState.value.copy(buttonGpio = v) }
    fun setDeviceBuzzerGpio(v: Int) { markDirty("device"); deviceState.value = deviceState.value.copy(buzzerGpio = v) }
    fun setDeviceRebroadcastMode(mode: String) { markDirty("device"); deviceState.value = deviceState.value.copy(rebroadcastMode = mode) }
    fun setDeviceNodeInfoBroadcastSecs(v: Int) { markDirty("device"); deviceState.value = deviceState.value.copy(nodeInfoBroadcastSecs = v) }
    fun setDeviceDoubleTapAsButtonPress(v: Boolean) { markDirty("device"); deviceState.value = deviceState.value.copy(doubleTapAsButtonPress = v) }
    fun setDeviceIsManaged(v: Boolean) { markDirty("device"); deviceState.value = deviceState.value.copy(isManaged = v) }
    fun setDeviceDisableTripleClick(v: Boolean) { markDirty("device"); deviceState.value = deviceState.value.copy(disableTripleClick = v) }

    // --- Position ---
    fun setPositionBroadcastSecs(v: Int) { markDirty("position"); positionState.value = positionState.value.copy(positionBroadcastSecs = v) }
    fun setPositionSmartEnabled(v: Boolean) { markDirty("position"); positionState.value = positionState.value.copy(positionBroadcastSmartEnabled = v) }
    fun setPositionFixed(v: Boolean) { markDirty("position"); positionState.value = positionState.value.copy(fixedPosition = v) }
    fun setPositionGpsEnabled(v: Boolean) {
        markDirty("position")
        positionState.value = positionState.value.copy(gpsEnabled = v)
        // Disabling GPS entirely also stops the phone acting as the source.
        if (!v) stopPhoneGpsTracking()
        else if (positionState.value.gpsSource == GpsSource.PHONE) startPhoneGpsTracking()
    }

    /**
     * Choose whether the node's live position comes from its own GPS or this
     * phone's GPS. Selecting [GpsSource.PHONE] starts streaming the phone's
     * location to the mesh; [GpsSource.DEVICE] stops it. The caller (UI) must
     * have obtained ACCESS_FINE_LOCATION before selecting PHONE.
     */
    fun setPositionGpsSource(source: GpsSource) {
        positionState.value = positionState.value.copy(gpsSource = source)
        if (source == GpsSource.PHONE && positionState.value.gpsEnabled) {
            startPhoneGpsTracking()
        } else {
            stopPhoneGpsTracking()
        }
    }
    fun setPositionGpsUpdateInterval(v: Int) { markDirty("position"); positionState.value = positionState.value.copy(gpsUpdateInterval = v) }
    fun setPositionGpsMode(mode: String) { markDirty("position"); positionState.value = positionState.value.copy(gpsMode = mode) }
    fun setPositionSmartMinDistance(v: Int) { markDirty("position"); positionState.value = positionState.value.copy(broadcastSmartMinimumDistance = v) }
    fun setPositionSmartMinInterval(v: Int) { markDirty("position"); positionState.value = positionState.value.copy(broadcastSmartMinimumIntervalSecs = v) }
    fun setPositionFixedLatitude(v: Double) { markDirty("position"); positionState.value = positionState.value.copy(fixedLatitude = v) }
    fun setPositionFixedLongitude(v: Double) { markDirty("position"); positionState.value = positionState.value.copy(fixedLongitude = v) }
    fun setPositionFixedAltitude(v: Int) { markDirty("position"); positionState.value = positionState.value.copy(fixedAltitude = v) }

    // --- Power ---
    fun setPowerSaving(v: Boolean) { markDirty("power"); powerState.value = powerState.value.copy(isPowerSaving = v) }
    fun setPowerShutdownAfterSecs(v: Int) { markDirty("power"); powerState.value = powerState.value.copy(onBatteryShutdownAfterSecs = v) }
    fun setPowerAdcMultiplier(v: Float) { markDirty("power"); powerState.value = powerState.value.copy(adcMultiplierOverride = v) }
    fun setPowerWaitBluetoothSecs(v: Int) { markDirty("power"); powerState.value = powerState.value.copy(waitBluetoothSecs = v) }
    fun setPowerSdsSecs(v: Int) { markDirty("power"); powerState.value = powerState.value.copy(sdsSecs = v) }
    fun setPowerLsSecs(v: Int) { markDirty("power"); powerState.value = powerState.value.copy(lsSecs = v) }
    fun setPowerMinWakeSecs(v: Int) { markDirty("power"); powerState.value = powerState.value.copy(minWakeSecs = v) }
    fun setPowerShutdownOnPowerLoss(v: Boolean) { markDirty("power"); powerState.value = powerState.value.copy(shutdownOnPowerLoss = v) }

    // --- Network ---
    fun setNetworkWifiEnabled(v: Boolean) { markDirty("network"); networkState.value = networkState.value.copy(wifiEnabled = v) }
    fun setNetworkWifiSsid(v: String) { markDirty("network"); networkState.value = networkState.value.copy(wifiSsid = v) }
    fun setNetworkWifiPsk(v: String) { markDirty("network"); networkState.value = networkState.value.copy(wifiPsk = v) }
    fun setNetworkEthEnabled(v: Boolean) { markDirty("network"); networkState.value = networkState.value.copy(ethEnabled = v) }
    fun setNetworkAddressMode(mode: String) { markDirty("network"); networkState.value = networkState.value.copy(addressMode = mode) }
    fun setNetworkNtpServer(v: String) { markDirty("network"); networkState.value = networkState.value.copy(ntpServer = v) }
    fun setNetworkRsyslogServer(v: String) { markDirty("network"); networkState.value = networkState.value.copy(rsyslogServer = v) }

    // --- Display ---
    fun setDisplayScreenOnSecs(v: Int) { markDirty("display"); displayState.value = displayState.value.copy(screenOnSecs = v) }
    fun setDisplayGpsFormat(v: String) { markDirty("display"); displayState.value = displayState.value.copy(gpsFormat = v) }
    fun setDisplayAutoCarouselSecs(v: Int) { markDirty("display"); displayState.value = displayState.value.copy(autoScreenCarouselSecs = v) }
    fun setDisplayCompassNorthTop(v: Boolean) { markDirty("display"); displayState.value = displayState.value.copy(compassNorthTop = v) }
    fun setDisplayFlipScreen(v: Boolean) { markDirty("display"); displayState.value = displayState.value.copy(flipScreen = v) }
    fun setDisplayUnits(v: String) { markDirty("display"); displayState.value = displayState.value.copy(units = v) }
    fun setDisplayOled(v: String) { markDirty("display"); displayState.value = displayState.value.copy(oled = v) }
    fun setDisplayMode(v: String) { markDirty("display"); displayState.value = displayState.value.copy(displaymode = v) }
    fun setDisplayHeadingBold(v: Boolean) { markDirty("display"); displayState.value = displayState.value.copy(headingBold = v) }
    fun setDisplayWakeOnTapOrMotion(v: Boolean) { markDirty("display"); displayState.value = displayState.value.copy(wakeOnTapOrMotion = v) }

    // --- Bluetooth ---
    fun setBluetoothEnabled(v: Boolean) { markDirty("bluetooth"); bluetoothState.value = bluetoothState.value.copy(enabled = v) }
    fun setBluetoothMode(mode: String) { markDirty("bluetooth"); bluetoothState.value = bluetoothState.value.copy(mode = mode) }
    fun setBluetoothFixedPin(v: Int) { markDirty("bluetooth"); bluetoothState.value = bluetoothState.value.copy(fixedPin = v) }

    // --- MQTT module ---
    fun setMqttEnabled(v: Boolean) { markDirty("mqtt"); mqttState.value = mqttState.value.copy(enabled = v) }
    fun setMqttAddress(v: String) { markDirty("mqtt"); mqttState.value = mqttState.value.copy(address = v) }
    fun setMqttUsername(v: String) { markDirty("mqtt"); mqttState.value = mqttState.value.copy(username = v) }
    fun setMqttPassword(v: String) { markDirty("mqtt"); mqttState.value = mqttState.value.copy(password = v) }
    fun setMqttRoot(v: String) { markDirty("mqtt"); mqttState.value = mqttState.value.copy(root = v) }
    fun setMqttEncryptionEnabled(v: Boolean) { markDirty("mqtt"); mqttState.value = mqttState.value.copy(encryptionEnabled = v) }
    fun setMqttJsonEnabled(v: Boolean) { markDirty("mqtt"); mqttState.value = mqttState.value.copy(jsonEnabled = v) }
    fun setMqttTlsEnabled(v: Boolean) { markDirty("mqtt"); mqttState.value = mqttState.value.copy(tlsEnabled = v) }
    fun setMqttProxyToClientEnabled(v: Boolean) { markDirty("mqtt"); mqttState.value = mqttState.value.copy(proxyToClientEnabled = v) }
    fun setMqttMapReportingEnabled(v: Boolean) { markDirty("mqtt"); mqttState.value = mqttState.value.copy(mapReportingEnabled = v) }
    fun setMqttMapPublishIntervalSecs(v: Int) { markDirty("mqtt"); mqttState.value = mqttState.value.copy(mapPublishIntervalSecs = v) }
    fun setMqttMapPositionPrecision(v: Int) { markDirty("mqtt"); mqttState.value = mqttState.value.copy(mapPositionPrecision = v) }
    fun setMqttMapShouldReportLocation(v: Boolean) { markDirty("mqtt"); mqttState.value = mqttState.value.copy(mapShouldReportLocation = v) }

    // --- Channel ---
    fun setChannelName(index: Int, name: String) {
        markDirty("channels")
        channelsState.value = channelsState.value.map {
            if (it.index == index) it.copy(name = name) else it
        }
    }
    fun setChannelPskHex(index: Int, hex: String) {
        markDirty("channels")
        channelsState.value = channelsState.value.map {
            if (it.index == index) it.copy(pskHex = hex) else it
        }
    }
    fun setChannelUplink(index: Int, v: Boolean) {
        markDirty("channels")
        channelsState.value = channelsState.value.map {
            if (it.index == index) it.copy(uplinkEnabled = v) else it
        }
    }
    fun setChannelDownlink(index: Int, v: Boolean) {
        markDirty("channels")
        channelsState.value = channelsState.value.map {
            if (it.index == index) it.copy(downlinkEnabled = v) else it
        }
    }

    // --- Voice ---
    fun setVoiceCodec(codec: VoiceCodecChoice) {
        voiceConfig.value = voiceConfig.value.copy(codec = codec)
    }
    fun setVoiceBitrate(bitrate: AmrNbBitrate) {
        voiceConfig.value = voiceConfig.value.copy(bitrate = bitrate)
    }
    fun setOpusBitrateKbps(bitrate: Int) {
        voiceConfig.value = voiceConfig.value.copy(opusBitrateKbps = bitrate.coerceIn(6, 16))
    }
    fun setCodec2Mode(mode: Codec2Mode) {
        voiceConfig.value = voiceConfig.value.copy(codec2Mode = mode)
    }
    fun setMaxDuration(seconds: Int) {
        voiceConfig.value = voiceConfig.value.copy(maxDurationSeconds = seconds.coerceIn(1, 60))
    }
    fun setChunkTimeout(seconds: Int) {
        // Bounds mirror core's REASSEMBLY_TIMEOUT_LOWER/UPPER_SECS (10..3600).
        voiceConfig.value = voiceConfig.value.copy(chunkTimeoutSeconds = seconds.coerceIn(10, 3600))
    }
    fun setPartialPlayOnTimeout(enabled: Boolean) {
        voiceConfig.value = voiceConfig.value.copy(partialPlayOnTimeout = enabled)
    }
    fun setNoiseSuppressionEnabled(enabled: Boolean) {
        voiceConfig.value = voiceConfig.value.copy(noiseSuppressionEnabled = enabled)
    }

    // ========================  APPLY METHODS  ========================

    fun applyOwner() {
        if (!meshService.isConnected) { configStatus.value = "Not connected"; return }
        if (meshService.owner.value == null) {
            configStatus.value = "Owner not yet loaded — refresh first"
            return
        }
        val s = ownerState.value
        if (s.longName.isBlank() || s.shortName.isBlank()) {
            configStatus.value = "Long/short name cannot be empty"
            return
        }
        val user = MeshProtos.User.newBuilder()
            .setLongName(s.longName)
            .setShortName(s.shortName)
            .setIsLicensed(s.isLicensed)
            .build()
        val ok = meshService.writeOwner(user)
        if (ok) dirty.remove("owner")
        configStatus.value = if (ok) "Owner config sent" else "Failed to send owner config"
    }

    fun applyLoraConfig() {
        if (!meshService.isConnected) { configStatus.value = "Not connected"; return }
        if (meshService.radioConfig.value == null) {
            configStatus.value = "LoRa config not yet loaded from device — refresh first"
            return
        }
        val s = loraState.value
        // Refuse obviously dangerous combinations that can crash the radio task
        // on the firmware (and bootloop the device).
        if (s.region.startsWith("UNSET") || s.region.contains("unknown")) {
            configStatus.value = "Refusing to write: pick a real region first"
            return
        }
        if (!s.usePreset && (s.bandwidth == 0 || s.spreadFactor == 0 || s.codingRate == 0)) {
            configStatus.value = "Refusing to write: bandwidth / SF / CR cannot be zero when not using a preset"
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
            configStatus.value = if (ok) "LoRa config sent" else "Failed to send LoRa config"
        } catch (e: Exception) {
            configStatus.value = "Error: ${e.message}"
        }
    }

    fun applyDeviceConfig() {
        if (!meshService.isConnected) { configStatus.value = "Not connected"; return }
        if (meshService.deviceConfig.value == null) {
            configStatus.value = "Device config not yet loaded — refresh first"; return
        }
        val s = deviceState.value
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
        configStatus.value = if (ok) "Device config sent" else "Failed to send device config"
    }

    fun applyPositionConfig() {
        if (!meshService.isConnected) { configStatus.value = "Not connected"; return }
        if (meshService.positionConfig.value == null) {
            configStatus.value = "Position config not yet loaded — refresh first"; return
        }
        val s = positionState.value
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
        configStatus.value = if (ok) "Position config sent" else "Failed to send position config"
    }

    /**
     * Push the user-entered fixed lat/lon/altitude to the device via the
     * `set_fixed_position` admin message. The PositionConfig.fixed_position
     * flag must also be enabled (via [applyPositionConfig]) for the device
     * to use this location in subsequent broadcasts.
     */
    fun applyFixedPosition() {
        if (!meshService.isConnected) { configStatus.value = "Not connected"; return }
        val s = positionState.value
        if (!s.fixedPosition) {
            configStatus.value = "Enable Fixed Position first"; return
        }
        val pos = MeshProtos.Position.newBuilder()
            .setLatitudeI((s.fixedLatitude * 1e7).toInt())
            .setLongitudeI((s.fixedLongitude * 1e7).toInt())
            .setAltitude(s.fixedAltitude)
            .build()
        val ok = meshService.setFixedPosition(pos)
        configStatus.value = if (ok) "Fixed position sent" else "Failed to send fixed position"
    }

    /**
     * Use the phone's own GPS as the node's position source: read a single
     * fix from the smartphone, reflect it in the fixed-position fields, and
     * push it to the device via `set_fixed_position`. This lets a GPS-less
     * node report the phone's location.
     *
     * The caller (UI) must ensure ACCESS_FINE_LOCATION has been granted before
     * invoking this; a missing grant or disabled location service surfaces as
     * a "could not get phone location" status.
     */
    fun applyFixedPositionFromPhone() {
        if (!meshService.isConnected) { configStatus.value = "Not connected"; return }
        val provider = phoneLocation ?: run {
            configStatus.value = "Phone GPS unavailable"; return
        }
        configStatus.value = "Getting phone location…"
        viewModelScope.launch {
            val fix = provider.currentFix()
            if (fix == null) {
                configStatus.value =
                    "Could not get phone location (enable location services / grant permission)"
                return@launch
            }
            val altitude = if (fix.hasAltitude()) fix.altitude.toInt() else 0
            // Reflect the phone fix in the UI and turn on fixed-position so a
            // subsequent "Apply Position Config" keeps the device using it.
            markDirty("position")
            positionState.value = positionState.value.copy(
                fixedPosition = true,
                fixedLatitude = fix.latitude,
                fixedLongitude = fix.longitude,
                fixedAltitude = altitude
            )
            val pos = MeshProtos.Position.newBuilder()
                .setLatitudeI((fix.latitude * 1e7).toInt())
                .setLongitudeI((fix.longitude * 1e7).toInt())
                .setAltitude(altitude)
                .build()
            val ok = meshService.setFixedPosition(pos)
            configStatus.value = if (ok) {
                "Fixed position set from phone GPS"
            } else {
                "Failed to send phone position"
            }
        }
    }

    /** UI feedback when the user declines the location permission prompt. */
    fun onPhoneGpsPermissionDenied() {
        // Selecting "Phone GPS" never took effect, so make sure the source
        // shows as Device and tracking is stopped.
        positionState.value = positionState.value.copy(gpsSource = GpsSource.DEVICE)
        stopPhoneGpsTracking()
        configStatus.value = "Location permission denied — can't use phone GPS"
    }

    /** Active phone-GPS streaming job, non-null while PHONE source is on. */
    private var phoneGpsJob: Job? = null

    /**
     * Stream this phone's location to the mesh as Position packets, so the
     * node reports the phone's location as its own. Cadence follows the
     * configured broadcast interval (default 30 s).
     */
    private fun startPhoneGpsTracking() {
        val provider = phoneLocation ?: run {
            configStatus.value = "Phone GPS unavailable"; return
        }
        if (phoneGpsJob?.isActive == true) return
        val secs = positionState.value.positionBroadcastSecs.takeIf { it > 0 } ?: 30
        val intervalMs = secs.toLong() * 1000L
        configStatus.value = "Phone GPS active — broadcasting this phone's location"
        phoneGpsJob = viewModelScope.launch {
            provider.locationUpdates(intervalMs).collect { loc ->
                if (!meshService.isConnected) return@collect
                val altitude = if (loc.hasAltitude()) loc.altitude.toInt() else 0
                val pos = MeshProtos.Position.newBuilder()
                    .setLatitudeI((loc.latitude * 1e7).toInt())
                    .setLongitudeI((loc.longitude * 1e7).toInt())
                    .setAltitude(altitude)
                    .build()
                meshService.broadcastPosition(pos, channel = 0, dest = null)
            }
        }
    }

    private fun stopPhoneGpsTracking() {
        phoneGpsJob?.cancel()
        phoneGpsJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopPhoneGpsTracking()
    }

    /** Tell the device to forget any previously-set fixed position. */
    fun clearFixedPosition() {
        if (!meshService.isConnected) { configStatus.value = "Not connected"; return }
        val ok = meshService.removeFixedPosition()
        configStatus.value = if (ok) "Fixed position cleared" else "Failed to clear fixed position"
    }

    /**
     * Broadcast the user-entered lat/lon/altitude as a one-shot Position
     * packet on the mesh (POSITION_APP). Distinct from
     * [applyFixedPosition] which writes a config admin message to the
     * local radio without emitting a mesh packet.
     */
    fun broadcastPosition() {
        if (!meshService.isConnected) { configStatus.value = "Not connected"; return }
        val s = positionState.value
        val pos = MeshProtos.Position.newBuilder()
            .setLatitudeI((s.fixedLatitude * 1e7).toInt())
            .setLongitudeI((s.fixedLongitude * 1e7).toInt())
            .setAltitude(s.fixedAltitude)
            .build()
        val ok = meshService.broadcastPosition(pos, channel = 0, dest = null)
        configStatus.value = if (ok) "Position broadcast sent" else "Failed to broadcast position"
    }

    fun applyPowerConfig() {
        if (!meshService.isConnected) { configStatus.value = "Not connected"; return }
        if (meshService.powerConfig.value == null) {
            configStatus.value = "Power config not yet loaded — refresh first"; return
        }
        val s = powerState.value
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
        configStatus.value = if (ok) "Power config sent" else "Failed to send power config"
    }

    fun applyNetworkConfig() {
        if (!meshService.isConnected) { configStatus.value = "Not connected"; return }
        if (meshService.networkConfig.value == null) {
            configStatus.value = "Network config not yet loaded — refresh first"; return
        }
        val s = networkState.value
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
        configStatus.value = if (ok) "Network config sent" else "Failed to send network config"
    }

    fun applyDisplayConfig() {
        if (!meshService.isConnected) { configStatus.value = "Not connected"; return }
        if (meshService.displayConfig.value == null) {
            configStatus.value = "Display config not yet loaded — refresh first"; return
        }
        val s = displayState.value
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
        configStatus.value = if (ok) "Display config sent" else "Failed to send display config"
    }

    fun applyBluetoothConfig() {
        if (!meshService.isConnected) { configStatus.value = "Not connected"; return }
        if (meshService.bluetoothConfig.value == null) {
            configStatus.value = "Bluetooth config not yet loaded — refresh first"; return
        }
        val s = bluetoothState.value
        val bt = MeshProtos.Config.BluetoothConfig.newBuilder()
            .setEnabled(s.enabled)
            .setMode(safeEnum(MeshProtos.Config.BluetoothConfig.PairingMode::valueOf, s.mode, MeshProtos.Config.BluetoothConfig.PairingMode.RANDOM_PIN))
            .setFixedPin(s.fixedPin)
            .build()
        val config = MeshProtos.Config.newBuilder().setBluetooth(bt).build()
        val ok = meshService.writeConfig(config)
        if (ok) dirty.remove("bluetooth")
        configStatus.value = if (ok) "Bluetooth config sent" else "Failed to send bluetooth config"
    }

    fun applyMqttConfig() {
        if (!meshService.isConnected) { configStatus.value = "Not connected"; return }
        if (meshService.mqttConfig.value == null) {
            configStatus.value = "MQTT config not yet loaded — refresh first"; return
        }
        val s = mqttState.value
        val mqttBuilder = MeshProtos.ModuleConfig.MQTTConfig.newBuilder()
            .setEnabled(s.enabled)
            .setAddress(s.address)
            .setUsername(s.username)
            .setPassword(s.password)
            .setRoot(s.root)
            .setEncryptionEnabled(s.encryptionEnabled)
            .setJsonEnabled(s.jsonEnabled)
            .setTlsEnabled(s.tlsEnabled)
            .setProxyToClientEnabled(s.proxyToClientEnabled)
            .setMapReportingEnabled(s.mapReportingEnabled)
        if (s.mapReportingEnabled) {
            mqttBuilder.mapReportSettings = MeshProtos.ModuleConfig.MapReportSettings.newBuilder()
                .setPublishIntervalSecs(s.mapPublishIntervalSecs)
                .setPositionPrecision(s.mapPositionPrecision)
                .setShouldReportLocation(s.mapShouldReportLocation)
                .build()
        }
        val mc = MeshProtos.ModuleConfig.newBuilder().setMqtt(mqttBuilder).build()
        val ok = meshService.writeModuleConfig(mc)
        if (ok) dirty.remove("mqtt")
        configStatus.value = if (ok) "MQTT module config sent" else "Failed to send MQTT module config"
    }

    fun applyChannel(index: Int) {
        if (!meshService.isConnected) { configStatus.value = "Not connected"; return }
        if (meshService.channels.value.isEmpty()) {
            configStatus.value = "Channels not yet loaded — refresh first"; return
        }
        val chUi = channelsState.value.find { it.index == index } ?: return
        val pskBytes = try {
            chUi.pskHex.hexToBytes()
        } catch (e: IllegalArgumentException) {
            // Surface malformed PSK to the user instead of crashing the
            // apply path. Don't clear the dirty flag — the user's edit
            // is still pending until they fix the field.
            configStatus.value = "Channel $index PSK invalid: ${e.message}"
            return
        }
        val settings = MeshProtos.ChannelSettings.newBuilder()
            .setName(chUi.name)
            .setPsk(com.google.protobuf.ByteString.copyFrom(pskBytes))
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
        configStatus.value = if (ok) "Channel $index config sent" else "Failed to send channel config"
    }

    // ========================  ACTIONS  ========================

    fun refreshDeviceConfig() {
        if (!meshService.isConnected) { configStatus.value = "Not connected"; return }
        clearDirty()
        configStatus.value = "Config refresh requested…"
        viewModelScope.launch {
            // Snapshot the LoRa config reference so we can detect *any* refresh
            // activity (a new emission with the same value or a new instance).
            val before = meshService.radioConfig.value
            meshService.refreshConfig()
            // Race the firmware's configComplete event against a timeout so
            // the status never sticks at "requested…". On USB the burst is
            // typically <300 ms; BLE may need a couple of seconds. Modern
            // Meshtastic firmware always sends configCompleteId at the end
            // of a want_config_id burst, but if it ever doesn't (LogRecord
            // floods, oneof confusion, etc.) we still resync the UI here.
            val completedId = withTimeoutOrNull(8_000) {
                meshService.configComplete.first()
            }
            if (completedId != null) {
                // The configComplete collector in init{} already ran sync +
                // set "Config received"; nothing else to do.
                return@launch
            }
            // Timed out. Decide whether *anything* arrived in the meantime.
            syncFromServiceFlows()
            val after = meshService.radioConfig.value
            configStatus.value = when {
                after != null && after !== before -> "Refresh partial — no end-of-config marker (using what we got)"
                after != null -> "Refresh timed out — UI shows last known values"
                else -> "Refresh timed out — no response from device"
            }
        }
    }

    fun rebootDevice() {
        if (!meshService.isConnected) { configStatus.value = "Not connected"; return }
        meshService.rebootDevice(5)
        configStatus.value = "Reboot command sent (5s)"
    }

    fun factoryReset() {
        if (!meshService.isConnected) { configStatus.value = "Not connected"; return }
        meshService.factoryReset()
        configStatus.value = "Factory reset command sent"
    }

    fun resetNodeDb() {
        if (!meshService.isConnected) { configStatus.value = "Not connected"; return }
        if (!meshService.resetNodeDb()) {
            configStatus.value = "Reset NodeDB failed to send"
            return
        }
        // `resetNodeDb()` now wraps reset-plus-refresh in the bridge, so
        // we don't need a separate `refreshConfig()` call here.
        configStatus.value = "Reset NodeDB sent — refreshing…"
    }

    fun clearStatus() { configStatus.value = null }

    /** Live in-app event log, surfaced on the Debug settings card. */
    val debugLog: StateFlow<List<re.chasam.voicetastic.service.DebugEntry>> =
        meshService.debugLog

    fun clearDebugLog() {
        (meshService as? re.chasam.voicetastic.service.MeshServiceManager)?.clearDebugLog()
    }

    // ========================  UTILITIES  ========================

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * Parse a hex-encoded byte string into raw bytes.
     *
     * Accepts optional `0x` prefix and embedded whitespace. Throws
     * [IllegalArgumentException] with a user-readable message on
     * malformed input — callers (currently [applyChannel]) catch this
     * and surface it via [configStatus] instead of letting a
     * `NumberFormatException` propagate and crash the apply path.
     */
    private fun String.hexToBytes(): ByteArray {
        val clean = this.replace(" ", "").replace("0x", "", ignoreCase = true)
        if (clean.isEmpty()) return byteArrayOf()
        require(clean.length % 2 == 0) {
            "PSK hex must have an even number of digits (got ${clean.length})"
        }
        require(clean.all { it.isHexChar() }) {
            "PSK hex contains non-hex characters"
        }
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun Char.isHexChar(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

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
