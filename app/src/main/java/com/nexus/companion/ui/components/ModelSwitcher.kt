package com.nexus.companion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteSweep
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.companion.llm.ModelInfo
import com.nexus.companion.llm.ModelManager
import com.nexus.companion.ui.theme.BatteryGreen
import com.nexus.companion.ui.theme.BatteryRed
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
fun ModelSwitcherSheet(
    currentModelId: String?,
    downloadedModels: Set<String>,
    downloadState: ModelManager.DownloadState,
    availableStorageGb: Float = 0f,
    availableRamGb: Float = 0f,
    totalRamGb: Float = 0f,
    onModelSelected: (ModelInfo) -> Unit,
    onModelDeleted: ((ModelInfo) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDownload by remember { mutableStateOf<ModelInfo?>(null) }
    var confirmDelete by remember { mutableStateOf<ModelInfo?>(null) }

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
            Text(
                text = "LLM Model",
                fontSize = 20.sp,
                color = NexusTextPrimary,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            // Storage & RAM info
            Text(
                text = "Speicher: ${String.format("%.1f", availableStorageGb)} GB frei  •  RAM: ${String.format("%.1f", availableRamGb)} / ${String.format("%.0f", totalRamGb)} GB",
                fontSize = 11.sp,
                color = NexusTextDim,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            ModelInfo.ALL_MODELS.forEach { model ->
                val isSelected = model.id == currentModelId
                val isDownloaded = model.id in downloadedModels
                val isDownloading = downloadState is ModelManager.DownloadState.Downloading
                        && downloadState.modelId == model.id

                ModelCard(
                    model = model,
                    isSelected = isSelected,
                    isDownloaded = isDownloaded,
                    isDownloading = isDownloading,
                    downloadProgress = if (isDownloading)
                        (downloadState as ModelManager.DownloadState.Downloading).progress
                    else 0f,
                    onClick = {
                        if (isDownloaded) {
                            onModelSelected(model)
                        } else {
                            // Show download confirmation for non-downloaded models
                            confirmDownload = model
                        }
                    },
                    onLongClick = if (isDownloaded && !isSelected) {
                        { confirmDelete = model }
                    } else null
                )

                Spacer(modifier = Modifier.height(8.dp))
            }

            if (downloadState is ModelManager.DownloadState.Error) {
                Text(
                    text = downloadState.message,
                    color = BatteryRed,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }

    // Download confirmation dialog with RAM warning
    confirmDownload?.let { model ->
        val estimatedRam = model.sizeGb + 2.0f
        val ramWarning = totalRamGb > 0 && estimatedRam > availableRamGb
        val storageWarning = availableStorageGb < model.sizeGb + 0.5f

        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDownload = null },
            title = { Text("${model.displayName} herunterladen?", color = NexusTextPrimary) },
            text = {
                Column {
                    Text(
                        "Download: ${model.sizeGb} GB\nSpeicher frei: ${String.format("%.1f", availableStorageGb)} GB",
                        color = NexusTextSecondary
                    )
                    if (ramWarning) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.BatteryChargingFull, null, tint = BatteryYellow, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "RAM-Warnung: Dieses Modell braucht ~${String.format("%.0f", estimatedRam)} GB RAM. " +
                                "Dein Gerät hat ${String.format("%.0f", totalRamGb)} GB (${String.format("%.1f", availableRamGb)} GB frei). " +
                                "Es könnte langsam werden oder abstürzen.",
                                fontSize = 12.sp,
                                color = BatteryYellow
                            )
                        }
                    }
                    if (storageWarning) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Nicht genug Speicherplatz!",
                            fontSize = 12.sp,
                            color = BatteryRed
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    onModelSelected(model)
                    confirmDownload = null
                }) {
                    Text("Herunterladen", color = NexusPrimary)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDownload = null }) {
                    Text("Abbrechen", color = NexusTextDim)
                }
            },
            containerColor = NexusCard
        )
    }

    // Delete confirmation dialog
    confirmDelete?.let { model ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("${model.displayName} löschen?", color = NexusTextPrimary) },
            text = {
                Text(
                    "${model.sizeGb} GB Speicher werden freigegeben.",
                    color = NexusTextSecondary
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    onModelDeleted?.invoke(model)
                    confirmDelete = null
                }) {
                    Text("Löschen", color = BatteryRed)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = null }) {
                    Text("Abbrechen", color = NexusTextDim)
                }
            },
            containerColor = NexusCard
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelCard(
    model: ModelInfo,
    isSelected: Boolean,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val batteryColor = when {
        model.batteryPerHour <= 10 -> BatteryGreen
        model.batteryPerHour <= 20 -> BatteryYellow
        else -> BatteryRed
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) NexusSurfaceVariant else NexusCard,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = !isDownloading,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
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
                        fontSize = 16.sp,
                        color = if (isSelected) NexusPrimary else NexusTextPrimary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.BatteryChargingFull,
                        contentDescription = null,
                        tint = batteryColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "~${model.batteryPerHour}%/h",
                        fontSize = 12.sp,
                        color = batteryColor
                    )
                }
            }

            Text(
                text = model.description,
                fontSize = 13.sp,
                color = NexusTextSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${model.sizeGb} GB",
                    fontSize = 12.sp,
                    color = NexusTextDim
                )

                when {
                    isDownloading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = NexusPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${(downloadProgress * 100).toInt()}%",
                                fontSize = 12.sp,
                                color = NexusTextSecondary
                            )
                        }
                    }
                    isDownloaded -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Heruntergeladen",
                                tint = BatteryGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            if (!isSelected && onLongClick != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    Icons.Default.DeleteSweep,
                                    contentDescription = "Löschen",
                                    tint = NexusTextDim,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onLongClick() }
                                )
                            }
                        }
                    }
                    else -> {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = "Download required",
                            tint = NexusTextDim,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (isDownloading) {
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(3.dp),
                    color = NexusPrimary,
                    trackColor = NexusSurfaceVariant,
                )
            }
        }
    }
}
