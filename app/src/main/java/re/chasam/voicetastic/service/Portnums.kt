package re.chasam.voicetastic.service

/**
 * Meshtastic port numbers for different data types.
 */
object Portnums {
    /** Standard text messaging port */
    const val TEXT_MESSAGE_APP = 1

    /** Position data */
    const val POSITION_APP = 3

    /** Node info */
    const val NODEINFO_APP = 4

    /** Admin messages (config changes) */
    const val ADMIN_APP = 6

    /** Private app data – used for voice chunks */
    const val PRIVATE_APP = 256
}

