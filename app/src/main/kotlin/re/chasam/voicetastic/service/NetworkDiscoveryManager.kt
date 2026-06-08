package re.chasam.voicetastic.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque

/** A Meshtastic node discovered on the local network via mDNS. */
data class NetworkDevice(
    val name: String,
    val host: String,
    val port: Int,
) {
    val key: String get() = "net-$host:$port"
}

/**
 * Discovers WiFi/Ethernet Meshtastic nodes on the local network through
 * Android's [NsdManager] (mDNS / DNS-SD), looking for the `_meshtastic._tcp.`
 * service the firmware advertises when its network module is enabled.
 *
 * Mirrors [DeviceDiscoveryManager]'s shape: a [devices] snapshot flow plus an
 * [isDiscovering] flag, started/stopped explicitly by the user's "scan
 * network" action. Resolves are serialised through a small queue because
 * `NsdManager` only tolerates one `resolveService` in flight at a time on
 * pre-34 devices.
 */
class NetworkDiscoveryManager(context: Context) {

    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager

    val devices: StateFlow<List<NetworkDevice>>
        field = MutableStateFlow<List<NetworkDevice>>(emptyList())

    val isDiscovering: StateFlow<Boolean>
        field = MutableStateFlow(false)

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // Serialise resolves: NsdManager rejects concurrent resolveService calls.
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val lock = Any()

    fun start() {
        if (discoveryListener != null) return
        devices.value = emptyList()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                isDiscovering.value = true
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.contains(SERVICE_TYPE_MATCH)) enqueueResolve(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                val name = service.serviceName
                devices.value = devices.value.filterNot { it.name == name }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isDiscovering.value = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "discovery start failed: $errorCode")
                isDiscovering.value = false
                discoveryListener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery stop failed: $errorCode")
                isDiscovering.value = false
                discoveryListener = null
            }
        }
        discoveryListener = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                Log.e(TAG, "discoverServices threw", it)
                isDiscovering.value = false
                discoveryListener = null
            }
    }

    fun stop() {
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveryListener = null
        isDiscovering.value = false
        synchronized(lock) { resolveQueue.clear(); resolving = false }
    }

    private fun enqueueResolve(service: NsdServiceInfo) {
        synchronized(lock) {
            resolveQueue.add(service)
            if (!resolving) pumpResolveLocked()
        }
    }

    private fun pumpResolveLocked() {
        val next = resolveQueue.poll() ?: run { resolving = false; return }
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val addr = info.host?.hostAddress
                if (addr != null) {
                    val dev = NetworkDevice(info.serviceName, addr, info.port)
                    // De-dup by host:port; replace any stale same-key entry.
                    devices.value = devices.value.filterNot { it.key == dev.key } + dev
                }
                resolveNext()
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "resolve failed for ${info.serviceName}: $errorCode")
                resolveNext()
            }
        })
    }

    private fun resolveNext() {
        synchronized(lock) { pumpResolveLocked() }
    }

    fun destroy() = stop()

    companion object {
        private const val TAG = "NetworkDiscovery"
        /** Service type passed to NsdManager (trailing dot per DNS-SD form). */
        private const val SERVICE_TYPE = "_meshtastic._tcp."
        /** Substring used to match the resolved service type loosely. */
        private const val SERVICE_TYPE_MATCH = "_meshtastic._tcp"
    }
}
