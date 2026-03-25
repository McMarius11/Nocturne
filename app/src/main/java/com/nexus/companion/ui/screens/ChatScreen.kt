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
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onNavigateToPhone: () -> Unit
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

    var inputText by remember { mutableStateOf("") }
    var showModelSwitcher by remember { mutableStateOf(false) }
    var showVoiceSwitcher by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Auto-scroll to bottom
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
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
                            isLoadingModel -> "${currentModel} wird geladen..."
                            isModelLoaded -> currentModel!!
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
                    Icon(Icons.Default.Call, "Phone Mode", tint = NexusPrimary)
                }
                IconButton(onClick = { showModelSwitcher = true }) {
                    Icon(Icons.Default.Psychology, "Model", tint = NexusPrimary)
                }
                IconButton(onClick = { showVoiceSwitcher = true }) {
                    Icon(Icons.Default.RecordVoiceOver, "Voice", tint = NexusPrimary)
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "More", tint = NexusTextSecondary)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
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
                                    if (isModelLoaded) Icons.Default.PowerSettingsNew
                                    else Icons.Default.PowerSettingsNew,
                                    null,
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
                    }
                }
            }
        )

        // Messages
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Nexus", fontSize = 32.sp, color = NexusPrimary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Say something... / Schreib mir etwas...",
                                fontSize = 14.sp,
                                color = NexusTextDim
                            )
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

            // Typing indicator
            if (isGenerating) {
                item {
                    TypingIndicator()
                }
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
                    Text("Message...", color = NexusTextDim)
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
                        if (inputText.isNotBlank() && !isGenerating) {
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
                    if (inputText.isNotBlank() && !isGenerating) {
                        viewModel.sendMessage(inputText.trim())
                        inputText = ""
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (inputText.isNotBlank()) NexusPrimary else NexusSurfaceVariant)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (inputText.isNotBlank()) NexusBlack else NexusTextDim
                )
            }
        }
    }

    // Error snackbar
    if (errorMessage != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Snackbar(
                action = {
                    TextButton(onClick = { viewModel.clearError() }) {
                        Text("OK", color = NexusPrimary)
                    }
                },
                containerColor = NexusCard,
                contentColor = NexusTextPrimary
            ) {
                Text(errorMessage ?: "")
            }
        }
    }

    // Model switcher bottom sheet
    if (showModelSwitcher) {
        ModelSwitcherSheet(
            currentModelId = currentModel,
            downloadedModels = downloadedModels,
            downloadState = downloadState,
            onModelSelected = { model ->
                viewModel.switchModel(model)
                showModelSwitcher = false
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
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Chat löschen?", color = NexusTextPrimary) },
            text = { Text("Der gesamte Chatverlauf wird unwiderruflich gelöscht.", color = NexusTextSecondary) },
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
