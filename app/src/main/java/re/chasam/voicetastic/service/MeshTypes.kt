package re.chasam.voicetastic.service

/**
 * Top-level types shared by [MeshFacade] and its implementation
 * [MeshServiceManager]. Lifted out of MeshServiceManager so the
 * interface can be self-contained — having the interface reference
 * `MeshServiceManager.IncomingText` would defeat the point of
 * extracting it.
 */

/** Which transport is currently active on the Rust mesh session. */
enum class TransportType { NONE, BLE, USB }

/** Inbound text message after Rust → Kotlin demarshalling. */
data class IncomingText(
    val from: String,
    val to: String,
    val text: String,
    val channel: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
)

/** Inbound data packet after Rust → Kotlin demarshalling. */
data class IncomingData(
    val from: String,
    val to: String,
    val portNum: Int,
    val payload: ByteArray,
    val channel: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
) {
    // payload is a ByteArray — Kotlin's compiler-generated equals uses
    // reference equality on arrays, so override for content equality.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IncomingData) return false
        return from == other.from &&
            to == other.to &&
            portNum == other.portNum &&
            channel == other.channel &&
            timestamp == other.timestamp &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = from.hashCode()
        result = 31 * result + to.hashCode()
        result = 31 * result + portNum
        result = 31 * result + channel
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
