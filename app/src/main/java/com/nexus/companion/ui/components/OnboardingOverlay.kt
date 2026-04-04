package com.nexus.companion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.companion.ui.theme.NexusBlack
import com.nexus.companion.ui.theme.NexusPrimary
import com.nexus.companion.ui.theme.NexusSurfaceVariant
import com.nexus.companion.ui.theme.NexusTextDim
import com.nexus.companion.ui.theme.NexusTextPrimary
import com.nexus.companion.ui.theme.NexusTextSecondary
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val description: String
)

private val pages = listOf(
    OnboardingPage(
        icon = Icons.Default.Psychology,
        title = "Dein KI-Begleiter",
        description = "Nexus ist ein persönlicher KI-Begleiter der komplett auf deinem Gerät läuft. Keine Cloud, keine Abos, keine Daten verlassen dein Handy."
    ),
    OnboardingPage(
        icon = Icons.Default.CloudDownload,
        title = "Modell herunterladen",
        description = "Lade ein KI-Modell herunter (2.7–7.9 GB). Empfohlen: Gemma 4 E4B (5 GB) — beste Qualität und unzensiert. Oder Qwen3 4B (2.7 GB) für weniger Speicher."
    ),
    OnboardingPage(
        icon = Icons.Default.Call,
        title = "Chat & Sprachmodus",
        description = "Schreib Nachrichten oder nutze den Sprachmodus — wie ein Telefonat mit Nexus. Nexus merkt sich deinen Namen, deine Vorlieben und Stimmung."
    )
)

@Composable
fun OnboardingOverlay(
    onDismiss: () -> Unit,
    onDownloadModel: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NexusBlack)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Title
            Text(
                "Nexus",
                fontSize = 42.sp,
                color = NexusPrimary,
                modifier = Modifier.padding(bottom = 48.dp)
            )

            // Pager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        pages[page].icon,
                        contentDescription = null,
                        tint = NexusPrimary,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        pages[page].title,
                        fontSize = 22.sp,
                        color = NexusTextPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        pages[page].description,
                        fontSize = 14.sp,
                        color = NexusTextSecondary,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                }
            }

            // Page indicators
            Row(
                modifier = Modifier.padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(pages.size) { index ->
                    Box(
                        modifier = Modifier
                            .size(if (index == pagerState.currentPage) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == pagerState.currentPage) NexusPrimary
                                else NexusSurfaceVariant
                            )
                    )
                }
            }

            // Buttons
            if (pagerState.currentPage == pages.size - 1) {
                // Last page — show Start button
                Button(
                    onClick = {
                        onDownloadModel()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NexusPrimary,
                        contentColor = NexusBlack
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Modell herunterladen & starten")
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onDismiss) {
                    Text("Überspringen", color = NexusTextDim)
                }
            } else {
                // Not last page — show Next button
                Button(
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NexusPrimary,
                        contentColor = NexusBlack
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Weiter")
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onDismiss) {
                    Text("Überspringen", color = NexusTextDim)
                }
            }
        }
    }
}
