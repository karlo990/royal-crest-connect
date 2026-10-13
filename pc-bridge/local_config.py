"""local_config.py — machine-specific settings for the PC-side scripts
(wa_bridge.py, fee_worker.py, rca_supervisor.py).

The ONE place the server address and local paths live, so all scripts agree
on which server they talk to. Every value can be overridden with an
environment variable of the same name (rca_supervisor.py passes them down to
the processes it starts). The easiest way on Windows is a file called
`bridge.env` next to this one, with lines like

    RCA_SERVER_URL=https://<your-space>.hf.space
    RCA_API_KEY=<the key set on the server>

which is read automatically. Keep bridge.env private: the key opens the
school's chats.
"""

import os
from pathlib import Path


def _load_env_file(path: Path) -> None:
    """KEY=value lines from bridge.env; real environment variables win."""
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, _, v = line.partition("=")
        os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))


_load_env_file(Path(__file__).with_name("bridge.env"))


def _int_env(name: str, default: int, minimum: int = 1) -> int:
    """Env-var int with a floor, so a typo or 0 can't crash a script at import
    or turn a poll loop into a busy-spin."""
    raw = os.environ.get(name)
    if raw is None:
        return default
    try:
        return max(minimum, int(raw))
    except ValueError:
        print(f"[local_config] ignoring non-integer {name}={raw!r}, using {default}")
        return default


# ── The Royal Crest server every script talks to ───────────────────────────
SERVER_URL = os.environ.get("RCA_SERVER_URL", "http://127.0.0.1:8000").rstrip("/")

# Sent as `Authorization: Bearer <key>` on every request (rca_client.py).
# Must match RCA_API_KEY on the server.
RCA_API_KEY = os.environ.get("RCA_API_KEY", "")

# ── Polling cadences (seconds) ─────────────────────────────────────────────
WA_POLL_INTERVAL   = _int_env("WA_POLL_INTERVAL", 5)        # WhatsApp → server import cycle
WA_OUTBOX_POLL_SEC = _int_env("WA_OUTBOX_POLL_SEC", 5)      # outbox REST poll
FEE_POLL_SEC       = _int_env("FEE_POLL_SEC", 5)            # fee_worker poll

# Every Nth pass re-reads ALL chats even if their sidebar row looks unchanged.
WA_FULL_SWEEP_EVERY  = _int_env("WA_FULL_SWEEP_EVERY", 10)

# How many chats each pass reads: the N most recent in WhatsApp's own order.
# 0 = every chat in the sidebar.
WA_SYNC_LAST_N_CHATS = _int_env("WA_SYNC_LAST_N_CHATS", 15, minimum=0)

# Each synced chat's JSON snapshot is written here as <chat id>.json.
CHAT_SNAPSHOT_DIR = os.environ.get("CHAT_SNAPSHOT_DIR", "./chat_snapshots")

# WhatsApp shows local times; they're converted to UTC with this PC's time
# zone. Set to force an offset instead, in minutes (Zimbabwe = 120).
WA_UTC_OFFSET_MINUTES = (int(os.environ["WA_UTC_OFFSET_MINUTES"])
                         if os.environ.get("WA_UTC_OFFSET_MINUTES", "").lstrip("-").isdigit() else None)

# Reload WhatsApp Web every N hours (0 = never); a tab left open for a day
# grows in memory and slows every scroll.
WA_RELOAD_HOURS = _int_env("WA_RELOAD_HOURS", 6, minimum=0)

# ── Broadcasts ─────────────────────────────────────────────────────────────
# Seconds between two broadcast (or poll) sends to different chats, plus a
# random 0-50% on top, and the most broadcast sends per 24 hours. WhatsApp
# restricts numbers that message many people quickly; for a notice to every
# parent, send it to the parents' WhatsApp groups (one send per group) and
# keep one-to-one broadcasts for smaller lists.
BROADCAST_GAP_SEC   = _int_env("BROADCAST_GAP_SEC", 8)
BROADCAST_DAILY_CAP = _int_env("BROADCAST_DAILY_CAP", 250)

# ── Paths ──────────────────────────────────────────────────────────────────
LOG_DIR = os.environ.get("LOG_DIR", "./logs")


def print_active_config() -> None:
    print(
        "[local_config] RCA_SERVER_URL=%s  key=%s  WA_POLL=%ss  OUTBOX_POLL=%ss  FEE_POLL=%ss  "
        "LAST_N_CHATS=%s  BROADCAST_GAP=%ss  BROADCAST_CAP=%s/day"
        % (SERVER_URL, "set" if RCA_API_KEY else "NOT SET", WA_POLL_INTERVAL, WA_OUTBOX_POLL_SEC,
           FEE_POLL_SEC, WA_SYNC_LAST_N_CHATS, BROADCAST_GAP_SEC, BROADCAST_DAILY_CAP)
    )
