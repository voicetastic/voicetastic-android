package re.chasam.voicetastic.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import com.geeksville.mesh.MeshProtos
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import re.chasam.voicetastic.core.Ports
import re.chasam.voicetastic.model.MeshNode
import uniffi.voicetastic.MeshConfigListener
import uniffi.voicetastic.MeshConnectionState
import uniffi.voicetastic.MeshDataListener
import uniffi.voicetastic.MeshService
import uniffi.voicetastic.MeshStateListener
import uniffi.voicetastic.MeshTextListener

class MeshServiceManager(private val context: Context) {

    companion object {
        private const val TAG = "MeshServiceManager"
    }

    data class IncomingText(val from: String, val to: String, val text: String, val channel: Int = 0, val timestamp: Long = System.currentTimeMillis())
    data class IncomingData(val from: String, val to: String, val portNum: Int, val payload: ByteArray, val channel: Int = 0, val timestamp: Long = System.currentTimeMillis())

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val deviceDiscovery = DeviceDiscoveryManager(context)

    private val rustService: MeshService = MeshService()
    private var rustSession: RustMeshSession? = null

    // Nullable sentinel: `null` means MyNodeInfo hasn't been received yet.
    // Previously this was `Int = 0`, which collided with valid NodeInfo
    // bursts arriving before MyNodeInfo — any `ni.num == 0` entry would
    // match the filter below and clobber `_owner` with a remote user, and
    // any 0-num peer would land in the node list as `!00000000`.
    private var myNodeNum: Int? = null

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

    /**
     * `true` while a node-info scan is in progress. Held for a fixed window
     * after [requestNodeInfo] is invoked so the UI has time to swap to a
     * spinner and so peer NodeInfo replies have a chance to arrive.
     * Distinct from [isScanning] which tracks BLE device discovery.
     */
    private val _isNodeScanInProgress = MutableStateFlow(false)
    val isNodeScanInProgress: StateFlow<Boolean> = _isNodeScanInProgress.asStateFlow()
    private var nodeScanJob: Job? = null

    /**
     * When non-zero, the state listener treats CONFIGURING (which Rust
     * fires during a refresh burst) as a no-op until this absolute
     * timestamp passes — i.e. user-initiated refreshes don't visually
     * disconnect the UI. Set by [requestNodeInfo]; checked by the
     * state listener in [init].
     */
    @Volatile private var suppressConnectingUntilMs: Long = 0L

    val discoveredDevices: StateFlow<List<BluetoothDevice>> = deviceDiscovery.discoveredBleDevices
    val isScanning: StateFlow<Boolean> = deviceDiscovery.isScanning

    // ==========  USB TRANSPORT  ==========

    enum class TransportType { NONE, BLE, USB }

    private val usbTransport: UsbMeshTransport = UsbMeshTransport(context)
    private var usbActive: Boolean = false

    val usbState: StateFlow<UsbMeshTransport.State> = usbTransport.state
    val usbConnectedDevice: StateFlow<UsbDevice?> = usbTransport.connectedDevice
    val usbErrors: SharedFlow<String> = usbTransport.errors

    private val _activeTransport = MutableStateFlow(TransportType.NONE)
    val activeTransport: StateFlow<TransportType> = _activeTransport.asStateFlow()

    // NOTE: the listener-registration `init { ... }` block lives further down
    // in the file, AFTER every StateFlow / nodeMap field is declared. The
    // Rust side fires `on_state(Disconnected)` synchronously during
    // `setStateListener`, which then re-enters our Kotlin code and calls
    // `clearSessionState()` — that touches `nodeMap`, `_radioConfig`, ...,
    // so they MUST already be initialised. Kotlin runs initialisers in
    // declaration order, hence the deliberate placement.

    fun discoverUsbDevices(): List<UsbSerialDriver> = deviceDiscovery.discoverUsbDevices()

    fun usbHasPermission(device: UsbDevice): Boolean = usbTransport.hasPermission(device)

    fun requestUsbPermission(device: UsbDevice, onResult: (Boolean) -> Unit) =
        usbTransport.requestPermission(device, onResult)

    fun connectUsb(driver: UsbSerialDriver): Boolean {
        runCatching { rustSession?.close() }
        rustSession = null
        usbActive = true
        _activeTransport.value = TransportType.USB
        _connectionState.value = "CONNECTING"
        configBurstInProgress = true
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
        clearSessionState()
        _connectionState.value = "DISCONNECTED"
        Log.i(TAG, "Disconnected (USB)")
    }

    private fun clearSessionState() {
        myNodeNum = null
        configBurstInProgress = false
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

    fun onUsbDeviceDetached(device: UsbDevice) = usbTransport.onDeviceDetached(device)

    private fun mergeNodeFromUser(nodeNum: Int, payload: ByteArray, rxTime: Long) {
        if (nodeNum == 0 || nodeNum == MeshtasticBle.BROADCAST_ADDR) return
        val user = runCatching { MeshProtos.User.parseFrom(payload) }.getOrNull() ?: return
        val existing = nodeMap[nodeNum]
        val node = MeshNode(
            nodeId = MeshtasticBle.nodeNumToId(nodeNum),
            longName = user.longName.ifEmpty { existing?.longName ?: "Unknown" },
            shortName = user.shortName.ifEmpty { existing?.shortName ?: "??" },
            lastHeard = if (rxTime != 0L) rxTime else existing?.lastHeard ?: 0L,
            batteryLevel = existing?.batteryLevel,
            snr = existing?.snr,
        )
        nodeMap[nodeNum] = node
        _nodes.value = nodeMap.values.toList()
    }

    private fun touchNodeLastHeard(nodeNum: Int, rxTime: Long) {
        if (nodeNum == 0 || nodeNum == MeshtasticBle.BROADCAST_ADDR) return
        if (rxTime == 0L && nodeMap.containsKey(nodeNum)) return
        val existing = nodeMap[nodeNum]
        val node = (existing ?: MeshNode(nodeId = MeshtasticBle.nodeNumToId(nodeNum)))
            .copy(lastHeard = if (rxTime != 0L) rxTime else existing?.lastHeard ?: 0L)
        nodeMap[nodeNum] = node
        _nodes.value = nodeMap.values.toList()
    }

    // --- Per-section config flows ---
    private val _radioConfig = MutableStateFlow<MeshProtos.Config.LoRaConfig?>(null)
    val radioConfig: StateFlow<MeshProtos.Config.LoRaConfig?> = _radioConfig.asStateFlow()

    private val _deviceConfig = MutableStateFlow<MeshProtos.Config.DeviceConfig?>(null)
    val deviceConfig: StateFlow<MeshProtos.Config.DeviceConfig?> = _deviceConfig.asStateFlow()

    private val _positionConfig = MutableStateFlow<MeshProtos.Config.PositionConfig?>(null)
    val positionConfig: StateFlow<MeshProtos.Config.PositionConfig?> = _positionConfig.asStateFlow()

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

    private val _configComplete = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val configComplete: SharedFlow<Int> = _configComplete.asSharedFlow()

    private val nodeMap = mutableMapOf<Int, MeshNode>()

    @Volatile private var configBurstInProgress = false

    val isConnected: Boolean
        get() = _connectionState.value == "CONNECTED" || _connectionState.value == "CONNECTING"

    init {
        rustService.setStateListener(object : MeshStateListener {
            override fun onState(state: MeshConnectionState) {
                val mapped = when (state) {
                    MeshConnectionState.CONNECTED, MeshConnectionState.READY -> "CONNECTED"
                    MeshConnectionState.CONNECTING, MeshConnectionState.CONFIGURING -> "CONNECTING"
                    MeshConnectionState.DISCONNECTED -> "DISCONNECTED"
                }
                Log.d(TAG, "Rust state -> $state (mapped=$mapped)")

                // Suppress CONNECTING (which Rust emits as CONFIGURING during a
                // user-initiated refresh) while we're already connected and inside
                // the scan window. Without this, tapping Scan Nodes makes the UI
                // fall back to the device picker for the duration of the burst.
                // DISCONNECTED is never suppressed — actual transport loss must
                // still flow through.
                val now = System.currentTimeMillis()
                if (mapped == "CONNECTING"
                    && _connectionState.value == "CONNECTED"
                    && now < suppressConnectingUntilMs
                ) {
                    Log.d(TAG, "state: suppressing transient CONNECTING during scan refresh")
                    return
                }

                _connectionState.value = mapped
                if (state == MeshConnectionState.DISCONNECTED) {
                    clearSessionState()
                    _activeTransport.value = TransportType.NONE
                    usbActive = false
                }
            }
        })

        rustService.setTextListener(object : MeshTextListener {
            override fun onText(message: uniffi.voicetastic.IncomingTextMsg) {
                val toInt = message.to.toInt()
                val isBroadcast = toInt == MeshtasticBle.BROADCAST_ADDR
                val toId = if (isBroadcast) "broadcast" else MeshtasticBle.nodeNumToId(toInt)
                Log.d(
                    TAG,
                    "rx text from=${message.fromId} to=$toId (raw=0x${toInt.toUInt().toString(16)}) ch=${message.channel} bytes=${message.text.length}"
                )
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
                val fromNum = message.from.toInt()
                val fromId = MeshtasticBle.nodeNumToId(fromNum)
                val toInt = message.to.toInt()
                val isBroadcast = toInt == MeshtasticBle.BROADCAST_ADDR
                val toId = if (isBroadcast) "broadcast" else MeshtasticBle.nodeNumToId(toInt)
                Log.d(
                    TAG,
                    "rx data from=$fromId to=$toId (raw=0x${toInt.toUInt().toString(16)}) port=${message.portnum} ch=${message.channel} len=${message.payload.size}"
                )

                when (message.portnum) {
                    Ports.NODEINFO_APP -> mergeNodeFromUser(fromNum, message.payload, message.rxTime.toLong())
                    Ports.POSITION_APP -> touchNodeLastHeard(fromNum, message.rxTime.toLong())
                }

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
                        _myNodeId.value = MeshtasticBle.nodeNumToId(info.myNodeNum)
                    }
            }

            override fun onNodeInfo(encoded: ByteArray) {
                runCatching { MeshProtos.NodeInfo.parseFrom(encoded) }
                    .onSuccess { ni ->
                        // `0` is never a real Meshtastic node num; treat it as a
                        // stale / malformed entry and drop it. Letting it through
                        // produced "!00000000" rows in the node list and let
                        // pre-MyNodeInfo entries hijack `_owner`.
                        if (ni.num == 0) {
                            Log.d(TAG, "onNodeInfo: dropping num=0 entry")
                            return@onSuccess
                        }
                        val nodeIdStr = MeshtasticBle.nodeNumToId(ni.num)
                        val longName = if (ni.hasUser()) ni.user.longName else "Unknown"
                        val shortName = if (ni.hasUser()) ni.user.shortName else "??"
                        Log.d(
                            TAG,
                            "onNodeInfo: $nodeIdStr long='$longName' short='$shortName' " +
                                "hasUser=${ni.hasUser()} lastHeard=${ni.lastHeard} " +
                                "burst=$configBurstInProgress mapSizeBefore=${nodeMap.size}"
                        )
                        val node = MeshNode(
                            nodeId = nodeIdStr,
                            longName = longName,
                            shortName = shortName,
                            lastHeard = ni.lastHeard.toLong(),
                            batteryLevel = if (ni.hasDeviceMetrics()) ni.deviceMetrics.batteryLevel.toInt() else null,
                            snr = if (ni.snr != 0f) ni.snr else null,
                        )
                        nodeMap[ni.num] = node
                        if (!configBurstInProgress) {
                            _nodes.value = nodeMap.values.toList()
                        }
                        val my = myNodeNum
                        if (my != null && ni.num == my && ni.hasUser()) _owner.value = ni.user
                        if (my != null && ni.num == my && ni.hasPosition()) _myPosition.value = ni.position
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
                configBurstInProgress = false
                _nodes.value = nodeMap.values.toList()
                _configComplete.tryEmit(nonce.toInt())
            }
        })
    }

    // ==========  BLE SCANNING  ==========

    fun startScan() = deviceDiscovery.startBleScan()
    fun stopScan() = deviceDiscovery.stopBleScan()

    // ==========  BLE CONNECTION  ==========

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan()
        _connectionState.value = "CONNECTING"
        _activeTransport.value = TransportType.BLE
        configBurstInProgress = true
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
        deviceDiscovery.destroy()
        runCatching { rustService.close() }
        scope.cancel()
    }

    /**
     * Lazily-resolved handle to the Rust voice sender. The Rust side caches
     * one instance per `MeshService`, so we just forward each call —
     * `voiceSender()` is cheap to re-invoke. Returns null until connected,
     * to mirror the explicit `isConnected` check the rest of this class uses.
     */
    fun voiceSender(): uniffi.voicetastic.VoiceSender? =
        if (isConnected) runCatching { rustService.voiceSender() }.getOrNull() else null

    // ==========  MESHTASTIC PROTOCOL  ==========

    fun refreshConfig() {
        if (!isConnected) return
        runCatching { rustService.refreshConfig() }
            .onFailure { Log.e(TAG, "refreshConfig failed", it) }
    }

    /**
     * Broadcast our own NodeInfo to nudge peers into announcing themselves.
     *
     * Unlike [refreshConfig], this does NOT re-burst the local device's
     * configuration (and does NOT flip the UI back to `CONNECTING`). It just
     * sends a single NodeInfo packet on the mesh; nearby nodes typically
     * pick it up, update their own DB, and re-broadcast their NodeInfo at
     * their next interval — at which point we hear them through the
     * existing inbound NodeInfo path.
     *
     * If the local owner record hasn't arrived yet, falls back to a minimal
     * `User` proto carrying just our node id — peers still get a usable
     * entry (firmware fills the `from` field on the wire). The previous
     * implementation silently no-op'd in that race, which made the Scan
     * button look broken right after connect.
     *
     * Holds [isNodeScanInProgress] true for ~10 s so the UI can show a
     * spinner and so peers have time to reply.
     */
    fun requestNodeInfo(): Boolean {
        if (!isConnected) {
            Log.w(TAG, "requestNodeInfo: not connected")
            return false
        }
        val user = _owner.value ?: _myNodeId.value?.let { id ->
            Log.d(TAG, "requestNodeInfo: owner not yet known; using minimal User(id=$id)")
            MeshProtos.User.newBuilder().setId(id).build()
        } ?: run {
            Log.w(TAG, "requestNodeInfo: my node id unknown; aborting scan")
            return false
        }
        return try {
            // Two-pronged scan:
            //
            // 1. Refresh the radio's own node DB. The connected radio has been
            //    hearing peers passively (text msgs, position broadcasts,
            //    ambient NodeInfo cycles) and stores them in an internal
            //    table. `refreshConfig` re-bursts that table, which the bridge
            //    fans out as `onNodeInfo` callbacks. This is what surfaces
            //    peers we already know about but haven't displayed yet.
            //
            // 2. Broadcast our own NodeInfo with want_response=true. Peers
            //    on firmware that honors this reply with their own NodeInfo,
            //    which arrives via the same path.
            //
            // The state-listener's suppress check above prevents (1) from
            // making the UI flicker back to the device picker.
            suppressConnectingUntilMs = System.currentTimeMillis() + 15_000L
            runCatching { rustService.refreshConfig() }
                .onFailure { Log.e(TAG, "requestNodeInfo: refreshConfig failed", it) }

            val pktId = rustService.sendData(
                Ports.NODEINFO_APP,
                user.toByteArray(),
                0u,
                null,
                false, // want_ack
                true,  // want_response
            )
            Log.d(TAG, "requestNodeInfo: refresh+broadcast fired (pktId=$pktId, ownerKnown=${_owner.value != null})")

            // Visual scan window: 10 s covers the refresh burst + typical
            // peer reply latency, and self-clears so the button reverts.
            nodeScanJob?.cancel()
            nodeScanJob = scope.launch {
                _isNodeScanInProgress.value = true
                try { delay(10_000) } finally { _isNodeScanInProgress.value = false }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "requestNodeInfo failed", e)
            false
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

    // ==========  SENDING  ==========

    fun sendText(text: String, destination: String? = null, channel: Int = 0): Boolean {
        if (!isConnected) {
            Log.w(TAG, "sendText dropped: not connected (state=${_connectionState.value}, transport=${_activeTransport.value})")
            return false
        }

        val destUInt: UInt? = if (destination != null) {
            MeshtasticBle.nodeIdToNum(destination)?.toUInt() ?: run {
                Log.e(TAG, "Invalid destination node ID: $destination")
                return false
            }
        } else {
            null
        }

        return try {
            val id = rustService.sendText(text, channel.toUInt(), destUInt)
            Log.d(
                TAG,
                "sendText ok (id=$id, dest=${destination ?: "broadcast"}, destNum=${destUInt?.toString(16)?.let { "0x$it" } ?: "broadcast"}, ch=$channel, bytes=${text.length})"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendText failed", e)
            false
        }
    }

    /**
     * Send an arbitrary data packet on the mesh.
     *
     * @param wantAck request firmware-side hop ACKs for this packet.
     *   Default is **false** because most uses of this entry point are
     *   control-class traffic (voice NACK reframes today) where the
     *   per-packet ACK round-trip just adds mesh congestion without
     *   any sender-side benefit — voice retransmit is FEC-driven, not
     *   ACK-driven. Set to true only when the caller genuinely needs
     *   delivery confirmation (e.g. user-visible text or admin ops).
     */
    fun sendData(
        data: ByteArray,
        portNum: Int,
        destination: String? = null,
        channel: Int = 0,
        wantAck: Boolean = false,
        wantResponse: Boolean = false,
    ): Boolean {
        if (!isConnected) {
            Log.w(TAG, "sendData dropped: not connected (state=${_connectionState.value}, transport=${_activeTransport.value})")
            return false
        }

        val destUInt: UInt? = if (destination != null) {
            MeshtasticBle.nodeIdToNum(destination)?.toUInt() ?: run {
                Log.e(TAG, "Invalid destination node ID: $destination")
                return false
            }
        } else {
            null
        }

        return try {
            val id = rustService.sendData(portNum, data, channel.toUInt(), destUInt, wantAck, wantResponse)
            Log.d(TAG, "sendData ok (id=$id, port=$portNum, dest=${destination ?: "broadcast"}, ch=$channel, len=${data.size}, wantAck=$wantAck, wantResponse=$wantResponse)")
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

    fun writeConfig(config: MeshProtos.Config): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setSetConfig(config)
            .build()
        return sendAdminMessage(admin)
    }

    fun writeModuleConfig(moduleConfig: MeshProtos.ModuleConfig): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setSetModuleConfig(moduleConfig)
            .build()
        return sendAdminMessage(admin)
    }

    fun setFixedPosition(position: MeshProtos.Position): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setSetFixedPosition(position)
            .build()
        return sendAdminMessage(admin)
    }

    fun removeFixedPosition(): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setRemoveFixedPosition(true)
            .build()
        return sendAdminMessage(admin)
    }

    fun writeChannel(channel: MeshProtos.Channel): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setSetChannel(channel)
            .build()
        return sendAdminMessage(admin)
    }

    fun writeOwner(user: MeshProtos.User): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setSetOwner(user)
            .build()
        return sendAdminMessage(admin)
    }

    fun rebootDevice(seconds: Int = 5): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setRebootSeconds(seconds)
            .build()
        return sendAdminMessage(admin)
    }

    fun factoryReset(): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setFactoryResetConfig(1)
            .build()
        return sendAdminMessage(admin)
    }
}
