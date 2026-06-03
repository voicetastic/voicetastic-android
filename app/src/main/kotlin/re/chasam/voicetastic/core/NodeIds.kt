package re.chasam.voicetastic.core

/**
 * Node-id helpers.
 *
 * Meshtastic uses a `u32` node number internally; the textual form used in
 * the UI is `"!" + 8 lowercase hex digits` (e.g. `!a1b2c3d4`).
 *
 * Wire-compatible with the upstream Rust core
 * (`crates/voicetastic-core/src/ids.rs`) at
 * <https://git.cha-sam.re/acarteron/voicetastic-desktop>.
 */
object NodeIds {

    /** Meshtastic broadcast destination (`0xFFFFFFFF`). */
    const val BROADCAST_ADDR: Int = -1 // 0xFFFFFFFF as signed Int

    /** Format a node number as `!aabbccdd` (always 8 lowercase hex digits). */
    fun nodeNumToId(num: Int): String = "!%08x".format(num)

    /**
     * Parse a `!aabbccdd` node id into a node number.
     *
     * Returns `null` for any invalid input — wrong prefix, wrong length, or
     * non-hex characters. Mirrors the strict `Error::InvalidNodeId` behaviour
     * of the Rust core, where the API surfaces a typed error instead of `null`.
     */
    fun nodeIdToNum(id: String): Int? {
        val trimmed = id.removePrefix("!")
        if (trimmed.length != 8 || trimmed.length == id.length) return null
        // Reject non-hex characters explicitly — toLongOrNull(16) already
        // does this, but checking up-front documents the contract.
        if (!trimmed.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }) return null
        return trimmed.toLongOrNull(16)?.toInt()
    }
}

