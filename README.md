# Cliff - Real-Time Voice Assistant for Android

Cliff is an open-source Android voice assistant that delivers natural, real-time conversations powered by Claude and Deepgram. It features streaming speech-to-text, streaming LLM responses, streaming text-to-speech, and full barge-in (interruption) support — so conversations feel fluid and human.

## How It Works

```
Mic → Deepgram Flux STT (WebSocket) → Claude Sonnet (SSE streaming) → Deepgram Aura-2 TTS (WebSocket) → Speaker
                                              ↑
                                   Barge-in controller
                                   (cancel + squelch on interruption)
```

1. **You speak** — mic captures 16kHz PCM with hardware echo cancellation
2. **Deepgram Flux** transcribes in real-time via WebSocket with turn detection
3. **Claude Sonnet 4.6** generates a response via SSE streaming (text deltas arrive incrementally)
4. **Deepgram Aura-2** synthesizes speech from those deltas *as they arrive* — no waiting for the full response
5. **You interrupt** — the barge-in controller detects overlapping speech, cancels the in-flight Claude response, and squelches TTS playback instantly

The result: the bot starts speaking before it's done "thinking", and you can cut it off mid-sentence just like a real conversation.

## Features

- **End-to-end streaming** — text deltas pipe directly from Claude to TTS with no buffering delay
- **Barge-in / interruptions** — 150ms debounced detection, cancels LLM + squelches audio instantly
- **Conversation history** — full multi-turn context sent to Claude, persisted locally via Room DB
- **Customizable personality** — configurable system prompt, mood, and personality settings
- **Smart audio routing** — automatic speaker/headset/Bluetooth detection and switching
- **Audio orb visualizer** — layered, additively-blended orange orbs that orbit, breathe, and accelerate with voice energy (see [Audio Orb Visualizer](#audio-orb-visualizer))
- **Latency readout** — live time-to-first-token (TTFT) overlay so you can feel how the pipeline is performing
- **Secure token management** — API keys minted server-side with short TTLs, never stored long-term on device

## Architecture

| Layer | Tech |
|-------|------|
| UI | Jetpack Compose + Material 3 |
| State | Kotlin Coroutines + Flow, MVVM |
| STT | Deepgram Flux (`flux-general-en`) via WebSocket |
| LLM | Claude Sonnet 4.6 (`claude-sonnet-4-6`) via Anthropic Messages API (SSE) |
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
- **Zero user friction** — no signup, no email, no password

**Two shared secrets.** The app authenticates to the backend with a secret path segment baked into the API URL (`CLIFF_SECRET_PATH`) and an app-key header (`X-Cliff-App-Key` / `CLIFF_APP_KEY`). Both come from the build config and must match the server. There is no device identity, no bearer token, and no server-side state — the contract is stateless.

**Startup gate.** On launch, the app mints a Deepgram token as a connectivity preflight. On success it goes to `Ready` (the token is cached and reused for the first session); on failure a connection error is shown with a retry.

## Backend Requirements

Cliff is a client-only app. It expects a backend server that provides the two
token-minting endpoints below, served under a secret base path
(`/api/{secret_path}`). All request and response bodies are JSON.

Every request must send the app-key header (`X-Cliff-App-Key`), whose value comes
from the `CLIFF_APP_KEY` build config and must match the server. Together with the
secret path segment in the URL, that is the only authentication — there is no device
identity, no bearer token, and no server-side state.

### `POST /api/{secret_path}/deepgram/token`

Mint a short-lived Deepgram access token (used for both Flux STT and Aura-2 TTS).

- **Headers:** `X-Cliff-App-Key: {app_key}`, `Content-Type: application/json`
- **Request:** `{ "ttlSeconds": 600 }`
- **Response 200:** `{ "access_token": "...", "expires_in": 600 }` (`expires_in` optional, seconds; client defaults to 600)

### `POST /api/{secret_path}/claude/api-key`

Return a Claude API key for the Anthropic Messages API.

- **Headers:** `X-Cliff-App-Key: {app_key}`, `Content-Type: application/json`
- **Request:** `{}`
- **Response 200:** `{ "api_key": "sk-ant-...", "expires_in": 3600 }` (`expires_in` optional, seconds; client defaults to 600)

A reference implementation is included in the [`server/`](server/) directory — a stateless FastAPI server you can deploy in minutes. See [`server/README.md`](server/README.md) for setup instructions.

The backend is responsible for:
- Holding your Deepgram and Anthropic API keys securely
- Minting short-lived Deepgram and Claude credentials on demand for any caller that presents the secret path and app key

## Project Structure

```
app/src/main/java/com/m15/cliff/
├── MainActivity.kt              # Entry point, navigation, gate routing
├── VoiceAgentViewModel.kt       # Main orchestrator, startup gate
├── BargeInController.kt         # Interruption detection
├── di/
│   └── ServiceLocator.kt        # Dependency injection
├── audio/
│   └── AudioCapture.kt          # Mic input (16kHz PCM)
├── net/
│   ├── LlmClient.kt             # Claude streaming interface
│   ├── claude/
│   │   └── ClaudeStreamingClient.kt  # Anthropic Messages API (SSE)
│   └── flux/
│       ├── FluxClientImpl.kt    # Deepgram Flux STT (WebSocket)
│       └── PromptDictationController.kt
├── tts/
│   └── DeepgramTtsClient.kt     # Deepgram Aura-2 TTS (WebSocket)
├── prefs/
│   ├── PrefsApiClient.kt        # Backend HTTP client (2 token endpoints)
│   └── PrefsRepository.kt       # Token minting & in-memory caching
├── data/
│   ├── db/
│   │   └── AppDatabase.kt       # Room DB (+ dao/, model/)
│   └── repo/
│       └── ConversationRepository.kt
├── util/
│   └── LatencyTracker.kt        # Time-to-first-token (TTFT) measurement
└── ui/
    ├── AudioBlobVisualizer.kt   # Reactive orange-orb audio visualizer
    └── ...                       # Compose screens
```

## Tech Stack

- **Kotlin** + Jetpack Compose + Coroutines/Flow
- **Deepgram** — Flux (STT) + Aura-2 (TTS) via WebSocket
- **Anthropic Claude** — Sonnet 4.6 via Messages API with SSE streaming
- **Room** — local conversation persistence
- **OkHttp** — HTTP + WebSocket networking

## System Message

The system message is what makes Cliff feel like a conversation instead of a chatbot reading an essay. Here's the default:

> *You are a helpful voice assistant. Your responses are spoken aloud via text-to-speech. Keep responses short and conversational — 1 to 3 sentences max. Never use bullet points, numbered lists, markdown, emojis, or special formatting. Speak in plain, natural sentences like a real conversation. If a topic needs more detail, offer to explain further rather than dumping everything at once.*

This matters more than you'd expect. Without it, Claude defaults to long-form written responses — bullet points, markdown headers, numbered lists — which sound terrible when read aloud by TTS. The system message constrains output to short, spoken-style sentences that work naturally with the streaming pipeline.

Key design choices:
- **1-3 sentences max** — keeps TTS latency low (less text to synthesize per turn) and conversations snappy
- **No formatting** — bullet points and markdown become literal "dash", "asterisk", "hash" in TTS output
- **Offer to elaborate** — instead of dumping a wall of text, the assistant asks if you want more, which plays to the barge-in model (you can just say "yes" or cut it off)

The system message is user-customizable at runtime via the app's settings screen, so you can tune the personality and response style to your preference.

## Latency Readout (TTFT)

Cliff shows a live **time-to-first-token (TTFT)** readout at the top of the conversation window — `TTFT 842 ms` — so you get an immediate feel for how the STT → LLM → TTS pipeline is performing.

TTFT measures the elapsed time from when your final transcript is dispatched to Claude until the first response token streams back. It's the dominant, user-perceptible slice of pipeline latency — the gap between you finishing your sentence and the assistant starting to "think out loud." The value refreshes on every turn and is shown over both the audio visualizer and the text/chat view.

```
You stop speaking ──▶ final transcript sent to Claude ──▶ first token arrives
                      └──────────────── TTFT ────────────────┘
```

The measurement lives in `util/LatencyTracker.kt`, instrumented at two points in `VoiceAgentViewModel`: when the request is dispatched and when the first streamed `TextDelta` arrives.

## Audio Orb Visualizer

A session opens on the text/chat view by default; tapping the visualizer FAB swaps in a reactive orb — a stack of soft, translucent orange "blobs" that drift, breathe, and swirl in response to the conversation. It's a single Jetpack Compose `Canvas` in [`ui/AudioBlobVisualizer.kt`](app/src/main/java/com/m15/cliff/ui/AudioBlobVisualizer.kt), drawn entirely in code — no images, no shader assets.

**Layered, additively-blended orbs.** Seven orbs are rendered in a warm amber/bronze palette (Burnt Orange and Bronze form the deep base; Bright Orange, Amber, Buff, Apricot, and Bisque float on top as highlights). Each is filled with a radial gradient and composited with `BlendMode.Plus` over the black background, so wherever orbs overlap they *brighten* and the shades mix toward gold — giving the soft, glowing, lava-lamp look. Edges use only low harmonics (gentle rounded lobes), so the shape stays organic rather than spiky.

**It reacts to both voices.** The visualizer is driven by `max(ttsLevel, micLevel)`:
- `ttsLevel` comes from the Deepgram Aura-2 playback stream (the assistant's voice).
- `micLevel` is the RMS energy of the captured mic PCM, computed per ~20 ms frame in `VoiceAgentViewModel` — so the orb comes alive while **you** speak, not only during TTS.

Both feed a fast-attack / slow-release envelope so peaks pop instantly and then settle, keeping the motion snappy but never jittery.

**Amplitude maps to motion** in three ways, so louder audio reads as more energy:
- **Orbit speed** — the orbit angle is *integrated* per frame (`withFrameNanos`) at `revPerSec = 0.05 + 0.6 × level`, so the orbs revolve ~0.05 rev/s at idle and spin up to ~0.5 rev/s when loud. Integrating (rather than scaling a fixed phase) keeps speed changes smooth — louder just accelerates the swirl, it never jumps positions.
- **Breathing** — the whole stack's radius grows with `level²`.
- **Spread** — orbs push apart as it gets louder, so the colored fringes separate into distinct bands, then converge back into one warm core when quiet.

The net effect: silence is a slow, lazy drift; speech — yours or the assistant's — accelerates it into a bright, blended swirl that spins back down as the voice trails off.

## Conversation History: Stateless vs. Stateful

If you've used OpenAI's Realtime API, you'll notice Cliff takes a fundamentally different approach to conversation history.

**OpenAI Realtime API** maintains a persistent WebSocket session on the server. The server tracks conversation state — you send audio in, get audio out, and the history lives server-side for the duration of the session. You don't resend previous messages; the server already has them.

**Cliff + Claude Messages API** is stateless. Every request to Claude is an independent HTTP call with no server-side session. The app must send the **full conversation history** with each turn — system message, every user message, every assistant response, all in one request. Claude has no memory between requests.

```
OpenAI Realtime:   Audio ←→ [Persistent WebSocket Session w/ Server-Side State]
Cliff + Claude:    Full History + New Message → [Stateless HTTP] → Streamed Response
```

**Why this approach?**

- **Simplicity** — no session management, no reconnection logic, no state sync. Each request is self-contained. If a request fails, just retry it.
- **Full control over context** — the app decides exactly what history to include. You can trim, summarize, or filter history client-side before sending.
- **No session timeouts** — OpenAI's Realtime sessions expire after inactivity. Cliff's approach has no idle timeout — pick up the conversation whenever.
- **Transparency** — the conversation history is stored locally in Room DB and sent explicitly. Nothing is hidden in server-side state.

**The tradeoff** is bandwidth — resending full history means larger payloads as conversations grow. For voice conversations (which tend to be short, natural exchanges), this is negligible. For very long sessions, you'd want to implement history truncation or summarization.

The local Room database persists all conversations across app restarts, so the user never loses context even though every Claude request starts fresh.

## License

MIT License. See [LICENSE](LICENSE) for details.