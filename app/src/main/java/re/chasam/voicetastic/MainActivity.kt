package re.chasam.voicetastic

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import re.chasam.voicetastic.model.VoiceConfig
import re.chasam.voicetastic.navigation.AppNavigation
import re.chasam.voicetastic.service.MeshServiceManager
import re.chasam.voicetastic.ui.chat.MessagingViewModel
import re.chasam.voicetastic.ui.settings.ConfigViewModel

class MainActivity : ComponentActivity() {

    private lateinit var meshServiceManager: MeshServiceManager
    private lateinit var messagingViewModel: MessagingViewModel
    private lateinit var configViewModel: ConfigViewModel

    private val voiceConfig = MutableStateFlow(VoiceConfig())

    /**
     * Listens for USB device hot-plug / unplug events broadcast by the OS.
     *
     * Attach is intentionally a no-op: the user explicitly chooses BLE *or*
     * USB from the Devices screen, so silently auto-connecting on plug-in
     * would race with — or override — that choice. The attached device will
     * appear in the device list automatically (DeviceScreen re-enumerates
     * on relevant state changes).
     *
     * Detach still has to be observed so the manager can tear down an
     * active USB session deterministically (otherwise we keep a stale
     * port handle around until the next IO error fires).
     */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!::meshServiceManager.isInitialized) return
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device: UsbDevice = (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                }
            ) ?: return
            meshServiceManager.onUsbDeviceDetached(device)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            initializeApp()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (hasRequiredPermissions()) {
            initializeApp()
        } else {
            requestPermissions()
        }
    }

    private fun initializeApp() {
        meshServiceManager = MeshServiceManager(this)

        messagingViewModel = MessagingViewModel(meshServiceManager, this, voiceConfig)
        configViewModel = ConfigViewModel(meshServiceManager, voiceConfig)

        registerUsbReceiver()
        // NOTE: we deliberately do NOT auto-connect on the launching intent
        // either, even if the OS launched us via USB_DEVICE_ATTACHED. The
        // user picks the transport on the Devices screen.

        setContent {
            MaterialTheme {
                AppNavigation(
                    messagingViewModel = messagingViewModel,
                    configViewModel = configViewModel,
                    meshServiceManager = meshServiceManager
                )
            }
        }
    }

    private fun registerUsbReceiver() {
        // Only DETACH — ATTACH auto-connect was removed (user picks transport).
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        if (::meshServiceManager.isInitialized) {
            meshServiceManager.destroy()
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(getRequiredPermissions())
    }

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return permissions.toTypedArray()
    }
}
