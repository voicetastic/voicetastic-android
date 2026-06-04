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
        this.sink = sink
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
                    val sk = sink ?: continue
                    for (f in frames) if (f.isNotEmpty()) sk.pushInbound(f)
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
            o.write(MeshSerialFraming.encode(data))
            o.flush()
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
    }
}
