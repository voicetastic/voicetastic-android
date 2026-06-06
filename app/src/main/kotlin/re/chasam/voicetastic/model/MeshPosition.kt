package re.chasam.voicetastic.model

import com.geeksville.mesh.MeshProtos

/**
 * Meshtastic encodes geographic position as integer degrees scaled by 1e7
 * (the protobuf `latitude_i` / `longitude_i` fields). The app works in plain
 * decimal degrees everywhere (see [MeshNode.latitude]/[MeshNode.longitude]),
 * so these extensions convert at the proto boundary and keep the scale factor
 * in a single place instead of repeating `/ 1e7` at every call site.
 */
private const val DEGREES_SCALE = 1e7

/** Latitude in decimal degrees, converted from the raw `latitude_i`. */
val MeshProtos.Position.latDegrees: Double get() = latitudeI / DEGREES_SCALE

/** Longitude in decimal degrees, converted from the raw `longitude_i`. */
val MeshProtos.Position.lonDegrees: Double get() = longitudeI / DEGREES_SCALE
