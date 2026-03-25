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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.companion.stt.SpeechRecognizerManager
import com.nexus.companion.ui.theme.BatteryGreen
import com.nexus.companion.ui.theme.NexusBlack
import com.nexus.companion.ui.theme.NexusPrimary
import com.nexus.companion.ui.theme.NexusSecondary
import com.nexus.companion.ui.theme.NexusSurfaceVariant
import com.nexus.companion.ui.theme.NexusTextDim
import com.nexus.companion.ui.theme.NexusTextPrimary
import com.nexus.companion.ui.theme.NexusTextSecondary
import com.nexus.companion.viewmodel.ChatViewModel
import kotlinx.coroutines.delay

@Composable
fun PhoneScreen(
    viewModel: ChatViewModel,
    onHangUp: () -> Unit
) {
    val isGenerating by viewModel.phoneIsGenerating.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val callState by viewModel.phoneCallState.collectAsState()
    val isCallActive = callState is com.nexus.companion.phone.PhoneCallService.CallState.Active
    val sttState by viewModel.sttState.collectAsState()
    val isListening = sttState is SpeechRecognizerManager.SttState.Listening
    val sttStarting = sttState is SpeechRecognizerManager.SttState.Starting
            || sttState is SpeechRecognizerManager.SttState.Idle
    val isProcessing = sttState is SpeechRecognizerManager.SttState.Processing
            || sttState is SpeechRecognizerManager.SttState.Done
    val sttText by viewModel.sttPartialText.collectAsState()
    val isSpeakerOn by viewModel.isSpeakerOn.collectAsState()

    // Call duration timer
    var callStartTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        callStartTime = System.currentTimeMillis()
        while (true) {
            delay(1000)
            elapsedSeconds = (System.currentTimeMillis() - callStartTime) / 1000
        }
    }

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

    // Different pulse for thinking/speaking
    val thinkingPulse by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "thinking"
    )

    DisposableEffect(Unit) {
        viewModel.startPhoneMode()
        onDispose {
            viewModel.stopPhoneMode()
        }
    }

    // Determine visual state
    val circleColor = when {
        isSpeaking -> BatteryGreen.copy(alpha = 0.4f)
        isGenerating -> NexusPrimary.copy(alpha = thinkingPulse * 0.3f)
        isListening -> NexusPrimary.copy(alpha = 0.2f)
        else -> NexusSurfaceVariant.copy(alpha = 0.3f)
    }
    val innerCircleColor = when {
        isSpeaking -> BatteryGreen.copy(alpha = 0.6f)
        isGenerating -> NexusPrimary.copy(alpha = thinkingPulse * 0.5f)
        isListening -> NexusPrimary.copy(alpha = 0.4f)
        else -> NexusSurfaceVariant.copy(alpha = 0.5f)
    }
    val circleScale = when {
        isListening -> pulseScale
        isSpeaking -> 1f + (thinkingPulse - 0.6f) * 0.3f
        isGenerating -> 1f
        else -> 1f
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Call duration
        val minutes = elapsedSeconds / 60
        val seconds = elapsedSeconds % 60
        Text(
            text = "%02d:%02d".format(minutes, seconds),
            fontSize = 14.sp,
            color = NexusTextDim
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Pulsating circle with state-dependent colors
        Box(
            modifier = Modifier
                .size(160.dp)
                .scale(circleScale)
                .clip(CircleShape)
                .background(circleColor),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(innerCircleColor),
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

        // Status text with state-appropriate color
        val (statusText, statusColor) = when {
            isSpeaking -> "Nexus spricht..." to BatteryGreen
            isGenerating -> "Nexus denkt nach..." to NexusPrimary
            isProcessing -> (sttText.ifBlank { "Verarbeite..." }) to NexusTextSecondary
            isListening -> "Hört zu..." to NexusPrimary
            sttStarting && isCallActive -> "Verbinde..." to NexusTextDim
            sttText.isNotBlank() -> sttText to NexusTextSecondary
            else -> "Tippe auf das Mikrofon" to NexusTextDim
        }

        Text(
            text = statusText,
            fontSize = 16.sp,
            color = statusColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        // Show recognized text while generating
        if (isGenerating && sttText.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "\"$sttText\"",
                fontSize = 13.sp,
                color = NexusTextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp)
            )
        }

        Spacer(modifier = Modifier.height(64.dp))

        // Control buttons row: Speaker + Mic + (balanced)
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Speaker toggle
            IconButton(
                onClick = { viewModel.toggleSpeaker() },
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(if (isSpeakerOn) NexusPrimary.copy(alpha = 0.3f) else NexusSurfaceVariant)
            ) {
                Icon(
                    if (isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = "Lautsprecher",
                    tint = if (isSpeakerOn) NexusPrimary else NexusTextDim,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(24.dp))

            // Mic button (main, bigger)
            IconButton(
                onClick = { viewModel.toggleListening() },
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(if (isListening) NexusPrimary else NexusSurfaceVariant)
            ) {
                Icon(
                    if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "Mikrofon",
                    tint = if (isListening) NexusBlack else NexusTextPrimary,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.width(24.dp))

            // Hang up (in row for balance)
            IconButton(
                onClick = onHangUp,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(NexusSecondary)
            ) {
                Icon(
                    Icons.Default.CallEnd,
                    contentDescription = "Auflegen",
                    tint = NexusBlack,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Speaker status label
        Text(
            text = if (isSpeakerOn) "Lautsprecher" else "Ohrhörer",
            fontSize = 12.sp,
            color = NexusTextDim
        )
    }
}
