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

    // Set when a barge-in fires during TTS. The final transcript of that same
    // interrupting turn is unreliable — AEC chews the first syllables while TTS
    // ramps down ("stop cliff" → タップ。タップ。) — so the ViewModel consumes this
    // to decide whether a short final should be dropped instead of sent to the LLM.
    private val bargeTurnPending = AtomicBoolean(false)

    /** True (once) if the current turn's final transcript follows a barge-in during TTS. */
    fun consumeBargeTurnPending(): Boolean = bargeTurnPending.getAndSet(false)

    companion object {
        private const val TAG = "BargeIn"
    }

    fun onFluxEvent(e: FluxClient.Event) {
        when (e) {
            is FluxClient.Event.UserStart -> {
                Log.i(TAG, "UserStart received (wasSpeaking=${userSpeaking.get()}, ttsSpeaking=${tts.isSpeaking()})")

                if (userSpeaking.getAndSet(true)) return

                // A new turn is starting; any unconsumed flag from a prior turn is stale.
                bargeTurnPending.set(false)

                pendingJob?.cancel()
                if (tts.isSpeaking()) {
                    // While TTS is playing, a turn start is almost certainly a real
                    // interruption — fire immediately, no debounce.
                    Log.i(TAG, "BARGE-IN TRIGGER (immediate, TTS active) → cancelResponse + tts.stop()")
                    llm.cancelResponse()
                    tts.stop()
                    bargeTurnPending.set(true)
                } else {
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
