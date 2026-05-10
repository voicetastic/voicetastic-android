package re.chasam.voicetastic.service

import re.chasam.voicetastic.core.NodeIds
import java.util.UUID

/**
 * Meshtastic BLE GATT UUIDs and helper functions.
 *
 * Node-id helpers ([nodeNumToId] / [nodeIdToNum] / [BROADCAST_ADDR]) are
 * thin wrappers around [re.chasam.voicetastic.core.NodeIds]; the platform-
 * agnostic logic lives there so it can be shared with the upstream Rust
 * `voicetastic-core` crate via the JNI bridge planned in `INTEGRATION.md`.
 */
object MeshtasticBle {
    val SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
    val TORADIO_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
    val FROMRADIO_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
    val FROMNUM_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547de15e6")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val BROADCAST_ADDR: Int = NodeIds.BROADCAST_ADDR

    fun nodeNumToId(num: Int): String = NodeIds.nodeNumToId(num)

    fun nodeIdToNum(id: String): Int? = NodeIds.nodeIdToNum(id)
}

