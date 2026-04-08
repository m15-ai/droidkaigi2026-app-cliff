"""
Cliff Backend (FastAPI + SQLite)

Token-minting backend for the Cliff voice assistant Android app.
Keeps vendor API keys (Deepgram, Anthropic) server-side and issues
short-lived tokens to authenticated devices.

Routes:
- POST /api/{secret}/claim-invite      — one-time invite redemption
- POST /api/{secret}/device-login      — device auth, returns Bearer token
- GET  /api/{secret}/prefs             — read user preferences
- PUT  /api/{secret}/prefs             — update user preferences
- POST /api/{secret}/deepgram/token    — mint short-lived Deepgram access token
- POST /api/{secret}/claude/api-key    — provide Claude API key to authenticated device
- POST /api/{secret}/invite-request    — request an invite (pre-auth)

Auth model:
- Pre-seed invites via SAM_INVITES_JSON env var (invite_code -> device_key).
- claim-invite marks the invite as used (one-time).
- device-login succeeds only if the device's invite has been claimed.
- All other endpoints require Authorization: Bearer <token> from device-login.
- X-Sam-App-Key header provides an extra gate on app-only endpoints.

Setup:
    pip install fastapi uvicorn aiosqlite
    export SAM_SECRET_PATH="your_secret_path"
    export SAM_APP_KEY="your_app_key"
    export SAM_DEEPGRAM_API_KEY="dg-..."
    export SAM_ANTHROPIC_API_KEY="sk-ant-..."
    export SAM_INVITES_JSON='[{"code":"demo123","deviceKey":"ANDROID_ID_HERE","label":"My Phone"}]'
    uvicorn cliff_server:app --host 0.0.0.0 --port 8000
"""

import json
import os
import secrets
import time
from typing import Optional

import aiosqlite
from fastapi import Body, Depends, FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

import asyncio
import urllib.request
import urllib.error

# -----------------------
# Config (env vars)
# -----------------------
SECRET_PATH = os.environ.get("SAM_SECRET_PATH", "change_me")
DB_PATH = os.environ.get("SAM_DB_PATH", "./cliff.db")

DEEPGRAM_API_KEY = os.environ.get("SAM_DEEPGRAM_API_KEY", "").strip()
ANTHROPIC_API_KEY = os.environ.get("SAM_ANTHROPIC_API_KEY", "").strip()

# Seed invites from env var:
# SAM_INVITES_JSON='[
#   {"code":"alice123","deviceKey":"ANDROIDID_ALICE","label":"Alice Pixel"},
#   {"code":"bob456","deviceKey":"ANDROIDID_BOB","label":"Bob Samsung"}
# ]'
INVITES_JSON = os.environ.get("SAM_INVITES_JSON", "[]")

DEFAULT_MOOD = os.environ.get("SAM_DEFAULT_MOOD", "VENTING")
DEFAULT_PERSONALITY = os.environ.get("SAM_DEFAULT_PERSONALITY", "NycVentMode")

# Extra gate: app must send header X-Sam-App-Key: <value>
SAM_APP_KEY = os.environ.get("SAM_APP_KEY", "").strip()

# If true, logging in will delete older tokens for the same device_key
DELETE_OLD_TOKENS_ON_LOGIN = os.environ.get("SAM_DELETE_OLD_TOKENS", "true").lower() == "true"


# -----------------------
# Models
# -----------------------
class DeviceLoginIn(BaseModel):
    deviceKey: str = Field(..., min_length=3, max_length=200)


class PrefsIn(BaseModel):
    mood: str = Field(..., max_length=64)
    personality: str = Field(..., max_length=64)
    customPrompt: str = Field("", max_length=8000)


class PrefsOut(PrefsIn):
    deviceKey: str
    updatedAt: int


class ClaimInviteIn(BaseModel):
    inviteCode: str
    deviceKey: str


class InviteRequestIn(BaseModel):
    deviceKey: str = Field(..., min_length=3, max_length=200)
    message: str = Field("", max_length=500)


class DeepgramTokenIn(BaseModel):
    ttlSeconds: int = 600  # 1..3600


class ClaudeApiKeyIn(BaseModel):
    pass


# -----------------------
# App + DB helpers
# -----------------------
app = FastAPI(title="Cliff Backend", version="1.0")


async def get_db() -> aiosqlite.Connection:
    db = await aiosqlite.connect(DB_PATH)
    db.row_factory = aiosqlite.Row
    return db


def _parse_preinvites() -> list[dict]:
    try:
        data = json.loads(INVITES_JSON)
        return data if isinstance(data, list) else []
    except Exception:
        return []


async def _post_json(url: str, headers: dict, payload: dict) -> tuple[int, dict, str]:
    """Async wrapper around urllib POST JSON."""
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


@app.on_event("startup")
async def startup() -> None:
    preinvites = _parse_preinvites()

    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute("PRAGMA journal_mode=WAL;")
        await db.execute("PRAGMA synchronous=NORMAL;")

        await db.execute(
            """
            CREATE TABLE IF NOT EXISTS prefs (
                device_key     TEXT PRIMARY KEY,
                mood           TEXT NOT NULL,
                personality    TEXT NOT NULL,
                custom_prompt  TEXT NOT NULL DEFAULT '',
                updated_at     INTEGER NOT NULL
            );
            """
        )

        await db.execute(
            """
            CREATE TABLE IF NOT EXISTS tokens (
                token       TEXT PRIMARY KEY,
                device_key  TEXT NOT NULL,
                kind        TEXT NOT NULL,
                created_at  INTEGER NOT NULL
            );
            """
        )

        await db.execute(
            """
            CREATE TABLE IF NOT EXISTS invites (
                invite_code TEXT PRIMARY KEY,
                device_key  TEXT NOT NULL,
                label       TEXT NOT NULL DEFAULT '',
                used_at     INTEGER
            );
            """
        )

        # Seed invites (idempotent)
        for item in preinvites:
            code = (item.get("code") or "").strip()
            device_key = (item.get("deviceKey") or "").strip()
            label = (item.get("label") or "").strip()
            if not code or not device_key:
                continue
            await db.execute(
                "INSERT OR IGNORE INTO invites (invite_code, device_key, label) VALUES (?, ?, ?)",
                (code, device_key, label),
            )

        await db.execute(
            """
            CREATE TABLE IF NOT EXISTS invite_requests (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                device_key  TEXT NOT NULL,
                message     TEXT NOT NULL DEFAULT '',
                created_at  INTEGER NOT NULL,
                ip          TEXT NOT NULL DEFAULT '',
                user_agent  TEXT NOT NULL DEFAULT '',
                status      TEXT NOT NULL DEFAULT 'PENDING'
            );
            """
        )

        await db.execute("CREATE INDEX IF NOT EXISTS idx_invreq_status_created ON invite_requests(status, created_at);")
        await db.execute("CREATE INDEX IF NOT EXISTS idx_invreq_device_created ON invite_requests(device_key, created_at);")

        await db.commit()


async def device_key_from_bearer(request: Request, db: aiosqlite.Connection) -> Optional[str]:
    auth = request.headers.get("Authorization", "")
    if not auth.lower().startswith("bearer "):
        return None
    token = auth.split(" ", 1)[1].strip()
    if not token:
        return None
    cur = await db.execute(
        "SELECT device_key FROM tokens WHERE token = ?",
        (token,),
    )
    row = await cur.fetchone()
    await cur.close()
    return row["device_key"] if row else None


async def require_device_key(request: Request, db: aiosqlite.Connection) -> str:
    dk = await device_key_from_bearer(request, db)
    if dk:
        return dk
    raise HTTPException(status_code=401, detail="Not authorized")


def _require_app_key(request: Request) -> None:
    if SAM_APP_KEY:
        hdr = (request.headers.get("X-Sam-App-Key") or "").strip()
        if hdr != SAM_APP_KEY:
            raise HTTPException(status_code=401, detail="Missing/invalid X-Sam-App-Key")


async def ensure_invite_claimed_for_device(db: aiosqlite.Connection, device_key: str) -> None:
    cur = await db.execute(
        "SELECT used_at FROM invites WHERE device_key = ?",
        (device_key,),
    )
    row = await cur.fetchone()
    await cur.close()
    if not row:
        raise HTTPException(status_code=403, detail="Device not allowed")
    if row["used_at"] is None:
        raise HTTPException(status_code=403, detail="Invite not yet claimed for this device")


# -----------------------
# Claim invite (app first-run)
# -----------------------
@app.post(f"/api/{SECRET_PATH}/claim-invite")
async def claim_invite(
    request: Request,
    body: ClaimInviteIn,
    db: aiosqlite.Connection = Depends(get_db),
):
    try:
        _require_app_key(request)

        invite_code = body.inviteCode.strip()
        device_key = body.deviceKey.strip()
        if not invite_code or not device_key:
            raise HTTPException(status_code=400, detail="inviteCode and deviceKey are required")

        now = int(time.time())

        cur = await db.execute(
            """
            UPDATE invites
            SET used_at = ?
            WHERE invite_code = ?
              AND device_key = ?
              AND used_at IS NULL
            """,
            (now, invite_code, device_key),
        )
        await db.commit()
        changed = cur.rowcount
        await cur.close()

        if changed != 1:
            cur2 = await db.execute(
                "SELECT device_key, used_at FROM invites WHERE invite_code = ?",
                (invite_code,),
            )
            row = await cur2.fetchone()
            await cur2.close()

            if not row:
                raise HTTPException(status_code=401, detail="Bad invite code")
            if row["device_key"] != device_key:
                raise HTTPException(status_code=401, detail="Invite not valid for this device")
            if row["used_at"] is not None:
                raise HTTPException(status_code=409, detail="Invite already used")

            raise HTTPException(status_code=401, detail="Invite rejected")

        return {"ok": True}
    finally:
        await db.close()


# -----------------------
# Device login (Bearer token)
# -----------------------
@app.post(f"/api/{SECRET_PATH}/device-login")
async def device_login(request: Request, body: DeviceLoginIn, db: aiosqlite.Connection = Depends(get_db)):
    try:
        _require_app_key(request)

        device_key = body.deviceKey.strip()
        now = int(time.time())

        await ensure_invite_claimed_for_device(db, device_key)

        if DELETE_OLD_TOKENS_ON_LOGIN:
            await db.execute("DELETE FROM tokens WHERE device_key = ? AND kind = 'app'", (device_key,))

        token = secrets.token_urlsafe(32)
        await db.execute(
            "INSERT INTO tokens (token, device_key, kind, created_at) VALUES (?, ?, 'app', ?)",
            (token, device_key, now),
        )
        await db.commit()

        return {"token": token}
    finally:
        await db.close()


# -----------------------
# Prefs API
# -----------------------
@app.get(f"/api/{SECRET_PATH}/prefs", response_model=PrefsOut)
async def get_prefs(request: Request, db: aiosqlite.Connection = Depends(get_db)):
    try:
        device_key = await require_device_key(request, db)

        cur = await db.execute(
            "SELECT device_key, mood, personality, custom_prompt, updated_at FROM prefs WHERE device_key = ?",
            (device_key,),
        )
        row = await cur.fetchone()
        await cur.close()
        if not row:
            now = int(time.time())
            return PrefsOut(
                deviceKey=device_key,
                mood=DEFAULT_MOOD,
                personality=DEFAULT_PERSONALITY,
                customPrompt="",
                updatedAt=now,
            )

        return PrefsOut(
            deviceKey=row["device_key"],
            mood=row["mood"],
            personality=row["personality"],
            customPrompt=row["custom_prompt"],
            updatedAt=row["updated_at"],
        )
    finally:
        await db.close()


@app.put(f"/api/{SECRET_PATH}/prefs", response_model=PrefsOut)
async def put_prefs(request: Request, body: PrefsIn, db: aiosqlite.Connection = Depends(get_db)):
    try:
        device_key = await require_device_key(request, db)
        now = int(time.time())

        await db.execute(
            """
            INSERT INTO prefs (device_key, mood, personality, custom_prompt, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(device_key) DO UPDATE SET
                mood = excluded.mood,
                personality = excluded.personality,
                custom_prompt = excluded.custom_prompt,
                updated_at = excluded.updated_at;
            """,
            (device_key, body.mood, body.personality, body.customPrompt, now),
        )
        await db.commit()

        return PrefsOut(
            deviceKey=device_key,
            mood=body.mood,
            personality=body.personality,
            customPrompt=body.customPrompt,
            updatedAt=now,
        )
    finally:
        await db.close()


# -----------------------
# Deepgram token mint
# -----------------------
@app.post(f"/api/{SECRET_PATH}/deepgram/token")
async def deepgram_token(
    request: Request,
    body: DeepgramTokenIn = Body(...),
    db: aiosqlite.Connection = Depends(get_db),
):
    try:
        device_key = await device_key_from_bearer(request, db)
        if not device_key:
            raise HTTPException(status_code=401, detail="Not authorized")

        _require_app_key(request)

        if not DEEPGRAM_API_KEY:
            raise HTTPException(status_code=501, detail="Server missing SAM_DEEPGRAM_API_KEY")

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
    finally:
        await db.close()


# -----------------------
# Claude API key
# -----------------------
@app.post(f"/api/{SECRET_PATH}/claude/api-key")
async def claude_api_key(
    request: Request,
    body: ClaudeApiKeyIn = Body(...),
    db: aiosqlite.Connection = Depends(get_db),
):
    try:
        device_key = await device_key_from_bearer(request, db)
        if not device_key:
            raise HTTPException(status_code=401, detail="Not authorized")

        _require_app_key(request)

        if not ANTHROPIC_API_KEY:
            raise HTTPException(status_code=501, detail="Server missing SAM_ANTHROPIC_API_KEY")

        return {
            "api_key": ANTHROPIC_API_KEY,
            "expires_in": 3600,
        }
    finally:
        await db.close()


# -----------------------
# Invite request (pre-auth)
# -----------------------
@app.post(f"/api/{SECRET_PATH}/invite-request")
async def invite_request(
    request: Request,
    body: InviteRequestIn,
    db: aiosqlite.Connection = Depends(get_db),
):
    try:
        _require_app_key(request)

        device_key = body.deviceKey.strip()
        message = (body.message or "").strip()
        now = int(time.time())
        ip = (request.client.host if request.client else "") or ""
        ua = (request.headers.get("User-Agent") or "")[:300]

        # Anti-spam: one request per device per 24h
        cur = await db.execute(
            "SELECT created_at FROM invite_requests WHERE device_key = ? ORDER BY created_at DESC LIMIT 1",
            (device_key,),
        )
        row = await cur.fetchone()
        await cur.close()
        if row and (now - int(row["created_at"])) < 24 * 3600:
            return {"ok": True, "already": True}

        await db.execute(
            """
            INSERT INTO invite_requests (device_key, message, created_at, ip, user_agent, status)
            VALUES (?, ?, ?, ?, ?, 'PENDING')
            """,
            (device_key, message, now, ip, ua),
        )
        await db.commit()

        return {"ok": True}
    finally:
        await db.close()
