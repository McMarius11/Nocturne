package com.nexus.companion.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.companion.llm.DebugLog
import com.nexus.companion.ui.theme.BatteryGreen
import com.nexus.companion.ui.theme.BatteryRed
import com.nexus.companion.ui.theme.BatteryYellow
import com.nexus.companion.ui.theme.NexusBlack
import com.nexus.companion.ui.theme.NexusCard
import com.nexus.companion.ui.theme.NexusPrimary
import com.nexus.companion.ui.theme.NexusTextDim
import com.nexus.companion.ui.theme.NexusTextPrimary
import com.nexus.companion.ui.theme.NexusTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(
    onBack: () -> Unit,
    debugInfo: String = ""
) {
    val context = LocalContext.current
    val entries by DebugLog.entries.collectAsState()
    val listState = rememberLazyListState()
    var showSystemInfo by remember { mutableStateOf(true) }

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        TopAppBar(
            windowInsets = WindowInsets(0),
            title = { Text("Debug Log", color = NexusTextPrimary) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = NexusBlack),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = NexusPrimary)
                }
            },
            actions = {
                // Toggle system info
                IconButton(onClick = { showSystemInfo = !showSystemInfo }) {
                    Icon(
                        if (showSystemInfo) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        "System Info",
                        tint = NexusTextSecondary
                    )
                }
                // Copy all logs to clipboard
                IconButton(onClick = {
                    val allText = buildString {
                        if (debugInfo.isNotBlank()) {
                            appendLine(debugInfo)
                            appendLine("---")
                        }
                        entries.forEach { appendLine(it) }
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Nexus Debug Log", allText))
                    Toast.makeText(context, "Log kopiert", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, "Log kopieren", tint = NexusTextSecondary)
                }
                // Clear logs
                IconButton(onClick = { DebugLog.clear() }) {
                    Icon(Icons.Default.Delete, "Log löschen", tint = NexusTextSecondary)
                }
            }
        )

        // System info header (collapsible)
        if (showSystemInfo && debugInfo.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NexusCard)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                debugInfo.lines().forEach { line ->
                    val color = when {
                        line.startsWith("===") -> NexusPrimary
                        line.contains("loaded: true") || line.contains("ready:") -> BatteryGreen
                        line.contains("loaded: false") || line.contains("None") -> BatteryRed
                        else -> NexusTextSecondary
                    }
                    Text(
                        text = line,
                        color = color,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            HorizontalDivider(color = NexusTextDim.copy(alpha = 0.3f))
        }

        if (entries.isEmpty()) {
            Text(
                "Keine Log-Einträge.\n\n" +
                "Sende eine Nachricht um Logs zu sehen.\n\n" +
                "Troubleshooting:\n" +
                "  1. Prüfe ob ein Modell geladen ist\n" +
                "  2. Sende eine Testnachricht\n" +
                "  3. Fehler werden rot markiert\n" +
                "  4. Log kopieren mit dem Copy-Button oben",
                color = NexusTextDim,
                fontSize = 13.sp,
                modifier = Modifier.padding(24.dp)
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp)
        ) {
            items(entries) { entry ->
                val color = when {
                    entry.contains("FAILED") || entry.contains("Error") || entry.contains("FATAL") -> BatteryRed
                    entry.contains("WARNING") -> BatteryYellow
                    entry.contains("loaded in") || entry.contains("Generated") || entry.contains("ready") -> BatteryGreen
                    entry.contains("LLM:") -> NexusTextPrimary
                    entry.contains("TTS:") -> NexusTextSecondary
                    entry.contains("STT:") -> NexusTextSecondary
                    entry.contains("DL:") -> NexusPrimary
                    else -> NexusTextDim
                }
                Text(
                    text = entry,
                    color = color,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                )
            }
        }
    }
}
