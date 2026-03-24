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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.companion.stt.SpeechRecognizerManager
import com.nexus.companion.ui.theme.NexusBlack
import com.nexus.companion.ui.theme.NexusPrimary
import com.nexus.companion.ui.theme.NexusSecondary
import com.nexus.companion.ui.theme.NexusSurfaceVariant
import com.nexus.companion.ui.theme.NexusTextDim
import com.nexus.companion.ui.theme.NexusTextPrimary
import com.nexus.companion.viewmodel.ChatViewModel

@Composable
fun PhoneScreen(
    viewModel: ChatViewModel,
    onHangUp: () -> Unit
) {
    val isGenerating by viewModel.isGenerating.collectAsState()
    val sttState by viewModel.sttState.collectAsState()
    val isListening = sttState is SpeechRecognizerManager.SttState.Listening
    val sttText by viewModel.sttPartialText.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    DisposableEffect(Unit) {
        viewModel.startPhoneMode()
        onDispose {
            viewModel.stopPhoneMode()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusBlack)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Pulsating circle when listening
        Box(
            modifier = Modifier
                .size(160.dp)
                .scale(if (isListening) pulseScale else 1f)
                .clip(CircleShape)
                .background(
                    if (isListening) NexusPrimary.copy(alpha = 0.2f)
                    else NexusSurfaceVariant.copy(alpha = 0.3f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(
                        if (isListening) NexusPrimary.copy(alpha = 0.4f)
                        else NexusSurfaceVariant.copy(alpha = 0.5f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "N",
                    fontSize = 40.sp,
                    color = NexusTextPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = when {
                isGenerating -> "Nexus is thinking..."
                isListening -> "Listening..."
                sttText.isNotBlank() -> sttText
                else -> "Tap the microphone"
            },
            fontSize = 16.sp,
            color = if (isListening) NexusPrimary else NexusTextDim,
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        Spacer(modifier = Modifier.height(64.dp))

        // Mic button
        IconButton(
            onClick = { viewModel.toggleListening() },
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(if (isListening) NexusPrimary else NexusSurfaceVariant)
        ) {
            Icon(
                if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = "Microphone",
                tint = if (isListening) NexusBlack else NexusTextPrimary,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Hang up
        IconButton(
            onClick = onHangUp,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(NexusSecondary)
        ) {
            Icon(
                Icons.Default.CallEnd,
                contentDescription = "Hang up",
                tint = NexusBlack,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
