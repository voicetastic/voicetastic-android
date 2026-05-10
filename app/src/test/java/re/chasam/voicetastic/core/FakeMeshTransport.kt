package re.chasam.voicetastic.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback [MeshTransport] for unit tests: caller writes go into [written],
 * and tests can push inbound bytes via [pushInbound]. Mirrors the role of
 * the `tests/loopback_transport.rs` fixture in the upstream Rust core.
 */
class FakeMeshTransport(
    inboundBufferCapacity: Int = 64,
) : MeshTransport {

    val written: MutableList<ByteArray> = CopyOnWriteArrayList()
    private val inboundChannel = Channel<ByteArray>(inboundBufferCapacity)
    private val closed = AtomicBoolean(false)

    /** Whether [disconnect] has been called. */
    val isDisconnected: Boolean get() = closed.get()

    override suspend fun writeToRadio(bytes: ByteArray) {
        if (closed.get()) throw TransportException("transport closed")
        written.add(bytes.copyOf())
    }

    override val inbound: Flow<ByteArray>
        get() = inboundChannel.receiveAsFlow()

    override suspend fun disconnect() {
        if (closed.compareAndSet(false, true)) {
            inboundChannel.close()
        }
    }

    /** Push one inbound `FromRadio`-style payload. Suspends if buffer full. */
    suspend fun pushInbound(bytes: ByteArray) {
        inboundChannel.send(bytes.copyOf())
    }
}

