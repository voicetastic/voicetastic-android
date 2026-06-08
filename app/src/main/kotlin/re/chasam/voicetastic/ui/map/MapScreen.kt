package re.chasam.voicetastic.ui.map

import android.preference.PreferenceManager
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import re.chasam.voicetastic.R
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
    val myNodeId by messagingViewModel.myNodeId.collectAsState()
    val selfNode by messagingViewModel.selfNode.collectAsState()
    val connectionState by messagingViewModel.connectionState.collectAsState()
    val context = LocalContext.current

    // Where to draw the "you" pin: our node's own reported position, and
    // nothing else. The device is the sole position source — whether that
    // fix comes from the radio's own GPS or a phone-provided position is the
    // device's setting, decided on the radio. The app never reads the
    // phone's location directly. Null until the node reports a fix.
    var selfPoint by remember { mutableStateOf<GeoPoint?>(null) }
    // One-shot latch: true once we've centered the camera on the self node for
    // this screen entry, so we don't fight the user's pan/zoom afterwards.
    // Bare holder (not State) so writing it doesn't trigger recomposition;
    // resets when the screen is re-entered (this remember is recreated).
    val centeredOnSelf = remember { booleanArrayOf(false) }
    LaunchedEffect(nodes, myNodeId) {
        // Resolve our position from our own entry in the node list. The
        // selfNode StateFlow can lack lat/lon even when the node reports a
        // position (including a fixed one), which is why we read it here.
        val myNode = nodes.firstOrNull { it.nodeId == myNodeId }
        val lat = myNode?.latitude
        val lon = myNode?.longitude
        selfPoint = if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
            // latitude/longitude are already decimal degrees.
            GeoPoint(lat, lon)
        } else {
            null
        }
    }

    val mapView = remember {
        // osmdroid needs a one-time user-agent + tile cache config before
        // any tile provider is built. The SharedPreferences-backed
        // `Configuration` is the official entry point; we set it here so it
        // runs once, synchronously, ahead of the MapView below.
        try {
            Configuration.getInstance().load(
                context.applicationContext,
                PreferenceManager.getDefaultSharedPreferences(context.applicationContext),
            )
            // Explicitly set cache dir to app cache (guaranteed writable)
            Configuration.getInstance().osmdroidBasePath = context.cacheDir
            Configuration.getInstance().osmdroidTileCache = context.cacheDir
            Configuration.getInstance().userAgentValue = "re.chasam.voicetastic"
        } catch (e: Exception) {
            android.util.Log.e("MapScreen", "osmdroid config failed", e)
        }

        // osmdroid's built-in NetworkAvailabliltyCheck reports "no
        // network" on some de-Googled / custom ROMs (e.g. /e/OS),
        // so it silently skips every tile download and renders a blank
        // map. We hold the INTERNET permission and the HTTP request is
        // itself the real connectivity test, so we build the default
        // provider chain with a check that always reports available.
        val alwaysOnline = object : INetworkAvailablityCheck {
            override fun getNetworkAvailable() = true
            override fun getWiFiNetworkAvailable() = true
            override fun getCellularDataNetworkAvailable() = true
            override fun getRouteToPathExists(hostAddress: Int) = true
        }
        val tileProvider = MapTileProviderBasic(
            SimpleRegisterReceiver(context),
            alwaysOnline,
            TileSourceFactory.MAPNIK,
            context,
            SqlTileWriter(),
        )
        MapView(context, tileProvider).apply {
            setMultiTouchControls(true)
            controller.setZoom(2.0)
            controller.setCenter(GeoPoint(20.0, 0.0))
        }
    }

    // Center the map on the self node once, when both its position and a
    // laid-out map are available. `mapView.post` defers the controller call to
    // after the view has a size, mirroring the "center on me" FAB (which only
    // ever runs post-layout, which is why it works while an in-`update`
    // setCenter silently no-ops before first layout).
    LaunchedEffect(selfPoint) {
        val sp = selfPoint
        if (sp != null && !centeredOnSelf[0]) {
            centeredOnSelf[0] = true
            mapView.post {
                mapView.controller.setZoom(18.0)
                mapView.controller.setCenter(sp)
            }
        }
    }

    // Refresh markers from the latest nodes snapshot whenever it
    // changes. AndroidView's `update` lambda runs on recomposition.
    Column(modifier = Modifier.fillMaxSize()) {
        val plotted = nodes.count { it.latitude != null && it.longitude != null }
        Text(
            text = "Plotted $plotted of ${nodes.size} known peer(s).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = {
                    mapView.onResume()
                    mapView
                },
                modifier = Modifier.fillMaxSize(),
                update = { mv ->
                    // Wipe + re-add markers. The peer list is small
                    // enough (< 200 typical) that a full rebuild on
                    // each NodeInfo update is cheaper than diffing.
                    mv.overlays.clear()
                    val points = mutableListOf<GeoPoint>()
                    val selfId = selfNode?.nodeId ?: myNodeId
                    for (node in nodes) {
                        // Our own node is drawn separately below (from
                        // `selfPoint`), so skip it here to avoid a duplicate
                        // default marker.
                        if (selfId != null && node.nodeId == selfId) continue
                        val lat = node.latitude ?: continue
                        val lon = node.longitude ?: continue
                        // (0, 0) is the Meshtastic "unknown position"
                        // sentinel; skip so peers without a fix don't
                        // all pile up off the coast of Ghana.
                        if (lat == 0.0 && lon == 0.0) continue
                        val p = GeoPoint(lat, lon)
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
                    // The "you" pin: tinted by link state (green = connected,
                    // grey otherwise). Sourced from our node's reported position.
                    selfPoint?.let { sp ->
                        points += sp
                        val name = selfNode?.longName?.ifBlank { null }
                            ?: selfNode?.shortName?.ifBlank { null }
                            ?: selfNode?.nodeId ?: "You"
                        val tint = if (connectionState == "CONNECTED") {
                            0xFF2E7D32.toInt()
                        } else {
                            0xFF9E9E9E.toInt()
                        }
                        Marker(mv).apply {
                            position = sp
                            title = "$name (you)"
                            snippet = selfNode?.nodeId ?: ""
                            // The Material "place" teardrop's tip is at y=22 of
                            // the 24-unit viewport (2 units of empty space below
                            // it), so anchoring at the image bottom (ANCHOR_BOTTOM
                            // = 1.0) places the GeoPoint a few dp below the visible
                            // tip and the pin reads slightly off. Anchor on the
                            // actual tip instead.
                            setAnchor(Marker.ANCHOR_CENTER, 22f / 24f)
                            icon = ContextCompat.getDrawable(context, R.drawable.ic_map_self_pin)
                                ?.mutate()
                                ?.also { DrawableCompat.setTint(it, tint) }
                            mv.overlays.add(this)
                        }
                    }
                    // Self-centering is handled by the LaunchedEffect above
                    // (post-layout). Until a self position exists, provisionally
                    // frame known peers from the world view only, so the map
                    // isn't stuck on the whole globe. The `!centeredOnSelf`
                    // guard means a self fix still takes over when it arrives.
                    if (!centeredOnSelf[0] && mv.zoomLevelDouble <= 2.5) {
                        if (points.size == 1) {
                            mv.controller.setCenter(points.first())
                            mv.controller.setZoom(18.0)
                        } else if (points.isNotEmpty()) {
                            val bb = org.osmdroid.util.BoundingBox.fromGeoPointsSafe(points)
                            mv.zoomToBoundingBox(bb, true, 80, 16.0, null)
                        }
                    }
                    mv.invalidate()
                },
            )
            FloatingActionButton(
                onClick = {
                    val myNode = nodes.firstOrNull { it.nodeId == myNodeId }
                    val lat = myNode?.latitude
                    val lon = myNode?.longitude
                    if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) {
                        // Our node reported a position (decimal degrees): use it.
                        mapView.controller.setCenter(GeoPoint(lat, lon))
                        mapView.controller.setZoom(18.0)
                    } else {
                        // The device is the only position source; if it hasn't
                        // reported a fix there's nothing to centre on.
                        Toast.makeText(
                            context,
                            "Your node hasn't reported a position yet",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                modifier = Modifier.padding(16.dp).align(androidx.compose.ui.Alignment.BottomEnd),
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Center on my location")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { mapView.onPause(); mapView.onDetach() }
    }
}
