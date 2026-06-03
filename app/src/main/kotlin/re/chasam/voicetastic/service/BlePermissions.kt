package re.chasam.voicetastic.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permission checks for BLE operations.
 *
 * The manifest declares scoped variants of each Bluetooth permission
 * (legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` on API ≤ 30; `BLUETOOTH_SCAN`
 * / `BLUETOOTH_CONNECT` on API 31+; `ACCESS_FINE_LOCATION` only on
 * API ≤ 30 since `BLUETOOTH_SCAN` carries `neverForLocation` on S+).
 * The activity requests them at startup, but the user can revoke them
 * from System Settings at any time. Before this helper existed, the
 * BLE entry points were marked `@SuppressLint("MissingPermission")`
 * and would `SecurityException` on revocation; now they ask first and
 * fail loudly to a log.
 */
internal object BlePermissions {

    fun canScan(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(context, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun canConnect(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(context, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Legacy BLUETOOTH is a normal permission — granted at
            // install time — so on API ≤ 30 this just confirms the
            // manifest declared it.
            true
        }
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
}
