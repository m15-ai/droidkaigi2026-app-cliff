package com.m15.cliff

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m15.cliff.net.flux.PromptDictationController

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CustomPromptScreen(
    initialPrompt: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val dictation = remember {
        PromptDictationController(
            appContext = context,
            prefsRepo = ServiceLocator.prefsRepo,
            deviceKey = ServiceLocator.deviceKey
        )
    }
    DisposableEffect(Unit) {
        onDispose { dictation.close() }
    }

    var text by remember { mutableStateOf(initialPrompt) }
    var isListening by remember { mutableStateOf(false) }
    var continuous by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    fun stopDictation() {
        isListening = false
        continuous = false
        partial = ""
        dictation.stop()
    }

    fun appendWithPunctuation(current: String, additionRaw: String): String {
        val addition = additionRaw.trim()
        if (addition.isBlank()) return current

        val prefixSep = when {
            current.isBlank() -> ""
            current.last().isWhitespace() -> ""
            else -> " "
        }

        val endsWithPunct = addition.lastOrNull()?.let { it in setOf('.', '!', '?', ';', ':') } == true
        val suffix = if (endsWithPunct) " " else ". "

        return current + prefixSep + addition + suffix
    }

    fun startDictation(continuousMode: Boolean) {
        isListening = true
        continuous = continuousMode
        partial = ""

        dictation.start(
            onPartial = { p -> partial = p },
            onFinal = { finalText ->
                if (finalText.isNotBlank()) {
                    text = appendWithPunctuation(text, finalText)
                }
                partial = ""
                if (!continuous) stopDictation()
            },
            onError = {
                stopDictation()
            }
        )
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            ) {
                CenterAlignedTopAppBar(
                    title = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp, bottom = 6.dp)
                        ) {
                            Text(
                                text = "Cliff",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 36.sp,
                                letterSpacing = 1.5.sp,
                                maxLines = 1
                            )
                            Text(
                                text = "System Message",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxSize()
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(padding)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    color = Color(0xFF1A1A1A),
                    shape = RoundedCornerShape(24.dp),
                    tonalElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Spacer(Modifier.height(24.dp))

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scroll)
                        ) {
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 260.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.White,
                                    unfocusedBorderColor = Color(0xFF444444),
                                    focusedLabelColor = Color.White,
                                    cursorColor = Color.White
                                )
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val hint = when {
                                    isListening && partial.isNotBlank() -> partial
                                    isListening && continuous -> "Listening… (continuous)"
                                    isListening -> "Listening…"
                                    else -> ""
                                }

                                Text(
                                    text = hint,
                                    color = Color.White.copy(alpha = 0.72f),
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(
                                        onClick = { text = "" },
                                        enabled = text.isNotBlank() && !isListening,
                                        modifier = Modifier.height(38.dp),
                                        shape = RoundedCornerShape(14.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.5.dp,
                                            Color.White.copy(alpha = if (text.isNotBlank() && !isListening) 0.85f else 0.35f)
                                        )
                                    ) {
                                        Text("CLEAR", fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
                                    }

                                    val micBg = if (isListening) Color.White.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.25f)
                                    val micFg = if (isListening) Color.Black else Color.White

                                    Surface(
                                        modifier = Modifier
                                            .height(38.dp)
                                            .combinedClickable(
                                                enabled = true,
                                                onClick = {
                                                    if (isListening) stopDictation()
                                                    else startDictation(continuousMode = false)
                                                },
                                                onLongClick = {
                                                    if (!isListening) startDictation(continuousMode = true)
                                                }
                                            ),
                                        color = micBg,
                                        contentColor = micFg,
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.5.dp,
                                            Color.White.copy(alpha = if (text.isNotBlank() && !isListening) 0.85f else 0.35f)
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                        tonalElevation = 1.dp
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .padding(horizontal = 12.dp)
                                                .fillMaxHeight(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (isListening) Icons.Filled.MicOff else Icons.Filled.Mic,
                                                contentDescription = if (isListening) "Stop dictation" else "Dictate"
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = if (isListening) "STOP" else "MIC",
                                                fontWeight = FontWeight.ExtraBold,
                                                letterSpacing = 0.6.sp
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(24.dp))
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            stopDictation()
                            onCancel()
                        },
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF444444))
                    ) {
                        Text("CANCEL", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            stopDictation()
                            onSave(text.trim())
                        },
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF444444))
                    ) {
                        Text("SAVE", fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}
