package re.chasam.voicetastic.service

import android.bluetooth.BluetoothDevice
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.voicetastic.MeshService
import uniffi.voicetastic.MeshTransportSink

/**
 * Lifecycle owner that ties a Kotlin [uniffi.voicetastic.MeshTransport]
 * adapter ([BleMeshTransport] or [UsbMeshTransportV2]) to the Rust-side
 * [MeshService].
 *
 * This is the entry point that PR 3 will wire into `MainActivity` /
 * the DI graph when `BuildConfig.USE_RUST_MESH_SERVICE` flips to true.
 * Keeping it as a small standalone helper means the legacy
 * [MeshServiceManager] stays untouched in PR 2.
 *
 * ```kotlin
 * // BLE
 * val session = RustMeshSession.openBle(context, meshService, device)
 *
 * // USB (caller already opened the underlying UsbMeshTransport)
 * val session = RustMeshSession.openUsb(meshService, legacyUsb)
 *
 * // ...
 * session.close()
 * ```
 *
 * Note the explicit settle-delay handling: BLE waits until the GATT
 * descriptor write has completed before letting Rust send the first
 * `WantConfigId`; USB has no settle period because the wire is ready
 * the moment we open the port.
 */
class RustMeshSession private constructor(
    private val meshService: MeshService,
    private val transport: Any,        // BleMeshTransport | UsbMeshTransportV2
    private val sink: MeshTransportSink,
) {
    companion object {
        /**
         * Delay between "transport ready" and the first `WantConfigId`
         * write. The upstream desktop core uses 300 ms here
         * (`CONFIG_REQUEST_DELAY`) to tolerate the indeterminate gap
         * between a btleplug connect and the radio being responsive.
         *
         * On Android we have a precise signal: [BleMeshTransport]'s
         * [BleMeshTransport.SetupListener.onSetupComplete] fires only
         * after `onDescriptorWrite` — meaning the CCCD is acknowledged
         * and the firmware is already armed for inbound traffic. There
         * is no unknown gap to bridge, so 0 ms is correct.
         */
        private const val BLE_SETTLE_DELAY_MS: ULong = 0u
        /** USB has no settle delay; the port is ready on `open`. */
        private const val USB_SETTLE_DELAY_MS: ULong = 0u
        /** How long we wait for [BleMeshTransport]'s setup callback before giving up. */
        private const val BLE_SETUP_TIMEOUT_MS = 15_000L

        /**
         * Open a BLE-backed Rust session. Suspends until GATT setup
         * completes (or fails) so the caller knows whether to retry.
         *
         * @throws IllegalStateException on GATT setup failure / timeout.
         */
        suspend fun openBle(
            context: Context,
            meshService: MeshService,
            device: BluetoothDevice,
        ): RustMeshSession {
            val transport = BleMeshTransport(context, device)
            val ready = CompletableDeferred<Pair<Boolean, String?>>()
            transport.connectGatt { success, error -> ready.complete(success to error) }
            val outcome = withTimeoutOrNull(BLE_SETUP_TIMEOUT_MS) { ready.await() }
                ?: run {
                    transport.shutdown()
                    error("BLE setup timed out after ${BLE_SETUP_TIMEOUT_MS}ms")
                }
            if (!outcome.first) {
                transport.shutdown()
                error("BLE setup failed: ${outcome.second ?: "unknown"}")
            }
            val sink = meshService.connect(transport, BLE_SETTLE_DELAY_MS)
            transport.attachSink(sink)
            return RustMeshSession(meshService, transport, sink)
        }

        /**
         * Open a USB-backed Rust session. The caller must have already
         * connected the underlying [UsbMeshTransport] (permission flow +
         * `connect(driver)`); this helper just adapts and wires it in.
         */
        fun openUsb(
            meshService: MeshService,
            legacyUsb: UsbMeshTransport,
        ): RustMeshSession {
            require(legacyUsb.isConnected) {
                "UsbMeshTransport must be connected before opening a RustMeshSession"
            }
            val transport = UsbMeshTransportV2(legacyUsb)
            val sink = meshService.connect(transport, USB_SETTLE_DELAY_MS)
            transport.attachSink(sink)
            return RustMeshSession(meshService, transport, sink)
        }
    }

    /**
     * Tear down the session: tell Rust to disconnect (which fires
     * [uniffi.voicetastic.MeshTransport.shutdown] on our adapter), then
     * idempotently shut the adapter and sink. Safe to call multiple
     * times.
     */
    fun close() {
        // `disconnect` on the Rust side issues a final `Disconnect`
        // packet and then calls back into MeshTransport.shutdown(); we
        // run it on a background thread because UniFFI blocks the
        // calling thread until the tokio future resolves.
        runBlocking(Dispatchers.IO) {
            runCatching { meshService.disconnect() }
        }
        when (transport) {
            is BleMeshTransport -> transport.shutdown()
            is UsbMeshTransportV2 -> transport.shutdown()
        }
        sink.shutdown()
    }
}



