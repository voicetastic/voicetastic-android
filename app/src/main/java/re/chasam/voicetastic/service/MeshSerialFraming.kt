package re.chasam.voicetastic.service

/**
 * Meshtastic serial-stream framing.
 *
 * Every `ToRadio` / `FromRadio` protobuf is wrapped in a 4-byte header on the
 * wire so the receiver can find message boundaries on a byte stream:
 *
 * ```
 *   byte 0 : 0x94   (magic1, START1)
 *   byte 1 : 0xC3   (magic2, START2)
 *   byte 2 : len_msb (UInt16, big-endian)
 *   byte 3 : len_lsb
 *   byte 4..N : protobuf payload (≤ 512 bytes)
 * ```
 *
 * This is the framing used for USB / Bluetooth-classic SPP / TCP transports.
 * BLE does NOT use it (each GATT characteristic write is already one
 * protobuf message).
 *
 * Pure JVM, no Android dependencies — fully unit-testable.
 */
object MeshSerialFraming {
    const val START1: Byte = 0x94.toByte()
    const val START2: Byte = 0xC3.toByte()
    const val HEADER_SIZE = 4

    /** Firmware refuses anything larger; we mirror the limit. */
    const val MAX_PAYLOAD_SIZE = 512

    /**
     * Wrap a single protobuf payload with the 4-byte stream header.
     *
     * @throws IllegalArgumentException if [payload] is empty or larger than
     *   [MAX_PAYLOAD_SIZE].
     */
    fun encode(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty()) { "payload must not be empty" }
        require(payload.size <= MAX_PAYLOAD_SIZE) {
            "payload too large: ${payload.size} > $MAX_PAYLOAD_SIZE"
        }
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = START1
        out[1] = START2
        out[2] = ((payload.size ushr 8) and 0xFF).toByte()
        out[3] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
        return out
    }

    /**
     * Stateful stream parser. Feed it bytes as they arrive from the serial
     * port; it emits zero or more complete protobuf payloads per call.
     *
     * Robust against:
     *  - Frames split across multiple [feed] calls.
     *  - Multiple frames in a single buffer.
     *  - Garbage / boot-log noise before / between frames (resyncs on START1).
     *  - Oversized length fields (drops the bad header and resyncs).
     *
     * Not thread-safe; callers must externally serialise [feed] invocations.
     */
    class Parser {
        private val buffer = ArrayDeque<Byte>()

        /** For tests / diagnostics. */
        val bufferedByteCount: Int get() = buffer.size

        fun feed(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size - offset): List<ByteArray> {
            require(offset >= 0 && length >= 0 && offset + length <= chunk.size) {
                "invalid offset/length: offset=$offset length=$length size=${chunk.size}"
            }
            if (length == 0) return emptyList()
            for (i in offset until offset + length) buffer.addLast(chunk[i])
            return drain()
        }

        private fun drain(): List<ByteArray> {
            val out = mutableListOf<ByteArray>()
            while (true) {
                // Resync: discard bytes until we see START1.
                while (buffer.isNotEmpty() && buffer.first() != START1) {
                    buffer.removeFirst()
                }
                if (buffer.size < HEADER_SIZE) return out

                // Validate magic (we know [0] is START1; check [1]).
                val it = buffer.iterator()
                it.next() // START1
                val b1 = it.next()
                if (b1 != START2) {
                    // False positive on START1 — drop it and retry.
                    buffer.removeFirst()
                    continue
                }
                val msb = it.next().toInt() and 0xFF
                val lsb = it.next().toInt() and 0xFF
                val len = (msb shl 8) or lsb

                if (len == 0 || len > MAX_PAYLOAD_SIZE) {
                    // Bad length — drop the magic and resync.
                    buffer.removeFirst()
                    continue
                }
                if (buffer.size < HEADER_SIZE + len) return out  // wait for more

                // Pop header + payload.
                repeat(HEADER_SIZE) { buffer.removeFirst() }
                val payload = ByteArray(len)
                for (i in 0 until len) payload[i] = buffer.removeFirst()
                out.add(payload)
            }
        }

        fun reset() { buffer.clear() }
    }
}

