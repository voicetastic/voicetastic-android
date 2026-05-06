package re.chasam.voicetastic.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * USB serial transport for talking to a Meshtastic node.
 *
 * Uses [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android)
 * to open the device's CDC / CP210x / CH34x / FTDI port at 115200 8N1, then
 * frames bytes through [MeshSerialFraming] so the rest of the app can keep
 * working with raw `FromRadio` / `ToRadio` protobuf byte arrays — exactly
 * like the BLE path.
 *
 * Lifecycle:
 *  1. [discoverDevices] enumerates currently attached probable Meshtastic
 *     devices.
 *  2. [hasPermission] / [requestPermission] gate access (USB host requires
 *     explicit user consent the first time).
 *  3. [connect] opens the port and starts the I/O pump.
 *  4. [write] pushes a framed `ToRadio` to the device.
 *  5. Incoming `FromRadio` payloads are emitted on [incomingFromRadio].
 *  6. [disconnect] tears everything down (also called automatically when the
 *     device is physically detached).
 *
 * Thread-safety: all GATT-equivalent IO happens on an internal background
 * dispatcher; public methods may be called from any thread.
 */
class UsbMeshTransport(private val context: Context) {

    companion object {
        private const val TAG = "UsbMeshTransport"
        private const val BAUD_RATE = 115_200
        private const val WRITE_TIMEOUT_MS = 2_000
        private const val READ_TIMEOUT_MS = 0  // 0 == async via SerialInputOutputManager
        const val ACTION_USB_PERMISSION = "re.chasam.voicetastic.USB_PERMISSION"

        /**
         * Meshtastic serial wake-up sequence.
         *
         * On boot — and any time the host has been silent for a while — the
         * firmware's serial port runs in *debug-log mode*, dumping `LogRecord`
         * text and ignoring `ToRadio` writes. The official Meshtastic clients
         * (Python `StreamInterface`, Android `SerialInterface`) put it back
         * into *protocol mode* by writing 32 × 0xC3 bytes followed by ~100 ms
         * of silence. The firmware's StreamAPI uses that pattern as its
         * "switch to API mode" trigger; the bytes themselves are not parsed
         * as a frame (they're not preceded by 0x94), they just flip the mode.
         *
         * Without this, a fresh `want_config_id` after the boot-time burst
         * silently times out — which looks like "Refresh hangs but Reset
         * works" because Reset re-runs the spontaneous boot burst.
         */
        private val WAKE_SEQUENCE: ByteArray = ByteArray(32) { MeshSerialFraming.START2 }
        private const val WAKE_SETTLE_MS = 100L
    }

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val parser = MeshSerialFraming.Parser()

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var ioJob: Job? = null
    private var permissionReceiver: BroadcastReceiver? = null

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _connectedDevice = MutableStateFlow<UsbDevice?>(null)
    val connectedDevice: StateFlow<UsbDevice?> = _connectedDevice.asStateFlow()

    private val _incomingFromRadio = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incomingFromRadio: SharedFlow<ByteArray> = _incomingFromRadio.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    val isConnected: Boolean get() = _state.value == State.CONNECTED

    // ===== Discovery & permissions =====

    /** Returns USB serial devices that look like Meshtastic candidates. */
    fun discoverDevices(): List<UsbSerialDriver> =
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /**
     * Requests USB permission for [device]. Result is delivered to
     * [onResult] via a one-shot broadcast receiver.
     */
    fun requestPermission(device: UsbDevice, onResult: (granted: Boolean) -> Unit) {
        if (hasPermission(device)) {
            onResult(true)
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                try { context.unregisterReceiver(this) } catch (_: Exception) {}
                permissionReceiver = null
                onResult(granted)
            }
        }
        permissionReceiver = receiver
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        usbManager.requestPermission(device, pendingIntent)
    }

    // ===== Connect / disconnect =====

    /** @return true on success. The driver must already have been granted permission. */
    fun connect(driver: UsbSerialDriver): Boolean {
        if (_state.value == State.CONNECTED || _state.value == State.CONNECTING) {
            Log.w(TAG, "connect called while state=${_state.value}; ignoring")
            return false
        }
        _state.value = State.CONNECTING
        return try {
            val connection = usbManager.openDevice(driver.device) ?: run {
                emitError("Cannot open USB device (permission denied?)")
                _state.value = State.ERROR
                return false
            }
            val serialPort = driver.ports.first()
            serialPort.open(connection)
            serialPort.setParameters(
                BAUD_RATE,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            // Assert DTR/RTS. Several USB-serial bridges used by Meshtastic
            // hardware (CP210x, CH34x, FTDI) only enable their line drivers
            // when DTR is high; without this the firmware never sees our
            // ToRadio writes and we never receive its FromRadio responses,
            // so the post-connect `requestConfig()` silently times out and
            // the settings UI stays empty. Some boards also wire RTS to
            // EN/RESET — leaving it asserted (true) keeps the MCU running;
            // *toggling* it would reset the device, which we don't want.
            try {
                serialPort.dtr = true
                serialPort.rts = true
            } catch (e: Exception) {
                // Not all drivers support these; the radio may still work.
                Log.w(TAG, "Could not set DTR/RTS: ${e.message}")
            }
            port = serialPort
            parser.reset()
            startIoPump(serialPort)
            // Kick the firmware out of debug-log mode and into protocol
            // mode so the very next ToRadio (typically `want_config_id`)
            // is actually processed instead of being treated as raw input.
            wakeFirmware(serialPort)
            _connectedDevice.value = driver.device
            _state.value = State.CONNECTED
            Log.i(TAG, "Connected to USB device ${driver.device.deviceName}")
            // Wake the firmware: ask for full config.
            true
        } catch (e: Exception) {
            Log.e(TAG, "USB connect failed", e)
            emitError("USB connect failed: ${e.message}")
            disconnect()
            _state.value = State.ERROR
            false
        }
    }

    private fun startIoPump(serialPort: UsbSerialPort) {
        val mgr = SerialInputOutputManager(serialPort, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                try {
                    val payloads = parser.feed(data)
                    if (payloads.isNotEmpty()) {
                        for (p in payloads) _incomingFromRadio.tryEmit(p)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Framing error", e)
                }
            }

            override fun onRunError(e: Exception) {
                Log.w(TAG, "Serial run error", e)
                emitError("USB I/O error: ${e.message}")
                scope.launch { disconnect() }
            }
        })
        mgr.readTimeout = READ_TIMEOUT_MS
        ioManager = mgr
        // The library's start() spins its own thread; we keep a Job purely so
        // disconnect() can also stop any pending coroutine work.
        ioJob = scope.launch { mgr.start() }
    }

    /** Send a `ToRadio` protobuf, framed for the serial wire. */
    fun write(toRadioBytes: ByteArray): Boolean {
        val p = port ?: return false
        return try {
            val framed = MeshSerialFraming.encode(toRadioBytes)
            p.write(framed, WRITE_TIMEOUT_MS)
            true
        } catch (e: Exception) {
            Log.e(TAG, "USB write failed", e)
            emitError("USB write failed: ${e.message}")
            false
        }
    }

    /**
     * Re-arm the firmware's protocol mode. Safe to call any time a connection
     * is open. Use this before issuing a [write] if the firmware may have
     * fallen back to debug-log mode (e.g. after a long idle period or a
     * spontaneous reboot we didn't see).
     *
     * Suspends for [WAKE_SETTLE_MS] so the next `write()` is guaranteed to
     * land in protocol mode.
     */
    suspend fun wake(): Boolean {
        val p = port ?: return false
        return try {
            p.write(WAKE_SEQUENCE, WRITE_TIMEOUT_MS)
            delay(WAKE_SETTLE_MS)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Wake sequence failed (non-fatal): ${e.message}")
            false
        }
    }

    /** Internal: blocking wake (used during connect, where we can't suspend). */
    private fun wakeFirmware(p: UsbSerialPort) {
        try {
            p.write(WAKE_SEQUENCE, WRITE_TIMEOUT_MS)
            Thread.sleep(WAKE_SETTLE_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Wake sequence failed (non-fatal): ${e.message}")
        }
    }

    fun disconnect() {
        try { ioManager?.stop() } catch (_: Exception) {}
        ioManager = null
        ioJob?.cancel(); ioJob = null
        try { port?.close() } catch (_: Exception) {}
        port = null
        permissionReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
            permissionReceiver = null
        }
        _connectedDevice.value = null
        if (_state.value != State.ERROR) _state.value = State.DISCONNECTED
        parser.reset()
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }

    /** Public hook so MeshServiceManager can drop USB when the device is detached. */
    fun onDeviceDetached(device: UsbDevice) {
        if (_connectedDevice.value?.deviceName == device.deviceName) {
            Log.i(TAG, "Active USB device detached → disconnecting")
            disconnect()
        }
    }

    private fun emitError(msg: String) {
        _errors.tryEmit(msg)
    }

    // For tests: expose a hook to drive a heartbeat loop if we add one later.
    @Suppress("unused")
    private suspend fun heartbeatLoop() {
        while (scope.isActive && isConnected) {
            delay(5 * 60_000L)
            // Heartbeat injection is the manager's responsibility (it owns the
            // ToRadio protobuf builder); kept here as a hook for future use.
        }
    }
}

