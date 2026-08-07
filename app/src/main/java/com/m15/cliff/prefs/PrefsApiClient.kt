package com.m15.cliff.prefs

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Minimal HTTP client for the Cliff backend. The contract is just two token-minting
 * endpoints, each authenticated with the app key alone (X-Cliff-App-Key) — no bearer
 * token, no device identity, no server-side state.
 */
class PrefsApiClient(
    private val okHttp: OkHttpClient,
    private val baseUrl: String,      // your backend URL
    private val secretPath: String,   // your backend secret path
    private val appKey: String,       // your backend app key (must match server)
) {
    class HttpException(
        val code: Int,
        val body: String,
        message: String
    ) : RuntimeException(message)

    data class ClaudeApiKeyDto(val apiKey: String, val expiresIn: Int?)
    data class DeepgramTokenDto(val accessToken: String, val expiresIn: Int?)

    private fun apiUrl(path: String): String =
        baseUrl.trimEnd('/') + "/api/" + secretPath + path

    // -------------------------
    // Claude API key
    // -------------------------
    suspend fun claudeApiKey(): ClaudeApiKeyDto {
        val req = Request.Builder()
            .url(apiUrl("/claude/api-key"))
            .post("{}".toRequestBody(JSON.toMediaType()))
            .header("Content-Type", JSON)
            .header("X-Cliff-App-Key", appKey)
            .build()

        val o = JSONObject(executeOrThrow(req))
        return ClaudeApiKeyDto(
            apiKey = o.getString("api_key"),
            expiresIn = if (o.has("expires_in")) o.optInt("expires_in") else null
        )
    }

    // -------------------------
    // Deepgram token mint
    // -------------------------
    suspend fun deepgramToken(ttlSeconds: Int = 600): DeepgramTokenDto {
        val body = """{"ttlSeconds":$ttlSeconds}"""
        val req = Request.Builder()
            .url(apiUrl("/deepgram/token"))
            .post(body.toRequestBody(JSON.toMediaType()))
            .header("Content-Type", JSON)
            .header("X-Cliff-App-Key", appKey)
            .build()

        val o = JSONObject(executeOrThrow(req))
        return DeepgramTokenDto(
            accessToken = o.getString("access_token"),
            expiresIn = if (o.has("expires_in")) o.optInt("expires_in") else null
        )
    }

    private suspend fun executeOrThrow(req: Request): String = withContext(Dispatchers.IO) {
        try {
            okHttp.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                Log.d("PrefsApiClient", "HTTP ${resp.code} ${req.method} ${req.url} bodyLen=${body.length}")

                if (!resp.isSuccessful) {
                    Log.e("PrefsApiClient", "Error body: ${body.take(300)}")
                    throw HttpException(
                        code = resp.code,
                        body = body,
                        message = "HTTP ${resp.code}: ${body.ifBlank { resp.message }}"
                    )
                }
                body
            }
        } catch (io: IOException) {
            Log.w("PrefsApiClient", "Network error ${req.method} ${req.url}", io)
            throw io
        }
    }

    private companion object {
        private const val JSON = "application/json"
    }
}