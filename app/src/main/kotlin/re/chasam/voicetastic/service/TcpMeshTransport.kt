package re.chasam.voicetastic.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import uniffi.voicetastic.MeshTransport
import uniffi.voicetastic.MeshTransportSink
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP transport for a WiFi/Ethernet Meshtastic node, implementing the UniFFI
 * [MeshTransport] foreign trait the same way [BleMeshTransport] /
 * [UsbMeshTransportV2] do — so the Rust [uniffi.voicetastic.MeshService] drives
 * the config-burst handshake over it without any core-side change.
 *
 * Meshtastic's TCP API (default port 4403) speaks the **same** length-delimited
 * stream protocol as serial, so we reuse [MeshSerialFraming] verbatim. Unlike
 * serial there is no wake sequence: the socket is ready the moment it connects.
 *
 *  1. [connect] opens the socket and starts the reader coroutine.
 *  2. The reader feeds bytes through [MeshSerialFraming.Parser] and pushes each
 *     decoded `FromRadio` frame into the Rust-side [MeshTransportSink].
 *  3. [writeToRadio] frames an outgoing `ToRadio` and writes it to the socket.
 *  4. [shutdown] closes the socket (idempotent); EOF/error on the reader also
 *     signals the sink so Rust moves to Disconnected.
 */
class TcpMeshTransport(
    private val host: String,
    private val port: Int,
) : MeshTransport {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val parser = MeshSerialFraming.Parser()

    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var sink: MeshTransportSink? = null
    @Volatile private var closed = false

    // Frames decoded before attachSink() runs are buffered here (under
    // sinkLock) and flushed in order once the sink is wired, so a fast
    // radio response (e.g. the first config-burst frame) that beats
    // attachSink isn't silently dropped.
    private val sinkLock = Any()
    private val preSinkBuffer = ArrayDeque<ByteArray>()
    // Serialises socket writes so two concurrent writeToRadio() callers can't
    // interleave the bytes of two frames on the stream.
    private val writeLock = Any()

    val isConnected: Boolean get() = !closed && socket?.isConnected == true

    /**
     * Open the TCP socket. Blocking — call off the main thread. Returns false
     * (and leaves the transport unusable) if the connect fails or times out.
     */
    fun connect(timeoutMs: Int = CONNECT_TIMEOUT_MS): Boolean {
        return try {
            val s = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, port), timeoutMs)
            }
            socket = s
            output = s.getOutputStream()
            startReader(s)
            Log.i(TAG, "TCP connected to $host:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "TCP connect to $host:$port failed", e)
            runCatching { socket?.close() }
            socket = null
            output = null
            false
        }
    }

    /** Wire the Rust-side inbound sink (call after [connect], before traffic). */
    fun attachSink(sink: MeshTransportSink) {
        val pending: List<ByteArray>
        synchronized(sinkLock) {
            this.sink = sink
            pending = preSinkBuffer.toList()
            preSinkBuffer.clear()
        }
        for (f in pending) sink.pushInbound(f)
    }

    private fun startReader(s: Socket) {
        scope.launch {
            val input = s.getInputStream()
            val buf = ByteArray(READ_BUFFER_SIZE)
            try {
                while (!closed) {
                    val n = input.read(buf)
                    if (n < 0) break          // EOF: peer closed
                    if (n == 0) continue
                    val frames = parser.feed(buf, 0, n)
                    for (f in frames) {
                        if (f.isEmpty()) continue
                        synchronized(sinkLock) {
                            val sk = sink
                            if (sk != null) {
                                sk.pushInbound(f)
                            } else {
                                if (preSinkBuffer.size >= PRE_SINK_BUFFER_MAX) preSinkBuffer.removeFirst()
                                preSinkBuffer.addLast(f)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (!closed) Log.w(TAG, "TCP read loop ended", e)
            } finally {
                // Link dropped: tell Rust so it transitions to Disconnected.
                sink?.shutdown()
            }
        }
    }

    override fun writeToRadio(data: ByteArray) {
        val o = output ?: return
        try {
            val framed = MeshSerialFraming.encode(data)
            synchronized(writeLock) {
                o.write(framed)
                o.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "TCP write failed", e)
        }
    }

    override fun shutdown() {
        if (closed) return
        closed = true
        sink?.shutdown()
        sink = null
        runCatching { socket?.close() }
        socket = null
        output = null
        scope.cancel()
    }

    companion object {
        private const val TAG = "TcpMeshTransport"
        /** Default Meshtastic TCP API port. */
        const val DEFAULT_PORT = 4403
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_BUFFER_SIZE = 4_096
        /** Cap on frames buffered before attachSink (drop-oldest on overflow). */
        private const val PRE_SINK_BUFFER_MAX = 64
    }
}
