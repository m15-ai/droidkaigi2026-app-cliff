package com.m15.cliff

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m15.cliff.net.flux.FluxClient
import com.m15.cliff.net.LlmClient
import com.m15.cliff.prefs.PrefsRepository
import com.m15.cliff.tts.DeepgramTtsClient
import com.m15.cliff.util.LatencyTracker
import com.m15.cliff.util.areSimilar
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.sqrt

interface SupportsSpeakerphone {
    fun setSpeakerphoneEnabled(enabled: Boolean)
}

data class AgentUiState(
    val sessionId: String? = null,
    val sessionActive: Boolean = false,
    val livePartial: String? = null,
    val assistantLive: String? = null,
    val isThinking: Boolean = false,
    val error: String? = null,
    val messages: List<Pair<String, String>> = emptyList(),
    val speakerOn: Boolean = true
)

class VoiceAgentViewModel(
    private val flux: FluxClient = ServiceLocator.flux,
    private val llm: LlmClient = ServiceLocator.llm,
    private val audio: com.m15.cliff.audio.AudioCapture = ServiceLocator.audio,
    private val barge: BargeInController = ServiceLocator.barge
) : ViewModel() {

    companion object {
        private const val TAG = "VoiceAgentViewModel"
    }

    // --- Backend gating ---
    sealed interface GateState {
        data object Checking : GateState
        data object Ready : GateState
        data class Error(val message: String) : GateState
    }

    private val prefsRepo: PrefsRepository = ServiceLocator.prefsRepo

    private val _gate = MutableStateFlow<GateState>(GateState.Checking)
    val gate: StateFlow<GateState> = _gate

    private val am: AudioManager = ServiceLocator.audioManager
    private val _ui = MutableStateFlow(AgentUiState())
    val ui: StateFlow<AgentUiState> = _ui

    private var micStarted = false
    private var sttJob: Job? = null
    private var llmJob: Job? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null

    // --- Simple system message (user-configurable) ---
    private val _systemMessage = MutableStateFlow(LlmClient.DEFAULT_SYSTEM_MESSAGE)
    val systemMessage: StateFlow<String> = _systemMessage

    // --- Visualizer plumbing ---
    private val _ttsLevel = MutableStateFlow(0f)
    val ttsLevel: StateFlow<Float> = _ttsLevel

    // Mic input energy (RMS of captured PCM), so the visualizer also reacts
    // while the user is speaking — not only during TTS playback.
    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel

    // Default to the text/chat view; the user can tap the visualizer FAB to show the orb.
    private val _showVisualizer = MutableStateFlow(false)
    val showVisualizer: StateFlow<Boolean> = _showVisualizer

    // --- Pipeline latency (time to first token) ---
    private val latency = LatencyTracker()
    val latencyMs: StateFlow<Long?> = latency.ttftMs

    init {
        Log.i(TAG, "VoiceAgentViewModel initialized")
        (ServiceLocator.tts as? DeepgramTtsClient)?.onAudioLevel = ::onTtsAudioLevel
        bootstrapGate()
    }

    private fun bootstrapGate() {
        viewModelScope.launch {
            _gate.value = GateState.Checking
            // Preflight: mint a Deepgram token. This confirms the backend is reachable and
            // the app key is accepted, and warms the token cache for the first session.
            runCatching {
                prefsRepo.getDeepgramAccessToken()
            }.onSuccess {
                _gate.value = GateState.Ready
            }.onFailure { t ->
                _gate.value = GateState.Error(t.message ?: "Could not connect to server")
            }
        }
    }

    fun retryGate() {
        viewModelScope.launch { bootstrapGate() }
    }

    fun setSystemMessage(message: String) {
        _systemMessage.value = message
    }

    fun toggleSpeaker() = setSpeaker(!ui.value.speakerOn)

    fun setSpeaker(enabled: Boolean) {
        _ui.update { it.copy(speakerOn = enabled) }
        applyRouting()
        (ServiceLocator.tts as? SupportsSpeakerphone)?.setSpeakerphoneEnabled(enabled)
    }

    fun onTtsAudioLevel(level: Float) {
        _ttsLevel.value = level
    }

    /** Compute a 0..1 energy from a mic PCM frame and update [micLevel]. */
    private fun updateMicLevel(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        var sumSq = 0.0
        for (s in pcm) {
            val v = s.toDouble()
            sumSq += v * v
        }
        val rms = sqrt(sumSq / pcm.size) / 32768.0
        // Boost so normal speech lands in the upper range, then clamp.
        val scaled = (rms * 6.0).coerceIn(0.0, 1.0).toFloat()
        // Fast attack, gentle release so the orbs keep some energy between words.
        _micLevel.value = max(scaled, _micLevel.value * 0.82f)
    }

    fun toggleVisualizer() {
        _showVisualizer.value = !_showVisualizer.value
    }

    private fun applyRouting() {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val speakerOn = ui.value.speakerOn
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val preferredTypes = if (speakerOn) {
                    listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                } else {
                    listOf(
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    )
                }
                val devices = am.availableCommunicationDevices
                for (type in preferredTypes) {
                    val dev = devices.firstOrNull { it.type == type }
                    if (dev != null) {
                        am.setCommunicationDevice(dev)
                        Log.i(TAG, "Routed to type $type (speakerOn=$speakerOn)")
                        return
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                if (speakerOn) {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                    am.isSpeakerphoneOn = true
                    Log.i(TAG, "Routed to speaker (pre-31, speakerOn=$speakerOn)")
                } else {
                    val allDevices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    if (allDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) {
                        am.startBluetoothSco()
                        am.isBluetoothScoOn = true
                        am.isSpeakerphoneOn = false
                        Log.i(TAG, "Routed to Bluetooth SCO (pre-31, speakerOn=$speakerOn)")
                    } else {
                        am.isSpeakerphoneOn = false
                        Log.i(TAG, "Routed to wired headset or earpiece (pre-31, speakerOn=$speakerOn)")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "applyRouting failed (speakerOn=$speakerOn)")
        }
    }

    /**
     * Send user text to Claude and collect the streaming response.
     * Passes full conversation history so Claude has context.
     */
    private fun sendToLLM(text: String) {
        // Cancel any in-flight LLM response
        llmJob?.cancel()

        llmJob = viewModelScope.launch {
            _ui.update { it.copy(isThinking = true) }

            val history = ui.value.messages
            val sysMsg = _systemMessage.value

            Log.i(TAG, "Claude ⇢ sending: ${text.take(80)} (${history.size} history msgs)")

            latency.markRequestSent()

            llm.sendUserText(
                text = text,
                history = history,
                systemMessage = sysMsg
            ).collect { ev ->
                when (ev) {
                    is LlmClient.Event.TextDelta -> {
                        val delta = ev.text
                        if (delta.isNotEmpty()) {
                            latency.markFirstToken()
                            _ui.update { st ->
                                st.copy(
                                    isThinking = true,
                                    assistantLive = (st.assistantLive ?: "") + delta
                                )
                            }
                            runCatching { ServiceLocator.tts.streamDelta(delta) }
                                .onFailure { Log.w(TAG, "TTS streamDelta failed") }
                        }
                    }
                    is LlmClient.Event.TextCompleted -> {
                        val finalText =
                            ev.text.ifBlank { ui.value.assistantLive.orEmpty() }.trim()
                        Log.i(TAG, "Claude: completed → ${finalText.take(80)}")

                        if (finalText.isNotEmpty()) {
                            ui.value.sessionId?.let { ServiceLocator.repo.addAssistantText(it, finalText) }
                            _ui.update { st ->
                                st.copy(
                                    isThinking = false,
                                    assistantLive = null,
                                    messages = st.messages + ("assistant" to finalText)
                                )
                            }
                        } else {
                            _ui.update { it.copy(isThinking = false, assistantLive = null) }
                        }

                        runCatching { ServiceLocator.tts.flush() }
                            .onFailure { Log.w(TAG, "TTS flush failed") }
                    }
                    is LlmClient.Event.Error -> {
                        Log.w(TAG, "Claude error: ${ev.t.message}")
                        _ui.update { it.copy(isThinking = false, error = ev.t.message) }
                        runCatching { ServiceLocator.tts.flush() }
                    }
                }
            }
        }
    }

    fun startSession() {
        if (ui.value.sessionActive) return

        _showVisualizer.value = false

        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                applyRouting()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                applyRouting()
            }
        }
        am.registerAudioDeviceCallback(audioDeviceCallback, null)

        applyRouting()

        viewModelScope.launch {
            val sid = ServiceLocator.repo.newSession("Voice Chat")
            _ui.update { it.copy(sessionId = sid, sessionActive = true, error = null) }

            // ---- Deepgram Flux STT: partial + final → send to Claude ----
            sttJob?.cancel()
            sttJob = viewModelScope.launch {
                Log.i(TAG, "Flux: connecting…")
                flux.connect().collect { e ->
                    barge.onFluxEvent(e)
                    when (e) {
                        is FluxClient.Event.Partial -> {
                            if (e.isFinal) {
                                val text = e.text.trim()
                                val lastUser = ui.value.messages.lastOrNull { it.first == "user" }?.second.orEmpty()

                                // The turn that barged in over TTS often transcribes as
                                // garbage (AEC eats the first syllables). If that turn's
                                // final is short, the user most likely just wanted to cut
                                // Cliff off — drop it and listen instead of replying.
                                val fromBargeTurn = barge.isBargeTurn()
                                val strippedLen = text.count { it.isLetterOrDigit() }
                                if (fromBargeTurn && strippedLen < 8) {
                                    Log.i(TAG, "Dropped short post-barge-in final: $text")
                                    _ui.update { it.copy(livePartial = null) }
                                } else if (text.isNotEmpty() && text != lastUser && !areSimilar(text, lastUser)) {
                                    _ui.value.sessionId?.let { ServiceLocator.repo.addUserText(it, text) }
                                    _ui.update { st ->
                                        st.copy(
                                            livePartial = null,
                                            messages = st.messages + ("user" to text)
                                        )
                                    }
                                    Log.i(TAG, "LLM ⇢ sending user text: $text")
                                    sendToLLM(text)
                                } else {
                                    _ui.update { it.copy(livePartial = null) }
                                    if (areSimilar(text, lastUser)) {
                                        Log.d(TAG, "Skipped duplicate/similar user text: $text (similar to $lastUser)")
                                    }
                                }
                            } else {
                                _ui.update { it.copy(livePartial = e.text) }
                            }
                        }
                        else -> Unit
                    }
                }
            }

            // ---- Start mic AFTER collectors are live ----
            if (!micStarted) {
                runCatching {
                    audio.start { pcm ->
                        flux.sendPcm(pcm)
                        updateMicLevel(pcm)
                    }
                }.onSuccess {
                    micStarted = true
                    Log.i(TAG, "Mic started → streaming PCM to Flux")
                }.onFailure {
                    Log.e(TAG, "Failed to start mic")
                    _ui.update { s -> s.copy(error = it.message) }
                }
            }
        }
    }

    fun stopSession() {
        if (!ui.value.sessionActive) return
        _ui.update {
            it.copy(
                sessionActive = false,
                isThinking = false,
                livePartial = null,
                assistantLive = null
            )
        }
        _ttsLevel.value = 0f
        _micLevel.value = 0f
        latency.reset()
        runCatching { ServiceLocator.tts.stop() }
        runCatching { llm.close() }
        runCatching { flux.close() }
        runCatching { audio.stop() }
        micStarted = false
        sttJob?.cancel(); sttJob = null
        llmJob?.cancel(); llmJob = null
        am.mode = AudioManager.MODE_NORMAL
        audioDeviceCallback?.let { am.unregisterAudioDeviceCallback(it) }
        audioDeviceCallback = null
        Log.i(TAG, "Session stopped")
    }
}
