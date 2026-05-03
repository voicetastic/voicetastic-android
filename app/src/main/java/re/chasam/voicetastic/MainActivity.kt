package re.chasam.voicetastic

import android.Manifest
import android.content.pm.PackageManager
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

    override fun onDestroy() {
        super.onDestroy()
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
