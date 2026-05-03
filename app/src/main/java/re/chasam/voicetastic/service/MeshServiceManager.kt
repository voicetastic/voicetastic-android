package re.chasam.voicetastic.service

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.geeksville.mesh.MeshProtos
import com.geeksville.mesh.Portnums.PortNum
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import re.chasam.voicetastic.model.MeshNode
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Manages direct BLE connection to a Meshtastic device.
 * No Meshtastic Android app required.
 */
class MeshServiceManager(private val context: Context) {

    companion object {
        private const val TAG = "MeshServiceManager"
        private const val MTU_SIZE = 512
        private const val POLL_INTERVAL_MS = 1000L
    }

    data class IncomingText(val from: String, val to: String, val text: String, val channel: Int = 0, val timestamp: Long = System.currentTimeMillis())
    data class IncomingData(val from: String, val to: String, val portNum: Int, val payload: ByteArray, val channel: Int = 0, val timestamp: Long = System.currentTimeMillis())

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bleMutex = Mutex()  // Single mutex for all BLE GATT operations (read + write)

    private var bluetoothGatt: BluetoothGatt? = null
    private var toRadioChar: BluetoothGattCharacteristic? = null
    private var fromRadioChar: BluetoothGattCharacteristic? = null

    private var myNodeNum: Int = 0

    // Queue for pending read results
    private val readResultQueue = ConcurrentLinkedQueue<ByteArray?>()
    private val readSemaphore = java.util.concurrent.Semaphore(0)
    private val writeSemaphore = java.util.concurrent.Semaphore(0)
    private val descriptorSemaphore = java.util.concurrent.Semaphore(0)

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

    // --- Per-section config flows ---
    private val _radioConfig = MutableStateFlow<MeshProtos.Config.LoRaConfig?>(null)
    val radioConfig: StateFlow<MeshProtos.Config.LoRaConfig?> = _radioConfig.asStateFlow()

    private val _deviceConfig = MutableStateFlow<MeshProtos.Config.DeviceConfig?>(null)
    val deviceConfig: StateFlow<MeshProtos.Config.DeviceConfig?> = _deviceConfig.asStateFlow()

    private val _positionConfig = MutableStateFlow<MeshProtos.Config.PositionConfig?>(null)
    val positionConfig: StateFlow<MeshProtos.Config.PositionConfig?> = _positionConfig.asStateFlow()

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

    private val nodeMap = mutableMapOf<Int, MeshNode>()

    val isConnected: Boolean
        get() = _connectionState.value == "CONNECTED"

    // ==========  BLE SCANNING  ==========

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val current = _discoveredDevices.value.toMutableList()
            if (current.none { it.address == device.address }) {
                current.add(device)
                _discoveredDevices.value = current
                Log.i(TAG, "Found Meshtastic device: ${device.name ?: device.address}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val scanner = adapter.bluetoothLeScanner ?: return

        _discoveredDevices.value = emptyList()
        _isScanning.value = true

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MeshtasticBle.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)

        scope.launch {
            delay(15_000)
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try {
            BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        _isScanning.value = false
    }

    // ==========  BLE CONNECTION  ==========

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan()
        _connectionState.value = "CONNECTING"
        Log.i(TAG, "Connecting to ${device.name ?: device.address}")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun bind() = startScan()

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopPolling()
        try { bluetoothGatt?.disconnect() } catch (_: Exception) {}
        try { bluetoothGatt?.close() } catch (_: Exception) {}
        bluetoothGatt = null
        toRadioChar = null
        fromRadioChar = null
        _connectionState.value = "DISCONNECTED"
        myNodeNum = 0
        _myNodeId.value = null
        _firmwareVersion.value = null
        nodeMap.clear()
        _nodes.value = emptyList()
        _radioConfig.value = null
        _deviceConfig.value = null
        _positionConfig.value = null
        _powerConfig.value = null
        _networkConfig.value = null
        _displayConfig.value = null
        _bluetoothConfig.value = null
        _channels.value = emptyList()
        _owner.value = null
        _moduleConfigs.value = emptyMap()
        Log.i(TAG, "Disconnected")
    }

    fun unbind() = disconnect()

    fun destroy() {
        disconnect()
        scope.cancel()
    }

    private var serviceDiscoveryRetries = 0
    private val maxServiceDiscoveryRetries = 3

    /**
     * Clears the BLE GATT cache via reflection.
     * This is a standard Android workaround for stale GATT service data.
     */
    @SuppressLint("MissingPermission")
    private fun refreshGattCache(gatt: BluetoothGatt): Boolean {
        return try {
            val method = gatt.javaClass.getMethod("refresh")
            method.invoke(gatt) as? Boolean ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh GATT cache", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected, refreshing cache and requesting MTU")
                    serviceDiscoveryRetries = 0
                    refreshGattCache(gatt)
                    // Small delay to let the cache clear take effect
                    scope.launch {
                        delay(600)
                        gatt.requestMtu(MTU_SIZE)
                    }
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT disconnected (status=$status)")
                    _connectionState.value = "DISCONNECTED"
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed to $mtu")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }

            val service = gatt.getService(MeshtasticBle.SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Meshtastic BLE service not found")
                scope.launch { disconnect() }
                return
            }

            toRadioChar = service.getCharacteristic(MeshtasticBle.TORADIO_UUID)
            fromRadioChar = service.getCharacteristic(MeshtasticBle.FROMRADIO_UUID)
            val fromNumChar = service.getCharacteristic(MeshtasticBle.FROMNUM_UUID)

            if (toRadioChar == null || fromRadioChar == null) {
                Log.e(TAG, "Missing required BLE characteristics: " +
                    "toRadio=${toRadioChar != null}, fromRadio=${fromRadioChar != null}")

                if (serviceDiscoveryRetries < maxServiceDiscoveryRetries) {
                    serviceDiscoveryRetries++
                    Log.w(TAG, "Retrying service discovery (attempt $serviceDiscoveryRetries/$maxServiceDiscoveryRetries)")
                    scope.launch {
                        delay(1000L * serviceDiscoveryRetries)
                        refreshGattCache(gatt)
                        delay(500)
                        gatt.discoverServices()
                    }
                    return
                }

                Log.e(TAG, "All retries exhausted, disconnecting")
                scope.launch { disconnect() }
                return
            }

            // Enable notifications: prefer FromNum if available (legacy firmware),
            // otherwise subscribe to FromRadio notifications (firmware 2.5+)
            val notifyChar = fromNumChar ?: fromRadioChar!!
            if (fromNumChar != null) {
                Log.i(TAG, "Subscribing to FromNum notifications (legacy mode)")
            } else {
                Log.i(TAG, "FromNum not available, subscribing to FromRadio notifications (firmware 2.5+)")
            }

            gatt.setCharacteristicNotification(notifyChar, true)
            val descriptor = notifyChar.getDescriptor(MeshtasticBle.CCCD_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            } else {
                // No descriptor, proceed anyway
                onSetupComplete()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.i(TAG, "Descriptor write status=$status")
            descriptorSemaphore.release()
            onSetupComplete()
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == MeshtasticBle.FROMRADIO_UUID) {
                readResultQueue.add(value)
            } else {
                readResultQueue.add(null)
            }
            readSemaphore.release()
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == MeshtasticBle.FROMRADIO_UUID) {
                readResultQueue.add(characteristic.value)
            } else {
                readResultQueue.add(null)
            }
            readSemaphore.release()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeSemaphore.release()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == MeshtasticBle.FROMNUM_UUID) {
                // Legacy firmware: FromNum notifies that new data is available
                scope.launch { readAllFromRadio() }
            } else if (characteristic.uuid == MeshtasticBle.FROMRADIO_UUID) {
                // Firmware 2.5+: FromRadio notifies directly with data
                scope.launch {
                    if (value.isNotEmpty()) {
                        try {
                            val fromRadio = MeshProtos.FromRadio.parseFrom(value)
                            handleFromRadio(fromRadio)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse FromRadio notification", e)
                        }
                    }
                    // There may be more queued, read remaining
                    readAllFromRadio()
                }
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == MeshtasticBle.FROMNUM_UUID) {
                scope.launch { readAllFromRadio() }
            } else if (characteristic.uuid == MeshtasticBle.FROMRADIO_UUID) {
                val value = characteristic.value
                scope.launch {
                    if (value != null && value.isNotEmpty()) {
                        try {
                            val fromRadio = MeshProtos.FromRadio.parseFrom(value)
                            handleFromRadio(fromRadio)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse FromRadio notification", e)
                        }
                    }
                    readAllFromRadio()
                }
            }
        }
    }

    private var pollingJob: Job? = null

    private fun onSetupComplete() {
        _connectionState.value = "CONNECTED"
        Log.i(TAG, "BLE setup complete, requesting config")
        scope.launch {
            delay(300)
            requestConfig()
        }
        startPolling()
    }

    /**
     * Start a periodic polling loop that reads queued messages from the device.
     * This acts as a safety net in case BLE notifications are missed or coalesced.
     */
    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (isConnected) {
                    try {
                        readAllFromRadio()
                    } catch (e: Exception) {
                        Log.w(TAG, "Polling read failed", e)
                    }
                }
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    // ==========  MESHTASTIC PROTOCOL  ==========

    /**
     * Request full device configuration from the connected node.
     * Public so the UI can trigger a re-read.
     */
    fun refreshConfig() {
        if (!isConnected) return
        scope.launch { requestConfig() }
    }

    private suspend fun requestConfig() {
        val configId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val toRadio = MeshProtos.ToRadio.newBuilder()
            .setWantConfigId(configId)
            .build()
        writeToRadio(toRadio)
        delay(500)
        readAllFromRadio()
    }

    @SuppressLint("MissingPermission")
    private suspend fun readAllFromRadio() {
        val gatt = bluetoothGatt ?: return
        val char = fromRadioChar ?: return

        bleMutex.withLock {
            var attempts = 0
            var packetsRead = 0
            while (attempts < 100) {
                readSemaphore.drainPermits()
                readResultQueue.clear()

                if (!gatt.readCharacteristic(char)) {
                    Log.w(TAG, "readCharacteristic(FromRadio) returned false (attempt=$attempts)")
                    break
                }

                // Wait for callback
                if (!readSemaphore.tryAcquire(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    Log.w(TAG, "readCharacteristic(FromRadio) timed out after 2s (attempt=$attempts)")
                    break
                }

                val data = readResultQueue.poll()
                if (data == null || data.isEmpty()) break  // queue drained, normal exit

                try {
                    val fromRadio = MeshProtos.FromRadio.parseFrom(data)
                    handleFromRadio(fromRadio)
                    packetsRead++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse FromRadio (${data.size} bytes)", e)
                }
                attempts++
                delay(50)
            }
            if (packetsRead > 0) {
                Log.d(TAG, "readAllFromRadio drained $packetsRead packet(s)")
            }
        }
    }

    private fun handleFromRadio(fromRadio: MeshProtos.FromRadio) {
        when {
            fromRadio.hasMyInfo() -> {
                val info = fromRadio.myInfo
                myNodeNum = info.myNodeNum
                _myNodeId.value = MeshtasticBle.nodeNumToId(myNodeNum)
                _firmwareVersion.value = info.firmwareVersion
                Log.i(TAG, "My node: ${_myNodeId.value} (fw: ${info.firmwareVersion})")
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
                    nodeMap[fromNum] = MeshNode(
                        nodeId = MeshtasticBle.nodeNumToId(fromNum),
                        longName = user.longName,
                        shortName = user.shortName
                    )
                    _nodes.value = nodeMap.values.toList()
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

    @SuppressLint("MissingPermission")
    private suspend fun writeToRadio(toRadio: MeshProtos.ToRadio) {
        val gatt = bluetoothGatt ?: return
        val char = toRadioChar ?: return
        val bytes = toRadio.toByteArray()

        bleMutex.withLock {
            writeSemaphore.drainPermits()
            val initiated = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = bytes
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
            if (initiated) {
                if (!writeSemaphore.tryAcquire(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    Log.w(TAG, "Write timeout waiting for callback (${bytes.size} bytes)")
                } else {
                    Log.d(TAG, "ToRadio write success (${bytes.size} bytes)")
                }
            } else {
                Log.e(TAG, "Failed to initiate BLE write ToRadio (${bytes.size} bytes)")
            }
        }
    }

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

        val data = MeshProtos.Data.newBuilder()
            .setPortnum(PortNum.TEXT_MESSAGE_APP)
            .setPayload(com.google.protobuf.ByteString.copyFromUtf8(text))
            .build()

        val packet = MeshProtos.MeshPacket.newBuilder()
            .setTo(destNum)
            .setChannel(channel)
            .setDecoded(data)
            .setWantAck(true)
            .build()

        val toRadio = MeshProtos.ToRadio.newBuilder()
            .setPacket(packet)
            .build()

        scope.launch { writeToRadio(toRadio) }
        Log.i(TAG, "Sending text to ${destination ?: "broadcast"} (dest=$destNum): $text")
        return true
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

        val meshData = MeshProtos.Data.newBuilder()
            .setPortnumValue(portNum)
            .setPayload(com.google.protobuf.ByteString.copyFrom(data))
            .build()

        val packet = MeshProtos.MeshPacket.newBuilder()
            .setTo(destNum)
            .setChannel(channel)
            .setDecoded(meshData)
            .setWantAck(true)
            .build()

        val toRadio = MeshProtos.ToRadio.newBuilder()
            .setPacket(packet)
            .build()

        scope.launch { writeToRadio(toRadio) }
        Log.i(TAG, "Sending data (port=$portNum, ${data.size} bytes) to ${destination ?: "broadcast"}")
        return true
    }

    // ==========  ADMIN CONFIG WRITES (local node only)  ==========

    private fun sendAdminMessage(admin: MeshProtos.AdminMessage): Boolean {
        if (!isConnected || myNodeNum == 0) return false

        val data = MeshProtos.Data.newBuilder()
            .setPortnum(PortNum.ADMIN_APP)
            .setPayload(admin.toByteString())
            .setWantResponse(true)
            .build()

        val packet = MeshProtos.MeshPacket.newBuilder()
            .setTo(myNodeNum)
            .setDecoded(data)
            .setWantAck(true)
            .build()

        val toRadio = MeshProtos.ToRadio.newBuilder()
            .setPacket(packet)
            .build()

        scope.launch { writeToRadio(toRadio) }
        return true
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
            .setFactoryReset(true)
            .build()
        return sendAdminMessage(admin)
    }

    // ==========  LEGACY CONFIG ACCESSORS  ==========

    fun getRadioConfig(): ByteArray? = _radioConfig.value?.toByteArray()

    fun setRadioConfig(config: ByteArray): Boolean {
        Log.i(TAG, "setRadioConfig: ${config.size} bytes")
        return isConnected
    }

    fun getChannels(): ByteArray? = null

    fun setChannels(channels: ByteArray): Boolean {
        Log.i(TAG, "setChannels: ${channels.size} bytes")
        return isConnected
    }
}
