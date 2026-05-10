package re.chasam.voicetastic.core

import kotlinx.coroutines.flow.Flow

/**
 * Bidirectional byte-frame transport to a Meshtastic radio.
 *
 * Mirrors the Rust `voicetastic_core::Transport` trait — implementers
 * own framing (BLE GATT writes, COBS over serial, …) and surface
 * an inbound stream of already-deframed `FromRadio` payloads.
 *
 * The seam is intentionally narrow so the upstream Rust core can drop in
 * (via JNI / UniFFI) and consume `Transport` implementations supplied by
 * the Android side — see `INTEGRATION.md` for the migration path.
 */
interface MeshTransport {

    /**
     * Send one already-encoded `ToRadio` protobuf message.
     *
     * @throws TransportException on I/O failure.
     */
    suspend fun writeToRadio(bytes: ByteArray)

    /**
     * Inbound stream of decoded `FromRadio` payloads (already deframed by
     * the transport). The flow completes when the transport disconnects.
     */
    val inbound: Flow<ByteArray>

    /**
     * Tear down the underlying connection. Idempotent.
     */
    suspend fun disconnect()
}

/** Generic transport-layer failure. */
class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

