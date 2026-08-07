package com.m15.cliff.prefs

/**
 * Mints and caches the two short-lived vendor credentials the app needs: a Deepgram
 * access token (STT + TTS) and a Claude API key. Both are fetched from the backend with
 * the app key alone and cached in memory until shortly before they expire.
 *
 * There is no device identity or bearer token here anymore — the backend contract is
 * stateless, so a failed mint is simply retried on the next call.
 */
class PrefsRepository(
    private val api: PrefsApiClient
) {
    @Volatile private var dgToken: String? = null
    @Volatile private var dgTokenExpiryMs: Long = 0L

    @Volatile private var claudeApiKey: String? = null
    @Volatile private var claudeKeyExpiryMs: Long = 0L

    suspend fun getDeepgramAccessToken(ttlSeconds: Int = 600): String {
        val now = System.currentTimeMillis()
        val cached = dgToken
        if (!cached.isNullOrBlank() && now < (dgTokenExpiryMs - 30_000)) {
            return cached
        }

        val dto = api.deepgramToken(ttlSeconds = ttlSeconds)
        val expiresIn = (dto.expiresIn ?: ttlSeconds).coerceAtLeast(1)
        dgToken = dto.accessToken
        dgTokenExpiryMs = now + expiresIn * 1000L
        return dto.accessToken
    }

    suspend fun getClaudeApiKey(): String {
        val now = System.currentTimeMillis()
        val cached = claudeApiKey
        if (!cached.isNullOrBlank() && now < (claudeKeyExpiryMs - 30_000)) {
            return cached
        }

        val dto = api.claudeApiKey()
        val expiresIn = (dto.expiresIn ?: 600).coerceAtLeast(1)
        claudeApiKey = dto.apiKey
        claudeKeyExpiryMs = now + expiresIn * 1000L
        return dto.apiKey
    }
}