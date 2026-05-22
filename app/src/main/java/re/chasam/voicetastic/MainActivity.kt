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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import re.chasam.voicetastic.model.ThemePreference
import re.chasam.voicetastic.model.ThemePreferenceStore
import re.chasam.voicetastic.model.VoiceConfig
import re.chasam.voicetastic.model.VoiceConfigStore
import re.chasam.voicetastic.navigation.AppNavigation
import re.chasam.voicetastic.service.MeshServiceManager
import re.chasam.voicetastic.ui.chat.MessagingViewModel
import re.chasam.voicetastic.ui.settings.ConfigViewModel
import re.chasam.voicetastic.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    private lateinit var meshServiceManager: MeshServiceManager
    private lateinit var messagingViewModel: MessagingViewModel
    private lateinit var configViewModel: ConfigViewModel

    // Initialised in initializeApp() so we can hand the activity context
    // to VoiceConfigStore before constructing the flow. Default value is
    // used as a fallback if persistence has never been written.
    private lateinit var voiceConfigStore: VoiceConfigStore
    private val voiceConfig = MutableStateFlow(VoiceConfig())

    private lateinit var themePreferenceStore: ThemePreferenceStore
    private val themePreference = MutableStateFlow(ThemePreference.SYSTEM)

    /**
     * Drives the Compose tree: true once [initializeApp] has run and all
     * lateinit fields are populated. Setting `setContent` unconditionally
     * in `onCreate` (rather than only after permissions are granted)
     * means a process-death restore can never end up with `setContent`
     * holding references to uninitialised lateinit fields, and a
     * permission denial leaves the user with a visible explanation
     * instead of a frozen blank screen.
     */
    private var initialized by mutableStateOf(false)
    private var permissionsDenied by mutableStateOf(false)

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
            permissionsDenied = false
            initializeApp()
        } else {
            permissionsDenied = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // setContent unconditionally so the Compose tree is always live —
        // it reacts to `initialized` and `permissionsDenied` instead of
        // being gated by them. Without this, a process-death restore
        // followed by a permission denial leaves the activity with no
        // visible UI at all.
        setContent { Root() }

        if (hasRequiredPermissions()) {
            initializeApp()
        } else {
            requestPermissions()
        }
    }

    @Composable
    private fun Root() {
        val currentTheme by themePreference.collectAsState()
        AppTheme(preference = currentTheme) {
            when {
                initialized -> AppNavigation(
                    messagingViewModel = messagingViewModel,
                    configViewModel = configViewModel,
                    meshServiceManager = meshServiceManager,
                    themePreference = currentTheme,
                    onThemePreferenceChange = { themePreference.value = it },
                )
                permissionsDenied -> PermissionsDeniedScreen(onRetry = ::requestPermissions)
                else -> LoadingScreen()
            }
        }
    }

    private fun initializeApp() {
        meshServiceManager = MeshServiceManager(this)

        // Hydrate voice config from disk before wiring view models so the
        // first render sees the user's last-saved values (codec choice,
        // mode, noise suppression toggle, etc.) instead of defaults.
        voiceConfigStore = VoiceConfigStore(this)
        voiceConfig.value = voiceConfigStore.load()
        // `drop(1)` skips the initial emission we just set above — no
        // point writing back what we just read.
        lifecycleScope.launch {
            voiceConfig.drop(1).collect { voiceConfigStore.save(it) }
        }

        themePreferenceStore = ThemePreferenceStore(this)
        themePreference.value = themePreferenceStore.load()
        lifecycleScope.launch {
            themePreference.drop(1).collect { themePreferenceStore.save(it) }
        }

        messagingViewModel = MessagingViewModel(meshServiceManager, this, voiceConfig)
        configViewModel = ConfigViewModel(meshServiceManager, voiceConfig)

        registerUsbReceiver()
        // NOTE: we deliberately do NOT auto-connect on the launching intent
        // either, even if the OS launched us via USB_DEVICE_ATTACHED. The
        // user picks the transport on the Devices screen.

        // Flip last: the Compose Root is already live, and this single
        // assignment swaps the loading screen for the real navigation
        // tree once every lateinit above has been populated.
        initialized = true
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

    @Composable
    private fun LoadingScreen() {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
    }

    @Composable
    private fun PermissionsDeniedScreen(onRetry: () -> Unit) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.permissions_denied_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.permissions_denied_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    androidx.compose.material3.OutlinedButton(onClick = onRetry) {
                        Text(stringResource(R.string.permissions_denied_retry))
                    }
                }
            }
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: BLE scan/connect runtime perms; BLUETOOTH_SCAN
            // is declared with `neverForLocation` in the manifest so
            // location is not required.
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Android 11 and below: the platform requires fine location
            // to allow BLE scanning at all.
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.toTypedArray()
    }
}
