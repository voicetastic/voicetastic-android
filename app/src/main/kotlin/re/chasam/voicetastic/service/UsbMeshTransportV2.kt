package re.chasam.voicetastic.service

import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import uniffi.voicetastic.MeshTransport
import uniffi.voicetastic.MeshTransportSink

/**
 * USB serial adapter that implements the UniFFI [MeshTransport] foreign
 * trait by delegating to the existing [UsbMeshTransport].
 *
 * The legacy `UsbMeshTransport` already handles all the platform glue:
 * USB permission flow, [UsbSerialDriver] enumeration, DTR/RTS, the
 * Meshtastic wake sequence, the 0x94 stream-framer parser. This adapter
 * is a thin pass-through that:
 *
 * 1. Forwards [writeToRadio] → [UsbMeshTransport.write] (with the same
 *    framing the legacy code uses).
 * 2. Pipes each decoded `FromRadio` payload from
 *    [UsbMeshTransport.incomingFromRadio] into the Rust-side
 *    [MeshTransportSink].
 * 3. Forwards [close] → [UsbMeshTransport.disconnect].
 *
 * Usage mirrors [BleMeshTransport]:
 *
 * ```kotlin
 * val legacyUsb = UsbMeshTransport(context)
 * // ... discover + permission flow + legacyUsb.connect(driver) ...
 * val transport = UsbMeshTransportV2(legacyUsb)
 * val sink = meshService.connect(transport, settleDelayMs = 0u)
 * transport.attachSink(sink)
 * ```
 *
 * Lifetime of the wrapped [UsbMeshTransport] is **not** owned by this
 * adapter — the caller still controls when the underlying transport is
 * destroyed. [close] only disconnects the active port, matching the
 * idempotent contract of [MeshTransport].
 */
class UsbMeshTransportV2(
    private val inner: UsbMeshTransport,
) : MeshTransport {

    companion object {
        private const val TAG = "UsbMeshTransportV2"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var sink: MeshTransportSink? = null
    private var pumpJob: Job? = null
    @Volatile private var closed = false

    /**
     * Wire the Rust-side inbound sink. Starts the
     * [UsbMeshTransport.incomingFromRadio] → [MeshTransportSink.pushInbound]
     * pump. Calling twice replaces the sink (previous sink is **not**
     * closed by this class).
     */
    fun attachSink(sink: MeshTransportSink) {
        this.sink = sink
        if (pumpJob == null) startPump()
    }

    private fun startPump() {
        pumpJob = scope.launch {
            // Forward every frame (no `collectLatest` — we don't want to
            // cancel an in-flight delivery just because the next frame
            // showed up). `incomingFromRadio` is a SharedFlow with a
            // 64-slot buffer, so a brief Rust-side stall doesn't lose
            // data either.
            inner.incomingFromRadio.collect { bytes ->
                val s = sink ?: return@collect
                if (bytes.isNotEmpty()) s.pushInbound(bytes)
            }
        }
    }

    override fun writeToRadio(data: ByteArray) {
        if (closed) {
            Log.w(TAG, "writeToRadio after close; dropping ${data.size} bytes")
            return
        }
        val ok = inner.write(data)
        if (!ok) Log.w(TAG, "USB write failed (${data.size} bytes)")
    }

    override fun shutdown() {
        if (closed) return
        closed = true
        pumpJob?.cancel(); pumpJob = null
        // Notify Rust that the wire is gone. Safe to call even before
        // a sink was attached — `sink?` short-circuits. Renamed from
        // `close` to avoid `AutoCloseable.close()` ambiguity in the
        // generated Kotlin bindings (see [MeshTransport] UDL comment).
        sink?.shutdown()
        sink = null
        inner.disconnect()
        scope.cancel()
        Log.i(TAG, "UsbMeshTransportV2 shut down")
    }
}






