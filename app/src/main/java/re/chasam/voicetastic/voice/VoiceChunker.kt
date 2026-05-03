package re.chasam.voicetastic.voice

import re.chasam.voicetastic.model.AmrNbBitrate
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Splits AMR-NB audio data into mesh-compatible chunks.
 *
 * **Protocol v1** chunk format (total max 231 bytes per Meshtastic packet):
 * - Header: 6 bytes
 *   - [0]    version    (UInt8) — protocol version, currently 1
 *   - [1..2] messageId  (UInt16, big-endian) — unique voice message identifier
 *   - [3]    chunkIndex (UInt8) — 0-based chunk index (max 255)
 *   - [4]    totalChunks(UInt8) — total number of chunks (max 255)
 *   - [5]    bitrateIndex(UInt8) — index into AmrNbBitrate enum
 * - Payload: up to 225 bytes of raw AMR-NB frame data (no AMR file header)
 *
 * The AMR-NB file header (`#!AMR\n`) is stripped before chunking on the sender
 * side and prepended during reassembly on the receiver side. This avoids the
 * duplicate-header bug and saves 6 bytes in chunk 0.
 */
object VoiceChunker {

    const val PROTOCOL_VERSION = 1
    const val HEADER_SIZE = 6
    const val MAX_PACKET_SIZE = 231
    const val MAX_PAYLOAD_SIZE = MAX_PACKET_SIZE - HEADER_SIZE // 225

    /** AMR-NB file header: "#!AMR\n" */
    val AMR_FILE_HEADER = byteArrayOf(0x23, 0x21, 0x41, 0x4D, 0x52, 0x0A)

    /**
     * Split audio data into numbered chunks ready for transmission.
     *
     * The AMR-NB file header is automatically stripped if present.
     * On the receive side, [VoiceAssembler] re-adds it during reassembly.
     *
     * @param audioData raw AMR-NB file bytes (may include AMR file header)
     * @param messageId unique message identifier (will be truncated to UInt16)
     * @param bitrate the AMR-NB bitrate used for encoding
     * @return list of chunk byte arrays, each ready to send as a mesh data packet
     * @throws IllegalArgumentException if audio data would require more than 255 chunks
     */
    fun chunkAudio(audioData: ByteArray, messageId: Int, bitrate: AmrNbBitrate): List<ByteArray> {
        if (audioData.isEmpty()) return emptyList()

        // Strip AMR file header if present
        val frameData = stripAmrHeader(audioData)
        if (frameData.isEmpty()) return emptyList()

        val totalChunks = (frameData.size + MAX_PAYLOAD_SIZE - 1) / MAX_PAYLOAD_SIZE
        require(totalChunks <= 255) {
            "Audio data too large: $totalChunks chunks needed (max 255). " +
                    "Data size: ${frameData.size} bytes"
        }

        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        var index = 0

        while (offset < frameData.size) {
            val payloadSize = minOf(MAX_PAYLOAD_SIZE, frameData.size - offset)
            val chunk = ByteArray(HEADER_SIZE + payloadSize)

            // Write header
            writeHeader(chunk, messageId, index, totalChunks, bitrate)

            // Copy audio payload
            System.arraycopy(frameData, offset, chunk, HEADER_SIZE, payloadSize)

            chunks.add(chunk)
            offset += payloadSize
            index++
        }

        return chunks
    }

    /**
     * Parse the header from a chunk.
     * @return ChunkHeader with parsed fields, or null if data is too short or version is unknown
     */
    fun parseHeader(chunk: ByteArray): ChunkHeader? {
        if (chunk.size < HEADER_SIZE) return null

        val version = chunk[0].toInt() and 0xFF
        if (version != PROTOCOL_VERSION) return null

        val buf = ByteBuffer.wrap(chunk, 1, 2).order(ByteOrder.BIG_ENDIAN)
        val msgId = buf.short.toInt() and 0xFFFF
        val chunkIdx = chunk[3].toInt() and 0xFF
        val total = chunk[4].toInt() and 0xFF
        val bitrateIdx = chunk[5].toInt() and 0xFF

        return ChunkHeader(version, msgId, chunkIdx, total, bitrateIdx)
    }

    /**
     * Extract the audio payload from a chunk (everything after the header).
     */
    fun extractPayload(chunk: ByteArray): ByteArray {
        if (chunk.size <= HEADER_SIZE) return ByteArray(0)
        return chunk.copyOfRange(HEADER_SIZE, chunk.size)
    }

    /**
     * Strip the AMR-NB file header (#!AMR\n) if present.
     * Returns audio frame data without the file header.
     */
    internal fun stripAmrHeader(data: ByteArray): ByteArray {
        if (data.size >= AMR_FILE_HEADER.size) {
            val prefix = data.copyOfRange(0, AMR_FILE_HEADER.size)
            if (prefix.contentEquals(AMR_FILE_HEADER)) {
                return data.copyOfRange(AMR_FILE_HEADER.size, data.size)
            }
        }
        // No AMR header found — assume raw frame data
        return data
    }

    private fun writeHeader(
        chunk: ByteArray,
        messageId: Int,
        chunkIndex: Int,
        totalChunks: Int,
        bitrate: AmrNbBitrate
    ) {
        chunk[0] = PROTOCOL_VERSION.toByte()
        val buf = ByteBuffer.wrap(chunk, 1, 2).order(ByteOrder.BIG_ENDIAN)
        buf.putShort((messageId and 0xFFFF).toShort())
        chunk[3] = (chunkIndex and 0xFF).toByte()
        chunk[4] = (totalChunks and 0xFF).toByte()
        chunk[5] = bitrate.ordinal.toByte()
    }

    /**
     * Parsed chunk header.
     */
    data class ChunkHeader(
        val version: Int,
        val messageId: Int,
        val chunkIndex: Int,
        val totalChunks: Int,
        val bitrateIndex: Int
    ) {
        val bitrate: AmrNbBitrate
            get() = AmrNbBitrate.entries.getOrElse(bitrateIndex) { AmrNbBitrate.MR795 }
    }
}
