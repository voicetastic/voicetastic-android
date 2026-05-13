package re.chasam.voicetastic.service

import android.annotation.SuppressLint
import android.bluetooth.*
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

/**
 * Manages direct BLE connection to a Meshtastic device.
 * No Meshtastic Android app required.
 */
class MeshServiceManager(private val context: Context) {

    companion object {
        private const val TAG = "MeshServiceManager"
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

    // NOTE: the listener-registration `init { ... }` block lives further down
    // in the file, AFTER every StateFlow / nodeMap field is declared. The
    // Rust side fires `on_state(Disconnected)` synchronously during
    // `setStateListener`, which then re-enters our Kotlin code and calls
    // `clearSessionState()` — that touches `nodeMap`, `_radioConfig`, ...,
    // so they MUST already be initialised. Kotlin runs initialisers in
    // declaration order, hence the deliberate placement.

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

    /** Notify when the OS reports a USB device was unplugged. */
    fun onUsbDeviceDetached(device: UsbDevice) = usbTransport.onDeviceDetached(device)

    /**
     * Insert/update a node in [nodeMap] from a `NODEINFO_APP` MeshPacket.
     *
     * Meshtastic firmware emits two distinct streams of NodeInfo:
     *   1. `FromRadio.NodeInfo` during the initial config burst, for nodes
     *      already in the device's node DB. The Rust bridge forwards those
     *      through [MeshConfigListener.onNodeInfo].
     *   2. On-air `MeshPacket`s with portnum `NODEINFO_APP` and a `User`
     *      proto payload for every node the radio subsequently hears. The
     *      Rust core doesn't merge those into the nodes watch, so we have
     *      to do it here or the chat-node picker would never list anyone
     *      other than whatever was in the DB at connect time (often only
     *      ourselves on a fresh radio).
     */
    private fun mergeNodeFromUser(nodeNum: Int, payload: ByteArray, rxTime: Long) {
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

    /**
     * Refresh a node's `lastHeard` without overwriting identity fields, for
     * portnums where we have a timestamp but no `User` proto to merge
     * (e.g. POSITION_APP, TELEMETRY_APP). Creates a placeholder if we've
     * never seen this node so the picker still surfaces it.
     */
    private fun touchNodeLastHeard(nodeNum: Int, rxTime: Long) {
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

    private val nodeMap = mutableMapOf<Int, MeshNode>()

    /**
     * True while the initial config burst is in progress (from connect() until
     * onConfigComplete fires). During this window we suppress per-node
     * `_nodes.value` emissions and instead emit a single snapshot at the end,
     * avoiding N StateFlow emissions + N recompositions for a burst of N nodes.
     */
    @Volatile private var configBurstInProgress = false

    val isConnected: Boolean
        get() = _connectionState.value == "CONNECTED" || _connectionState.value == "CONNECTING"

    init {
        // Register Rust-side listeners synchronously.
        //
        // This MUST happen after every StateFlow / SharedFlow / nodeMap field
        // above is declared: `setStateListener` immediately fires
        // `on_state(current_state)` with `Disconnected`, which re-enters
        // Kotlin and ends up calling `clearSessionState()` — that touches
        // `nodeMap`, `_radioConfig`, `_channels`, `_moduleConfigs`, ... so
        // any of those still being null produces an NPE inside a UniFFI
        // callback (surfaced as
        // `InternalException: Callback interface failure ...
        //  NullPointerException: ... on a null object reference`).
        //
        // The previous design used `scope.launch { ... }` to dodge this
        // ordering issue at the cost of racing user-initiated connects;
        // doing it synchronously here — but AFTER all fields are
        // initialised — is correct on both counts.
        rustService.setStateListener(object : MeshStateListener {
            override fun onState(state: MeshConnectionState) {
                val mapped = when (state) {
                    MeshConnectionState.CONNECTED, MeshConnectionState.READY -> "CONNECTED"
                    MeshConnectionState.CONNECTING, MeshConnectionState.CONFIGURING -> "CONNECTING"
                    MeshConnectionState.DISCONNECTED -> "DISCONNECTED"
                }
                Log.d(TAG, "Rust state → $state (mapped=$mapped)")
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
                val fromNum = message.from.toInt()
                val fromId = MeshtasticBle.nodeNumToId(fromNum)
                val toId = if (message.to.toInt() == MeshtasticBle.BROADCAST_ADDR) "broadcast"
                else MeshtasticBle.nodeNumToId(message.to.toInt())

                // Merge live node-DB updates heard over the air.
                //
                // The Rust core only feeds `onNodeInfo` from the initial
                // config burst (the device's pre-existing node DB). Every
                // subsequent NodeInfo / Position / Telemetry packet heard
                // on the mesh arrives here as a MeshPacket on its
                // respective portnum and would otherwise never make it
                // into `nodeMap` — leaving the chat node picker stuck on
                // "Broadcast" only.
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
                        // During the config burst we suppress per-node StateFlow
                        // emissions. A single snapshot is emitted at onConfigComplete,
                        // cutting N intermediate list allocations + recompositions.
                        if (!configBurstInProgress) {
                            _nodes.value = nodeMap.values.toList()
                        }
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
                // Burst is over — emit the full node list once and re-enable
                // per-node live updates.
                configBurstInProgress = false
                _nodes.value = nodeMap.values.toList()
                _configComplete.tryEmit(nonce.toInt())
            }
        })
    }


    // ==========  BLE SCANNING  ==========
    //
    // The Rust bridge does not expose device discovery on Android (the
    // `ble-btleplug` feature is desktop-only). We keep the BLE scan
    // implemented natively here using `BluetoothLeScanner`; only the
    // connect/I-O path is delegated to Rust via `RustMeshSession`.

    private val scanCallback = object : android.bluetooth.le.ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(
            callbackType: Int,
            result: android.bluetooth.le.ScanResult,
        ) {
            val device = result.device ?: return
            val current = _discoveredDevices.value
            if (current.none { it.address == device.address }) {
                _discoveredDevices.value = current + device
                Log.i(TAG, "Found Meshtastic device: ${device.name ?: device.address}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
            _isScanning.value = false
        }
    }

    /** Resolve the system Bluetooth adapter via BluetoothManager (API 18+). */
    private fun bluetoothAdapter(): android.bluetooth.BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager
        return manager?.adapter
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            Log.w(TAG, "startScan: no Bluetooth adapter")
            return
        }
        if (!adapter.isEnabled) {
            Log.w(TAG, "startScan: Bluetooth disabled")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "startScan: bluetoothLeScanner unavailable")
            return
        }
        if (_isScanning.value) {
            Log.d(TAG, "startScan: already scanning")
            return
        }
        _discoveredDevices.value = emptyList()
        _isScanning.value = true

        val filter = android.bluetooth.le.ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(MeshtasticBle.SERVICE_UUID))
            .build()
        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            Log.i(TAG, "BLE scan started")
        } catch (e: Exception) {
            Log.e(TAG, "BLE scan failed to start", e)
            _isScanning.value = false
            return
        }

        // Stop automatically after 15s — Android caps background scans
        // anyway and an indefinite scan drains the battery.
        scope.launch {
            delay(15_000)
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!_isScanning.value) return
        try {
            bluetoothAdapter()?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan threw", e)
        }
        _isScanning.value = false
        Log.i(TAG, "BLE scan stopped")
    }

    // ==========  BLE CONNECTION  ==========

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        // Stop scanning before opening GATT — Android serialises scan +
        // connect on the same radio and an active scan can wedge the
        // GATT setup.
        stopScan()
        _connectionState.value = "CONNECTING"
        _activeTransport.value = TransportType.BLE
        // Mark burst in progress so onNodeInfo suppresses intermediate
        // _nodes.value emissions during the config download.
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

        // Rust's `send_text` takes `Option<u32>` for dest. Mapping the
        // Meshtastic BROADCAST_ADDR (0xFFFFFFFF) to `null` makes the Rust
        // side correctly omit `want_ack` (firmware silently drops acks for
        // broadcast packets, so requesting one would just stall the queue).
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
            Log.d(TAG, "sendText ok (id=$id, dest=${destination ?: "broadcast"}, ch=$channel)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendText failed", e)
            false
        }
    }

    fun sendData(data: ByteArray, portNum: Int, destination: String? = null, channel: Int = 0): Boolean {
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
            val id = rustService.sendData(portNum, data, channel.toUInt(), destUInt, true)
            Log.d(TAG, "sendData ok (id=$id, port=$portNum, dest=${destination ?: "broadcast"}, ch=$channel, len=${data.size})")
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
