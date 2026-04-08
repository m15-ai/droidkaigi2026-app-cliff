# Cliff - Real-Time Voice Assistant for Android

Cliff is an open-source Android voice assistant that delivers natural, real-time conversations powered by Claude and Deepgram. It features streaming speech-to-text, streaming LLM responses, streaming text-to-speech, and full barge-in (interruption) support — so conversations feel fluid and human.

## Demo

https://github.com/m15-ai/Cliff/releases/download/v0.1/Cliff-demo.mp4

## How It Works

```
Mic → Deepgram Flux STT (WebSocket) → Claude Sonnet (SSE streaming) → Deepgram Aura-2 TTS (WebSocket) → Speaker
                                              ↑
                                   Barge-in controller
                                   (cancel + squelch on interruption)
```

1. **You speak** — mic captures 16kHz PCM with hardware echo cancellation
2. **Deepgram Flux** transcribes in real-time via WebSocket with turn detection
3. **Claude Sonnet 4** generates a response via SSE streaming (text deltas arrive incrementally)
4. **Deepgram Aura-2** synthesizes speech from those deltas *as they arrive* — no waiting for the full response
5. **You interrupt** — the barge-in controller detects overlapping speech, cancels the in-flight Claude response, and squelches TTS playback instantly

The result: the bot starts speaking before it's done "thinking", and you can cut it off mid-sentence just like a real conversation.

## Features

- **End-to-end streaming** — text deltas pipe directly from Claude to TTS with no buffering delay
- **Barge-in / interruptions** — 150ms debounced detection, cancels LLM + squelches audio instantly
- **Conversation history** — full multi-turn context sent to Claude, persisted locally via Room DB
- **Customizable personality** — configurable system prompt, mood, and personality settings
- **Smart audio routing** — automatic speaker/headset/Bluetooth detection and switching
- **Real-time visualizer** — RMS-based audio level display at ~30fps
- **Secure token management** — API keys minted server-side with short TTLs, never stored long-term on device

## Architecture

| Layer | Tech |
|-------|------|
| UI | Jetpack Compose + Material 3 |
| State | Kotlin Coroutines + Flow, MVVM |
| STT | Deepgram Flux (`flux-general-en`) via WebSocket |
| LLM | Claude Sonnet 4 via Anthropic Messages API (SSE) |
| TTS | Deepgram Aura-2 (`aura-2-arcas-en`) via WebSocket |
| Audio | Android AudioRecord (capture) + AudioTrack (playback) |
| Storage | Room DB for conversations, SharedPreferences for settings |
| Networking | OkHttp 4 |
| DI | Service Locator pattern |

## Prerequisites

- Android Studio Hedgehog or later
- JDK 21
- Android SDK 35
- A backend server that implements the token-minting API (see [Backend Requirements](#backend-requirements))

## Setup

1. **Clone the repo**
   ```bash
   git clone https://github.com/m15-ai/cliff.git
   cd cliff
   ```

2. **Configure your backend credentials** in `local.properties`:
   ```properties
   CLIFF_PREFS_BASE_URL=https://your-backend.example.com
   CLIFF_SECRET_PATH=your_secret_path
   CLIFF_APP_KEY=your_app_key
   ```
   These can also be set as environment variables.

3. **Build and run**
   ```bash
   ./gradlew installDebug
   ```

## Security Model

Most voice assistant demos hardcode API keys in the app — anyone who decompiles the APK gets your keys. Cliff takes a different approach:

**No API keys on-device.** The app never holds long-lived Deepgram or Anthropic credentials. Instead, a lightweight backend mints short-lived tokens (10-minute TTL) on demand. This means:

- **Keys can't be stolen from the APK** — the app only ever holds ephemeral tokens
- **Devices can be revoked instantly** server-side, no app update needed
- **Zero user friction** — no signup, no email, no password

**Device identity** uses Android's `Settings.Secure.ANDROID_ID` — a per-app, per-device ID generated automatically by Android. It's not personally identifiable and resets on factory reset. It's used to authenticate with the backend and request tokens.

**Invite gating** (optional) lets you control who gets access. Devices redeem an invite code once, then authenticate freely. You can remove this if you're running your own open instance.

## Backend Requirements

Cliff is a client-only app. It expects a backend server that provides these authenticated endpoints:

| Endpoint | Purpose |
|----------|---------|
| `POST /api/{secret_path}/claim-invite` | Redeem an invite code |
| `POST /api/{secret_path}/device-login` | Authenticate a device, returns bearer token |
| `GET/PUT /api/{secret_path}/prefs` | Read/write user preferences |
| `POST /api/{secret_path}/deepgram/token` | Mint a short-lived Deepgram access token |
| `POST /api/{secret_path}/claude/api-key` | Mint a short-lived Claude API key |

See [`backend-endpoint-requirements.txt`](backend-endpoint-requirements.txt) for the full API contract.

The backend is responsible for:
- Holding your Deepgram and Anthropic API keys securely
- Issuing short-lived tokens to authenticated devices
- Managing invite codes and device authorization

## Project Structure

```
app/src/main/java/com/m15/cliff/
├── MainActivity.kt              # Entry point, navigation
├── VoiceAgentViewModel.kt       # Main orchestrator
├── BargeInController.kt         # Interruption detection
├── ServiceLocator.kt            # Dependency injection
├── audio/
│   └── AudioCapture.kt          # Mic input (16kHz PCM)
├── net/
│   ├── LlmClient.kt             # Claude streaming interface
│   ├── claude/
│   │   └── ClaudeStreamingClient.kt  # Anthropic Messages API (SSE)
│   └── flux/
│       └── FluxClientImpl.kt    # Deepgram Flux STT (WebSocket)
├── tts/
│   └── DeepgramTtsClient.kt     # Deepgram Aura-2 TTS (WebSocket)
├── prefs/
│   ├── PrefsApiClient.kt        # Backend HTTP client
│   └── PrefsRepository.kt       # Token caching & auth lifecycle
├── data/
│   ├── AppDatabase.kt           # Room DB
│   └── ConversationRepository.kt
└── ui/
    └── ...                       # Compose screens
```

## Tech Stack

- **Kotlin** + Jetpack Compose + Coroutines/Flow
- **Deepgram** — Flux (STT) + Aura-2 (TTS) via WebSocket
- **Anthropic Claude** — Sonnet 4 via Messages API with SSE streaming
- **Room** — local conversation persistence
- **OkHttp** — HTTP + WebSocket networking

## OpenClaw Integration

[OpenClaw](https://github.com/openclaw/openclaw) is a self-hosted AI assistant control plane that connects messaging platforms, devices, and agent runtimes through a local WebSocket gateway. It already includes an Android node with voice support, but uses ElevenLabs + system TTS.

Cliff's streaming voice pipeline (Deepgram Flux STT with turn detection + Aura-2 TTS with delta streaming + barge-in) could serve as a high-quality voice interface for OpenClaw on Android. The integration path:

- **OpenClaw's gateway** exposes a WebSocket RPC at `ws://127.0.0.1:18789` with session and tool-streaming APIs
- **Cliff could connect as a client node**, sending transcribed user speech to OpenClaw and streaming agent responses back through the Deepgram TTS pipeline
- This would give OpenClaw users real-time voice conversations with full interruption support, while OpenClaw handles agent orchestration, tool execution, and multi-device coordination

This integration is not yet implemented — contributions welcome. The main work would be adding an OpenClaw gateway client alongside the existing Claude streaming client, routing transcribed text through OpenClaw's session API instead of directly to Claude.

## License

MIT License. See [LICENSE](LICENSE) for details.