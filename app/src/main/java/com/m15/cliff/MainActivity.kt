package com.m15.cliff

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.m15.cliff.prefs.CliffLocalPrefs
import com.m15.cliff.ui.CliffSetupScreen
import com.m15.cliff.ui.VoiceAgentScreen
import com.m15.cliff.ui.theme.CliffTheme

class MainActivity : ComponentActivity() {

    private val vm by viewModels<VoiceAgentViewModel>()

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Only start the session once the mic is actually available, so AudioRecord
        // never initializes without RECORD_AUDIO (the first-run failure).
        if (granted) vm.startSession()
    }

    /** Start the voice session, requesting the mic first if it isn't already granted. */
    private fun startSessionWithMic() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.startSession()
        else requestPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CliffTheme {
                val context = LocalContext.current
                val prefs = remember { CliffLocalPrefs(context) }

                val gate by vm.gate.collectAsStateWithLifecycle()
                val uiState by vm.ui.collectAsStateWithLifecycle()
                val showViz by vm.showVisualizer.collectAsStateWithLifecycle()
                val ttsLevel by vm.ttsLevel.collectAsStateWithLifecycle()
                val micLevel by vm.micLevel.collectAsStateWithLifecycle()
                val latencyMs by vm.latencyMs.collectAsStateWithLifecycle()
                val systemMessage by vm.systemMessage.collectAsStateWithLifecycle()

                var showSystemMessageEditor by rememberSaveable { mutableStateOf(false) }

                // --- Restore prefs once ---
                LaunchedEffect(Unit) {
                    vm.setSystemMessage(prefs.getSystemMessage())
                }

                // --- Persist when system message changes ---
                LaunchedEffect(systemMessage) {
                    prefs.setSystemMessage(systemMessage)
                }

                when (val g = gate) {
                    is VoiceAgentViewModel.GateState.Checking -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }

                    is VoiceAgentViewModel.GateState.Error -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = g.message.ifBlank { "Couldn't connect to the server." },
                                    color = MaterialTheme.colorScheme.onBackground,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = { vm.retryGate() }) {
                                    Text("Retry")
                                }
                            }
                        }
                    }

                    is VoiceAgentViewModel.GateState.Ready -> {
                        if (!uiState.sessionActive) {
                            if (showSystemMessageEditor) {
                                CustomPromptScreen(
                                    initialPrompt = systemMessage,
                                    onSave = { newMessage ->
                                        vm.setSystemMessage(newMessage)
                                        showSystemMessageEditor = false
                                    },
                                    onCancel = { showSystemMessageEditor = false }
                                )
                            } else {
                                CliffSetupScreen(
                                    systemMessagePreview = systemMessage,
                                    onEditSystemMessage = { showSystemMessageEditor = true },
                                    onStartSession = { startSessionWithMic() }
                                )
                            }
                        } else {
                            VoiceAgentScreen(
                                ui = uiState,
                                isSpeakerOn = uiState.speakerOn,
                                onSpeakerToggle = { vm.toggleSpeaker() },
                                onDismissSession = { vm.stopSession() },
                                onToggleVisualizer = vm::toggleVisualizer,
                                showVisualizer = showViz,
                                // Drive the visualizer from whichever is louder —
                                // assistant TTS or the user's mic input.
                                ttsLevel = maxOf(ttsLevel, micLevel),
                                latencyMs = latencyMs
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        vm.stopSession()
    }
}
