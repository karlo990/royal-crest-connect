"""routers/broadcast.py — one message from the school to many chats.

Targets, in order of precedence:
  chat_ids   the exact chats picked in the app
  audiences  every chat tagged parent / staff / group / enquiry / other
             (tag a chat with PATCH /api/chats/{id}/audience)
  neither    every chat

Each copy is an ordinary outbound message (direction='out',
wa_status='pending'), so wa_bridge.py delivers it to WhatsApp one chat at a
time. A chat is skipped when the same text is still waiting to go to it, or
went to it in the last 24 hours, so a double tap or a retried request never
sends a parent the same notice twice.
"""

import hashlib
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, HTTPException

from ..database import get_db
from ..models import BroadcastIn, serialize_message
from ..ws_manager import manager
from .chats import _is_unsaved

router = APIRouter(tags=["broadcast"])

AUDIENCES = {"parent", "staff", "group", "enquiry", "other"}


def _targets(conn, body: BroadcastIn) -> list[str]:
    if body.chat_ids:
        marks = ",".join("?" * len(body.chat_ids))
        found = [r["id"] for r in conn.execute(f"SELECT id FROM chats WHERE id IN ({marks})", body.chat_ids)]
        missing = set(body.chat_ids) - set(found)
        if missing:
            raise HTTPException(404, f"unknown chat(s): {', '.join(sorted(missing))}")
        return found
    if body.audiences:
        bad = set(body.audiences) - AUDIENCES
        if bad:
            raise HTTPException(422, f"unknown audience(s): {', '.join(sorted(bad))}")
        rows = conn.execute("SELECT id, name, wa_name, audience FROM chats").fetchall()
        # An untagged chat with an unsaved number counts as an enquiry, the
        # same way GET /api/chats shows it.
        return [r["id"] for r in rows
                if (r["audience"] or ("enquiry" if _is_unsaved(r["wa_name"], r["name"]) else None))
                in body.audiences]
    return [r["id"] for r in conn.execute("SELECT id FROM chats")]


@router.post("/api/broadcast")
async def broadcast(body: BroadcastIn):
    text = body.text.strip()
    if not text:
        raise HTTPException(422, "text must not be empty")
    norm = " ".join(text.lower().split())
    text_key = hashlib.sha1(norm.encode()).hexdigest()
    now = datetime.now(timezone.utc).isoformat()

    conn = get_db()
    try:
        chat_ids = _targets(conn, body)
        sent, skipped = [], []
        for chat_id in chat_ids:
            dup = conn.execute(
                """
                SELECT 1 FROM messages
                WHERE chat_id = ? AND direction = 'out'
                  AND (wa_status = 'pending'
                       OR (wa_status = 'sent' AND created_at >= datetime(?, '-1 day')))
                  AND lower(replace(replace(trim(text), char(10), ' '), char(13), ' ')) = ?
                LIMIT 1
                """,
                (chat_id, now, norm),
            ).fetchone()
            if dup:
                skipped.append(chat_id)
                continue
            msg_id = str(uuid.uuid4())
            conn.execute(
                "INSERT INTO messages "
                "(id, chat_id, sender, kind, text, poll_id, created_at, direction, wa_status, external_key) "
                "VALUES (?, ?, ?, 'broadcast', ?, NULL, ?, 'out', 'pending', ?)",
                (msg_id, chat_id, body.sender.strip() or "Royal Crest", text, now, text_key),
            )
            sent.append((chat_id, msg_id))
        conn.commit()
        msgs = [(c, serialize_message(conn, conn.execute("SELECT * FROM messages WHERE id=?", (m,)).fetchone()))
                for c, m in sent]
    finally:
        conn.close()

    for chat_id, msg in msgs:
        await manager.broadcast(chat_id, {"type": "message", "data": msg})
    return {"sent_to": len(sent), "skipped_duplicate": len(skipped), "targets": len(chat_ids)}
