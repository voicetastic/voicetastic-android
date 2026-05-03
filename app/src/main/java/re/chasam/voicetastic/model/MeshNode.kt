package re.chasam.voicetastic.model

/**
 * Represents a node in the Meshtastic mesh network.
 */
data class MeshNode(
    val nodeId: String,
    val longName: String = "Unknown",
    val shortName: String = "??",
    val lastHeard: Long = 0L,
    val batteryLevel: Int? = null,
    val snr: Float? = null
)

