package re.chasam.voicetastic.voice

import re.chasam.voicetastic.model.AmrNbBitrate
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Splits AMR-NB audio data into mesh-compatible chunks.
 *
 * Chunk format (total max 230 bytes per Meshtastic packet):
 * - Header: 6 bytes
 *   - [0..1] messageId (UInt16, big-endian) — unique voice message identifier
 *   - [2..3] chunkIndex (UInt16, big-endian) — 0-based chunk index
 *   - [4]    totalChunks (UInt8) — total number of chunks (max 255)
 *   - [5]    bitrateIndex (UInt8) — index into AmrNbBitrate enum
 * - Payload: up to 224 bytes of AMR-NB audio data
 */
object VoiceChunker {

    const val HEADER_SIZE = 6
    const val MAX_PACKET_SIZE = 230
    const val MAX_PAYLOAD_SIZE = MAX_PACKET_SIZE - HEADER_SIZE // 224

    /**
     * Split audio data into numbered chunks ready for transmission.
     *
     * @param audioData raw AMR-NB file bytes (including AMR header)
     * @param messageId unique message identifier (will be truncated to UInt16)
     * @param bitrate the AMR-NB bitrate used for encoding
     * @return list of chunk byte arrays, each ready to send as a mesh data packet
     * @throws IllegalArgumentException if audio data would require more than 255 chunks
     */
    fun chunkAudio(audioData: ByteArray, messageId: Int, bitrate: AmrNbBitrate): List<ByteArray> {
        if (audioData.isEmpty()) return emptyList()

        val totalChunks = (audioData.size + MAX_PAYLOAD_SIZE - 1) / MAX_PAYLOAD_SIZE
        require(totalChunks <= 255) {
            "Audio data too large: $totalChunks chunks needed (max 255). " +
                    "Data size: ${audioData.size} bytes"
        }

        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        var index = 0

        while (offset < audioData.size) {
            val payloadSize = minOf(MAX_PAYLOAD_SIZE, audioData.size - offset)
            val chunk = ByteArray(HEADER_SIZE + payloadSize)

            // Write header
            writeHeader(chunk, messageId, index, totalChunks, bitrate)

            // Copy audio payload
            System.arraycopy(audioData, offset, chunk, HEADER_SIZE, payloadSize)

            chunks.add(chunk)
            offset += payloadSize
            index++
        }

        return chunks
    }

    /**
     * Parse the header from a chunk.
     * @return ChunkHeader with parsed fields, or null if data is too short
     */
    fun parseHeader(chunk: ByteArray): ChunkHeader? {
        if (chunk.size < HEADER_SIZE) return null

        val buf = ByteBuffer.wrap(chunk, 0, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        val msgId = buf.short.toInt() and 0xFFFF
        val chunkIdx = buf.short.toInt() and 0xFFFF
        val total = chunk[4].toInt() and 0xFF
        val bitrateIdx = chunk[5].toInt() and 0xFF

        return ChunkHeader(msgId, chunkIdx, total, bitrateIdx)
    }

    /**
     * Extract the audio payload from a chunk (everything after the header).
     */
    fun extractPayload(chunk: ByteArray): ByteArray {
        if (chunk.size <= HEADER_SIZE) return ByteArray(0)
        return chunk.copyOfRange(HEADER_SIZE, chunk.size)
    }

    private fun writeHeader(
        chunk: ByteArray,
        messageId: Int,
        chunkIndex: Int,
        totalChunks: Int,
        bitrate: AmrNbBitrate
    ) {
        val buf = ByteBuffer.wrap(chunk, 0, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.putShort((messageId and 0xFFFF).toShort())
        buf.putShort((chunkIndex and 0xFFFF).toShort())
        chunk[4] = (totalChunks and 0xFF).toByte()
        chunk[5] = bitrate.ordinal.toByte()
    }

    /**
     * Parsed chunk header.
     */
    data class ChunkHeader(
        val messageId: Int,
        val chunkIndex: Int,
        val totalChunks: Int,
        val bitrateIndex: Int
    ) {
        val bitrate: AmrNbBitrate
            get() = AmrNbBitrate.entries.getOrElse(bitrateIndex) { AmrNbBitrate.MR795 }
    }
}

