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

class PrefsApiClient(
    private val okHttp: OkHttpClient,
    private val baseUrl: String,      // your backend URL
    private val secretPath: String,   // your backend secret path
    private val appKey: String,       // your backend app key (must match server)
) {
    data class PrefsDto(
        val mood: String,
        val personality: String,
        val customPrompt: String,
        val deviceKey: String,
        val updatedAt: Long,
    )

    class HttpException(
        val code: Int,
        val body: String,
        message: String
    ) : RuntimeException(message)

    private fun apiUrl(path: String): String =
        baseUrl.trimEnd('/') + "/api/" + secretPath + path

    suspend fun deviceLogin(deviceKey: String): String {
        val body = JSONObject()
            .put("deviceKey", deviceKey)
            .toString()

        val req = Request.Builder()
            .url(apiUrl("/device-login"))
            .post(body.toRequestBody(JSON.toMediaType()))
            .header("Content-Type", JSON)
            .header("X-Cliff-App-Key", appKey)
            .build()

        val text = executeOrThrow(req)
        val obj = JSONObject(text)
        return obj.getString("token")
    }

    suspend fun getPrefs(bearerToken: String): PrefsDto {
        val req = Request.Builder()
            .url(apiUrl("/prefs"))
            .get()
            .header("Authorization", "Bearer $bearerToken")
            .build()

        val text = executeOrThrow(req)
        return parsePrefs(text)
    }

    suspend fun putPrefs(
        bearerToken: String,
        mood: String,
        personality: String,
        customPrompt: String,
    ): PrefsDto {
        val body = JSONObject()
            .put("mood", mood)
            .put("personality", personality)
            .put("customPrompt", customPrompt)
            .toString()

        val req = Request.Builder()
            .url(apiUrl("/prefs"))
            .put(body.toRequestBody(JSON.toMediaType()))
            .header("Content-Type", JSON)
            .header("Authorization", "Bearer $bearerToken")
            .build()

        val text = executeOrThrow(req)
        return parsePrefs(text)
    }

    private fun parsePrefs(json: String): PrefsDto {
        val o = JSONObject(json)
        return PrefsDto(
            mood = o.getString("mood"),
            personality = o.getString("personality"),
            customPrompt = o.optString("customPrompt", ""),
            deviceKey = o.getString("deviceKey"),
            updatedAt = o.getLong("updatedAt"),
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

    // -------------------------
    // Claude API key
    // -------------------------

    data class ClaudeApiKeyDto(
        val apiKey: String,
        val expiresIn: Int?
    )

    suspend fun claudeApiKey(bearerToken: String): ClaudeApiKeyDto {
        val req = Request.Builder()
            .url(apiUrl("/claude/api-key"))
            .post("{}".toRequestBody(JSON.toMediaType()))
            .header("Content-Type", JSON)
            .header("Authorization", "Bearer $bearerToken")
            .header("X-Cliff-App-Key", appKey)
            .build()

        val text = executeOrThrow(req)
        val o = JSONObject(text)

        return ClaudeApiKeyDto(
            apiKey = o.getString("api_key"),
            expiresIn = if (o.has("expires_in")) o.optInt("expires_in") else null
        )
    }

    // -------------------------
    // Deepgram token mint
    // -------------------------

    data class DeepgramTokenDto(val accessToken: String, val expiresIn: Int?)

    suspend fun deepgramToken(bearerToken: String, ttlSeconds: Int = 600): DeepgramTokenDto {
        val bodyJson = """{"ttlSeconds":$ttlSeconds}"""
        val req = Request.Builder()
            .url(apiUrl("/deepgram/token"))
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHeader("Authorization", "Bearer $bearerToken")
            .addHeader("X-Cliff-App-Key", appKey)
            .build()

        val raw = executeOrThrow(req)
        val o = JSONObject(raw)
        return DeepgramTokenDto(
            accessToken = o.getString("access_token"),
            expiresIn = if (o.has("expires_in")) o.optInt("expires_in") else null
        )
    }

    private companion object {
        private const val JSON = "application/json"
    }
}
