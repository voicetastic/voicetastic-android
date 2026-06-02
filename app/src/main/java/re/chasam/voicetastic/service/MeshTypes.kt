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

/**
 * Coarse delivery outcome for an outgoing text/data packet. Mirrors the
 * Rust bridge's `AckResultKind` (which in turn flattens core's
 * `AckResult`). UI surfaces this on outgoing chat bubbles as the
 * ⏳/✓/❌/⏱ icon.
 */
enum class DeliveryStatus {
    /** No ack/nak yet; default for freshly-sent messages. */
    Pending,

    /** Firmware reported a successful delivery routing ack. */
    Delivered,

    /** Firmware reported a NAK or other routing failure. */
    Failed,

    /** No ack within the firmware's retry window. */
    TimedOut,

    /** Service shut down before the ack arrived (e.g. disconnect). */
    Cancelled,
}

/**
 * One per-packet ack/nak event surfaced from [MeshFacade.ackEvents].
 * `packetId` matches the value returned by
 * [MeshFacade.sendTextTracked] / [MeshFacade.sendData].
 */
data class MeshAckEvent(val packetId: UInt, val status: DeliveryStatus)

/**
 * Severity tier used by the Debug log surface. Mirrors the desktop
 * GUI's `DebugLevel` enum so a future structured-log channel through
 * the bridge would map straight across.
 */
enum class DebugLevel { Info, Warn, Error }

/**
 * One in-app event surfaced in the Debug log panel. `source` groups
 * entries by subsystem ("transport", "protocol", "voice", "mesh",
 * "settings") so the panel can filter; `message` is a short
 * human-readable summary.
 */
data class DebugEntry(
    val at: Long = System.currentTimeMillis(),
    val level: DebugLevel = DebugLevel.Info,
    val source: String = "",
    val message: String = "",
)

/**
 * One telemetry sample of a peer's latest reported metrics. Stored
 * in [MeshFacade.nodeHistory] keyed by node_num; the node-detail
 * dialog renders these as sparklines so the user can see battery
 * + signal trends without leaving the chat tab.
 *
 * `battery` is null when the firmware didn't include `device_metrics`
 * on that NodeInfo update; `snr` is always present (may be 0).
 */
data class NodeSample(
    val at: Long,
    val battery: Int?,
    val snr: Float,
)
