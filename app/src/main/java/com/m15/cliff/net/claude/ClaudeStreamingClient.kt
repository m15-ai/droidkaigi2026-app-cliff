package com.m15.cliff.net.claude

import android.util.Log
import com.m15.cliff.net.LlmClient
import com.m15.cliff.prefs.PrefsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.util.concurrent.atomic.AtomicBoolean

class ClaudeStreamingClient(
    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(java.time.Duration.ofMinutes(2))
        .build(),
    //private val model: String = "claude-sonnet-4-6",
    private val model: String = "claude-haiku-4-5",
    private val prefsRepo: PrefsRepository,
) : LlmClient {

    private val TAG = "ClaudeStreaming"
    private val _events = MutableSharedFlow<LlmClient.Event>(extraBufferCapacity = 128)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var currentJob: Job? = null
    private val active = AtomicBoolean(false)

    override fun sendUserText(
        text: String,
        history: List<Pair<String, String>>,
        systemMessage: String
    ): Flow<LlmClient.Event> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return _events.asSharedFlow()

        // Cancel any in-flight request
        currentJob?.cancel()

        currentJob = scope.launch {
            active.set(true)
            try {
                val apiKey = fetchClaudeApiKey()
                streamRequest(apiKey, trimmed, history, systemMessage)
            } catch (t: Throwable) {
                if (active.get()) {
                    Log.e(TAG, "Claude request failed", t)
                    _events.tryEmit(LlmClient.Event.Error(t))
                }
            } finally {
                active.set(false)
            }
        }

        return _events.asSharedFlow()
    }

    override fun cancelResponse() {
        if (!active.get()) return
        Log.i(TAG, "Cancelling in-flight response")
        active.set(false)
        currentJob?.cancel()
        currentJob = null
    }

    override fun close() {
        cancelResponse()
    }

    // -----------------------
    // Internal
    // -----------------------

    private suspend fun streamRequest(
        apiKey: String,
        userText: String,
        history: List<Pair<String, String>>,
        systemMessage: String
    ) = withContext(Dispatchers.IO) {

        // Build messages array: history + new user message
        val messagesArray = JSONArray()
        for ((role, content) in history) {
            messagesArray.put(JSONObject().put("role", role).put("content", content))
        }
        messagesArray.put(JSONObject().put("role", "user").put("content", userText))

        val payload = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("stream", true)
            .put("system", systemMessage)
            .put("messages", messagesArray)

        val req = Request.Builder()
            .url(API_URL)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .build()

        Log.i(TAG, "Claude request → ${userText.take(80)} (${history.size} history msgs)")

        val response = okHttp.newCall(req).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string().orEmpty()
            Log.e(TAG, "Claude HTTP ${response.code}: ${errorBody.take(300)}")
            throw RuntimeException("Claude API error ${response.code}: ${errorBody.take(200)}")
        }

        val reader = response.body?.charStream()?.buffered()
            ?: throw RuntimeException("Empty response body")

        try {
            parseSSEStream(reader)
        } finally {
            reader.close()
            response.close()
        }
    }

    private fun parseSSEStream(reader: BufferedReader) {
        val accumulated = StringBuilder()
        var eventType = ""

        var line = reader.readLine()
        while (line != null) {
            if (!active.get()) return

            when {
                line.startsWith("event: ") -> {
                    eventType = line.removePrefix("event: ").trim()
                }
                line.startsWith("data: ") -> {
                    val data = line.removePrefix("data: ").trim()
                    handleSSEData(eventType, data, accumulated)
                }
                line.isBlank() -> {
                    eventType = ""
                }
            }

            line = reader.readLine()
        }

        // If we accumulated text but never got a message_stop, emit completed anyway
        if (accumulated.isNotEmpty()) {
            _events.tryEmit(LlmClient.Event.TextCompleted(accumulated.toString()))
        }
    }

    private fun handleSSEData(eventType: String, data: String, accumulated: StringBuilder) {
        try {
            when (eventType) {
                "content_block_delta" -> {
                    val obj = JSONObject(data)
                    val delta = obj.optJSONObject("delta")
                    if (delta != null && delta.optString("type") == "text_delta") {
                        val text = delta.getString("text")
                        if (text.isNotEmpty()) {
                            accumulated.append(text)
                            _events.tryEmit(LlmClient.Event.TextDelta(text))
                        }
                    }
                }
                "message_stop" -> {
                    val finalText = accumulated.toString().trim()
                    accumulated.clear()
                    if (finalText.isNotEmpty()) {
                        _events.tryEmit(LlmClient.Event.TextCompleted(finalText))
                    }
                }
                "error" -> {
                    val obj = JSONObject(data)
                    val errorMsg = obj.optJSONObject("error")?.optString("message")
                        ?: "Unknown Claude error"
                    Log.e(TAG, "Claude SSE error: $errorMsg")
                    _events.tryEmit(LlmClient.Event.Error(RuntimeException(errorMsg)))
                }
                // message_start, content_block_start, content_block_stop, message_delta, ping — ignore
                else -> {
                    Log.v(TAG, "SSE event: $eventType")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "SSE parse error for event=$eventType: ${data.take(200)}", t)
        }
    }

    private suspend fun fetchClaudeApiKey(): String {
        return prefsRepo.getClaudeApiKey()
    }

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
    }
}
