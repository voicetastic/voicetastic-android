package re.chasam.voicetastic.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.voicetastic.MeshTransport
import uniffi.voicetastic.MeshTransportSink
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * BLE GATT adapter that implements the UniFFI [MeshTransport] foreign
 * trait so the Rust-side [uniffi.voicetastic.MeshService] can drive a
 * Meshtastic radio over Bluetooth Low Energy.
 *
 * # Lifecycle
 *
 * Typical usage by a caller wiring up the Rust state machine:
 *
 * ```kotlin
 * val transport = BleMeshTransport(context, device)
 * transport.connectGatt()            // 1. open GATT, discover services
 * val sink = meshService.connect(    // 2. register transport with Rust
 *     transport,
 *     settleDelayMs = 800u,
 * )
 * transport.attachSink(sink)         // 3. start streaming inbound frames
 * // ... use meshService ...
 * meshService.disconnect()           // tears down via MeshTransport.shutdown()
 * ```
 *
 * Step 3 is **mandatory**: until [attachSink] is called, inbound BLE
 * notifications are dropped on the floor. This is by design — we don't
 * know the Rust-side sink handle until `MeshService.connect` returns,
 * and that call needs the transport to already exist so it can wire up
 * `write_to_radio`. The short window between steps 2 and 3 corresponds
 * to Rust sending the very first `WantConfigId`; the firmware can't
 * answer faster than the next BLE notify, so in practice no frames are
 * lost.
 *
 * # Thread safety
 *
 * All public methods are thread-safe:
 * - [writeToRadio] / [shutdown] are called from arbitrary Rust runtime
 *   threads through UniFFI. We serialize them through [bleMutex] just
 *   like the legacy `MeshServiceManager` does, because Android's
 *   `BluetoothGatt` requires one outstanding operation at a time.
 * - [attachSink] is idempotent and can race with the very first inbound
 *   notification — the sink reference is read via a volatile load on
 *   each notify, so a late attach simply means a few early frames are
 *   dropped (see lifecycle note above).
 *
 * # Why a separate class (vs. extracting from `MeshServiceManager`)
 *
 * Per the PR 2 plan, the legacy `MeshServiceManager` BLE path stays
 * intact behind the `USE_RUST_MESH_SERVICE = false` build flag. The two
 * code paths run side-by-side until PR 3 flips the flag and PR 5
 * deletes the legacy manager. Keeping this class self-contained makes
 * the diff in PR 5 a pure delete.
 */
@SuppressLint("MissingPermission")
class BleMeshTransport(
    private val context: Context,
    private val device: BluetoothDevice,
) : MeshTransport {

    companion object {
        private const val TAG = "BleMeshTransport"
        private const val MTU_SIZE = 512
        private const val GATT_OP_TIMEOUT_MS = 2_000L
        private const val MAX_SERVICE_DISCOVERY_RETRIES = 3
        /** Periodic safety-net read to catch missed FromNum notifies. */
        private const val POLL_INTERVAL_MS = 30_000L
    }

    /**
     * Listener invoked once GATT setup finishes (services discovered,
     * characteristics resolved, CCCD descriptor written). The caller
     * uses this signal to know when it is safe to issue the first
     * write (and equivalently when the Rust side's
     * `settle_delay_ms` should have elapsed).
     */
    fun interface SetupListener {
        fun onSetupComplete(success: Boolean, error: String?)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bleMutex = Mutex()

    @Volatile private var sink: MeshTransportSink? = null
    @Volatile private var setupListener: SetupListener? = null

    private var gatt: BluetoothGatt? = null
    private var toRadioChar: BluetoothGattCharacteristic? = null
    private var fromRadioChar: BluetoothGattCharacteristic? = null

    /** Synchronisation primitives for GATT callbacks. */
    private val readQueue = ConcurrentLinkedQueue<ByteArray>()
    private val readSemaphore = Semaphore(0)
    private val writeSemaphore = Semaphore(0)

    private var serviceDiscoveryRetries = 0
    private var setupCompleted = false
    private var pollingJob: Job? = null
    @Volatile private var closed = false

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Open the GATT connection and start the setup sequence (MTU →
     * service discovery → CCCD notify enable). Returns immediately;
     * progress is reported asynchronously via [setupListener].
     */
    fun connectGatt(listener: SetupListener? = null) {
        if (closed) error("BleMeshTransport already closed")
        if (gatt != null) {
            Log.w(TAG, "connectGatt called twice; ignoring")
            return
        }
        setupListener = listener
        Log.i(TAG, "Opening GATT to ${device.name ?: device.address}")
        gatt = device.connectGatt(context, /* autoConnect = */ false, gattCallback,
            BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * Wire the Rust-side inbound sink into the BLE notify path. Must be
     * called exactly once, after [uniffi.voicetastic.MeshService.connect]
     * returns. Calling more than once replaces the sink (the previous
     * one is **not** closed — that's the caller's responsibility).
     */
    fun attachSink(sink: MeshTransportSink) {
        this.sink = sink
    }

    // -------------------------------------------------------------------------
    // MeshTransport (UniFFI foreign trait)
    // -------------------------------------------------------------------------

    /**
     * Send one already-encoded `ToRadio` protobuf message.
     *
     * Called from Rust via `tokio::task::spawn_blocking`, so blocking
     * on the GATT callback is OK — the bridge runtime has dedicated
     * blocking worker threads for exactly this.
     */
    override fun writeToRadio(data: ByteArray) {
        if (closed) {
            Log.w(TAG, "writeToRadio after close; dropping ${data.size} bytes")
            return
        }
        // We're on a Rust runtime blocking thread; bridge into a
        // coroutine to share the same bleMutex semantics as inbound
        // reads. runBlocking is safe because the Rust caller already
        // expects us to block.
        kotlinx.coroutines.runBlocking { doWrite(data) }
    }

    private suspend fun doWrite(bytes: ByteArray) {
        val gatt = gatt ?: return
        val char = toRadioChar ?: run {
            Log.w(TAG, "writeToRadio before setup; dropping ${bytes.size} bytes")
            return
        }
        bleMutex.withLock {
            writeSemaphore.drainPermits()
            val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    char.value = bytes
                    char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    gatt.writeCharacteristic(char)
                }
            }
            if (!initiated) {
                Log.e(TAG, "Failed to initiate BLE write (${bytes.size} bytes)")
                return@withLock
            }
            if (!writeSemaphore.tryAcquire(GATT_OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "BLE write timed out (${bytes.size} bytes)")
            }
        }
    }

    /**
     * Tear down the GATT connection. Idempotent. Called by Rust's
     * `MeshService::disconnect`, but also safe to call directly (e.g.
     * when the user cancels mid-connect).
     *
     * Named [shutdown] (not `close`) to avoid an
     * `'Overload resolution ambiguity'` against `AutoCloseable.close()`
     * the UniFFI bindings put on every handle. See the UDL comment on
     * `interface MeshTransport`.
     */
    override fun shutdown() {
        if (closed) return
        closed = true
        pollingJob?.cancel(); pollingJob = null
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        toRadioChar = null
        fromRadioChar = null
        sink = null
        setupListener = null
        scope.cancel()
        Log.i(TAG, "BleMeshTransport shut down")
    }

    // -------------------------------------------------------------------------
    // GATT callbacks
    // -------------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected; refreshing cache and requesting MTU")
                    serviceDiscoveryRetries = 0
                    refreshGattCache(g)
                    scope.launch {
                        delay(600)
                        g.requestMtu(MTU_SIZE)
                    }
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT disconnected (status=$status)")
                    // Notify the Rust side so MeshService moves to
                    // Disconnected without waiting on a separate close.
                    sink?.shutdown()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed to $mtu (status=$status)")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }
            val service = g.getService(MeshtasticBle.SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Meshtastic BLE service not found")
                emitSetup(false, "Meshtastic BLE service not found")
                return
            }
            toRadioChar = service.getCharacteristic(MeshtasticBle.TORADIO_UUID)
            fromRadioChar = service.getCharacteristic(MeshtasticBle.FROMRADIO_UUID)
            val fromNumChar = service.getCharacteristic(MeshtasticBle.FROMNUM_UUID)

            if (toRadioChar == null || fromRadioChar == null) {
                if (serviceDiscoveryRetries < MAX_SERVICE_DISCOVERY_RETRIES) {
                    serviceDiscoveryRetries++
                    Log.w(TAG, "Missing characteristics; retry " +
                        "$serviceDiscoveryRetries/$MAX_SERVICE_DISCOVERY_RETRIES")
                    scope.launch {
                        delay(1_000L * serviceDiscoveryRetries)
                        refreshGattCache(g)
                        delay(500)
                        g.discoverServices()
                    }
                    return
                }
                emitSetup(false, "missing Meshtastic GATT characteristics")
                return
            }

            // Prefer FromNum (legacy firmware) over FromRadio direct notify.
            val notifyChar = fromNumChar ?: fromRadioChar!!
            g.setCharacteristicNotification(notifyChar, true)
            val descriptor = notifyChar.getDescriptor(MeshtasticBle.CCCD_UUID)
            if (descriptor != null) {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            } else {
                onSetupComplete()
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int,
        ) {
            Log.i(TAG, "Descriptor write status=$status")
            onSetupComplete()
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
            value: ByteArray, status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS
                && characteristic.uuid == MeshtasticBle.FROMRADIO_UUID) {
                readQueue.add(value)
            } else {
                readQueue.add(ByteArray(0))
            }
            readSemaphore.release()
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int,
        ) {
            @Suppress("DEPRECATION")
            val v = characteristic.value
            if (status == BluetoothGatt.GATT_SUCCESS
                && characteristic.uuid == MeshtasticBle.FROMRADIO_UUID) {
                readQueue.add(v)
            } else {
                readQueue.add(ByteArray(0))
            }
            readSemaphore.release()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int,
        ) {
            writeSemaphore.release()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            handleNotify(characteristic, value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            handleNotify(characteristic, characteristic.value ?: ByteArray(0))
        }
    }

    private fun handleNotify(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        when (characteristic.uuid) {
            MeshtasticBle.FROMNUM_UUID -> {
                // Legacy firmware: FromNum just signals "there's data";
                // drain FromRadio reads until empty.
                scope.launch { drainFromRadio() }
            }
            MeshtasticBle.FROMRADIO_UUID -> {
                // Firmware 2.5+: FromRadio carries the payload directly.
                if (value.isNotEmpty()) sink?.pushInbound(value)
                // Also drain any queued packets to stay current.
                scope.launch { drainFromRadio() }
            }
        }
    }

    /** Repeatedly read FROMRADIO until the firmware returns an empty payload. */
    private suspend fun drainFromRadio() {
        val g = gatt ?: return
        val char = fromRadioChar ?: return
        bleMutex.withLock {
            var attempts = 0
            while (attempts < 100 && !closed) {
                readSemaphore.drainPermits()
                readQueue.clear()
                if (!g.readCharacteristic(char)) {
                    Log.w(TAG, "readCharacteristic returned false (attempt=$attempts)")
                    break
                }
                if (!readSemaphore.tryAcquire(GATT_OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "readCharacteristic timed out (attempt=$attempts)")
                    break
                }
                val payload = readQueue.poll() ?: break
                if (payload.isEmpty()) break  // firmware signals "queue drained"
                sink?.pushInbound(payload)
                attempts++
            }
        }
    }

    private fun onSetupComplete() {
        if (setupCompleted) return
        setupCompleted = true
        Log.i(TAG, "BLE setup complete")
        emitSetup(true, null)
        startPolling()
    }

    private fun emitSetup(success: Boolean, error: String?) {
        setupListener?.onSetupComplete(success, error)
    }

    /** Safety-net poll: a missed FromNum notify would otherwise strand data. */
    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (!closed) {
                delay(POLL_INTERVAL_MS)
                if (setupCompleted) {
                    runCatching { drainFromRadio() }
                        .onFailure { Log.w(TAG, "Polling drain failed", it) }
                }
            }
        }
    }

    /** Android workaround: clear stale cached GATT service data. */
    private fun refreshGattCache(g: BluetoothGatt): Boolean = try {
        val method = g.javaClass.getMethod("refresh")
        method.invoke(g) as? Boolean ?: false
    } catch (e: Exception) {
        Log.w(TAG, "refreshGattCache failed", e)
        false
    }
}



