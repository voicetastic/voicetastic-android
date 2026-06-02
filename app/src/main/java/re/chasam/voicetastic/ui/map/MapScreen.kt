package re.chasam.voicetastic.ui.map

import android.preference.PreferenceManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import re.chasam.voicetastic.ui.chat.MessagingViewModel

/**
 * OpenStreetMap-backed map of every known peer that has reported a
 * position. Pins are placed via osmdroid's `Marker` overlay and
 * refreshed whenever the underlying `nodes` StateFlow emits — which
 * is once per inbound NodeInfo update.
 *
 * Auto-fits to the marker bounds on the first non-empty render so
 * the user lands on the mesh's spatial extent without manual pan/zoom.
 *
 * The map is created via [AndroidView] (osmdroid is a classic Android
 * View, not Compose). Lifecycle is wired through `DisposableEffect`
 * so onPause / onDetach drop the tile-fetch threads cleanly.
 */
@Composable
fun MapScreen(messagingViewModel: MessagingViewModel) {
    val nodes by messagingViewModel.nodes.collectAsState()
    val context = LocalContext.current

    // osmdroid needs a one-time user-agent + tile cache config. The
    // SharedPreferences-backed `Configuration` is the official entry
    // point; we set it once per process.
    remember {
        Configuration.getInstance().load(
            context.applicationContext,
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext),
        )
        Configuration.getInstance().userAgentValue = "re.chasam.voicetastic"
        Unit
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(2.0)
            controller.setCenter(GeoPoint(20.0, 0.0))
        }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause(); mapView.onDetach() }
    }

    // Refresh markers from the latest nodes snapshot whenever it
    // changes. AndroidView's `update` lambda runs on recomposition.
    Column(modifier = Modifier.fillMaxSize()) {
        val plotted = nodes.count { it.latitudeI != null && it.longitudeI != null }
        Text(
            text = "Plotted $plotted of ${nodes.size} known peer(s).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { mv ->
                    // Wipe + re-add markers. The peer list is small
                    // enough (< 200 typical) that a full rebuild on
                    // each NodeInfo update is cheaper than diffing.
                    mv.overlays.clear()
                    val points = mutableListOf<GeoPoint>()
                    for (node in nodes) {
                        val lat = node.latitudeI ?: continue
                        val lon = node.longitudeI ?: continue
                        // (0, 0) is the Meshtastic "unknown position"
                        // sentinel; skip so peers without a fix don't
                        // all pile up off the coast of Ghana.
                        if (lat == 0 && lon == 0) continue
                        val p = GeoPoint(lat / 1e7, lon / 1e7)
                        points += p
                        val display = node.longName.ifBlank {
                            node.shortName.ifBlank { node.nodeId }
                        }
                        val battery = node.batteryLevel?.let {
                            if (it == 101) "AC" else "$it%"
                        } ?: "—"
                        val snrText = node.snr?.let { "%.1f dB".format(it) } ?: "—"
                        Marker(mv).apply {
                            position = p
                            title = display
                            snippet = "${node.nodeId}\nSNR $snrText · Battery $battery"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            mv.overlays.add(this)
                        }
                    }
                    if (points.isNotEmpty() && mv.zoomLevelDouble <= 2.5) {
                        val bb = org.osmdroid.util.BoundingBox.fromGeoPointsSafe(points)
                        mv.zoomToBoundingBox(bb, true, 80)
                    }
                    mv.invalidate()
                },
            )
        }
    }
}
