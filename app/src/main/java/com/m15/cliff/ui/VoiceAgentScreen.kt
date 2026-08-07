package com.m15.cliff.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.border
import com.m15.cliff.AgentUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceAgentScreen(
    ui: AgentUiState,
    isSpeakerOn: Boolean,
    onSpeakerToggle: () -> Unit,
    onDismissSession: () -> Unit,
    onToggleVisualizer: () -> Unit,
    showVisualizer: Boolean,
    ttsLevel: Float,
    latencyMs: Long?
) {
    val snackbarHostState = remember { SnackbarHostState() }
    ui.error?.let { errorMsg ->
        LaunchedEffect(errorMsg) {
            snackbarHostState.showSnackbar(
                message = errorMsg,
                actionLabel = "OK",
                duration = SnackbarDuration.Long
            )
        }
    }

    val lastUserMsg = ui.messages.lastOrNull { it.first == "user" }?.second
    val lastAssistantMsg = ui.messages.lastOrNull { it.first == "assistant" }?.second
    val showLiveUser = !ui.livePartial.isNullOrEmpty() && ui.livePartial != lastUserMsg
    val showLiveAssistant = !ui.assistantLive.isNullOrEmpty() && ui.assistantLive != lastAssistantMsg

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Black,
        topBar = {
            // Plain inset-aware header. A CenterAlignedTopAppBar clips its title to a
            // fixed ~64dp row, which sliced the top off the 36sp "Cliff" glyphs.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 16.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Cliff",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 36.sp,
                    letterSpacing = 1.5.sp,
                    maxLines = 1
                )
            }
        },
        floatingActionButton = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // End Session FAB
                FloatingActionButton(
                    onClick = onDismissSession,
                    containerColor = Color.White,
                    contentColor = Color.Black
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "End Session",
                        modifier = Modifier.size(32.dp)
                    )
                }

                // Speakerphone FAB
                FloatingActionButton(
                    onClick = onSpeakerToggle,
                    containerColor = if (isSpeakerOn) Color.White else Color(0xFF1A1A1A),
                    contentColor = if (isSpeakerOn) Color.Black else Color.White,
                    modifier = Modifier
                        .clip(CircleShape)
                        .border(
                            width = 2.dp,
                            color = if (isSpeakerOn) Color.Transparent else Color(0xFF444444),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.Headset,
                        contentDescription = if (isSpeakerOn) "Speaker On" else "Speaker Off",
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Toggle Visualizer/Text
                FloatingActionButton(
                    onClick = onToggleVisualizer,
                    containerColor = if (showVisualizer) Color.White else Color(0xFF1A1A1A),
                    contentColor = if (showVisualizer) Color.Black else Color.White
                ) {
                    Icon(
                        imageVector = if (showVisualizer) Icons.Default.Chat else Icons.Default.GraphicEq,
                        contentDescription = "Toggle Visualizer"
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { pad ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(pad)
        ) {
            if (showVisualizer) {
                AudioBlobVisualizer(
                    level = ttsLevel,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                        // Keep the glow/mist from overflowing upward over the "Cliff" title.
                        .clipToBounds(),
                    accent = Color.White
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        reverseLayout = true,
                        // Reserve room for the bottom-right FAB stack (3 × 56dp +
                        // 2 × 16dp spacing) so the newest messages float above it
                        // instead of being hidden behind it. With reverseLayout the
                        // list is bottom-anchored, so this padding lands at the bottom.
                        contentPadding = PaddingValues(bottom = 208.dp)
                    ) {
                        if (showLiveAssistant) {
                            item { ChatBubble("assistant", ui.assistantLive!!, Color.White) }
                        }
                        if (showLiveUser) {
                            item { ChatBubble("user", ui.livePartial!!, Color(0xFF888888)) }
                        }
                        items(ui.messages.asReversed()) { (role, msg) ->
                            val color = if (role == "assistant") Color.White else Color(0xFF888888)
                            ChatBubble(role, msg, color)
                        }
                    }

                    if (ui.isThinking) {
                        Text(
                            "thinking...",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            textAlign = TextAlign.Center,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // Pipeline latency (time to first token), shown over both views
            latencyMs?.let { ms ->
                Text(
                    text = "TTFT $ms ms",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 4.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
fun ChatBubble(role: String, text: String, color: Color) {
    val alignment = if (role == "assistant") Alignment.CenterStart else Alignment.CenterEnd
    val bubbleColor = color.copy(alpha = 0.12f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .background(bubbleColor, MaterialTheme.shapes.large)
                .padding(14.dp)
                .widthIn(max = 320.dp)
        ) {
            Text(
                text = text,
                color = color,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
