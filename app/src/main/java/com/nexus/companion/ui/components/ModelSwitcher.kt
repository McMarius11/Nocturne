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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
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
    onModelSelected: (ModelInfo) -> Unit,
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
            Text(
                text = "Modell wählen",
                fontSize = 20.sp,
                color = NexusTextPrimary,
                modifier = Modifier.padding(bottom = 16.dp)
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
                    onClick = { onModelSelected(model) }
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
}

@Composable
private fun ModelCard(
    model: ModelInfo,
    isSelected: Boolean,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    onClick: () -> Unit
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
            .clickable(enabled = !isDownloading) { onClick() }
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
                        text = "~${model.batteryPerHour}%/Std",
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
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Heruntergeladen",
                            tint = BatteryGreen,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    else -> {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = "Download nötig",
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
