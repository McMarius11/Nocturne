package com.nexus.companion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.companion.VoiceProfile
import com.nexus.companion.llm.ModelManager
import com.nexus.companion.tts.TtsModelInfo
import com.nexus.companion.tts.TtsSpeaker
import com.nexus.companion.ui.theme.BatteryGreen
import com.nexus.companion.ui.theme.BatteryYellow
import com.nexus.companion.ui.theme.NexusBlack
import com.nexus.companion.ui.theme.NexusCard
import com.nexus.companion.ui.theme.NexusPrimary
import com.nexus.companion.ui.theme.NexusSurfaceVariant
import com.nexus.companion.ui.theme.NexusTextDim
import com.nexus.companion.ui.theme.NexusTextPrimary
import com.nexus.companion.ui.theme.NexusTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSwitcherSheet(
    currentProfileId: String,
    currentTtsModelId: String?,
    currentSpeakerId: Int,
    downloadedTtsModels: Set<String>,
    ttsDownloadState: ModelManager.DownloadState,
    onProfileSelected: (VoiceProfile) -> Unit,
    onTtsModelSelected: (TtsModelInfo) -> Unit,
    onSpeakerSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NexusBlack,
        scrimColor = NexusBlack.copy(alpha = 0.7f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 32.dp)
        ) {
            // --- Voice Profile Section ---
            Text(
                text = "Spracheingabe",
                fontSize = 20.sp,
                color = NexusTextPrimary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "Sprache für Spracherkennung (STT) wählen",
                fontSize = 13.sp,
                color = NexusTextDim,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            VoiceProfile.ALL_PROFILES.forEach { profile ->
                val isSelected = profile.id == currentProfileId

                VoiceCard(
                    name = profile.displayName,
                    description = profile.description,
                    languages = profile.languages.joinToString(" + ") { it.uppercase() },
                    isSelected = isSelected,
                    warning = profile.warning,
                    onClick = { onProfileSelected(profile) }
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(20.dp))

            // --- TTS Model Section ---
            Text(
                text = "Sprachausgabe",
                fontSize = 20.sp,
                color = NexusTextPrimary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "Neuronale Stimme für natürlichere Sprachausgabe",
                fontSize = 13.sp,
                color = NexusTextDim,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Android System TTS (always available)
            VoiceCard(
                name = "Android System TTS",
                description = "Eingebaut — sofort verfügbar",
                languages = "DE + EN",
                isSelected = currentTtsModelId == null,
                onClick = { onTtsModelSelected(TtsModelInfo(
                    id = "system", displayName = "System TTS", fileName = "",
                    downloadUrl = "", sizeBytes = 0, languages = listOf("de", "en"),
                    description = ""
                )) }
            )
            Spacer(modifier = Modifier.height(6.dp))

            // Downloadable TTS models
            TtsModelInfo.ALL_TTS_MODELS.forEach { model ->
                val isDownloaded = model.id in downloadedTtsModels
                val isSelected = model.id == currentTtsModelId
                val isDownloading = ttsDownloadState is ModelManager.DownloadState.Downloading
                        && ttsDownloadState.modelId == model.id

                TtsModelCard(
                    model = model,
                    isSelected = isSelected,
                    isDownloaded = isDownloaded,
                    isDownloading = isDownloading,
                    downloadProgress = if (isDownloading)
                        (ttsDownloadState as ModelManager.DownloadState.Downloading).progress
                    else 0f,
                    onClick = { onTtsModelSelected(model) }
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            // --- Speaker Selection (only for Kokoro) ---
            if (currentTtsModelId == "kokoro-en" && "kokoro-en" in downloadedTtsModels) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Stimme",
                    fontSize = 20.sp,
                    color = NexusTextPrimary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Kokoro Sprecher auswählen (männlich / weiblich)",
                    fontSize = 13.sp,
                    color = NexusTextDim,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                TtsSpeaker.KOKORO_SPEAKERS.forEach { speaker ->
                    val isSelected = speaker.id == currentSpeakerId
                    VoiceCard(
                        name = speaker.displayName,
                        description = speaker.name,
                        languages = speaker.accent,
                        isSelected = isSelected,
                        onClick = { onSpeakerSelected(speaker.id) }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            // Experimental section (pending llama.cpp upstream)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Bald verfügbar",
                fontSize = 14.sp,
                color = NexusTextDim,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            TtsModelInfo.EXPERIMENTAL_MODELS.forEach { model ->
                TtsModelCard(
                    model = model,
                    isSelected = false,
                    isDownloaded = false,
                    isDownloading = false,
                    downloadProgress = 0f,
                    onClick = { /* not yet available */ }
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun VoiceCard(
    name: String,
    description: String,
    languages: String,
    isSelected: Boolean,
    warning: String? = null,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) NexusSurfaceVariant else NexusCard,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(NexusPrimary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = name,
                        fontSize = 15.sp,
                        color = if (isSelected) NexusPrimary else NexusTextPrimary
                    )
                }
                Text(text = languages, fontSize = 11.sp, color = NexusTextDim)
            }
            Text(
                text = description,
                fontSize = 12.sp,
                color = NexusTextSecondary,
                modifier = Modifier.padding(top = 3.dp)
            )
            if (warning != null) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, null, tint = BatteryYellow, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = warning, fontSize = 11.sp, color = BatteryYellow)
                }
            }
        }
    }
}

@Composable
private fun TtsModelCard(
    model: TtsModelInfo,
    isSelected: Boolean,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    onClick: () -> Unit
) {
    val sizeMb = model.sizeBytes / 1_000_000

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) NexusSurfaceVariant else NexusCard,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isDownloading) { onClick() }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(NexusPrimary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = model.displayName,
                        fontSize = 15.sp,
                        color = if (isSelected) NexusPrimary else NexusTextPrimary
                    )
                }

                when {
                    isDownloading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 2.dp,
                                color = NexusPrimary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("${(downloadProgress * 100).toInt()}%", fontSize = 11.sp, color = NexusTextSecondary)
                        }
                    }
                    isDownloaded -> {
                        Icon(Icons.Default.Check, "Downloaded", tint = BatteryGreen, modifier = Modifier.size(15.dp))
                    }
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudDownload, null, tint = NexusTextDim, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("${sizeMb} MB", fontSize = 11.sp, color = NexusTextDim)
                        }
                    }
                }
            }

            Text(
                text = model.description,
                fontSize = 12.sp,
                color = NexusTextSecondary,
                modifier = Modifier.padding(top = 3.dp)
            )

            if (isDownloading) {
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp),
                    color = NexusPrimary,
                    trackColor = NexusSurfaceVariant,
                )
            }
        }
    }
}
