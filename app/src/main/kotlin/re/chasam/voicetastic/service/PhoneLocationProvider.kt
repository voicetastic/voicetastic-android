package re.chasam.voicetastic.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import androidx.core.content.getSystemService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reads a single location fix from the phone's own GPS / fused provider so the
 * app can act as the position source for a mesh node that has no GPS of its
 * own ("mimic the device GPS with the smartphone GPS").
 *
 * Callers must have obtained ACCESS_FINE_LOCATION first; a missing grant or
 * disabled location service surfaces as a null fix rather than a crash
 * (SecurityException is swallowed).
 */
class PhoneLocationProvider(context: Context) {

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService<LocationManager>()

    /**
     * Obtain a current location fix, or null if unavailable within [timeoutMs]
     * (no permission, no provider enabled, or no fix produced in time). Tries a
     * fresh fix first, then falls back to the most recent last-known location.
     */
    @SuppressLint("MissingPermission")
    suspend fun currentFix(timeoutMs: Long = 15_000): Location? {
        val lm = locationManager ?: return null
        val provider = bestProvider(lm) ?: return lastKnown(lm)
        val fresh = withTimeoutOrNull(timeoutMs) {
            try {
                requestSingle(lm, provider)
            } catch (e: SecurityException) {
                Log.w(TAG, "location permission missing", e)
                null
            }
        }
        return fresh ?: lastKnown(lm)
    }

    /**
     * Continuously emit location fixes roughly every [intervalMs] for as long
     * as the flow is collected. Closes immediately if there's no provider or
     * permission. The caller is responsible for stopping collection (e.g. when
     * the phone-GPS source is deselected).
     */
    @SuppressLint("MissingPermission")
    fun locationUpdates(intervalMs: Long): Flow<Location> = callbackFlow {
        val lm = locationManager ?: run { close(); return@callbackFlow }
        val provider = bestProvider(lm) ?: run { close(); return@callbackFlow }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location)
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}

            @Deprecated("Required override on API < 30")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        try {
            lm.requestLocationUpdates(provider, intervalMs, 0f, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.w(TAG, "location permission missing for updates", e)
            close(e)
            return@callbackFlow
        }
        awaitClose { lm.removeUpdates(listener) }
    }

    /** Pick the most accurate provider that is currently enabled. */
    private fun bestProvider(lm: LocationManager): String? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        else -> null
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingle(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // API 30+: single-shot helper that auto-cancels.
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(provider, signal, appContext.mainExecutor) { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
            } else {
                // API 29: subscribe and remove ourselves after the first fix.
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(location)
                    }

                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}

                    @Deprecated("Required override on API < 30")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }
        }

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager): Location? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        return providers.mapNotNull { p ->
            try {
                lm.getLastKnownLocation(p)
            } catch (e: SecurityException) {
                null
            }
        }.maxByOrNull { it.time }
    }

    companion object {
        private const val TAG = "PhoneLocationProvider"
    }
}
