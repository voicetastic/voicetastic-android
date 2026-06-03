package re.chasam.voicetastic.model

/**
 * Represents a node in the Meshtastic mesh network.
 */
data class MeshNode(
    val nodeId: String,
    val longName: String = "Unknown",
    val shortName: String = "??",
    val lastHeard: Long = 0L,
    val batteryLevel: Int? = null,
    val snr: Float? = null,
    /** Battery voltage in volts. From `device_metrics.voltage`. */
    val voltage: Float? = null,
    /** Channel utilisation 0..100. From `device_metrics.channel_utilization`. */
    val channelUtilization: Float? = null,
    /** Air util TX 0..100. From `device_metrics.air_util_tx`. */
    val airUtilTx: Float? = null,
    /** Device uptime in seconds since boot. */
    val uptimeSeconds: Int? = null,
    /** Latitude (1e-7 degrees) from the node's last reported position. */
    val latitudeI: Int? = null,
    val longitudeI: Int? = null,
    val altitude: Int? = null,
    /** Mesh channel index this node was last heard on. */
    val channel: Int = 0,
    /** `HardwareModel` proto value. 0 = UNSET. */
    val hwModel: Int = 0,
    /** `Config.DeviceConfig.Role` proto value. 0 = CLIENT. */
    val role: Int = 0,
    /** True iff the user marked themself as a licensed HAM. */
    val isLicensed: Boolean = false,
    /** Node was learned via an MQTT bridge rather than direct mesh contact. */
    val viaMqtt: Boolean = false,
    /** User pinned this node as a favourite. */
    val isFavorite: Boolean = false,
)

