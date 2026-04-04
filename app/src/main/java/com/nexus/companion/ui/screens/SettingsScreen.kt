package com.nexus.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.companion.ui.theme.BatteryGreen
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
fun SettingsScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    androidx.activity.compose.BackHandler { onBack() }

    val isModelLoaded by viewModel.isModelLoaded.collectAsState()
    val currentModel by viewModel.currentModelId.collectAsState()
    val voiceProfile by viewModel.voiceProfile.collectAsState()
    val storageInfo = viewModel.getStorageInfo()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        TopAppBar(
            windowInsets = WindowInsets(0),
            title = { Text("Einstellungen", color = NexusTextPrimary) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = NexusBlack),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = NexusPrimary)
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // --- LLM Section ---
            SectionTitle("KI-Modell")

            SettingsCard(
                icon = Icons.Default.Psychology,
                title = currentModel?.let {
                    com.nexus.companion.llm.ModelInfo.findById(it)?.displayName ?: it
                } ?: "Kein Modell",
                subtitle = if (isModelLoaded) "Geladen und bereit" else "Nicht geladen",
                subtitleColor = if (isModelLoaded) BatteryGreen else NexusSecondary
            )

            Spacer(modifier = Modifier.height(8.dp))

            SettingsToggle(
                icon = Icons.Default.PowerSettingsNew,
                title = "LLM aktiv",
                subtitle = if (isModelLoaded) "Verbraucht RAM — nach 10 Min Inaktivität wird automatisch entladen"
                    else "Einschalten um zu chatten",
                isChecked = isModelLoaded,
                onCheckedChange = { viewModel.toggleModelLoaded() }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // --- Voice Section ---
            SectionTitle("Stimme")

            SettingsCard(
                icon = Icons.Default.RecordVoiceOver,
                title = voiceProfile.displayName,
                subtitle = voiceProfile.description
            )

            Spacer(modifier = Modifier.height(24.dp))

            // --- System Info ---
            SectionTitle("System")

            SettingsCard(
                icon = Icons.Default.Memory,
                title = "RAM",
                subtitle = "${String.format("%.1f", storageInfo.availableRamGb)} GB frei von ${String.format("%.0f", storageInfo.totalRamGb)} GB"
            )

            Spacer(modifier = Modifier.height(8.dp))

            SettingsCard(
                icon = Icons.Default.Storage,
                title = "Speicher",
                subtitle = "${String.format("%.1f", storageInfo.availableStorageGb)} GB frei"
            )

            Spacer(modifier = Modifier.height(24.dp))

            // --- App Info ---
            SectionTitle("App")

            Text(
                "Nexus Companion v1.0.0\nllama.cpp b8648 • sherpa-onnx 1.12.34\n100% offline • Apache 2.0",
                color = NexusTextDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 14.sp,
        color = NexusPrimary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun SettingsCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    subtitleColor: androidx.compose.ui.graphics.Color = NexusTextSecondary,
    onClick: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = NexusCard,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = NexusPrimary, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(title, fontSize = 15.sp, color = NexusTextPrimary)
                Text(subtitle, fontSize = 12.sp, color = subtitleColor)
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = NexusCard,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(icon, null, tint = NexusPrimary, modifier = Modifier.size(24.dp))
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Text(title, fontSize = 15.sp, color = NexusTextPrimary)
                    Text(subtitle, fontSize = 12.sp, color = NexusTextSecondary)
                }
            }
            Switch(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = NexusPrimary,
                    checkedTrackColor = NexusSurfaceVariant,
                    uncheckedThumbColor = NexusTextDim,
                    uncheckedTrackColor = NexusSurfaceVariant
                )
            )
        }
    }
}
