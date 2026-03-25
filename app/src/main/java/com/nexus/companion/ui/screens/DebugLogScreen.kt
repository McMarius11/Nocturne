package com.nexus.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.companion.llm.DebugLog
import com.nexus.companion.ui.theme.NexusBlack
import com.nexus.companion.ui.theme.NexusCard
import com.nexus.companion.ui.theme.NexusPrimary
import com.nexus.companion.ui.theme.NexusTextDim
import com.nexus.companion.ui.theme.NexusTextPrimary
import com.nexus.companion.ui.theme.NexusTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(
    onBack: () -> Unit
) {
    val entries by DebugLog.entries.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll to bottom
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = NexusPrimary)
                }
            },
            actions = {
                IconButton(onClick = { DebugLog.clear() }) {
                    Icon(Icons.Default.Delete, "Clear", tint = NexusTextSecondary)
                }
            }
        )

        if (entries.isEmpty()) {
            Text(
                "Keine Log-Einträge.\nInteragiere mit der App um Logs zu sehen.",
                color = NexusTextDim,
                fontSize = 14.sp,
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
                    entry.contains("FAILED") || entry.contains("Error") -> NexusPrimary
                    entry.contains("LLM:") -> NexusTextPrimary
                    entry.contains("TTS:") -> NexusTextSecondary
                    entry.contains("STT:") -> NexusTextSecondary
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
