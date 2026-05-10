package re.chasam.voicetastic.core

/**
 * Coarse connection state of a [MeshTransport] / `MeshService` pair.
 *
 * Mirrors `voicetastic_core::service::ConnectionState`:
 * `Disconnected → Connecting → Connected → Configuring → Ready`.
 */
enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Configuring,
    Ready,
}

/**
 * One inbound text message routed off the mesh.
 * Mirrors Rust `service::types::IncomingText`.
 */
data class IncomingText(
    val from: Int,
    val fromId: String,
    val to: Int,
    val channel: Int,
    val text: String,
    val rxTime: Int,
    val rxSnr: Float,
    val rxRssi: Int,
)

/**
 * One inbound application data packet (used for voice + private-app).
 * Mirrors Rust `service::types::IncomingData`.
 */
data class IncomingData(
    val from: Int,
    val to: Int,
    val channel: Int,
    val portnum: Int,
    val payload: ByteArray,
    val rxTime: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IncomingData) return false
        return from == other.from && to == other.to && channel == other.channel &&
            portnum == other.portnum && rxTime == other.rxTime &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = from
        result = 31 * result + to
        result = 31 * result + channel
        result = 31 * result + portnum
        result = 31 * result + rxTime
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

