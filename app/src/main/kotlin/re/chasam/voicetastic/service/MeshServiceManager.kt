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
import uniffi.voicetastic.AckResultKind
import uniffi.voicetastic.MeshAckListener
import uniffi.voicetastic.MeshConfigListener
import uniffi.voicetastic.MeshConnectionState
import uniffi.voicetastic.MeshDataListener
import uniffi.voicetastic.MeshService
import uniffi.voicetastic.MeshStateListener
import uniffi.voicetastic.MeshTextListener

class MeshServiceManager(private val context: Context) : MeshFacade {

    companion object {
        private const val TAG = "MeshServiceManager"
        /**
         * Cap on retained Debug log entries before FIFO eviction. ~500
         * lines covers a few minutes of dense radio activity without
         * leaking memory.
         */
        private const val DEBUG_LOG_CAP = 500
        /**
         * Cap on retained per-node telemetry samples before FIFO
         * eviction. 60 samples covers ~30 min of NodeInfo updates at
         * a typical broadcast cadence; enough for a sparkline trend
         * without leaking memory.
         */
        private const val NODE_HISTORY_CAP = 60

        /**
         * Auto-reconnect backoff bounds (BLE only). After an unexpected drop
         * (e.g. the radio rebooting) we retry the last device starting at
         * [RECONNECT_INITIAL_DELAY_MS] and doubling up to
         * [RECONNECT_MAX_DELAY_MS], indefinitely, until we reconnect or the
         * user deliberately disconnects.
         */
        private const val RECONNECT_INITIAL_DELAY_MS = 2_000L
        private const val RECONNECT_MAX_DELAY_MS = 30_000L
    }
    // IncomingText / IncomingData / TransportType moved to MeshTypes.kt
    // so the [MeshFacade] interface can reference them without
    // depending on this concrete class.

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val deviceDiscovery = DeviceDiscoveryManager(context)
    private val networkDiscovery = NetworkDiscoveryManager(context)

    private val rustService: MeshService = MeshService()
    private var rustSession: RustMeshSession? = null

    // --- Auto-reconnect (BLE only) ---
    // `lastBleDevice` is the device we should fall back to after an unexpected
    // drop; `autoReconnect` is the user's intent to stay connected (set on a
    // BLE connect, cleared on a deliberate disconnect) and gates the retry
    // loop so we never reconnect after the user has chosen to disconnect.
    private var lastBleDevice: BluetoothDevice? = null
    @Volatile private var autoReconnect = false
    private var reconnectJob: Job? = null

    // Nullable sentinel: `null` means MyNodeInfo hasn't been received yet.
    // Previously this was `Int = 0`, which collided with valid NodeInfo
    // bursts arriving before MyNodeInfo — any `ni.num == 0` entry would
    // match the filter below and clobber `_owner` with a remote user, and
    // any 0-num peer would land in the node list as `!00000000`.
    private var myNodeNum: Int? = null

    // --- Public state flows ---
    private val _connectionState = MutableStateFlow("DISCONNECTED")
    override val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    private val _nodes = MutableStateFlow<List<MeshNode>>(emptyList())
    override val nodes: StateFlow<List<MeshNode>> = _nodes.asStateFlow()

    private val _incomingTextMessages = MutableSharedFlow<IncomingText>(extraBufferCapacity = 64)
    override val incomingTextMessages: SharedFlow<IncomingText> = _incomingTextMessages.asSharedFlow()

    private val _incomingDataMessages = MutableSharedFlow<IncomingData>(extraBufferCapacity = 64)
    override val incomingDataMessages: SharedFlow<IncomingData> = _incomingDataMessages.asSharedFlow()

    private val _ackEvents = MutableSharedFlow<MeshAckEvent>(extraBufferCapacity = 64)
    override val ackEvents: SharedFlow<MeshAckEvent> = _ackEvents.asSharedFlow()

    private val _debugLog = MutableStateFlow<List<DebugEntry>>(emptyList())
    override val debugLog: StateFlow<List<DebugEntry>> = _debugLog.asStateFlow()

    private val _nodeHistory = MutableStateFlow<Map<Int, List<NodeSample>>>(emptyMap())
    override val nodeHistory: StateFlow<Map<Int, List<NodeSample>>> = _nodeHistory.asStateFlow()

    private fun pushNodeSample(nodeNum: Int, battery: Int?, snr: Float) {
        val current = _nodeHistory.value
        val buf = current[nodeNum].orEmpty()
        val last = buf.lastOrNull()
        // Skip when neither metric moved since the last sample — keeps
        // the ring buffer trend-shaped instead of repeating values.
        if (last != null && last.battery == battery && kotlin.math.abs(last.snr - snr) < 0.01f) return
        val next = (buf + NodeSample(System.currentTimeMillis(), battery, snr))
            .takeLast(NODE_HISTORY_CAP)
        _nodeHistory.value = current + (nodeNum to next)
    }

    /**
     * Append a [DebugEntry] to the in-app log, evicting FIFO past
     * [DEBUG_LOG_CAP]. Called from inside the listener callbacks for
     * every event worth surfacing on the Debug screen.
     */
    private fun pushDebug(source: String, message: String, level: DebugLevel = DebugLevel.Info) {
        val current = _debugLog.value
        val capped = if (current.size >= DEBUG_LOG_CAP) {
            current.drop(current.size - DEBUG_LOG_CAP + 1)
        } else {
            current
        }
        _debugLog.value = capped + DebugEntry(level = level, source = source, message = message)
    }

    fun clearDebugLog() {
        _debugLog.value = emptyList()
    }

    private val _myNodeId = MutableStateFlow<String?>(null)
    override val myNodeId: StateFlow<String?> = _myNodeId.asStateFlow()

    private val _selfNode = MutableStateFlow<MeshNode?>(null)
    override val selfNode: StateFlow<MeshNode?> = _selfNode.asStateFlow()

    private val _firmwareVersion = MutableStateFlow<String?>(null)
    override val firmwareVersion: StateFlow<String?> = _firmwareVersion.asStateFlow()

    /**
     * `true` while a node-info scan is in progress. Held for a fixed window
     * after [requestNodeInfo] is invoked so the UI has time to swap to a
     * spinner and so peer NodeInfo replies have a chance to arrive.
     * Distinct from [isScanning] which tracks BLE device discovery.
     */
    private val _isNodeScanInProgress = MutableStateFlow(false)
    override val isNodeScanInProgress: StateFlow<Boolean> = _isNodeScanInProgress.asStateFlow()

    override val discoveredNetworkDevices: StateFlow<List<NetworkDevice>> = networkDiscovery.devices
    override val isNetworkScanning: StateFlow<Boolean> = networkDiscovery.isDiscovering
    private var nodeScanJob: Job? = null

    /**
     * When non-zero, the state listener treats CONFIGURING (which Rust
     * fires during a refresh burst) as a no-op until this absolute
     * timestamp passes — i.e. user-initiated refreshes don't visually
     * disconnect the UI. Set by [requestNodeInfo]; checked by the
     * state listener in [init].
     */
    @Volatile private var suppressConnectingUntilMs: Long = 0L

    override val discoveredDevices: StateFlow<List<BluetoothDevice>> = deviceDiscovery.discoveredBleDevices
    override val isScanning: StateFlow<Boolean> = deviceDiscovery.isScanning

    // ==========  USB TRANSPORT  ==========
    // TransportType moved to MeshTypes.kt (top-level).

    private val usbTransport: UsbMeshTransport = UsbMeshTransport(context)
    private var usbActive: Boolean = false

    override val usbState: StateFlow<UsbMeshTransport.State> = usbTransport.state
    override val usbConnectedDevice: StateFlow<UsbDevice?> = usbTransport.connectedDevice
    override val usbErrors: SharedFlow<String> = usbTransport.errors

    private val _activeTransport = MutableStateFlow(TransportType.NONE)
    override val activeTransport: StateFlow<TransportType> = _activeTransport.asStateFlow()

    // NOTE: the listener-registration `init { ... }` block lives further down
    // in the file, AFTER every StateFlow / nodeMap field is declared. The
    // Rust side fires `on_state(Disconnected)` synchronously during
    // `setStateListener`, which then re-enters our Kotlin code and calls
    // `clearSessionState()` — that touches `nodeMap`, `_radioConfig`, ...,
    // so they MUST already be initialised. Kotlin runs initialisers in
    // declaration order, hence the deliberate placement.

    override fun discoverUsbDevices(): List<UsbSerialDriver> = deviceDiscovery.discoverUsbDevices()

    override fun usbHasPermission(device: UsbDevice): Boolean = usbTransport.hasPermission(device)

    override fun requestUsbPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        usbTransport.requestPermission(device, onResult)
    }

    override fun connectUsb(driver: UsbSerialDriver): Boolean {
        // USB re-enumerates with a fresh handle on reboot (the OS attach
        // broadcast drives reconnection), so we don't run the BLE retry loop
        // here; just make sure any in-flight one is stood down.
        autoReconnect = false
        lastBleDevice = null
        cancelReconnect()
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

    override fun disconnectUsb() {
        autoReconnect = false
        lastBleDevice = null
        cancelReconnect()
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
        _selfNode.value = null
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
        _mqttConfig.value = null
        _channels.value = emptyList()
        _owner.value = null
        _moduleConfigs.value = emptyMap()
    }

    override fun onUsbDeviceDetached(device: UsbDevice) {
        usbTransport.onDeviceDetached(device)
    }


    private fun mergeNodeFromUser(nodeNum: Int, payload: ByteArray, rxTime: Long) {
        if (nodeNum == 0 || nodeNum == MeshtasticBle.BROADCAST_ADDR) return
        val user = runCatching { MeshProtos.User.parseFrom(payload) }.getOrNull() ?: return
        val existing = nodeMap[nodeNum]
        val nodeId = MeshtasticBle.nodeNumToId(nodeNum)
        val base = existing ?: MeshNode(nodeId = nodeId)
        val node = base.copy(
            nodeId = nodeId,
            longName = user.longName.ifEmpty { base.longName },
            shortName = user.shortName.ifEmpty { base.shortName },
            lastHeard = if (rxTime != 0L) rxTime else base.lastHeard,
            hwModel = user.hwModelValue,
            role = user.roleValue,
            isLicensed = user.isLicensed,
        )
        nodeMap[nodeNum] = node
        if (myNodeNum != null && nodeNum == myNodeNum) _selfNode.value = node
        _nodes.value = nodeMap.values.toList()
    }

    private fun touchNodeLastHeard(nodeNum: Int, rxTime: Long) {
        if (nodeNum == 0 || nodeNum == MeshtasticBle.BROADCAST_ADDR) return
        if (rxTime == 0L && nodeMap.containsKey(nodeNum)) return
        val existing = nodeMap[nodeNum]
        val nodeId = MeshtasticBle.nodeNumToId(nodeNum)
        val node = (existing ?: MeshNode(nodeId = nodeId))
            .copy(
                nodeId = nodeId,
                lastHeard = if (rxTime != 0L) rxTime else existing?.lastHeard ?: 0L
            )
        nodeMap[nodeNum] = node
        if (myNodeNum != null && nodeNum == myNodeNum) _selfNode.value = node
        _nodes.value = nodeMap.values.toList()
    }

    /**
     * Merge a live POSITION_APP broadcast into the node's coordinates. These
     * packets are the only position source for nodes that broadcast on the
     * fly (rather than via a NodeInfo carrying an embedded position), and for
     * our own node they feed [myPosition] (used to pre-fill the fixed-position
     * settings and the map's "you" pin). A (0, 0) payload means "no fix", so
     * the existing coordinates are kept; lastHeard is always bumped.
     */
    private fun mergeNodeFromPosition(nodeNum: Int, payload: ByteArray, rxTime: Long) {
        if (nodeNum == 0 || nodeNum == MeshtasticBle.BROADCAST_ADDR) return
        val pos = runCatching { MeshProtos.Position.parseFrom(payload) }.getOrNull()
            ?: return touchNodeLastHeard(nodeNum, rxTime)
        val hasFix = pos.latitudeI != 0 || pos.longitudeI != 0
        val existing = nodeMap[nodeNum]
        val nodeId = MeshtasticBle.nodeNumToId(nodeNum)
        val node = (existing ?: MeshNode(nodeId = nodeId)).copy(
            nodeId = nodeId,
            latitude = if (hasFix) pos.latitudeI / 1e7 else existing?.latitude,
            longitude = if (hasFix) pos.longitudeI / 1e7 else existing?.longitude,
            altitude = if (hasFix) pos.altitude else existing?.altitude,
            lastHeard = if (rxTime != 0L) rxTime else existing?.lastHeard ?: 0L,
        )
        nodeMap[nodeNum] = node
        if (myNodeNum != null && nodeNum == myNodeNum) {
            _selfNode.value = node
            if (hasFix) _myPosition.value = pos
        }
        _nodes.value = nodeMap.values.toList()
    }

    // --- Per-section config flows ---
    private val _radioConfig = MutableStateFlow<MeshProtos.Config.LoRaConfig?>(null)
    override val radioConfig: StateFlow<MeshProtos.Config.LoRaConfig?> = _radioConfig.asStateFlow()

    private val _deviceConfig = MutableStateFlow<MeshProtos.Config.DeviceConfig?>(null)
    override val deviceConfig: StateFlow<MeshProtos.Config.DeviceConfig?> = _deviceConfig.asStateFlow()

    private val _positionConfig = MutableStateFlow<MeshProtos.Config.PositionConfig?>(null)
    override val positionConfig: StateFlow<MeshProtos.Config.PositionConfig?> = _positionConfig.asStateFlow()

    private val _myPosition = MutableStateFlow<MeshProtos.Position?>(null)
    override val myPosition: StateFlow<MeshProtos.Position?> = _myPosition.asStateFlow()

    private val _powerConfig = MutableStateFlow<MeshProtos.Config.PowerConfig?>(null)
    override val powerConfig: StateFlow<MeshProtos.Config.PowerConfig?> = _powerConfig.asStateFlow()

    private val _networkConfig = MutableStateFlow<MeshProtos.Config.NetworkConfig?>(null)
    override val networkConfig: StateFlow<MeshProtos.Config.NetworkConfig?> = _networkConfig.asStateFlow()

    private val _displayConfig = MutableStateFlow<MeshProtos.Config.DisplayConfig?>(null)
    override val displayConfig: StateFlow<MeshProtos.Config.DisplayConfig?> = _displayConfig.asStateFlow()

    private val _bluetoothConfig = MutableStateFlow<MeshProtos.Config.BluetoothConfig?>(null)
    override val bluetoothConfig: StateFlow<MeshProtos.Config.BluetoothConfig?> = _bluetoothConfig.asStateFlow()

    private val _mqttConfig = MutableStateFlow<MeshProtos.ModuleConfig.MQTTConfig?>(null)
    override val mqttConfig: StateFlow<MeshProtos.ModuleConfig.MQTTConfig?> = _mqttConfig.asStateFlow()

    private val _channels = MutableStateFlow<List<MeshProtos.Channel>>(emptyList())
    override val channels: StateFlow<List<MeshProtos.Channel>> = _channels.asStateFlow()

    private val _owner = MutableStateFlow<MeshProtos.User?>(null)
    override val owner: StateFlow<MeshProtos.User?> = _owner.asStateFlow()

    private val _moduleConfigs = MutableStateFlow<Map<String, MeshProtos.ModuleConfig>>(emptyMap())
    override val moduleConfigs: StateFlow<Map<String, MeshProtos.ModuleConfig>> = _moduleConfigs.asStateFlow()

    private val _configComplete = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    override val configComplete: SharedFlow<Int> = _configComplete.asSharedFlow()

    private val nodeMap = mutableMapOf<Int, MeshNode>()

    @Volatile private var configBurstInProgress = false

    override val isConnected: Boolean
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
                pushDebug("transport", "state → $state")

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
                    // Unexpected drop (radio reboot, link loss): start retrying
                    // the last BLE device. No-op after a deliberate disconnect,
                    // which clears `autoReconnect` first.
                    maybeScheduleReconnect()
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
                pushDebug(
                    "mesh",
                    "rx text from=${message.fromId} to=$toId ch=${message.channel} (${message.text.length}B)",
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
                    Ports.POSITION_APP -> mergeNodeFromPosition(fromNum, message.payload, message.rxTime.toLong())
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

        rustService.setAckListener(object : MeshAckListener {
            override fun onAck(packetId: UInt, result: AckResultKind) {
                val status = when (result) {
                    AckResultKind.DELIVERED -> DeliveryStatus.Delivered
                    AckResultKind.FAILED -> DeliveryStatus.Failed
                    AckResultKind.TIMED_OUT -> DeliveryStatus.TimedOut
                    AckResultKind.CANCELLED -> DeliveryStatus.Cancelled
                }
                val level = when (status) {
                    DeliveryStatus.Delivered -> DebugLevel.Info
                    DeliveryStatus.Failed,
                    DeliveryStatus.TimedOut,
                    DeliveryStatus.Cancelled -> DebugLevel.Warn
                    DeliveryStatus.Pending -> DebugLevel.Info
                }
                pushDebug(
                    "mesh",
                    "ack id=0x${packetId.toString(16)} → $status",
                    level,
                )
                _ackEvents.tryEmit(MeshAckEvent(packetId, status))
            }
        })

        rustService.setConfigListener(object : MeshConfigListener {
            override fun onMyInfo(encoded: ByteArray) {
                runCatching { MeshProtos.MyNodeInfo.parseFrom(encoded) }
                    .onSuccess { info ->
                        // `0` is never a real node num. A 0 here means the
                        // `my_node_num` field was lost on the wire (e.g. a
                        // proto wire-type mismatch in the native bridge) —
                        // letting it through stamps `_myNodeId`/`_selfNode`
                        // with "!00000000", which then surfaces in the app
                        // nav. Ignore it and wait for a valid MyNodeInfo.
                        if (info.myNodeNum == 0) {
                            Log.w(TAG, "onMyInfo: ignoring my_node_num=0 (field likely dropped on decode)")
                            return@onSuccess
                        }
                        myNodeNum = info.myNodeNum
                        _myNodeId.value = MeshtasticBle.nodeNumToId(info.myNodeNum)
                        nodeMap[info.myNodeNum]?.let { _selfNode.value = it }
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
                        val existing = nodeMap[ni.num]
                        // A NodeInfo without a user block (e.g. a position-only
                        // broadcast from our own node) must NOT wipe a name we
                        // already learned. Keep the prior name unless this packet
                        // actually carries a non-empty one; only fall back to the
                        // placeholders when we've never seen a name.
                        val longName = ni.user.longName
                            .takeIf { ni.hasUser() && it.isNotEmpty() }
                            ?: existing?.longName ?: "Unknown"
                        val shortName = ni.user.shortName
                            .takeIf { ni.hasUser() && it.isNotEmpty() }
                            ?: existing?.shortName ?: "??"
                        Log.d(
                            TAG,
                            "onNodeInfo: $nodeIdStr long='$longName' short='$shortName' " +
                                "hasUser=${ni.hasUser()} lastHeard=${ni.lastHeard} " +
                                "burst=$configBurstInProgress mapSizeBefore=${nodeMap.size}"
                        )
                        val metrics = if (ni.hasDeviceMetrics()) ni.deviceMetrics else null
                        val pos = if (ni.hasPosition()) ni.position else null
                        // Merge onto the existing entry so fields absent from this
                        // packet (name, metrics, position) are preserved rather
                        // than reset to null/0/placeholder.
                        val node = (existing ?: MeshNode(nodeId = nodeIdStr)).copy(
                            nodeId = nodeIdStr,
                            longName = longName,
                            shortName = shortName,
                            lastHeard = if (ni.lastHeard != 0) ni.lastHeard.toLong() else existing?.lastHeard ?: 0L,
                            batteryLevel = metrics?.batteryLevel?.toInt() ?: existing?.batteryLevel,
                            snr = if (ni.snr != 0f) ni.snr else existing?.snr,
                            voltage = metrics?.voltage ?: existing?.voltage,
                            channelUtilization = metrics?.channelUtilization ?: existing?.channelUtilization,
                            airUtilTx = metrics?.airUtilTx ?: existing?.airUtilTx,
                            uptimeSeconds = metrics?.uptimeSeconds ?: existing?.uptimeSeconds,
                            latitude = pos?.latitudeI?.let { it / 1e7 } ?: existing?.latitude,
                            longitude = pos?.longitudeI?.let { it / 1e7 } ?: existing?.longitude,
                            altitude = pos?.altitude ?: existing?.altitude,
                            channel = ni.channel,
                            hwModel = if (ni.hasUser()) ni.user.hwModelValue else existing?.hwModel ?: 0,
                            role = if (ni.hasUser()) ni.user.roleValue else existing?.role ?: 0,
                            isLicensed = if (ni.hasUser()) ni.user.isLicensed else existing?.isLicensed ?: false,
                            viaMqtt = ni.viaMqtt,
                            isFavorite = ni.isFavorite,
                        )
                        nodeMap[ni.num] = node
                        if (!configBurstInProgress) {
                            _nodes.value = nodeMap.values.toList()
                        }
                        // Append a telemetry sample (battery + SNR) so
                        // the node-detail dialog can render trend
                        // sparklines.
                        pushNodeSample(
                            nodeNum = ni.num,
                            battery = metrics?.batteryLevel?.toInt(),
                            snr = ni.snr,
                        )
                        val my = myNodeNum
                        if (my != null && ni.num == my) _selfNode.value = node
                        if (my != null && ni.num == my && ni.hasUser()) _owner.value = ni.user
                        if (my != null && ni.num == my && ni.hasPosition()) _myPosition.value = ni.position
                    }
            }

            override fun onConfig(encoded: ByteArray) {
                runCatching { MeshProtos.Config.parseFrom(encoded) }
                    .onSuccess { handleConfig(it) }
            }

            override fun onModuleConfig(encoded: ByteArray) {
                runCatching { MeshProtos.ModuleConfig.parseFrom(encoded) }
                    .onSuccess { mc ->
                        // Only the MQTT variant has a UI today; the rest
                        // are swallowed (Telemetry, Serial, etc.).
                        if (mc.hasMqtt()) {
                            _mqttConfig.value = mc.mqtt
                        }
                    }
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
                    .onSuccess { user ->
                        _owner.value = user
                        val myId = _myNodeId.value
                        if (myId != null) {
                            val myNum = myNodeNum
                            val existing = if (myNum != null) nodeMap[myNum] else _selfNode.value
                            val node = (existing ?: MeshNode(nodeId = myId)).copy(
                                nodeId = myId,
                                longName = user.longName.ifEmpty { existing?.longName ?: "Unknown" },
                                shortName = user.shortName.ifEmpty { existing?.shortName ?: "??" },
                                hwModel = user.hwModelValue,
                                role = user.roleValue,
                                isLicensed = user.isLicensed,
                            )
                            // Write the owner name back into the node map too, not
                            // just selfNode. Otherwise the map keeps a stale
                            // "Unknown" entry and the next position-only NodeInfo
                            // merges that placeholder back over the real name.
                            myNum?.let {
                                nodeMap[it] = node
                                if (!configBurstInProgress) _nodes.value = nodeMap.values.toList()
                            }
                            _selfNode.value = node
                        }
                    }
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

    override fun startScan() {
        deviceDiscovery.startBleScan()
    }

    override fun stopScan() {
        deviceDiscovery.stopBleScan()
    }

    // ==========  NETWORK SCANNING  ==========

    override fun startNetworkScan() = networkDiscovery.start()

    override fun stopNetworkScan() = networkDiscovery.stop()

    // ==========  NETWORK CONNECTION  ==========

    override fun connectTcp(host: String, port: Int): Boolean {
        // Like USB, TCP isn't part of the BLE auto-reconnect loop; stand any
        // pending one down and forget the BLE device.
        autoReconnect = false
        lastBleDevice = null
        cancelReconnect()
        stopNetworkScan()
        runCatching { rustSession?.close() }
        rustSession = null
        _activeTransport.value = TransportType.NETWORK
        _connectionState.value = "CONNECTING"
        configBurstInProgress = true
        val transport = TcpMeshTransport(host, port)
        if (!transport.connect()) {
            _activeTransport.value = TransportType.NONE
            _connectionState.value = "DISCONNECTED"
            return false
        }
        return runCatching {
            rustSession = RustMeshSession.openTcp(rustService, transport)
            true
        }.getOrElse { t ->
            Log.e(TAG, "Rust TCP connect failed", t)
            transport.shutdown()
            _activeTransport.value = TransportType.NONE
            _connectionState.value = "DISCONNECTED"
            false
        }
    }

    // ==========  BLE CONNECTION  ==========

    @SuppressLint("MissingPermission")
    override fun connect(device: BluetoothDevice) {
        // User-initiated connect: arm auto-reconnect for this device and
        // supersede any retry loop that was chasing a previous one.
        autoReconnect = true
        lastBleDevice = device
        cancelReconnect()
        openBle(device)
    }

    /**
     * Open a BLE session to [device]. Shared by the user-initiated [connect]
     * and the auto-reconnect loop, so it must NOT touch [autoReconnect] /
     * [reconnectJob] (the caller owns that). A failed attempt schedules a
     * retry so transient open failures during a reboot keep trying.
     */
    @SuppressLint("MissingPermission")
    private fun openBle(device: BluetoothDevice) {
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
                // The Rust state listener doesn't fire for an open that never
                // connected, so kick the retry loop directly.
                maybeScheduleReconnect()
            }
        }
    }

    override fun disconnect() {
        // Deliberate disconnect: stop auto-reconnect and forget the device so
        // the drop below isn't treated as something to recover from.
        autoReconnect = false
        lastBleDevice = null
        cancelReconnect()
        runCatching { rustSession?.close() }
        rustSession = null
        if (usbActive) usbTransport.disconnect()
        usbActive = false
        _activeTransport.value = TransportType.NONE
        _connectionState.value = "DISCONNECTED"
    }

    fun unbind() = disconnect()

    /** Cancel any in-flight auto-reconnect loop. */
    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /**
     * Start (if not already running) a background loop that retries the last
     * BLE device until we reconnect or the user disconnects. Safe to call from
     * multiple threads / repeatedly; `@Synchronized` plus the active-job guard
     * keep it to a single loop. Driven off [connectionState] so it backs off
     * while an attempt is in flight and stops the moment we're connected.
     */
    @Synchronized
    private fun maybeScheduleReconnect() {
        if (!autoReconnect) return
        val device = lastBleDevice ?: return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var delayMs = RECONNECT_INITIAL_DELAY_MS
            while (isActive && autoReconnect) {
                if (_connectionState.value == "CONNECTED") return@launch
                // Wait first: gives a rebooting radio time to come back, and
                // spaces out retries with exponential backoff.
                delay(delayMs)
                if (!autoReconnect) return@launch
                when (_connectionState.value) {
                    "CONNECTED" -> return@launch
                    // An attempt is still establishing; let it resolve before
                    // firing another (don't reset backoff).
                    "CONNECTING" -> continue
                    else -> {
                        Log.i(TAG, "auto-reconnect: retrying ${device.address}")
                        pushDebug("transport", "auto-reconnect: retrying…")
                        openBle(device)
                        delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
                    }
                }
            }
        }
    }

    override fun destroy() {
        disconnect()
        usbTransport.destroy()
        deviceDiscovery.destroy()
        networkDiscovery.destroy()
        runCatching { rustService.close() }
        scope.cancel()
    }

    /**
     * Lazily-resolved handle to the Rust voice sender. The Rust side caches
     * one instance per `MeshService`, so we just forward each call —
     * `voiceSender()` is cheap to re-invoke. Returns null until connected,
     * to mirror the explicit `isConnected` check the rest of this class uses.
     */
    override fun voiceSender(): uniffi.voicetastic.VoiceSender? =
        if (isConnected) runCatching { rustService.voiceSender() }.getOrNull() else null

    // ==========  MESHTASTIC PROTOCOL  ==========

    override fun refreshConfig() {
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
    override fun requestNodeInfo(): Boolean {
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

    override fun sendText(text: String, destination: String?, channel: Int): Boolean {
        return sendTextTracked(text, destination, channel) != null
    }

    override fun sendTextTracked(text: String, destination: String?, channel: Int): UInt? {
        if (!isConnected) {
            Log.w(TAG, "sendText dropped: not connected (state=${_connectionState.value}, transport=${_activeTransport.value})")
            return null
        }

        val destUInt: UInt? = if (destination != null) {
            MeshtasticBle.nodeIdToNum(destination)?.toUInt() ?: run {
                Log.e(TAG, "Invalid destination node ID: $destination")
                return null
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
            id
        } catch (e: Exception) {
            Log.e(TAG, "sendText failed", e)
            null
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
    override fun sendData(
        data: ByteArray,
        portNum: Int,
        destination: String?,
        channel: Int,
        wantAck: Boolean,
        wantResponse: Boolean,
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

    override fun writeConfig(config: MeshProtos.Config): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setSetConfig(config)
            .build()
        return sendAdminMessage(admin)
    }

    override fun writeModuleConfig(moduleConfig: MeshProtos.ModuleConfig): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setSetModuleConfig(moduleConfig)
            .build()
        return sendAdminMessage(admin)
    }

    override fun setFixedPosition(position: MeshProtos.Position): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setSetFixedPosition(position)
            .build()
        return sendAdminMessage(admin)
    }

    override fun removeFixedPosition(): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setRemoveFixedPosition(true)
            .build()
        return sendAdminMessage(admin)
    }

    override fun broadcastPosition(
        position: MeshProtos.Position,
        channel: Int,
        dest: String?,
    ): Boolean {
        if (!isConnected) {
            Log.w(TAG, "broadcastPosition dropped: not connected")
            return false
        }
        val destUInt: UInt? = dest?.let {
            MeshtasticBle.nodeIdToNum(it)?.toUInt() ?: run {
                Log.e(TAG, "broadcastPosition: invalid destination $it")
                return false
            }
        }
        return try {
            rustService.broadcastPosition(position.toByteArray(), channel.toUInt(), destUInt)
            true
        } catch (e: Exception) {
            Log.e(TAG, "broadcastPosition failed", e)
            false
        }
    }

    override fun writeChannel(channel: MeshProtos.Channel): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setSetChannel(channel)
            .build()
        return sendAdminMessage(admin)
    }

    override fun writeOwner(user: MeshProtos.User): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setSetOwner(user)
            .build()
        return sendAdminMessage(admin)
    }

    override fun rebootDevice(seconds: Int): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setRebootSeconds(seconds)
            .build()
        return sendAdminMessage(admin)
    }

    override fun factoryReset(): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setFactoryResetConfig(1)
            .build()
        return sendAdminMessage(admin)
    }

    override fun resetNodeDb(): Boolean {
        val admin = MeshProtos.AdminMessage.newBuilder()
            .setNodedbReset(1)
            .build()
        if (!sendAdminMessage(admin)) return false
        // The firmware never re-bursts NodeInfo for an empty NodeDB, so
        // `refreshConfig()` alone wouldn't clear the visible peer list.
        // Drop the local mirror in lockstep so the UI forgets the wiped
        // peers immediately, then re-pull config sections.
        nodeMap.clear()
        _nodes.value = emptyList()
        runCatching { rustService.refreshConfig() }
            .onFailure { Log.e(TAG, "resetNodeDb: refreshConfig failed", it) }
        return true
    }
}
