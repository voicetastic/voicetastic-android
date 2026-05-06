package re.chasam.voicetastic.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import re.chasam.voicetastic.service.MeshServiceManager
import re.chasam.voicetastic.ui.chat.ChatScreen
import re.chasam.voicetastic.ui.chat.MessagingViewModel
import re.chasam.voicetastic.ui.device.DeviceScreen
import re.chasam.voicetastic.ui.settings.ConfigViewModel
import re.chasam.voicetastic.ui.settings.SettingsScreen

enum class Screen(val route: String, val title: String, val icon: ImageVector) {
    // Generic "Devices" icon: the screen now lists USB *and* BLE radios, so
    // the previous Bluetooth glyph implied a single transport.
    Devices("devices", "Devices", Icons.Default.Devices),
    Chat("chat", "Chat", Icons.AutoMirrored.Filled.Chat),
    Settings("settings", "Settings", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    messagingViewModel: MessagingViewModel,
    configViewModel: ConfigViewModel,
    meshServiceManager: MeshServiceManager
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voicetastic") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
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
            composable(Screen.Settings.route) {
                SettingsScreen(viewModel = configViewModel)
            }
        }
    }
}
