package com.nexus.companion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.nexus.companion.ui.screens.ChatScreen
import com.nexus.companion.ui.screens.DebugLogScreen
import com.nexus.companion.ui.screens.PhoneScreen
import com.nexus.companion.ui.screens.SettingsScreen
import com.nexus.companion.ui.theme.NexusTheme
import com.nexus.companion.viewmodel.ChatViewModel

/** Type-safe navigation destinations */
sealed class Screen {
    data object Chat : Screen()
    data object Phone : Screen()
    data object Debug : Screen()
    data object Settings : Screen()
}

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    /** Pending navigation target — set when waiting for permission result */
    private var pendingScreen: Screen? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — no action needed */ }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingScreen == Screen.Phone) {
            navigateToScreen?.invoke(Screen.Phone)
        }
        pendingScreen = null
    }

    /** Set by setContent so the permission callback can trigger navigation */
    private var navigateToScreen: ((Screen) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Only request notification permission eagerly (needed for downloads)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            NexusTheme {
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Chat) }
                navigateToScreen = { currentScreen = it }

                when (currentScreen) {
                    Screen.Chat -> ChatScreen(
                        viewModel = viewModel,
                        onNavigateToPhone = {
                            // Only enter phone mode when RECORD_AUDIO is granted
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED
                            ) {
                                currentScreen = Screen.Phone
                            } else {
                                pendingScreen = Screen.Phone
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onNavigateToDebugLog = { currentScreen = Screen.Debug },
                        onNavigateToSettings = { currentScreen = Screen.Settings }
                    )
                    Screen.Phone -> PhoneScreen(
                        viewModel = viewModel,
                        onHangUp = { currentScreen = Screen.Chat }
                    )
                    Screen.Debug -> DebugLogScreen(
                        onBack = { currentScreen = Screen.Chat },
                        debugInfo = viewModel.getDebugInfo()
                    )
                    Screen.Settings -> SettingsScreen(
                        viewModel = viewModel,
                        onBack = { currentScreen = Screen.Chat }
                    )
                }
            }
        }
    }
}
