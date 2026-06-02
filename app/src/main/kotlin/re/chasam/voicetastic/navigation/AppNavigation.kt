package re.chasam.voicetastic.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import re.chasam.voicetastic.R
import re.chasam.voicetastic.model.ThemePreference
import re.chasam.voicetastic.service.MeshServiceManager
import re.chasam.voicetastic.ui.chat.ChatScreen
import re.chasam.voicetastic.ui.chat.MessagingViewModel
import re.chasam.voicetastic.ui.device.DeviceScreen
import re.chasam.voicetastic.ui.map.MapScreen
import re.chasam.voicetastic.ui.settings.ConfigViewModel
import re.chasam.voicetastic.ui.settings.SettingsScreen

enum class Screen(val route: String, val titleResId: Int, val icon: ImageVector) {
    Devices("devices", R.string.nav_devices, Icons.Default.Devices),
    Chat("chat", R.string.nav_chat, Icons.AutoMirrored.Filled.Chat),
    Map("map", R.string.nav_map, Icons.Default.Map),
    Settings("settings", R.string.nav_settings, Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    messagingViewModel: MessagingViewModel,
    configViewModel: ConfigViewModel,
    meshServiceManager: MeshServiceManager,
    themePreference: ThemePreference,
    onThemePreferenceChange: (ThemePreference) -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val connectionState by meshServiceManager.connectionState.collectAsState()
    val selfNode by meshServiceManager.selfNode.collectAsState()
    val myNodeId by meshServiceManager.myNodeId.collectAsState()

    // The brand palette is entirely warm (peach seed), so primaryContainer
    // (connected) and errorContainer (disconnected) render as near-identical
    // tones — indistinguishable as a solid logo silhouette. Use an explicit,
    // clearly lighter green for connected (matching the map's "you" pin
    // convention) so the connected state reads at a glance.
    val logoColor = when (connectionState) {
        "CONNECTED" -> Color(0xFF66BB6A) // light green
        "CONNECTING" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }

    val shimmerProgress by animateFloatAsState(
        targetValue = if (connectionState == "CONNECTING") 1f else 0f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1500)),
        label = "shimmerAnimation"
    )

    val logoTint = if (connectionState == "CONNECTING") {
        lerp(logoColor, logoColor.copy(alpha = 0.4f), shimmerProgress)
    } else {
        logoColor
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val node = selfNode
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onLongPress = { meshServiceManager.disconnect() }
                                    )
                                }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_launcher_foreground),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = logoTint
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))
                        // Swap the app name for node info as soon as we know our
                        // node id (from MyNodeInfo). The full `selfNode` (and so
                        // the long name) arrives a beat later via the self
                        // NodeInfo / Owner, so fall back to the id-only view in
                        // the meantime rather than waiting for everything.
                        val nodeIdText = node?.nodeId ?: myNodeId
                        if (connectionState == "CONNECTED" && nodeIdText != null) {
                            val longName = node?.longName?.takeIf { it.isNotBlank() }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    longName ?: nodeIdText,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (longName != null) {
                                    Text(
                                        nodeIdText,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        } else {
                            Text(
                                stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = stringResource(screen.titleResId)) },
                        label = { Text(stringResource(screen.titleResId)) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Devices.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Devices.route) {
                DeviceScreen(meshServiceManager = meshServiceManager)
            }
            composable(Screen.Chat.route) {
                ChatScreen(viewModel = messagingViewModel)
            }
            composable(Screen.Map.route) {
                MapScreen(messagingViewModel = messagingViewModel)
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = configViewModel,
                    themePreference = themePreference,
                    onThemePreferenceChange = onThemePreferenceChange
                )
            }
        }
    }
}
