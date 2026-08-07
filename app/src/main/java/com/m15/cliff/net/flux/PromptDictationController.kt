package com.m15.cliff.net.flux

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.m15.cliff.audio.DefaultAudioCapture
import com.m15.cliff.net.flux.FluxClient
import com.m15.cliff.net.flux.FluxClientImpl
import com.m15.cliff.prefs.PrefsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class PromptDictationController(
    private val appContext: Context,
    private val prefsRepo: PrefsRepository,
    okHttp: OkHttpClient = OkHttpClient()
) {
    private val tag = "PromptDictation"

    // IMPORTANT: Flux must be constructed with prefsRepo so it can mint Deepgram tokens.
    private val flux: FluxClient = FluxClientImpl(
        okHttp = okHttp,
        useMocks = false,
        prefsRepo = prefsRepo
    )

    private val audio = DefaultAudioCapture(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectJob: Job? = null
    @Volatile private var started = false

    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (started) return
        started = true

        collectJob = scope.launch {
            try {
                // Start collecting STT events first
                val eventsJob = launch {
                    flux.connect().collect { e ->
                        when (e) {
                            is FluxClient.Event.Partial -> {
                                val txt = e.text.trim()
                                if (txt.isEmpty()) return@collect
                                if (e.isFinal) onFinal(txt) else onPartial(txt)
                            }

                            // FluxClient.Event.Error exposes 't'
                            is FluxClient.Event.Error -> onError(e.t)

                            else -> Unit
                        }
                    }
                }

                // ✅ Explicit permission check to satisfy lint / compile-time checks
                val granted = ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (!granted) {
                    val ex = SecurityException("RECORD_AUDIO permission not granted")
                    Log.w(tag, ex.message ?: "Missing RECORD_AUDIO")
                    onError(ex)
                    return@launch
                }

                // Start mic after collector is live
                try {
                    audio.start { pcm ->
                        // FluxClientImpl should buffer audio until WS is open.
                        flux.sendPcm(pcm)
                    }
                } catch (se: SecurityException) {
                    Log.w(tag, "audio.start SecurityException", se)
                    onError(se)
                    return@launch
                }

                eventsJob.join()
            } catch (t: Throwable) {
                Log.w(tag, "Dictation failed", t)
                onError(t)
            } finally {
                // Ensure hardware/resources get released even if collector throws
                runCatching { audio.stop() }
                runCatching { flux.close() }
                started = false
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false

        runCatching { audio.stop() }
        runCatching { flux.close() }

        collectJob?.cancel()
        collectJob = null
    }

    fun close() {
        stop()
        scope.cancel()
    }
}
