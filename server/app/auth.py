"""auth.py — who may use the server.

Two secrets, both set as environment variables (on Hugging Face Spaces:
Settings → Variables and secrets → New secret, then restart the Space):

  RCA_API_KEY   Required by every /api/*, /ws/* and private /static/* call
                (chats, fee PDFs, photos). The app, wa_bridge.py and
                fee_worker.py send it as `Authorization: Bearer <key>`;
                /ws/* and links opened in a browser may pass `?key=<key>`.
                /api/health stays open so the app can test the address.
  RCA_PASSWORD  Password for the web dashboard ("/"). A correct password sets
                a signed cookie (30 days) that also unlocks the API for that
                browser. /logout clears it.

Unset = open, which is only meant for trying the server on one PC. The
server prints a warning at start-up when RCA_API_KEY is missing.
"""

from __future__ import annotations

import hashlib
import hmac
import html
import os
from urllib.parse import parse_qs

from fastapi import APIRouter, Form
from starlette.responses import HTMLResponse, JSONResponse, RedirectResponse

API_KEY = os.environ.get("RCA_API_KEY", "").strip()
PASSWORD = os.environ.get("RCA_PASSWORD", "").strip()
COOKIE = "rca_web"
MAX_AGE = 30 * 24 * 3600

# Public static files (the dashboard's own CSS/JS and the crest).
_PUBLIC_STATIC = ("/static/web/",)
_OPEN = {"/api/health", "/login", "/logout", "/favicon.ico"}

if not API_KEY:
    print("[auth] WARNING: RCA_API_KEY is not set, so the API is open to anyone who "
          "knows this address. Set it before real pupil or parent data goes in.")


def _cookie_token() -> str:
    secret = (PASSWORD or API_KEY).encode()
    return hmac.new(secret, b"rca-web-v1", hashlib.sha256).hexdigest()


def _headers(scope) -> dict[bytes, bytes]:
    return {k.lower(): v for k, v in scope.get("headers") or []}


def _cookie_ok(scope) -> bool:
    if not (PASSWORD or API_KEY):
        return False
    raw = _headers(scope).get(b"cookie", b"").decode("latin-1")
    for part in raw.split(";"):
        k, _, v = part.strip().partition("=")
        if k == COOKIE and hmac.compare_digest(v, _cookie_token()):
            return True
    return False


def _key_ok(scope) -> bool:
    if not API_KEY:
        return True
    h = _headers(scope)
    auth = h.get(b"authorization", b"").decode("latin-1")
    candidates = [auth[7:].strip() if auth.lower().startswith("bearer ") else "",
                  h.get(b"x-rca-key", b"").decode("latin-1").strip()]
    qs = parse_qs((scope.get("query_string") or b"").decode("latin-1"))
    candidates += qs.get("key", [])
    return any(c and hmac.compare_digest(c.encode(), API_KEY.encode()) for c in candidates) or _cookie_ok(scope)


def _needs_key(path: str) -> bool:
    if path in _OPEN or path.startswith(_PUBLIC_STATIC):
        return False
    return path.startswith(("/api/", "/ws/", "/static/"))


def _login_page(next_path: str, wrong: bool = False) -> HTMLResponse:
    msg = "Wrong password. Try again." if wrong else "Staff sign-in"
    return HTMLResponse(f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Royal Crest Connect</title>
<style>body{{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
background:#0A1F5C;font-family:system-ui,Segoe UI,Roboto,sans-serif;color:#0A1F5C}}
form{{background:#fff;padding:28px;border-radius:14px;width:min(320px,88vw);border-top:5px solid #F2B705}}
h1{{font-size:20px;margin:0 0 4px}}p{{font-size:14px;color:#4a5578;margin:0 0 16px}}
input{{width:100%;box-sizing:border-box;padding:11px;border-radius:8px;border:1px solid #c5cce0;font-size:16px}}
button{{margin-top:14px;width:100%;padding:12px;border:0;border-radius:8px;background:#F2B705;color:#0A1F5C;
font-weight:700;font-size:15px;cursor:pointer}}</style>
</head><body><form method="post" action="/login">
<h1>Royal Crest Connect</h1><p>{msg}</p>
<label for="pw" style="font-size:13px">Password</label>
<input id="pw" type="password" name="password" autocomplete="current-password" autofocus required>
<input type="hidden" name="next" value="{html.escape(next_path)}">
<button type="submit">Sign in</button></form></body></html>""", status_code=401 if wrong else 200)


class RCAAuthMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] not in ("http", "websocket"):
            await self.app(scope, receive, send)
            return
        path = scope.get("path", "")
        if _needs_key(path) and not _key_ok(scope):
            if scope["type"] == "websocket":
                await send({"type": "websocket.close", "code": 4401})
                return
            await JSONResponse({"detail": "missing or wrong API key"}, status_code=401)(scope, receive, send)
            return
        if (path == "/" and (PASSWORD or API_KEY) and scope["type"] == "http"
                and not _cookie_ok(scope)):
            await _login_page("/")(scope, receive, send)
            return
        await self.app(scope, receive, send)


router = APIRouter(tags=["auth"])


def _safe_next(n: str) -> str:
    # only same-site relative paths (no open redirect)
    return n if n.startswith("/") and not n.startswith("//") else "/"


@router.post("/login")
def login(password: str = Form(""), next: str = Form("/")):
    nxt = _safe_next(next)
    secret = PASSWORD or API_KEY
    if not secret:
        return RedirectResponse(nxt, status_code=303)
    if not hmac.compare_digest(password.encode(), secret.encode()):
        return _login_page(nxt, wrong=True)
    resp = RedirectResponse(nxt, status_code=303)
    resp.headers.append("set-cookie",
                        f"{COOKIE}={_cookie_token()}; Max-Age={MAX_AGE}; Path=/; HttpOnly; Secure; SameSite=Lax")
    return resp


@router.get("/logout")
def logout():
    resp = RedirectResponse("/", status_code=303)
    resp.headers.append("set-cookie", f"{COOKIE}=; Max-Age=0; Path=/; HttpOnly; Secure; SameSite=Lax")
    return resp


@router.get("/api/auth/check")
def auth_check():
    """Reached only with a valid key (the middleware stops the rest), so the
    app calls it after /api/health to confirm the key it was given."""
    return {"ok": True, "key_required": bool(API_KEY)}
