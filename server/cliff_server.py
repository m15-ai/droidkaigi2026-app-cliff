"""
Cliff Backend (FastAPI)

Token-minting backend for the Cliff voice assistant Android app. Keeps vendor API
keys (Deepgram, Anthropic) server-side and hands short-lived credentials to the app.

Routes:
- POST /api/{secret}/deepgram/token    — mint a short-lived Deepgram access token
- POST /api/{secret}/claude/api-key    — return the Claude API key

Auth model:
- The URL carries a secret path segment (CLIFF_SECRET_PATH).
- Every request must send header X-Cliff-App-Key: <CLIFF_APP_KEY>.
- That's it — no device identity, no bearer tokens, no server-side state, no database.

Setup:
    pip install fastapi uvicorn
    export CLIFF_SECRET_PATH="your_secret_path"
    export CLIFF_APP_KEY="your_app_key"
    export CLIFF_DEEPGRAM_API_KEY="dg-..."
    export CLIFF_ANTHROPIC_API_KEY="sk-ant-..."
    uvicorn cliff_server:app --host 0.0.0.0 --port 8000
"""

import asyncio
import json
import os
import urllib.error
import urllib.request

from fastapi import Body, FastAPI, HTTPException, Request
from pydantic import BaseModel

# -----------------------
# Config (env vars)
# -----------------------
SECRET_PATH = os.environ.get("CLIFF_SECRET_PATH", "change_me")
CLIFF_APP_KEY = os.environ.get("CLIFF_APP_KEY", "").strip()

DEEPGRAM_API_KEY = os.environ.get("CLIFF_DEEPGRAM_API_KEY", "").strip()
ANTHROPIC_API_KEY = os.environ.get("CLIFF_ANTHROPIC_API_KEY", "").strip()


# -----------------------
# Models
# -----------------------
class DeepgramTokenIn(BaseModel):
    ttlSeconds: int = 600  # clamped to 1..3600


class ClaudeApiKeyIn(BaseModel):
    pass


# -----------------------
# App + helpers
# -----------------------
app = FastAPI(title="Cliff Backend", version="2.0")


def _require_app_key(request: Request) -> None:
    if CLIFF_APP_KEY:
        hdr = (request.headers.get("X-Cliff-App-Key") or "").strip()
        if hdr != CLIFF_APP_KEY:
            raise HTTPException(status_code=401, detail="Missing/invalid X-Cliff-App-Key")


async def _post_json(url: str, headers: dict, payload: dict) -> tuple[int, dict, str]:
    """Async wrapper around a urllib POST JSON request."""
    def _do():
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(url, data=data, method="POST")
        for k, v in headers.items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                status = resp.getcode()
                raw = resp.read().decode("utf-8", errors="replace")
                try:
                    return status, json.loads(raw) if raw else {}, raw
                except Exception:
                    return status, {}, raw
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", errors="replace") if e.fp else str(e)
            try:
                return e.code, json.loads(raw) if raw else {}, raw
            except Exception:
                return e.code, {}, raw

    return await asyncio.to_thread(_do)


# -----------------------
# Deepgram token mint
# -----------------------
@app.post(f"/api/{SECRET_PATH}/deepgram/token")
async def deepgram_token(request: Request, body: DeepgramTokenIn = Body(...)):
    _require_app_key(request)

    if not DEEPGRAM_API_KEY:
        raise HTTPException(status_code=501, detail="Server missing CLIFF_DEEPGRAM_API_KEY")

    ttl = max(1, min(3600, int(body.ttlSeconds or 600)))

    status, j, raw = await _post_json(
        "https://api.deepgram.com/v1/auth/grant",
        headers={
            "Authorization": f"Token {DEEPGRAM_API_KEY}",
            "Content-Type": "application/json",
        },
        payload={"ttl_seconds": ttl},
    )

    if status != 200 or "access_token" not in j:
        raise HTTPException(status_code=502, detail=f"Deepgram grant failed ({status}): {raw[:200]}")

    return {"access_token": j["access_token"], "expires_in": j.get("expires_in")}


# -----------------------
# Claude API key
# -----------------------
@app.post(f"/api/{SECRET_PATH}/claude/api-key")
async def claude_api_key(request: Request, body: ClaudeApiKeyIn = Body(...)):
    _require_app_key(request)

    if not ANTHROPIC_API_KEY:
        raise HTTPException(status_code=501, detail="Server missing CLIFF_ANTHROPIC_API_KEY")

    return {"api_key": ANTHROPIC_API_KEY, "expires_in": 3600}