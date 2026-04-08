package com.m15.cliff.net.flux

import android.util.Log
import com.m15.cliff.prefs.PrefsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.ArrayDeque

class FluxClientImpl(
    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(java.time.Duration.ofSeconds(15))
        .build(),
    private val useMocks: Boolean = false,

    // Flux model id
    private val model: String = "flux-general-en",

    // Used to mint Deepgram short-lived tokens via sam_server.py
    private val prefsRepo: PrefsRepository? = null,
    private val deviceKey: String? = null,

    // How long you want DG tokens to last (server may cap). 10 min is fine.
    private val deepgramTtlSeconds: Int = 600,
) : FluxClient {

    @kotlinx.serialization.Serializable data class DGAlt(val transcript: String = "")
    @kotlinx.serialization.Serializable data class DGChannel(val alternatives: List<DGAlt> = emptyList())
    @kotlinx.serialization.Serializable data class DGResults(
        val channels: List<DGChannel> = emptyList(),
        val is_final: Boolean? = null
    )
    @kotlinx.serialization.Serializable data class DGMessage(
        val type: String? = null,
        val event: String? = null,
        val results: DGResults? = null,
        val transcript: String? = null,
        val speech_started: Boolean? = null,
        val speech_final: Boolean? = null
    )

    private val url: String =
        "wss://api.deepgram.com/v2/listen" +
                "?model=$model" +
                "&encoding=linear16" +
                "&sample_rate=16000" +
                "&eot_threshold=0.85" +
                "&eager_eot_threshold=0.85" +
                "&eot_timeout_ms=8000"

    private val _events = MutableSharedFlow<FluxClient.Event>(extraBufferCapacity = 128)

    private var ws: WebSocket? = null
    private var scope: CoroutineScope? = null

    @Volatile private var wsReady = false
    private var lastLogAt = System.currentTimeMillis()

    // Buffer a little audio until WS is open (avoids losing first syllable)
    private val pendingAudio: ArrayDeque<ByteString> = ArrayDeque()
    private val pendingMaxFrames = 50 // ~1 sec depending on your mic chunk size

    private val TAG = "FluxClientImpl"

    override fun connect(): Flow<FluxClient.Event> {
        if (useMocks) {
            scope?.cancel()
            scope = CoroutineScope(Dispatchers.Default)
            scope!!.launch {
                while (isActive) {
                    delay(2500)
                    _events.emit(FluxClient.Event.UserStart(System.currentTimeMillis()))
                    delay(400)
                    _events.emit(FluxClient.Event.Partial("hello", true))
                    _events.emit(FluxClient.Event.UserStop(System.currentTimeMillis()))
                }
            }
            return _events.asSharedFlow()
        }

        wsReady = false
        ws?.close(1000, null)
        ws = null

        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope!!.launch {
            try {
                val dgBearer = mintDeepgramBearerOrNull()
                if (dgBearer.isNullOrBlank()) {
                    throw IllegalStateException("No Deepgram auth available (token mint failed or prefsRepo/deviceKey not set).")
                }

                val req = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $dgBearer")
                    .addHeader("Accept", "application/json")
                    .build()

                ws = okHttp.newWebSocket(req, listener)
            } catch (t: Throwable) {
                Log.e(TAG, "Flux connect failed", t)
                _events.tryEmit(FluxClient.Event.Error(t))
            }
        }

        return _events.asSharedFlow()
    }

    private suspend fun mintDeepgramBearerOrNull(): String? {
        val repo = prefsRepo ?: return null
        val dk = deviceKey ?: return null
        return try {
            repo.getDeepgramAccessToken(dk, deepgramTtlSeconds)
        } catch (t: Throwable) {
            Log.w(TAG, "Deepgram token mint failed: ${t.message}")
            null
        }
    }

    private val listener = object : WebSocketListener() {

        override fun onOpen(ws: WebSocket, resp: Response) {
            wsReady = true
            Log.i(TAG, "Flux WS opened ${resp.code}")

            while (pendingAudio.isNotEmpty()) {
                ws.send(pendingAudio.removeFirst())
            }
        }

        private var msgCount = 0
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        private var currentTurnText = StringBuilder()

        override fun onMessage(ws: WebSocket, text: String) {
            if (msgCount++ < 5) Log.d("Flux ← %s", text.take(100))
            try {
                val m = json.decodeFromString(DGMessage.serializer(), text)

                if (m.type == "TurnInfo") {
                    when (m.event) {
                        "StartOfTurn" -> {
                            currentTurnText.clear()
                            _events.tryEmit(FluxClient.Event.UserStart(System.currentTimeMillis()))
                        }
                        "Update" -> {
                            m.transcript?.takeIf { it.isNotBlank() }?.let {
                                currentTurnText.clear()
                                currentTurnText.append(it)
                                _events.tryEmit(FluxClient.Event.Partial(it, false))
                            }
                        }
                        "EagerEndOfTurn", "EndOfTurn", "Stopped" -> {
                            val finalText = (m.transcript?.takeIf { it.isNotBlank() }
                                ?: currentTurnText.toString()).trim()

                            _events.tryEmit(FluxClient.Event.UserStop(System.currentTimeMillis()))
                            if (finalText.isNotEmpty()) {
                                _events.tryEmit(FluxClient.Event.Partial(finalText, true))
                            }
                            currentTurnText.clear()
                        }
                    }
                }

                if (m.type == "Results" && m.results != null) {
                    val alt = m.results.channels.firstOrNull()
                        ?.alternatives?.firstOrNull()?.transcript.orEmpty()
                    if (alt.isNotBlank()) {
                        val fin = m.results.is_final == true
                        if (fin) currentTurnText.clear()
                        _events.tryEmit(FluxClient.Event.Partial(alt, fin))
                    }
                }

                if (m.speech_started == true) _events.tryEmit(FluxClient.Event.UserStart(System.currentTimeMillis()))
                if (m.speech_final == true) _events.tryEmit(FluxClient.Event.UserStop(System.currentTimeMillis()))

            } catch (_: Throwable) {
                Log.w(TAG, "DG parse fail: ${text.take(300)}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            wsReady = false
            _events.tryEmit(FluxClient.Event.Error(t))
            Log.e(TAG, "Flux WS failure code=${response?.code}", t)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            wsReady = false
            webSocket.close(1000, null)
            Log.w(TAG, "Flux WS closing code=$code reason=$reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            wsReady = false
            Log.w(TAG, "Flux WS closed code=$code reason=$reason")
        }
    }

    override fun sendPcm(pcm: ShortArray) {
        val arr = ByteArray(pcm.size * 2)
        var j = 0
        for (s in pcm) {
            arr[j++] = (s.toInt() and 0xFF).toByte()
            arr[j++] = ((s.toInt() shr 8) and 0xFF).toByte()
        }

        val frame = arr.toByteString(0, arr.size)
        val socket = ws

        if (!wsReady || socket == null) {
            pendingAudio.addLast(frame)
            while (pendingAudio.size > pendingMaxFrames) pendingAudio.removeFirst()
            return
        }

        socket.send(frame)
        val now = System.currentTimeMillis()
        if (now - lastLogAt >= 1000) {
            lastLogAt = now
        }
    }

    override fun close() {
        wsReady = false
        scope?.cancel()
        ws?.close(1000, null)
        ws = null
        pendingAudio.clear()
    }
}


