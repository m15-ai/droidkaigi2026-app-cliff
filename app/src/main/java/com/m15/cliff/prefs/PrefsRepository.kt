package com.m15.cliff.prefs

import android.content.Context

class PrefsRepository(
    ctx: Context,
    private val api: PrefsApiClient
) {
    private val sp = ctx.getSharedPreferences("cliff_prefs_auth", Context.MODE_PRIVATE)

    @Volatile private var dgToken: String? = null
    @Volatile private var dgTokenExpiryMs: Long = 0L

    @Volatile private var claudeApiKey: String? = null
    @Volatile private var claudeKeyExpiryMs: Long = 0L

    fun getSavedToken(): String? = sp.getString(KEY_TOKEN, null)

    fun hasSavedToken(): Boolean = !getSavedToken().isNullOrBlank()

    fun clearToken() {
        sp.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_LAST_VALIDATED_MS)
            .apply()

        dgToken = null
        dgTokenExpiryMs = 0L
        claudeApiKey = null
        claudeKeyExpiryMs = 0L
    }

    fun getSavedTokenFastOrNull(): String? = getSavedToken()

    fun shouldValidateNow(): Boolean {
        val last = sp.getLong(KEY_LAST_VALIDATED_MS, 0L)
        val age = System.currentTimeMillis() - last
        return age > VALIDATION_TTL_MS
    }

    suspend fun ensureValidTokenOrNull(deviceKey: String): String? {
        val token = getSavedToken() ?: return null

        return try {
            api.getPrefs(token)
            sp.edit().putLong(KEY_LAST_VALIDATED_MS, System.currentTimeMillis()).apply()
            token
        } catch (t: Throwable) {
            val shouldRevoke = when (t) {
                is PrefsApiClient.HttpException -> (t.code == 401 || t.code == 403)
                else -> false
            }

            if (shouldRevoke) {
                clearToken()
                null
            } else {
                token
            }
        }
    }

    suspend fun deviceLoginAndPersist(deviceKey: String): String {
        val token = api.deviceLogin(deviceKey)
        sp.edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_LAST_VALIDATED_MS, System.currentTimeMillis())
            .apply()
        return token
    }

    suspend fun loadPrefs(token: String): PrefsApiClient.PrefsDto = api.getPrefs(token)

    suspend fun getBearerTokenOrLogin(deviceKey: String): String {
        val fast = getSavedTokenFastOrNull()
        if (!fast.isNullOrBlank() && !shouldValidateNow()) return fast

        val validated = ensureValidTokenOrNull(deviceKey)
        if (!validated.isNullOrBlank()) return validated

        return deviceLoginAndPersist(deviceKey)
    }

    suspend fun getDeepgramAccessToken(deviceKey: String, ttlSeconds: Int = 600): String {
        val now = System.currentTimeMillis()
        val cached = dgToken
        if (!cached.isNullOrBlank() && now < (dgTokenExpiryMs - 30_000)) {
            return cached
        }

        suspend fun mintOnce(): Pair<String, Long> {
            val bearer = getBearerTokenOrLogin(deviceKey)
            val dto = api.deepgramToken(bearerToken = bearer, ttlSeconds = ttlSeconds)
            val expiresIn = (dto.expiresIn ?: ttlSeconds).coerceAtLeast(1)
            val expiryMs = now + expiresIn * 1000L
            return dto.accessToken to expiryMs
        }

        return try {
            val (tok, expMs) = mintOnce()
            dgToken = tok
            dgTokenExpiryMs = expMs
            tok
        } catch (t: Throwable) {
            val retry = (t is PrefsApiClient.HttpException) && (t.code == 401 || t.code == 403)
            if (!retry) throw t

            clearToken()
            val (tok2, expMs2) = mintOnce()
            dgToken = tok2
            dgTokenExpiryMs = expMs2
            tok2
        }
    }

    suspend fun getClaudeApiKey(deviceKey: String): String {
        val now = System.currentTimeMillis()
        val cached = claudeApiKey
        if (!cached.isNullOrBlank() && now < (claudeKeyExpiryMs - 30_000)) {
            return cached
        }

        suspend fun mintOnce(): Pair<String, Long> {
            val bearer = getBearerTokenOrLogin(deviceKey)
            val dto = api.claudeApiKey(bearerToken = bearer)
            val expiresIn = (dto.expiresIn ?: 600).coerceAtLeast(1)
            val expiryMs = now + expiresIn * 1000L
            return dto.apiKey to expiryMs
        }

        return try {
            val (key, expMs) = mintOnce()
            claudeApiKey = key
            claudeKeyExpiryMs = expMs
            key
        } catch (t: Throwable) {
            val retry = (t is PrefsApiClient.HttpException) && (t.code == 401 || t.code == 403)
            if (!retry) throw t

            clearToken()
            val (key2, expMs2) = mintOnce()
            claudeApiKey = key2
            claudeKeyExpiryMs = expMs2
            key2
        }
    }

    private companion object {
        private const val KEY_TOKEN = "bearer_token"
        private const val KEY_LAST_VALIDATED_MS = "last_validated_ms"
        private const val VALIDATION_TTL_MS = 6 * 60 * 60 * 1000L
    }
}
