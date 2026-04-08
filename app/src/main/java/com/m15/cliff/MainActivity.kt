package com.m15.cliff

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.m15.cliff.prefs.CliffLocalPrefs
import com.m15.cliff.ui.CliffSetupScreen
import com.m15.cliff.ui.VoiceAgentScreen
import com.m15.cliff.ui.theme.CliffTheme

class MainActivity : ComponentActivity() {

    private val vm by viewModels<VoiceAgentViewModel>()

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

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

                // Only request mic permission when the user actually starts a session
                LaunchedEffect(uiState.sessionActive) {
                    if (uiState.sessionActive) {
                        requestPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }

                when (val g = gate) {
                    is VoiceAgentViewModel.GateState.Checking -> {
                        Box(Modifier.fillMaxSize()) { /* splash */ }
                    }

                    is VoiceAgentViewModel.GateState.NeedsInvite -> {
                        InviteCodeScreen(
                            isBusy = false,
                            errorText = null,
                            onSubmit = { code -> vm.submitInviteCode(code) },
                            onExit = { finish() },
                            onRequestInvite = { vm.requestInvite(message = "") }
                        )
                    }

                    is VoiceAgentViewModel.GateState.Error -> {
                        InviteCodeScreen(
                            isBusy = false,
                            errorText = g.message,
                            onSubmit = { code -> vm.submitInviteCode(code) },
                            onExit = { finish() },
                            onRequestInvite = { vm.requestInvite(message = "") }
                        )
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
                                    onStartSession = { vm.startSession() }
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
                                ttsLevel = ttsLevel
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
