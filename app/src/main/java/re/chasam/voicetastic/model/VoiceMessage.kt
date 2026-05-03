package re.chasam.voicetastic.model

/**
 * Represents a voice message in the mesh network.
 */
data class VoiceMessage(
    val messageId: Int,
    val from: String,
    val to: String,
    val audioData: ByteArray,
    val timestamp: Long = System.currentTimeMillis(),
    val isOutgoing: Boolean = false,
    val isComplete: Boolean = false,
    val totalChunks: Int = 0,
    val receivedChunks: Int = 0,
    val bitrateIndex: Int = 0,
    val channel: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoiceMessage) return false
        return messageId == other.messageId && from == other.from
    }

    override fun hashCode(): Int = 31 * messageId + from.hashCode()
}

