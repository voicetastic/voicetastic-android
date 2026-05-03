package re.chasam.voicetastic.model

/**
 * Unified chat item representing either a text or voice message.
 *
 * `contactKey` identifies the conversation this message belongs to:
 *   - "broadcast" → the channel/group broadcast conversation
 *   - a node ID (e.g. "!12345678") → a direct conversation with that node
 *
 * The contact key is computed deterministically when the message is ingested,
 * so filtering becomes a simple equality check (no perspective-dependent
 * re-computation at view time).
 */
sealed class ChatItem {
    abstract val id: Int
    abstract val from: String
    abstract val to: String
    abstract val timestamp: Long
    abstract val isOutgoing: Boolean
    abstract val channel: Int
    abstract val contactKey: String

    data class Text(
        override val id: Int,
        override val from: String,
        override val to: String,
        override val timestamp: Long = System.currentTimeMillis(),
        override val isOutgoing: Boolean = false,
        val text: String,
        override val channel: Int = 0,
        override val contactKey: String = "broadcast"
    ) : ChatItem()

    data class Voice(
        override val id: Int,
        override val from: String,
        override val to: String,
        override val timestamp: Long = System.currentTimeMillis(),
        override val isOutgoing: Boolean = false,
        val audioData: ByteArray,
        val isComplete: Boolean = true,
        val totalChunks: Int = 0,
        val receivedChunks: Int = 0,
        val bitrateIndex: Int = 0,
        override val channel: Int = 0,
        override val contactKey: String = "broadcast"
    ) : ChatItem() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Voice) return false
            return id == other.id && from == other.from
        }

        override fun hashCode(): Int = 31 * id + from.hashCode()
    }
}
