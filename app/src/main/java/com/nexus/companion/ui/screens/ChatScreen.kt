package com.nexus.companion.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Settings
import com.nexus.companion.llm.ModelManager
import com.nexus.companion.ui.components.MessageBubble
import com.nexus.companion.ui.components.ModelSwitcherSheet
import com.nexus.companion.ui.components.VoiceSwitcherSheet
import com.nexus.companion.ui.theme.NexusBlack
import com.nexus.companion.ui.theme.NexusCard
import com.nexus.companion.ui.theme.NexusPrimary
import com.nexus.companion.ui.theme.NexusSecondary
import com.nexus.companion.ui.theme.NexusSurfaceVariant
import com.nexus.companion.ui.theme.NexusTextDim
import com.nexus.companion.ui.theme.NexusTextPrimary
import com.nexus.companion.ui.theme.NexusTextSecondary
import com.nexus.companion.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToPhone: () -> Unit,
    onNavigateToDebugLog: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    val messages by viewModel.messages.collectAsState(initial = emptyList())
    val isGenerating by viewModel.isGenerating.collectAsState()
    val currentModel by viewModel.currentModelId.collectAsState()
    val downloadedModels by viewModel.downloadedModels.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val voiceProfile by viewModel.voiceProfile.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isModelLoaded by viewModel.isModelLoaded.collectAsState()
    val isLoadingModel by viewModel.isLoadingModel.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var showModelSwitcher by remember { mutableStateOf(false) }
    var showVoiceSwitcher by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val isDownloading = downloadState is ModelManager.DownloadState.Downloading

    // Unified canSend flag to prevent race conditions
    val canSend = inputText.isNotBlank() && !isGenerating && !isLoadingModel

    // Auto-scroll on new messages or generation start/stop (not every token)
    LaunchedEffect(messages.size, isGenerating) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(
                messages.size - 1 + if (isGenerating) 1 else 0
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Top bar
        TopAppBar(
            windowInsets = WindowInsets(0),
            title = {
                Column {
                    Text("Nexus", fontSize = 18.sp, color = NexusTextPrimary)
                    Text(
                        text = when {
                            currentModel == null -> "Kein Modell"
                            isLoadingModel -> "Wird geladen..."
                            isModelLoaded -> {
                                val model = com.nexus.companion.llm.ModelInfo.findById(currentModel!!)
                                model?.displayName ?: currentModel!!
                            }
                            else -> "${currentModel} (aus)"
                        },
                        fontSize = 12.sp,
                        color = when {
                            isLoadingModel -> NexusPrimary
                            isModelLoaded -> NexusTextDim
                            else -> NexusSecondary
                        }
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = NexusBlack),
            actions = {
                IconButton(onClick = onNavigateToPhone) {
                    Icon(Icons.Default.Call, "Sprachmodus", tint = NexusPrimary)
                }
                IconButton(onClick = { showModelSwitcher = true }) {
                    Icon(Icons.Default.Psychology, "Modell wählen", tint = NexusPrimary)
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "Menü", tint = NexusTextSecondary)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Stimme / TTS") },
                            onClick = {
                                showVoiceSwitcher = true
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(Icons.Default.RecordVoiceOver, null, tint = NexusPrimary)
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(if (isModelLoaded) "LLM ausschalten" else "LLM einschalten")
                            },
                            onClick = {
                                viewModel.toggleModelLoaded()
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.PowerSettingsNew, null,
                                    tint = if (isModelLoaded) NexusPrimary else NexusSecondary
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Chat löschen") },
                            onClick = {
                                showDeleteConfirm = true
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(Icons.Default.DeleteSweep, null, tint = NexusSecondary)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Einstellungen") },
                            onClick = {
                                onNavigateToSettings()
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Settings, null, tint = NexusTextDim)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Debug Log") },
                            onClick = {
                                onNavigateToDebugLog()
                                showMenu = false
                            },
                            leadingIcon = {
                                Icon(Icons.Default.BugReport, null, tint = NexusTextDim)
                            }
                        )
                    }
                }
            }
        )

        // Model loading indicator
        if (isLoadingModel) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NexusCard)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    "LLM wird geladen...",
                    fontSize = 12.sp,
                    color = NexusPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = NexusPrimary,
                    trackColor = NexusSurfaceVariant
                )
            }
        }

        // Download progress bar
        if (isDownloading) {
            val progress = (downloadState as ModelManager.DownloadState.Downloading).progress
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NexusCard)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Modell wird heruntergeladen...",
                        fontSize = 12.sp,
                        color = NexusTextSecondary
                    )
                    Text(
                        "${(progress * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = NexusPrimary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = NexusPrimary,
                    trackColor = NexusSurfaceVariant
                )
            }
        }

        // Messages list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (messages.isEmpty() && !isGenerating) {
                item {
                    // Welcome / Onboarding
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        ) {
                            Text("Nexus", fontSize = 36.sp, color = NexusPrimary)
                            Spacer(modifier = Modifier.height(12.dp))

                            if (currentModel == null && downloadedModels.isEmpty()) {
                                Text(
                                    "Willkommen! Um mit Nexus zu chatten,\nlade zuerst ein KI-Modell herunter.",
                                    fontSize = 14.sp,
                                    color = NexusTextSecondary,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(20.dp))
                                Button(
                                    onClick = { showModelSwitcher = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = NexusPrimary,
                                        contentColor = NexusBlack
                                    ),
                                    shape = RoundedCornerShape(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.CloudDownload, null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Modell herunterladen")
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "Empfohlen: Gemma 4 E4B (5 GB)\noder Qwen3 4B (2.7 GB)",
                                    fontSize = 12.sp,
                                    color = NexusTextDim,
                                    textAlign = TextAlign.Center
                                )
                            } else if (!isModelLoaded && !isLoadingModel) {
                                Text(
                                    "Modell ist ausgeschaltet.",
                                    fontSize = 14.sp,
                                    color = NexusTextSecondary,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                TextButton(
                                    onClick = { viewModel.toggleModelLoaded() }
                                ) {
                                    Text("LLM einschalten", color = NexusPrimary)
                                }
                            } else if (isLoadingModel) {
                                Text(
                                    "Modell wird geladen...",
                                    fontSize = 14.sp,
                                    color = NexusPrimary,
                                    textAlign = TextAlign.Center
                                )
                            } else {
                                Text(
                                    "Schreib mir etwas...",
                                    fontSize = 14.sp,
                                    color = NexusTextDim,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            items(messages, key = { it.id }) { message ->
                MessageBubble(
                    content = message.content,
                    isUser = message.role == "user",
                    timestamp = message.timestamp
                )
            }

            // Streaming response (real-time token display)
            if (isGenerating && streamingText.isNotBlank()) {
                item(key = "streaming") {
                    MessageBubble(
                        content = streamingText,
                        isUser = false,
                        timestamp = System.currentTimeMillis(),
                        isStreaming = true
                    )
                }
            } else if (isGenerating) {
                // Show typing indicator while waiting for first token
                item(key = "typing") {
                    TypingIndicator()
                }
            }
        }

        // Error bar (auto-dismisses after 8 seconds)
        if (errorMessage != null) {
            Snackbar(
                action = {
                    TextButton(onClick = { viewModel.clearError() }) {
                        Text("OK", color = NexusPrimary)
                    }
                },
                containerColor = NexusCard,
                contentColor = NexusTextPrimary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(errorMessage ?: "")
            }
        }

        // Input bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = {
                    Text("Nachricht...", color = NexusTextDim)
                },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp)),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = NexusCard,
                    unfocusedContainerColor = NexusCard,
                    cursorColor = NexusPrimary,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = NexusTextPrimary,
                    unfocusedTextColor = NexusTextPrimary
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (canSend) {
                            viewModel.sendMessage(inputText.trim())
                            inputText = ""
                        }
                    }
                ),
                singleLine = false,
                maxLines = 4
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = {
                    if (canSend) {
                        viewModel.sendMessage(inputText.trim())
                        inputText = ""
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (canSend) NexusPrimary else NexusSurfaceVariant)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (canSend) NexusBlack else NexusTextDim
                )
            }
        }
    }

    // Model switcher bottom sheet
    if (showModelSwitcher) {
        val storageInfo = viewModel.getStorageInfo()
        ModelSwitcherSheet(
            currentModelId = currentModel,
            downloadedModels = downloadedModels,
            downloadState = downloadState,
            availableStorageGb = storageInfo.availableStorageGb,
            availableRamGb = storageInfo.availableRamGb,
            totalRamGb = storageInfo.totalRamGb,
            onModelSelected = { model ->
                viewModel.switchModel(model)
                if (model.id in downloadedModels) {
                    showModelSwitcher = false
                }
            },
            onModelDeleted = { model ->
                viewModel.deleteModel(model)
            },
            onDismiss = { showModelSwitcher = false }
        )
    }

    // Voice switcher bottom sheet
    if (showVoiceSwitcher) {
        val currentTtsModel by viewModel.currentTtsModelId.collectAsState()
        val downloadedTtsModels by viewModel.downloadedTtsModels.collectAsState()
        val ttsDownloadState by viewModel.ttsDownloadState.collectAsState()

        VoiceSwitcherSheet(
            currentProfileId = voiceProfile.id,
            currentTtsModelId = currentTtsModel,
            downloadedTtsModels = downloadedTtsModels,
            ttsDownloadState = ttsDownloadState,
            onProfileSelected = { profile ->
                viewModel.setVoiceProfile(profile)
                showVoiceSwitcher = false
            },
            onTtsModelSelected = { ttsModel ->
                viewModel.selectTtsModel(ttsModel)
            },
            onDismiss = { showVoiceSwitcher = false }
        )
    }

    // Delete confirmation dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Chat löschen?", color = NexusTextPrimary) },
            text = {
                Text(
                    "Der gesamte Chatverlauf wird unwiderruflich gelöscht.",
                    color = NexusTextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearChat()
                    showDeleteConfirm = false
                }) {
                    Text("Löschen", color = NexusSecondary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Abbrechen", color = NexusPrimary)
                }
            },
            containerColor = NexusCard
        )
    }
}

@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")

    Row(
        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(3) { index ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = index * 200),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_$index"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(NexusPrimary.copy(alpha = alpha))
            )
        }
    }
}
