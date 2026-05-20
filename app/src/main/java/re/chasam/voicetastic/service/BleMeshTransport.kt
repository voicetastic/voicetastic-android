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
import uniffi.voicetastic.MeshTransport
import uniffi.voicetastic.MeshTransportSink
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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

    /**
     * Serialises every GATT op (reads, writes, descriptor writes).
     *
     * Android's `BluetoothGatt` only allows one outstanding operation
     * at a time, so we *must* serialise. We used to do this with a
     * coroutine `Mutex` which forced [doWrite] to be `suspend`, which
     * in turn forced the UniFFI callback in [writeToRadio] to wrap
     * everything in `runBlocking { ... }` — a fresh event loop per
     * write, plus a blocked Rust runtime worker. A plain
     * [ReentrantLock] gets us the same serialisation without either
     * cost: the BLE callbacks and the Rust write path are both
     * already blocking by design.
     */
    private val bleLock = ReentrantLock()

    /**
     * Counts pending drain requests across notify threads.
     *
     * `getAndIncrement` on the producer side and `decrementAndGet` on
     * the consumer side give us a race-free "did anything ask for a
     * drain while we were draining?" check, which the previous
     * `AtomicBoolean` flag could miss: a notify arriving between the
     * drain finishing and the flag clearing was silently dropped, so
     * the very last packet of a burst could sit in the firmware
     * queue until the 30 s safety-net poll picked it up. With the
     * counter, every increment is matched by exactly one drain pass.
     *
     * Modelled on the upstream desktop `Connection::subscribe_inbound`
     * which drains on every notify; the counter just collapses N
     * concurrent requests into a single drain task without losing any.
     */
    private val drainQueued = AtomicInteger(0)

    @Volatile private var sink: MeshTransportSink? = null
    @Volatile private var setupListener: SetupListener? = null

    /**
     * Negotiated ATT MTU. Defaults to the BLE minimum (23) so the
     * `WRITE_NO_RESPONSE` size check is safe even if a write somehow
     * races onMtuChanged. Updated from [onMtuChanged] on GATT_SUCCESS.
     *
     * `WRITE_NO_RESPONSE` is hard-capped at MTU − 3 bytes per write
     * (1 byte opcode + 2 byte handle = 3 byte ATT overhead). Anything
     * larger is silently truncated by the controller — the API call
     * still returns SUCCESS, but the firmware receives a partial
     * protobuf and rejects it. For voice DATA chunks whose ToRadio
     * encoding lands around 250-270 bytes that meant chunks > MTU − 3
     * never made it over LoRa, even though the sender thought they
     * had.
     */
    @Volatile private var negotiatedMtu = 23

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
     * directly here is the cheapest option — no coroutine machinery,
     * no `runBlocking` event loop, just take the GATT lock and write.
     */
    override fun writeToRadio(data: ByteArray) {
        if (closed) {
            Log.w(TAG, "writeToRadio after close; dropping ${data.size} bytes")
            return
        }
        doWrite(data)
    }

    private fun doWrite(bytes: ByteArray) {
        val gatt = gatt ?: return
        val char = toRadioChar ?: run {
            Log.w(TAG, "writeToRadio before setup; dropping ${bytes.size} bytes")
            return
        }
        bleLock.withLock {
            writeSemaphore.drainPermits()
            // Pick the write type based on payload size against the
            // negotiated MTU:
            //
            //   * `WRITE_NO_RESPONSE` is hard-capped at MTU − 3 bytes
            //     per write; anything larger is silently truncated by
            //     the controller. Keeps the per-write GATT ACK
            //     round-trip off the wire (~12 ms at HIGH priority,
            //     ~30-50 ms at default), which matters for the burst
            //     of small frames around connect (`WantConfigId`,
            //     admin pulls) and for NACK / control traffic.
            //
            //   * `WRITE_DEFAULT` (with response) automatically uses
            //     GATT *Long Write* (Prepare/Execute) when the payload
            //     exceeds MTU − 3, so frames up to the firmware's
            //     accept limit get there intact at the cost of one
            //     ACK round-trip. Required for voice DATA frames
            //     because a 16-byte v3 header + 215-byte body wraps
            //     to ~260 bytes of ToRadio protobuf, which truncates
            //     on a typical 255-byte negotiated MTU.
            val maxNoResponseBytes = negotiatedMtu - 3
            val writeType = if (bytes.size > maxNoResponseBytes) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, bytes, writeType) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    char.value = bytes
                    char.writeType = writeType
                    gatt.writeCharacteristic(char)
                }
            }
            if (!initiated) {
                Log.e(TAG, "Failed to initiate BLE write (${bytes.size} bytes)")
                return@withLock
            }
            // Even with WRITE_TYPE_NO_RESPONSE, Android fires
            // `onCharacteristicWrite` once the controller has accepted
            // the buffer. We still wait for it so back-to-back writes
            // don't stack up faster than the controller can drain its
            // own TX queue (which on some stacks silently drops frames
            // past a small threshold). The wait is now ~one packet
            // time instead of one full ACK round-trip.
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
                    Log.i(TAG, "GATT connected; requesting HIGH priority + MTU")
                    serviceDiscoveryRetries = 0
                    // Bump the connection interval down to ~7.5–15 ms.
                    // Default Android priority leaves it around 30–50 ms,
                    // which throttles the post-`WantConfigId` burst and
                    // every voice frame send to a fraction of what the
                    // link can actually carry. We keep HIGH for the whole
                    // session because voicetastic is bursty (config +
                    // voice) and the battery cost on the *radio* side is
                    // negligible compared to its TX duty cycle.
                    runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                        .onFailure { Log.w(TAG, "requestConnectionPriority failed", it) }
                    // MTU first, then services. A *very* short settle
                    // lets any in-flight bonding callback land before we
                    // start asking for things; the 600 ms we used to
                    // wait here was a relic of much older Android
                    // stacks and just added dead time to every connect.
                    scope.launch {
                        delay(120)
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
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
            }
            // Ask the controller to switch to LE 2M PHY where supported.
            // The call is a hint — if either side lacks 2M PHY the stack
            // silently keeps 1M, so this is safe on every radio. On
            // chips that do support it (ESP32-S3, nRF52, RAK4631, ...)
            // raw link throughput roughly doubles, which is where the
            // voice send loop spends most of its time.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching {
                    g.setPreferredPhy(
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_LE_2M_MASK,
                        BluetoothDevice.PHY_OPTION_NO_PREFERRED,
                    )
                }.onFailure { Log.w(TAG, "setPreferredPhy failed", it) }
            }
            g.discoverServices()
        }

        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            Log.i(TAG, "PHY update: tx=$txPhy rx=$rxPhy status=$status")
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
                scheduleDrain()
            }
            MeshtasticBle.FROMRADIO_UUID -> {
                // Firmware 2.5+: FromRadio carries the payload directly.
                // Each notify == one packet, so the on-notify drain we
                // used to schedule here was serialising a redundant
                // read behind `bleLock` *between every two notifies*,
                // which throttled the config burst and voice receive
                // path. The periodic safety-net `startPolling()` still
                // catches genuinely missed notifies.
                if (value.isNotEmpty()) sink?.pushInbound(value)
                else scheduleDrain()
            }
        }
    }

    /**
     * Launch a drain unless one is already in flight; if it is, just
     * note that another drain is needed when the current one finishes.
     *
     * The legacy FROMNUM path and the empty-FROMRADIO path can both
     * fire several times per second during a config burst. Without
     * coalescing, each fire spawns its own coroutine, every one of
     * which then queues behind [bleLock] and re-runs the full
     * read-until-empty loop. The first drain already reads to empty,
     * so subsequent passes are pure waste — but we still need *one*
     * follow-up pass per request to handle the race where a notify
     * arrives between "drain reads empty" and "drain releases the
     * counter". The counter pattern below guarantees that.
     */
    private fun scheduleDrain() {
        if (drainQueued.getAndIncrement() != 0) return
        scope.launch {
            do {
                drainFromRadio()
                // decrementAndGet is the linearisation point: any
                // notify that incremented the counter before this
                // moment will keep us in the loop; any that
                // increments after will trigger a fresh launch
                // because the counter is back at 0.
            } while (drainQueued.decrementAndGet() != 0)
        }
    }

    /** Repeatedly read FROMRADIO until the firmware returns an empty payload. */
    private fun drainFromRadio() {
        val g = gatt ?: return
        val char = fromRadioChar ?: return
        bleLock.withLock {
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
                if (setupCompleted) scheduleDrain()
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



