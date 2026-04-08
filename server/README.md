# Cliff Backend Server

Token-minting backend for the Cliff voice assistant. Keeps your Deepgram and Anthropic API keys server-side and issues short-lived tokens to authenticated Android devices.

## Quick Start

```bash
cd server
pip install -r requirements.txt

# Required
export CLIFF_SECRET_PATH="your_secret_path"
export CLIFF_APP_KEY="your_app_key"
export CLIFF_DEEPGRAM_API_KEY="dg-..."
export CLIFF_ANTHROPIC_API_KEY="sk-ant-..."

# Seed at least one invite (use your device's Android ID)
export CLIFF_INVITES_JSON='[{"code":"myinvite","deviceKey":"YOUR_ANDROID_ID","label":"My Phone"}]'

uvicorn cliff_server:app --host 0.0.0.0 --port 8000
```

## Finding Your Android ID

The Cliff app uses `Settings.Secure.ANDROID_ID` as the device key. To find yours:

1. Enable USB debugging on your phone
2. Run: `adb shell settings get secure android_id`
3. Use that value as `deviceKey` in your invite JSON

## Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `CLIFF_SECRET_PATH` | Yes | Secret path segment for API URLs (must match `CLIFF_SECRET_PATH` in the app) |
| `CLIFF_APP_KEY` | Yes | App key header value (must match `CLIFF_APP_KEY` in the app) |
| `CLIFF_DEEPGRAM_API_KEY` | Yes | Your Deepgram API key (get one at [deepgram.com](https://deepgram.com)) |
| `CLIFF_ANTHROPIC_API_KEY` | Yes | Your Anthropic API key (get one at [console.anthropic.com](https://console.anthropic.com)) |
| `CLIFF_INVITES_JSON` | Yes | JSON array of invite objects (see above) |
| `CLIFF_DB_PATH` | No | SQLite database path (default: `./cliff.db`) |
| `CLIFF_DEFAULT_MOOD` | No | Default mood for new users (default: `VENTING`) |
| `CLIFF_DEFAULT_PERSONALITY` | No | Default personality (default: `NycVentMode`) |
| `CLIFF_DELETE_OLD_TOKENS` | No | Clean old tokens on login (default: `true`) |

## API Endpoints

All endpoints are prefixed with `/api/{CLIFF_SECRET_PATH}`.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/claim-invite` | App key | One-time invite code redemption |
| POST | `/device-login` | App key | Device authentication, returns Bearer token |
| GET | `/prefs` | Bearer | Read user preferences |
| PUT | `/prefs` | Bearer | Update user preferences |
| POST | `/deepgram/token` | Bearer + App key | Mint short-lived Deepgram access token |
| POST | `/claude/api-key` | Bearer + App key | Get Claude API key |
| POST | `/invite-request` | App key | Request an invite (pre-auth) |

## Deployment

For production, run behind a reverse proxy (nginx, Caddy) with HTTPS. Example with Caddy:

```
your-domain.com {
    reverse_proxy localhost:8000
}
```

Then set `CLIFF_PREFS_BASE_URL=https://your-domain.com` in the Android app's `local.properties`.
