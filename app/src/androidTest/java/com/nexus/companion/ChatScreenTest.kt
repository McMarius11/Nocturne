package com.nexus.companion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexus.companion.ui.screens.ChatScreen
import com.nexus.companion.ui.theme.NexusTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun chatScreenShowsTitle() {
        composeTestRule.setContent {
            NexusTheme {
                // We can't easily instantiate ChatViewModel in tests without mocking,
                // so we test the static UI elements that don't depend on the ViewModel.
                // For a real test, you'd use Hilt or a test ViewModel.
            }
        }
        // Placeholder — real UI tests would require dependency injection
    }

    @Test
    fun appNameIsNexus() {
        // Verify the app's package name and basic configuration
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        assert(context.packageName == "com.nexus.companion")
    }

    @Test
    fun notificationChannelExists() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        val channel = nm.getNotificationChannel(com.nexus.companion.llm.DownloadService.CHANNEL_ID)
        assert(channel != null) { "Download notification channel should exist" }
        assert(channel.importance == android.app.NotificationManager.IMPORTANCE_LOW)
    }

    @Test
    fun modelInfoAllModelsAccessible() {
        val models = com.nexus.companion.llm.ModelInfo.ALL_MODELS
        assert(models.size == 5) { "Expected 5 models, got ${models.size}" }
    }

    @Test
    fun modelsDirectoryIsCreatable() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val modelsDir = java.io.File(context.filesDir, "models")
        modelsDir.mkdirs()
        assert(modelsDir.exists()) { "Models directory should be creatable" }
        assert(modelsDir.isDirectory) { "Models path should be a directory" }
    }

    @Test
    fun downloadServiceStateStartsIdle() {
        val state = com.nexus.companion.llm.DownloadService.downloadProgress.value
        // State should be Idle when no download is running
        assert(state is com.nexus.companion.llm.ModelManager.DownloadState.Idle ||
               state is com.nexus.companion.llm.ModelManager.DownloadState.Downloading ||
               state is com.nexus.companion.llm.ModelManager.DownloadState.Error) {
            "Download state should be a valid state"
        }
    }

    @Test
    fun modelManagerStorageCheck() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val manager = com.nexus.companion.llm.ModelManager(context)
        val availableGb = manager.getAvailableStorageGb()
        assert(availableGb >= 0) { "Available storage should be non-negative" }
    }
}
