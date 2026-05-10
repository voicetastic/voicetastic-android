package re.chasam.voicetastic.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * High-level facade over a [MeshTransport].
 *
 * The contract mirrors the Rust `voicetastic_core::service::MeshService`
 * surface from <https://git.cha-sam.re/acarteron/voicetastic-desktop>.
 * Today the Android app provides one implementation (`MeshServiceManager`,
 * BLE+USB); after Rust-core integration the same interface will be backed
 * by a JNI/UniFFI bridge to the upstream crate, with the Android side only
 * supplying the [MeshTransport].
 *
 * Channels in the Rust API map to Kotlin [StateFlow]/[Flow] here:
 *  - `watch_*` → [StateFlow] (last value cached, multi-subscriber).
 *  - `subscribe_*` → [Flow] (hot, multi-subscriber, no replay).
 */
interface MeshService {

    /** Coarse connection state. */
    val state: StateFlow<ConnectionState>

    /** Local node number, if known. Required as `to=` for admin writes. */
    val myNodeNum: StateFlow<Int?>

    /** Inbound text messages routed off the mesh. */
    val incomingText: Flow<IncomingText>

    /** Inbound application data packets (voice + private-app). */
    val incomingData: Flow<IncomingData>

    /** Emits the `configId` echoed back by the radio on `ConfigCompleteId`. */
    val configComplete: Flow<Int>

    /**
     * Connect using a caller-supplied [MeshTransport].
     *
     * The service sends a `WantConfigId` handshake, enters
     * [ConnectionState.Configuring] until the radio finishes its config burst,
     * then moves to [ConnectionState.Ready].
     */
    suspend fun connectWithTransport(
        transport: MeshTransport,
        settleDelayMs: Long = 0L,
    )

    /** Send a plain UTF-8 text message on [channel]. */
    suspend fun sendText(text: String, to: Int = NodeIds.BROADCAST_ADDR, channel: Int = 0)

    /** Send an application data packet on [portnum]. */
    suspend fun sendData(
        portnum: Int,
        payload: ByteArray,
        to: Int = NodeIds.BROADCAST_ADDR,
        channel: Int = 0,
        wantAck: Boolean = false,
    )

    /** Re-request the entire configuration burst. */
    suspend fun refreshConfig()

    /** Tear down the underlying transport. Idempotent. */
    suspend fun disconnect()
}

