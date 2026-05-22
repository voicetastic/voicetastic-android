package re.chasam.voicetastic.model

import uniffi.voicetastic.VoiceCodec

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
        val codec: VoiceCodec = VoiceCodec.AmrNb,
        val isComplete: Boolean = true,
        val totalChunks: Int = 0,
        val receivedChunks: Int = 0,
        val bitrateIndex: Int = 0,
        override val channel: Int = 0,
        override val contactKey: String = "broadcast"
    ) : ChatItem() {
        // ByteArray uses reference equality by default — the compiler-
        // generated `equals` on this data class would compare audioData
        // by identity, so two Voice items with identical contents but
        // different byte buffers would compare unequal. Override to use
        // content-equality for audioData; everything else is value-typed
        // and behaves correctly under the default rules.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Voice) return false
            return id == other.id &&
                from == other.from &&
                to == other.to &&
                timestamp == other.timestamp &&
                isOutgoing == other.isOutgoing &&
                codec == other.codec &&
                isComplete == other.isComplete &&
                totalChunks == other.totalChunks &&
                receivedChunks == other.receivedChunks &&
                bitrateIndex == other.bitrateIndex &&
                channel == other.channel &&
                contactKey == other.contactKey &&
                audioData.contentEquals(other.audioData)
        }

        override fun hashCode(): Int {
            var result = id
            result = 31 * result + from.hashCode()
            result = 31 * result + to.hashCode()
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + isOutgoing.hashCode()
            result = 31 * result + codec.hashCode()
            result = 31 * result + isComplete.hashCode()
            result = 31 * result + totalChunks
            result = 31 * result + receivedChunks
            result = 31 * result + bitrateIndex
            result = 31 * result + channel
            result = 31 * result + contactKey.hashCode()
            result = 31 * result + audioData.contentHashCode()
            return result
        }
    }
}
