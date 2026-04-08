package com.m15.cliff

import android.util.Log
import com.m15.cliff.net.flux.FluxClient
import com.m15.cliff.net.LlmClient
import com.m15.cliff.tts.TtsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class BargeInController(
    private val llm: LlmClient,
    private val tts: TtsClient
) {
    private val userSpeaking = AtomicBoolean(false)
    private var pendingJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private const val TAG = "BargeIn"
    }

    fun onFluxEvent(e: FluxClient.Event) {
        when (e) {
            is FluxClient.Event.UserStart -> {
                Log.i(TAG, "UserStart received (wasSpeaking=${userSpeaking.get()}, ttsSpeaking=${tts.isSpeaking()})")

                if (userSpeaking.getAndSet(true)) return

                pendingJob?.cancel()
                pendingJob = scope.launch {
                    delay(150)
                    val speakingNow = userSpeaking.get()
                    val ttsNow = tts.isSpeaking()
                    Log.i(TAG, "BARGE-IN check after delay → userSpeaking=$speakingNow, ttsSpeaking=$ttsNow")
                    if (speakingNow) {
                        Log.i(TAG, "BARGE-IN TRIGGER → cancelResponse + tts.stop()")
                        llm.cancelResponse()
                        tts.stop()
                    }
                }
            }

            is FluxClient.Event.UserStop -> {
                Log.i(TAG, "UserStop received")
                userSpeaking.set(false)
                pendingJob?.cancel()
                pendingJob = null
            }

            else -> Unit
        }
    }
}
