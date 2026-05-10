package re.chasam.voicetastic.service

import re.chasam.voicetastic.core.Ports

/**
 * Meshtastic port numbers for different data types.
 *
 * Re-exports of [re.chasam.voicetastic.core.Ports] kept as a stable alias
 * for existing callers; new code should depend on [Ports] directly so the
 * constants can be shared with the upstream Rust `voicetastic-core`
 * crate (see `INTEGRATION.md`).
 */
object Portnums {
    /** Standard text messaging port */
    const val TEXT_MESSAGE_APP = Ports.TEXT_MESSAGE_APP

    /** Position data */
    const val POSITION_APP = Ports.POSITION_APP

    /** Node info */
    const val NODEINFO_APP = Ports.NODEINFO_APP

    /** Admin messages (config changes) */
    const val ADMIN_APP = Ports.ADMIN_APP

    /** Private app data – used for voice chunks */
    const val PRIVATE_APP = Ports.PRIVATE_APP

    /** Maximum accepted UTF-8 text payload size (bytes). */
    const val MAX_TEXT_BYTES = Ports.MAX_TEXT_BYTES
}

