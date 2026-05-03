package re.chasam.voicetastic.model

/**
 * Represents a text message in the mesh network.
 */
data class Message(
    val id: Int,
    val text: String,
    val from: String,
    val to: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isOutgoing: Boolean = false,
    val channel: Int = 0
)

