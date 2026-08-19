# Cliff Backend Server

Token-minting backend for the Cliff voice assistant. Keeps your Deepgram and Anthropic API keys server-side and hands short-lived credentials to the app. It is fully stateless — no database, no device enrollment, no user accounts.

## Quick Start

```bash
cd server
pip install -r requirements.txt

# Required
export CLIFF_SECRET_PATH="your_secret_path"
export CLIFF_APP_KEY="your_app_key"
export CLIFF_DEEPGRAM_API_KEY="dg-..."
export CLIFF_ANTHROPIC_API_KEY="sk-ant-..."

uvicorn cliff_server:app --host 0.0.0.0 --port 8000
```

## Auth Model

Every request is authenticated by two shared secrets that must match the Android app's build config:

1. The **secret path** segment in the URL (`CLIFF_SECRET_PATH`).
2. The **app key** header `X-Cliff-App-Key` (`CLIFF_APP_KEY`).

There is no device identity, no bearer token, and no server-side state. Any client that presents both secrets can mint credentials, so keep them out of source control and rotate them if leaked.

## Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `CLIFF_SECRET_PATH` | Yes | Secret path segment for API URLs (must match `CLIFF_SECRET_PATH` in the app) |
| `CLIFF_APP_KEY` | Yes | App key header value (must match `CLIFF_APP_KEY` in the app) |
| `CLIFF_DEEPGRAM_API_KEY` | Yes | Your Deepgram API key (get one at [deepgram.com](https://deepgram.com)). Must be created with a role that can call `/v1/auth/grant` (Member or Owner) — a limited-scope key authenticates but returns `403 Insufficient permissions` when minting tokens |
| `CLIFF_ANTHROPIC_API_KEY` | Yes | Your Anthropic API key (get one at [console.anthropic.com](https://console.anthropic.com)) |

## API Endpoints

All endpoints are prefixed with `/api/{CLIFF_SECRET_PATH}` and require the `X-Cliff-App-Key` header.

| Method | Path | Description |
|--------|------|-------------|
| POST | `/deepgram/token` | Mint a short-lived Deepgram access token (STT + TTS) |
| POST | `/claude/api-key` | Return the Claude API key |

### `POST /api/{secret}/deepgram/token`

- **Request:** `{ "ttlSeconds": 600 }` (clamped server-side to 1..3600)
- **Response 200:** `{ "access_token": "...", "expires_in": 600 }`

The server exchanges your Deepgram API key for a short-lived access token via Deepgram's `/v1/auth/grant` endpoint, so the raw key never leaves the backend.

### `POST /api/{secret}/claude/api-key`

- **Request:** `{}`
- **Response 200:** `{ "api_key": "sk-ant-...", "expires_in": 3600 }`

## Deployment

### systemd (run as a service)

On a Linux host (e.g. an EC2 instance), run the server as a systemd unit so it starts on boot and restarts on crash. To bind port 80 directly without root, grant the bind capability:

```ini
# /etc/systemd/system/cliff.service
[Unit]
Description=Cliff token-minting backend
After=network.target

[Service]
User=ec2-user
WorkingDirectory=/home/ec2-user/cliff-server
EnvironmentFile=/home/ec2-user/cliff-server/cliff.env
AmbientCapabilities=CAP_NET_BIND_SERVICE
ExecStart=/home/ec2-user/.local/bin/uvicorn cliff_server:app --host 0.0.0.0 --port 80
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```

Put the four `CLIFF_*` variables in the `EnvironmentFile` (one `KEY=value` per line, `chmod 600`), then `sudo systemctl enable --now cliff.service`.

### HTTPS

For production, run behind a reverse proxy (nginx, Caddy) with HTTPS. Example with Caddy:

```
your-domain.com {
    reverse_proxy localhost:8000
}
```

Then set `CLIFF_PREFS_BASE_URL=https://your-domain.com` in the Android app's `local.properties`.

**No domain? Tailscale Serve.** If the server and your test devices are on the same [Tailscale](https://tailscale.com) tailnet, you can get valid HTTPS with zero DNS setup:

```bash
sudo tailscale serve --bg 80
```

This proxies `https://<machine>.<tailnet>.ts.net` → `localhost:80` with an auto-renewed Let's Encrypt certificate. The name is reachable only from inside the tailnet, and traffic rides WireGuard end-to-end. Set `CLIFF_PREFS_BASE_URL=https://<machine>.<tailnet>.ts.net` in the app.

**Plain HTTP fallback.** If you serve plain HTTP (bare IP, no TLS), the app must explicitly allow cleartext to that host — see the network security config note in the [main README](../README.md#setup). Keep in mind the app key and minted credentials then travel unencrypted.