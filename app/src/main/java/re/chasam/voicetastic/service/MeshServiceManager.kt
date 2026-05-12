package re.chasam.voicetastic.service

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import com.geeksville.mesh.MeshProtos
import com.geeksville.mesh.Portnums.PortNum
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import re.chasam.voicetastic.model.MeshNode
import uniffi.voicetastic.MeshConfigListener
import uniffi.voicetastic.MeshConnectionState
import uniffi.voicetastic.MeshDataListener
import uniffi.voicetastic.MeshService
import uniffi.voicetastic.MeshStateListener
import uniffi.voicetastic.MeshTextListener

/**
 * Manages direct BLE connection to a Meshtastic device.
 * No Meshtastic Android app required.
 */
class MeshServiceManager(private val context: Context) {

    companion object {
        private const val TAG = "MeshServiceManager"
        private const val MTU_SIZE = 512
        // Notifications drive real-time updates; the poll is only a safety net
        // for missed FromNum notifications. Aggressive polling can starve the
        // firmware's BLE stack and trigger watchdog reboots on some boards.
        private const val POLL_INTERVAL_MS = 30_000L
    }

    data class IncomingText(val from: String, val to: String, val text: String, val channel: Int = 0, val timestamp: Long = System.currentTimeMillis())
    data class IncomingData(val from: String, val to: String, val portNum: Int, val payload: ByteArray, val channel: Int = 0, val timestamp: Long = System.currentTimeMillis())

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rustService: MeshService = MeshService()
    private var rustSession: RustMeshSession? = null

    private var myNodeNum: Int = 0


    // --- Public state flows ---
    private val _connectionState = MutableStateFlow("DISCONNECTED")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    private val _nodes = MutableStateFlow<List<MeshNode>>(emptyList())
    val nodes: StateFlow<List<MeshNode>> = _nodes.asStateFlow()

    private val _incomingTextMessages = MutableSharedFlow<IncomingText>(extraBufferCapacity = 64)
    val incomingTextMessages: SharedFlow<IncomingText> = _incomingTextMessages.asSharedFlow()

    private val _incomingDataMessages = MutableSharedFlow<IncomingData>(extraBufferCapacity = 64)
    val incomingDataMessages: SharedFlow<IncomingData> = _incomingDataMessages.asSharedFlow()

    private val _myNodeId = MutableStateFlow<String?>(null)
    val myNodeId: StateFlow<String?> = _myNodeId.asStateFlow()

    private val _firmwareVersion = MutableStateFlow<String?>(null)
    val firmwareVersion: StateFlow<String?> = _firmwareVersion.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // ==========  USB TRANSPORT  ==========
    //
    // BLE remains the default; USB runs alongside as an alternative transport
    // for Meshtastic nodes connected via a USB-OTG cable. Only one transport
    // is active at a time. When `usbActive` is true, writeToRadio() goes out
    // over USB and BLE writes are short-circuited.

    enum class TransportType { NONE, BLE, USB }

    private val usbTransport: UsbMeshTransport = UsbMeshTransport(context)
    private var usbActive: Boolean = false

    val usbState: StateFlow<UsbMeshTransport.State> = usbTransport.state
    val usbConnectedDevice: StateFlow<UsbDevice?> = usbTransport.connectedDevice
    val usbErrors: SharedFlow<String> = usbTransport.errors

    private val _activeTransport = MutableStateFlow(TransportType.NONE)
    val activeTransport: StateFlow<TransportType> = _activeTransport.asStateFlow()

    init {

        scope.launch {
            rustService.setStateListener(object : MeshStateListener {
                override fun onState(state: MeshConnectionState) {
                _connectionState.value = when (state) {
                    MeshConnectionState.CONNECTED, MeshConnectionState.READY -> "CONNECTED"
                    MeshConnectionState.CONNECTING, MeshConnectionState.CONFIGURING -> "CONNECTING"
                    MeshConnectionState.DISCONNECTED -> "DISCONNECTED"
                }
                if (state == MeshConnectionState.DISCONNECTED) {
                    clearSessionState()
                    _activeTransport.value = TransportType.NONE
                    usbActive = false
                }
            }
            })

            rustService.setTextListener(object : MeshTextListener {
                override fun onText(message: uniffi.voicetastic.IncomingTextMsg) {
                    val toId = if (message.to.toInt() == MeshtasticBle.BROADCAST_ADDR) "broadcast"
                    else MeshtasticBle.nodeNumToId(message.to.toInt())
                    _incomingTextMessages.tryEmit(
                        IncomingText(
                            from = message.fromId,
                            to = toId,
                            text = message.text,
                            channel = message.channel.toInt(),
                            timestamp = message.rxTime.toLong() * 1000L,
                        )
                    )
                }
            })

            rustService.setDataListener(object : MeshDataListener {
                override fun onData(message: uniffi.voicetastic.IncomingDataMsg) {
                    val fromId = MeshtasticBle.nodeNumToId(message.from.toInt())
                    val toId = if (message.to.toInt() == MeshtasticBle.BROADCAST_ADDR) "broadcast"
                    else MeshtasticBle.nodeNumToId(message.to.toInt())
                    _incomingDataMessages.tryEmit(
                        IncomingData(
                            from = fromId,
                            to = toId,
                            portNum = message.portnum,
                            payload = message.payload,
                            channel = message.channel.toInt(),
                            timestamp = message.rxTime.toLong() * 1000L,
                        )
                    )
                }
            })

            rustService.setConfigListener(object : MeshConfigListener {
                override fun onMyInfo(encoded: ByteArray) {
                    runCatching { MeshProtos.MyNodeInfo.parseFrom(encoded) }
                        .onSuccess { info ->
                            myNodeNum = info.myNodeNum
                            _myNodeId.value = MeshtasticBle.nodeNumToId(myNodeNum)
                        }
                }

            override fun onNodeInfo(encoded: ByteArray) {
                runCatching { MeshProtos.NodeInfo.parseFrom(encoded) }
                    .onSuccess { ni ->
                        val node = MeshNode(
                            nodeId = MeshtasticBle.nodeNumToId(ni.num),
                            longName = if (ni.hasUser()) ni.user.longName else "Unknown",
                            shortName = if (ni.hasUser()) ni.user.shortName else "??",
                            lastHeard = ni.lastHeard.toLong(),
                            batteryLevel = if (ni.hasDeviceMetrics()) ni.deviceMetrics.batteryLevel.toInt() else null,
                            snr = if (ni.snr != 0f) ni.snr else null,
                        )
                        nodeMap[ni.num] = node
                        _nodes.value = nodeMap.values.toList()
                        if (ni.num == myNodeNum && ni.hasUser()) _owner.value = ni.user
                        if (ni.num == myNodeNum && ni.hasPosition()) _myPosition.value = ni.position
                    }
            }

            override fun onConfig(encoded: ByteArray) {
                runCatching { MeshProtos.Config.parseFrom(encoded) }
                    .onSuccess { handleConfig(it) }
            }

            override fun onChannel(encoded: ByteArray) {
                runCatching { MeshProtos.Channel.parseFrom(encoded) }
                    .onSuccess { ch ->
                        val current = _channels.value.toMutableList()
                        val idx = current.indexOfFirst { it.index == ch.index }
                        if (idx >= 0) current[idx] = ch else current.add(ch)
                        _channels.value = current
                    }
            }

            override fun onOwner(encoded: ByteArray) {
                runCatching { MeshProtos.User.parseFrom(encoded) }
                    .onSuccess { _owner.value = it }
            }

            override fun onMetadata(encoded: ByteArray) {
                runCatching { MeshProtos.DeviceMetadata.parseFrom(encoded) }
                    .onSuccess { md ->
                        if (md.firmwareVersion.isNotEmpty()) {
                            _firmwareVersion.value = md.firmwareVersion
                        }
                    }
            }

                override fun onConfigComplete(nonce: UInt) {
                    _configComplete.tryEmit(nonce.toInt())
                }
            })
        }
    }

    /** Enumerate USB serial drivers that look like Meshtastic devices. */
    fun discoverUsbDevices(): List<UsbSerialDriver> = usbTransport.discoverDevices()

    fun usbHasPermission(device: UsbDevice): Boolean = usbTransport.hasPermission(device)

    fun requestUsbPermission(device: UsbDevice, onResult: (Boolean) -> Unit) =
        usbTransport.requestPermission(device, onResult)

    /**
     * Connect over USB. If a BLE link is currently active it is dropped first
     * (only one transport at a time).
     *
     * @return true if the open succeeded.
     */
    fun connectUsb(driver: UsbSerialDriver): Boolean {
        runCatching { rustSession?.close() }
        rustSession = null
        usbActive = true
        _activeTransport.value = TransportType.USB
        _connectionState.value = "CONNECTING"
        val ok = usbTransport.connect(driver)
        if (!ok) {
            usbActive = false
            _activeTransport.value = TransportType.NONE
            _connectionState.value = "DISCONNECTED"
            return false
        }
        runCatching {
            rustSession = RustMeshSession.openUsb(rustService, usbTransport)
        }.onFailure { t ->
            Log.e(TAG, "Rust USB connect failed", t)
            usbTransport.disconnect()
            usbActive = false
            _activeTransport.value = TransportType.NONE
            _connectionState.value = "DISCONNECTED"
            return false
        }
        return ok
    }

    fun disconnectUsb() {
        runCatching { rustSession?.close() }
        rustSession = null
        usbTransport.disconnect()
        usbActive = false
        _activeTransport.value = TransportType.NONE
        // Mirror disconnectBle(): drop per-session state so a subsequent
        // attach (possibly to a different node) starts from a clean slate.
        // Without this, myNodeNum / cached configs from a prior session
        // leak across reconnects and break sendAdminMessage()'s gate
        // (`!isConnected || myNodeNum == 0`) → "Failed to send …".
        clearSessionState()
        _connectionState.value = "DISCONNECTED"
        Log.i(TAG, "Disconnected (USB)")
    }

    /**
     * Per-connection node/config caches. Cleared on either transport tearing down
     * so that the next connection (possibly to a different physical radio) starts
     * from a known state.
     */
    private fun clearSessionState() {
        myNodeNum = 0
        _myNodeId.value = null
        _firmwareVersion.value = null
        nodeMap.clear()
        _nodes.value = emptyList()
        _radioConfig.value = null
        _deviceConfig.value = null
        _positionConfig.value = null
        _myPosition.value = null
        _powerConfig.value = null
        _networkConfig.value = null
        _displayConfig.value = null
        _bluetoothConfig.value = null
        _channels.value = emptyList()
        _owner.value = null
        _moduleConfigs.value = emptyMap()
    }

    /** Notify when the OS reports a USB device was unplugged. */
    fun onUsbDeviceDetached(device: UsbDevice) = usbTransport.onDeviceDetached(device)

    // --- Per-section config flows ---
    private val _radioConfig = MutableStateFlow<MeshProtos.Config.LoRaConfig?>(null)
    val radioConfig: StateFlow<MeshProtos.Config.LoRaConfig?> = _radioConfig.asStateFlow()

    private val _deviceConfig = MutableStateFlow<MeshProtos.Config.DeviceConfig?>(null)
    val deviceConfig: StateFlow<MeshProtos.Config.DeviceConfig?> = _deviceConfig.asStateFlow()

    private val _positionConfig = MutableStateFlow<MeshProtos.Config.PositionConfig?>(null)
    val positionConfig: StateFlow<MeshProtos.Config.PositionConfig?> = _positionConfig.asStateFlow()

    /**
     * Last-known Position reported by our own NodeInfo. Used to seed the
     * "Fixed Position" editor in Settings so the user can edit and re-send
     * lat/lon/altitude rather than starting from zeros.
     */
    private val _myPosition = MutableStateFlow<MeshProtos.Position?>(null)
    val myPosition: StateFlow<MeshProtos.Position?> = _myPosition.asStateFlow()

    private val _powerConfig = MutableStateFlow<MeshProtos.Config.PowerConfig?>(null)
    val powerConfig: StateFlow<MeshProtos.Config.PowerConfig?> = _powerConfig.asStateFlow()

    private val _networkConfig = MutableStateFlow<MeshProtos.Config.NetworkConfig?>(null)
    val networkConfig: StateFlow<MeshProtos.Config.NetworkConfig?> = _networkConfig.asStateFlow()

    private val _displayConfig = MutableStateFlow<MeshProtos.Config.DisplayConfig?>(null)
    val displayConfig: StateFlow<MeshProtos.Config.DisplayConfig?> = _displayConfig.asStateFlow()

    private val _bluetoothConfig = MutableStateFlow<MeshProtos.Config.BluetoothConfig?>(null)
    val bluetoothConfig: StateFlow<MeshProtos.Config.BluetoothConfig?> = _bluetoothConfig.asStateFlow()

    private val _channels = MutableStateFlow<List<MeshProtos.Channel>>(emptyList())
    val channels: StateFlow<List<MeshProtos.Channel>> = _channels.asStateFlow()

    private val _owner = MutableStateFlow<MeshProtos.User?>(null)
    val owner: StateFlow<MeshProtos.User?> = _owner.asStateFlow()

    private val _moduleConfigs = MutableStateFlow<Map<String, MeshProtos.ModuleConfig>>(emptyMap())
    val moduleConfigs: StateFlow<Map<String, MeshProtos.ModuleConfig>> = _moduleConfigs.asStateFlow()

    /** Emits the firmware's configCompleteId after a config burst finishes. */
    private val _configComplete = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val configComplete: SharedFlow<Int> = _configComplete.asSharedFlow()

    private var pendingConfigId: Int = 0

    private val nodeMap = mutableMapOf<Int, MeshNode>()

    val isConnected: Boolean
        get() = _connectionState.value == "CONNECTED" || _connectionState.value == "CONNECTING"


    // ==========  BLE SCANNING (UI placeholders)  ==========

    @SuppressLint("MissingPermission")
    fun startScan() {
        // Scanning is handled by the Rust bridge; this is a UI placeholder
        // that may be revived if Rust-side scanning is exposed.
        Log.d(TAG, "startScan called (Rust bridge handles discovery)")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        // Scanning is handled by the Rust bridge; this is a UI placeholder.
        Log.d(TAG, "stopScan called")
    }

    // ==========  BLE CONNECTION  ==========

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        _connectionState.value = "CONNECTING"
        _activeTransport.value = TransportType.BLE
        scope.launch {
            runCatching {
                rustSession?.close()
                rustSession = RustMeshSession.openBle(context, rustService, device)
            }.onFailure { t ->
                Log.e(TAG, "Rust BLE connect failed", t)
                rustSession = null
                _activeTransport.value = TransportType.NONE
                _connectionState.value = "DISCONNECTED"
            }
        }
    }

    fun disconnect() {
        runCatching { rustSession?.close() }
        rustSession = null
        if (usbActive) usbTransport.disconnect()
        usbActive = false
        _activeTransport.value = TransportType.NONE
        _connectionState.value = "DISCONNECTED"
    }


    fun unbind() = disconnect()

    fun destroy() {
        disconnect()
        usbTransport.destroy()
        runCatching { rustService.close() }
        scope.cancel()
    }


    // ==========  MESHTASTIC PROTOCOL  ==========

    /**
     * Request full device configuration from the connected node.
     * Public so the UI can trigger a re-read.
     */
    fun refreshConfig() {
        if (!isConnected) return
        runCatching { rustService.refreshConfig() }
            .onFailure { Log.e(TAG, "refreshConfig failed", it) }
    }

    private fun handleFromRadio(fromRadio: MeshProtos.FromRadio) {
        when {
            fromRadio.hasMyInfo() -> {
                val info = fromRadio.myInfo
                myNodeNum = info.myNodeNum
                _myNodeId.value = MeshtasticBle.nodeNumToId(myNodeNum)
                Log.i(TAG, "My node: ${_myNodeId.value} (reboot_count=${info.rebootCount})")
            }

            fromRadio.hasMetadata() -> {
                // Modern firmware (≥2.3) reports firmware version here, not in MyNodeInfo.
                val md = fromRadio.metadata
                if (md.firmwareVersion.isNotEmpty()) {
                    _firmwareVersion.value = md.firmwareVersion
                }
                Log.i(TAG, "Device metadata: fw=${md.firmwareVersion} hw=${md.hwModel} role=${md.role}")
            }

            fromRadio.hasNodeInfo() -> {
                val ni = fromRadio.nodeInfo
                val node = MeshNode(
                    nodeId = MeshtasticBle.nodeNumToId(ni.num),
                    longName = if (ni.hasUser()) ni.user.longName else "Unknown",
                    shortName = if (ni.hasUser()) ni.user.shortName else "??",
                    lastHeard = ni.lastHeard.toLong(),
                    batteryLevel = if (ni.hasDeviceMetrics()) ni.deviceMetrics.batteryLevel.toInt() else null,
                    snr = if (ni.snr != 0f) ni.snr else null
                )
                nodeMap[ni.num] = node
                _nodes.value = nodeMap.values.toList()

                // If this is our own node, capture the User as owner
                if (ni.num == myNodeNum && ni.hasUser()) {
                    _owner.value = ni.user
                }
                // Track our own Position so the Fixed-Position editor can
                // pre-fill lat/lon/altitude with the firmware's current value.
                if (ni.num == myNodeNum && ni.hasPosition()) {
                    _myPosition.value = ni.position
                }
            }

            fromRadio.hasPacket() -> handleMeshPacket(fromRadio.packet)

            fromRadio.hasConfig() -> handleConfig(fromRadio.config)

            fromRadio.hasModuleConfig() -> handleModuleConfig(fromRadio.moduleConfig)

            fromRadio.hasChannel() -> {
                val ch = fromRadio.channel
                val current = _channels.value.toMutableList()
                val idx = current.indexOfFirst { it.index == ch.index }
                if (idx >= 0) current[idx] = ch else current.add(ch)
                _channels.value = current
            }

            fromRadio.configCompleteId != 0 -> {
                Log.i(TAG, "Config complete (id=${fromRadio.configCompleteId})")
                _configComplete.tryEmit(fromRadio.configCompleteId)
            }
        }
    }

    private fun handleConfig(config: MeshProtos.Config) {
        when {
            config.hasLora() -> {
                _radioConfig.value = config.lora
                Log.i(TAG, "LoRa config: region=${config.lora.region}, preset=${config.lora.modemPreset}")
            }
            config.hasDevice() -> {
                _deviceConfig.value = config.device
                Log.i(TAG, "Device config: role=${config.device.role}")
            }
            config.hasPosition() -> {
                _positionConfig.value = config.position
                Log.i(TAG, "Position config: gps=${config.position.gpsEnabled}, broadcast=${config.position.positionBroadcastSecs}s")
            }
            config.hasPower() -> {
                _powerConfig.value = config.power
                Log.i(TAG, "Power config: power_saving=${config.power.isPowerSaving}")
            }
            config.hasNetwork() -> {
                _networkConfig.value = config.network
                Log.i(TAG, "Network config: wifi=${config.network.wifiEnabled}")
            }
            config.hasDisplay() -> {
                _displayConfig.value = config.display
                Log.i(TAG, "Display config: screen_on=${config.display.screenOnSecs}s")
            }
            config.hasBluetooth() -> {
                _bluetoothConfig.value = config.bluetooth
                Log.i(TAG, "Bluetooth config: enabled=${config.bluetooth.enabled}, mode=${config.bluetooth.mode}")
            }
        }
    }

    private fun handleModuleConfig(moduleConfig: MeshProtos.ModuleConfig) {
        val key = when {
            moduleConfig.hasMqtt() -> "mqtt"
            moduleConfig.hasSerial() -> "serial"
            moduleConfig.hasExternalNotification() -> "external_notification"
            moduleConfig.hasStoreForward() -> "store_forward"
            moduleConfig.hasRangeTest() -> "range_test"
            moduleConfig.hasTelemetry() -> "telemetry"
            moduleConfig.hasCannedMessage() -> "canned_message"
            moduleConfig.hasAudio() -> "audio"
            moduleConfig.hasRemoteHardware() -> "remote_hardware"
            moduleConfig.hasNeighborInfo() -> "neighbor_info"
            moduleConfig.hasAmbientLighting() -> "ambient_lighting"
            moduleConfig.hasDetectionSensor() -> "detection_sensor"
            moduleConfig.hasPaxcounter() -> "paxcounter"
            else -> return
        }
        val current = _moduleConfigs.value.toMutableMap()
        current[key] = moduleConfig
        _moduleConfigs.value = current
        Log.i(TAG, "Module config received: $key")
    }

    private fun handleMeshPacket(packet: MeshProtos.MeshPacket) {
        if (!packet.hasDecoded()) return

        val data = packet.decoded
        val fromNum = packet.from
        val toNum = packet.getTo()
        val fromId = MeshtasticBle.nodeNumToId(fromNum)
        // Strict broadcast detection: ONLY 0xFFFFFFFF is broadcast.
        // (Now that `to` is fixed32 in mesh.proto, it parses correctly and
        // `0` is no longer a spurious default for DM packets.)
        val toId = if (toNum == MeshtasticBle.BROADCAST_ADDR) "broadcast"
                   else MeshtasticBle.nodeNumToId(toNum)
        val channel = packet.channel
        Log.d(TAG, "RX packet from=$fromNum ($fromId) to=$toNum ($toId) ch=$channel port=${data.portnum}")

        when (data.portnum) {
            PortNum.TEXT_MESSAGE_APP -> {
                val text = data.payload.toStringUtf8()
                _incomingTextMessages.tryEmit(IncomingText(from = fromId, to = toId, text = text, channel = channel))
                Log.i(TAG, "Text from $fromId to $toId (ch=$channel): $text")
            }

            PortNum.NODEINFO_APP -> {
                try {
                    val user = MeshProtos.User.parseFrom(data.payload)
                    val existing = nodeMap[fromNum]
                    nodeMap[fromNum] = (existing ?: MeshNode(nodeId = MeshtasticBle.nodeNumToId(fromNum))).copy(
                        longName = user.longName,
                        shortName = user.shortName
                    )
                    _nodes.value = nodeMap.values.toList()
                    if (fromNum == myNodeNum) {
                        _owner.value = user
                    }
                } catch (_: Exception) {}
            }

            PortNum.ADMIN_APP -> {
                // Handle admin responses (e.g. after a get_config_request)
                try {
                    val admin = MeshProtos.AdminMessage.parseFrom(data.payload)
                    handleAdminResponse(admin)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse AdminMessage", e)
                }
            }

            else -> {
                _incomingDataMessages.tryEmit(
                    IncomingData(from = fromId, to = toId, portNum = data.portnumValue, payload = data.payload.toByteArray(), channel = channel)
                )
            }
        }
    }

    private fun handleAdminResponse(admin: MeshProtos.AdminMessage) {
        when {
            admin.hasGetConfigResponse() -> handleConfig(admin.getConfigResponse)
            admin.hasGetModuleConfigResponse() -> handleModuleConfig(admin.getModuleConfigResponse)
            admin.hasGetChannelResponse() -> {
                val ch = admin.getChannelResponse
                val current = _channels.value.toMutableList()
                val idx = current.indexOfFirst { it.index == ch.index }
                if (idx >= 0) current[idx] = ch else current.add(ch)
                _channels.value = current
            }
            admin.hasGetOwnerResponse() -> {
                _owner.value = admin.getOwnerResponse
            }
        }
    }

    // ==========  SENDING  ==========

    fun sendText(text: String, destination: String? = null, channel: Int = 0): Boolean {
        if (!isConnected) return false

        val destNum = if (destination != null) {
            MeshtasticBle.nodeIdToNum(destination) ?: run {
                Log.e(TAG, "Invalid destination node ID: $destination")
                return false
            }
        } else {
            MeshtasticBle.BROADCAST_ADDR
        }

        return try {
            rustService.sendText(text, channel.toUInt(), destNum.toUInt())
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendText failed", e)
            false
        }
    }

    fun sendData(data: ByteArray, portNum: Int, destination: String? = null, channel: Int = 0): Boolean {
        if (!isConnected) return false

        val destNum = if (destination != null) {
            MeshtasticBle.nodeIdToNum(destination) ?: run {
                Log.e(TAG, "Invalid destination node ID: $destination")
                return false
            }
        } else {
            MeshtasticBle.BROADCAST_ADDR
        }

        return try {
            rustService.sendData(portNum, data, channel.toUInt(), destNum.toUInt(), true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendData failed", e)
            false
        }
    }


    // ==========  ADMIN CONFIG WRITES (local node only)  ==========

    private fun sendAdminMessage(admin: MeshProtos.AdminMessage): Boolean {
        return try {
            rustService.writeAdmin(admin.toByteArray())
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendAdminMessage failed", e)
            false
        }
    }


    /**
     * Write a Config section (LoRa, Device, Position, Power, Network, Display, Bluetooth)
     * to the local connected device.
     */
    fun writeConfig(config: MeshProtos.Config): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setSetConfig(config)
            .build()
        return sendAdminMessage(admin)
    }

    /**
     * Write a ModuleConfig section to the local connected device.
     */
    fun writeModuleConfig(moduleConfig: MeshProtos.ModuleConfig): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setSetModuleConfig(moduleConfig)
            .build()
        return sendAdminMessage(admin)
    }

    /**
     * Send a Position to be stored on the device as its manually-fixed
     * location. The device must also have PositionConfig.fixed_position=true
     * for this to take effect on subsequent broadcasts.
     */
    fun setFixedPosition(position: MeshProtos.Position): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setSetFixedPosition(position)
            .build()
        return sendAdminMessage(admin)
    }

    /**
     * Clear any previously-set fixed position on the device.
     */
    fun removeFixedPosition(): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setRemoveFixedPosition(true)
            .build()
        return sendAdminMessage(admin)
    }

    /**
     * Write a Channel configuration to the local connected device.
     */
    fun writeChannel(channel: MeshProtos.Channel): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setSetChannel(channel)
            .build()
        return sendAdminMessage(admin)
    }

    /**
     * Write the User/Owner info to the local connected device.
     */
    fun writeOwner(user: MeshProtos.User): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setSetOwner(user)
            .build()
        return sendAdminMessage(admin)
    }

    /**
     * Reboot the device after the specified number of seconds.
     */
    fun rebootDevice(seconds: Int = 5): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setRebootSeconds(seconds)
            .build()
        return sendAdminMessage(admin)
    }

    /**
     * Factory reset the device.
     */
    fun factoryReset(): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setFactoryResetConfig(1)
            .build()
        return sendAdminMessage(admin)
    }
}
