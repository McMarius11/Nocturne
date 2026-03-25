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
import com.nexus.companion.ui.screens.PhoneScreen
import com.nexus.companion.ui.theme.NexusTheme
import com.nexus.companion.viewmodel.ChatViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge for proper inset handling on Pixel 9 / Android 15+
        enableEdgeToEdge()

        // Request permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        // Notifications (Android 13+)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            NexusTheme {
                var currentScreen by remember { mutableStateOf("chat") }

                when (currentScreen) {
                    "chat" -> ChatScreen(
                        viewModel = viewModel,
                        onNavigateToPhone = { currentScreen = "phone" }
                    )
                    "phone" -> PhoneScreen(
                        viewModel = viewModel,
                        onHangUp = { currentScreen = "chat" }
                    )
                }
            }
        }
    }

}
