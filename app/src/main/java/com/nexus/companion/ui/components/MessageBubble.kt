package com.nexus.companion.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexus.companion.ui.theme.AssistantBubble
import com.nexus.companion.ui.theme.NexusPrimary
import com.nexus.companion.ui.theme.NexusSurfaceVariant
import com.nexus.companion.ui.theme.NexusTextDim
import com.nexus.companion.ui.theme.NexusTextPrimary
import com.nexus.companion.ui.theme.UserBubble
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    content: String,
    isUser: Boolean,
    timestamp: Long,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false
) {
    val context = LocalContext.current
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    var showTimestamp by remember { mutableStateOf(false) }

    // Responsive max width: 85% of screen, capped at 400dp
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxBubbleWidth = minOf(screenWidth * 0.85f, 400.dp)

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ),
                color = if (isUser) UserBubble else AssistantBubble,
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .combinedClickable(
                        onClick = { showTimestamp = !showTimestamp },
                        onLongClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Nexus", content))
                            Toast.makeText(context, "Kopiert", Toast.LENGTH_SHORT).show()
                        }
                    )
            ) {
                // Check for code blocks and render accordingly
                if (content.contains("```")) {
                    CodeAwareText(
                        text = if (isStreaming) "$content\u258C" else content,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                } else {
                    Text(
                        text = if (isStreaming) "$content\u258C" else content,
                        color = NexusTextPrimary,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }

            // Timestamp shown on tap (not always visible — cleaner UI)
            if (showTimestamp || isStreaming) {
                Text(
                    text = if (isStreaming) "wird generiert..." else timeFormat.format(Date(timestamp)),
                    color = if (isStreaming) NexusPrimary else NexusTextDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/**
 * Renders text with code block detection.
 * Text between ``` markers is shown in monospace with a dark background.
 */
@Composable
private fun CodeAwareText(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        val parts = text.split("```")
        parts.forEachIndexed { index, part ->
            if (index % 2 == 0) {
                // Normal text
                if (part.isNotBlank()) {
                    Text(
                        text = part.trimEnd(),
                        color = NexusTextPrimary,
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    )
                }
            } else {
                // Code block — strip optional language tag on first line
                val code = part.trimStart('\n').let { block ->
                    val firstNewline = block.indexOf('\n')
                    if (firstNewline > 0 && firstNewline < 20 && !block.substring(0, firstNewline).contains(' ')) {
                        block.substring(firstNewline + 1) // Remove language tag line
                    } else {
                        block
                    }
                }.trimEnd()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(NexusSurfaceVariant, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        text = code,
                        color = NexusTextPrimary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
