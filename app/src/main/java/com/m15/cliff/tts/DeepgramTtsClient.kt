package com.m15.cliff.tts

import android.content.Context
import android.media.*
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.coroutines.coroutineContext

class DeepgramTtsClient(
    private val context: Context,
    val okHttp: OkHttpClient,

    // NEW: token provider (minted from sam_server.py, cached in PrefsRepository)
    private val deepgramTokenProvider: suspend () -> String,

    // Aura-2 voice. Naming is aura-2-<voice>-<lang>. Japanese voices:
    //   male   → fujin (confident, professional), ebisu (deep, sincere)
    //   female → uzume, izanami, ama
    // The voice is fixed for the life of the WebSocket, which is why the pipeline
    // is pinned to Japanese end-to-end (see LlmClient.LANGUAGE_DIRECTIVE and the
    // Flux languageHint).
    private val model: String = "aura-2-fujin-ja",
    private val sampleRate: Int = 48_000,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    var onAudioLevel: ((Float) -> Unit)? = null
) : TtsClient, com.m15.cliff.SupportsSpeakerphone {

    private val TAG = "DeepgramTts"
    private val wsUrl =
        "wss://api.deepgram.com/v1/speak?model=$model&encoding=linear16&sample_rate=$sampleRate&container=none"

    private var ws: WebSocket? = null
    private val readyToSpeak = AtomicBoolean(false)
    private val isSpeaking = AtomicBoolean(false)
    private val outbox = Channel<Outgoing>(capacity = Channel.BUFFERED)
    private val connecting = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private var opened: CompletableDeferred<Unit>? = null
    private val connectMutex = Mutex()

    // Audio
    @Volatile private var audioTrack: AudioTrack? = null

    // Audio focus members
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private var focusGranted = false
    private var focusRequest: AudioFocusRequest? = null

    @Volatile private var squelched = false
    @Volatile private var acceptPcm = true
    @Volatile private var lastBargeAt = 0L
    @Volatile private var speakerphoneEnabled: Boolean = false
    @Volatile private var lastLevelAt = 0L

    private val sendDispatcher = Dispatchers.IO.limitedParallelism(1)

    init {
        scope.launch { senderLoop() }
    }

    private suspend fun senderLoop() {
        var lastSentAt = 0L
        val minGapMs = 0
        while (coroutineContext.isActive) {
            val msg = outbox.receive()
            try {
                ensureConnected()
                opened?.await()
                while (!readyToSpeak.get()) delay(2)

                when (msg) {
                    is Outgoing.Speak -> {
                        val now = System.currentTimeMillis()
                        if (now - lastSentAt < minGapMs) delay(minGapMs - (now - lastSentAt))
                        lastSentAt = now
                        val payload = """{"type":"Speak","text":${JSONObject.quote(msg.text)}}"""
                        val ok = ws?.send(payload) ?: false
                        if (ok) isSpeaking.set(true)
                    }
                    Outgoing.Flush -> {
                        val ok = ws?.send("""{"type":"Flush"}""") ?: false
                        Log.d(TAG, "TTS → Flush send=$ok")
                    }
                    Outgoing.Clear -> {
                        val ok = ws?.send("""{"type":"Clear"}""") ?: false
                        Log.d(TAG, "TTS → Clear send=$ok")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Sender failed for msg; dropping and continuing: ${t.message}")
                // Drop; next messages will retry connection
            }
        }
    }

    private suspend fun ensureConnected() {
        if (connected.get() || connecting.get()) return

        connectMutex.withLock {
            if (connected.get() || connecting.get()) return@withLock

            connecting.set(true)
            opened = CompletableDeferred()

            requestFocusIfNeeded()
            if (audioTrack == null) audioTrack = buildAudioTrack()

            // NEW: get short-lived Deepgram token
            val dgToken = deepgramTokenProvider()

            val req = Request.Builder()
                .url(wsUrl)
                .addHeader("Authorization", "Bearer $dgToken")
                .build()

            Log.i(TAG, "TTS WS CONNECT → $wsUrl")

            ws = okHttp.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, resp: Response) {
                    Log.i(TAG, "TTS WS OPEN ${resp.code} ${resp.message}")
                    connected.set(true)
                    readyToSpeak.set(true)
                    audioTrack?.play()
                    connecting.set(false)
                    opened?.complete(Unit)
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    if (squelched || !acceptPcm) {
                        Log.d(
                            TAG,
                            String.format(
                                "PCM ← dropped (%d bytes) squelched=%s accept=%s",
                                bytes.size, squelched, acceptPcm
                            )
                        )
                        return
                    }
                    val at = audioTrack ?: return
                    val data = bytes.toByteArray()

                    // Visualizer hook (~30fps)
                    val now = System.currentTimeMillis()
                    if (now - lastLevelAt >= 33) {
                        lastLevelAt = now
                        onAudioLevel?.invoke(rms16leTo01(data))
                    }

                    if (Build.VERSION.SDK_INT >= 23)
                        at.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING)
                    else
                        at.write(data, 0, data.size)
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    Log.d(TAG, "TTS ← $text")
                    if (text.contains("\"type\":\"Flushed\"")) {
                        isSpeaking.set(false)
                    }
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "TTS WS closing $code $reason")
                    connected.set(false)
                    readyToSpeak.set(false)
                    connecting.set(false)
                }

                override fun onFailure(ws: WebSocket, t: Throwable, resp: Response?) {
                    Log.e(TAG, "TTS WS failure code=${resp?.code} msg=${t.message}")
                    connected.set(false)
                    readyToSpeak.set(false)
                    connecting.set(false)
                }
            })
        }
    }

    private fun rms16leTo01(data: ByteArray): Float {
        val n = data.size / 2
        if (n <= 0) return 0f

        var sum = 0.0
        var i = 0
        while (i + 1 < data.size) {
            val lo = data[i].toInt() and 0xFF
            val hi = data[i + 1].toInt()
            val s = (hi shl 8) or lo
            val v = (s / 32768.0)
            sum += v * v
            i += 2
        }
        val rms = kotlin.math.sqrt(sum / n).toFloat()
        return (rms * 5.0f).coerceIn(0f, 1f)
    }

    private fun applyRouting() {
        try { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION } catch (_: Throwable) { }
    }

    private fun resumeIfSquelched() {
        if (squelched) {
            Log.d(TAG, "TTS(resume) after barge-in")
            squelched = false
            acceptPcm = true
            if (audioTrack == null) audioTrack = buildAudioTrack()
            try { audioTrack?.play() } catch (_: Throwable) {}
        }
    }

    override fun speak(text: String) {
        resumeIfSquelched()
        scope.launch(sendDispatcher) {
            outbox.send(Outgoing.Clear)
            outbox.send(Outgoing.Speak(text))
            outbox.send(Outgoing.Flush)
        }
    }

    override fun streamDelta(delta: String) {
        resumeIfSquelched()
        scope.launch(sendDispatcher) {
            outbox.send(Outgoing.Speak(delta))
        }
    }

    override fun flush() {
        Log.d(TAG, "TTS(Flush) queued")
        scope.launch(sendDispatcher) { outbox.send(Outgoing.Flush) }
    }

    override fun stop() {
        Log.d(TAG, "TTS(BargeIn) → squelch + Clear")
        lastBargeAt = System.currentTimeMillis()
        squelched = true
        acceptPcm = false
        outbox.trySend(Outgoing.Clear)

        audioTrack?.let {
            try {
                it.pause()
                it.flush()
            } catch (_: Throwable) {}
        }
        isSpeaking.set(false)
        onAudioLevel?.invoke(0f)
    }

    override fun close() {
        try { ws?.send("""{"type":"Close"}""") } catch (_: Throwable) {}
        try { ws?.close(1000, "bye") } catch (_: Throwable) {}
        ws = null

        audioTrack?.let {
            try { it.pause(); it.flush(); it.stop(); it.release() } catch (_: Throwable) {}
        }
        audioTrack = null

        connected.set(false)
        readyToSpeak.set(false)
        connecting.set(false)
        abandonFocusIfNeeded()
    }

    override fun isSpeaking(): Boolean = isSpeaking.get()

    private fun buildAudioTrack(): AudioTrack {
        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        val targetBuf = max(minBuf, sampleRate / 5 * 2)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(targetBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun requestFocusIfNeeded() {
        if (focusGranted) return
        focusGranted = try {
            if (Build.VERSION.SDK_INT >= 26) {
                val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setOnAudioFocusChangeListener { }
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                focusRequest = r
                audioManager.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (_: Throwable) { false }

        if (focusGranted) applyRouting()
    }

    override fun setSpeakerphoneEnabled(enabled: Boolean) {
        speakerphoneEnabled = enabled
        applyRouting()
    }

    private fun abandonFocusIfNeeded() {
        if (!focusGranted) return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION") audioManager.abandonAudioFocus(null)
            }
        } catch (_: Throwable) {}
        focusGranted = false
        focusRequest = null
    }
}

/** New TtsClient interface used by the app. */
interface TtsClient {
    fun speak(text: String)
    fun streamDelta(delta: String)
    fun flush()
    fun stop()
    fun close() {}
    fun isSpeaking(): Boolean
}

private sealed interface Outgoing {
    data class Speak(val text: String) : Outgoing
    data object Flush : Outgoing
    data object Clear : Outgoing
}
