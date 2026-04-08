package com.m15.cliff

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import com.m15.cliff.audio.DefaultAudioCapture
import com.m15.cliff.data.db.AppDatabase
import com.m15.cliff.data.repo.ConversationRepository
import com.m15.cliff.net.flux.FluxClient
import com.m15.cliff.net.flux.FluxClientImpl
import com.m15.cliff.net.claude.ClaudeStreamingClient
import com.m15.cliff.net.LlmClient
import com.m15.cliff.prefs.PrefsApiClient
import com.m15.cliff.prefs.PrefsRepository
import com.m15.cliff.tts.DeepgramTtsClient
import com.m15.cliff.tts.TtsClient
import okhttp3.OkHttpClient

object ServiceLocator {
    private var initialized = false

    lateinit var repo: ConversationRepository
    lateinit var flux: FluxClient
    lateinit var llm: LlmClient
    lateinit var tts: TtsClient
    lateinit var audio: DefaultAudioCapture
    lateinit var barge: BargeInController
    lateinit var audioManager: AudioManager
    lateinit var appContext: Context

    lateinit var deviceKey: String
    lateinit var prefsRepo: PrefsRepository

    fun init(ctx: Context) {
        if (initialized) return

        appContext = ctx.applicationContext
        audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        deviceKey = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "UNKNOWN_ANDROID_ID"

        Log.d("ServiceLocator", "Android Id=$deviceKey")

        val db = AppDatabase.get(appContext)
        repo = ConversationRepository(db)

        val okHttp = OkHttpClient()

        val prefsApi = PrefsApiClient(
            okHttp = okHttp,
            baseUrl = BuildConfig.CLIFF_PREFS_BASE_URL,
            secretPath = BuildConfig.CLIFF_SECRET_PATH,
            appKey = BuildConfig.CLIFF_APP_KEY
        )
        prefsRepo = PrefsRepository(appContext, prefsApi)

        flux = FluxClientImpl(
            okHttp = okHttp,
            useMocks = false,
            prefsRepo = prefsRepo,
            deviceKey = deviceKey
        )

        llm = ClaudeStreamingClient(
            okHttp = okHttp,
            model = "claude-sonnet-4-20250514",
            prefsRepo = prefsRepo,
            deviceKey = deviceKey
        )

        tts = DeepgramTtsClient(
            context = appContext,
            okHttp = okHttp,
            deepgramTokenProvider = { prefsRepo.getDeepgramAccessToken(deviceKey) },
            sampleRate = 48_000,
            onAudioLevel = null
        )

        audio = DefaultAudioCapture(appContext)

        barge = BargeInController(llm = llm, tts = tts)

        initialized = true
    }
}
