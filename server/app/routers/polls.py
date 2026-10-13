"""routers/polls.py — polls that parents answer on WhatsApp.

A poll goes out as a normal WhatsApp message:

    📊 *Which day suits you for the parents' meeting?*
    1. Friday 15 January
    2. Saturday 16 January
    Reply with the number of your choice.

and every reply in that chat that is just one of those numbers ("2", "2.",
"Option 2") counts as a vote, one per person (the sender's WhatsApp name),
latest reply wins. Replies are counted as wa_bridge syncs the chat
(routers/chat_sync.py calls tally_reply_votes), so the app's poll card fills
in by itself. Staff can still record a vote by hand (POST /api/polls/{id}/vote).

POST /api/broadcast/poll sends the same poll to many chats (same targeting
as POST /api/broadcast); each chat keeps its own tally.
"""

import re
import uuid
from datetime import datetime, timezone
from typing import List

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from ..config import DISPLAY_TZ_OFFSET_MINUTES
from ..database import get_db
from ..models import CreatePollIn, VoteIn, poll_options_with_counts, serialize_message
from ..wa_clean import to_offset_iso
from ..ws_manager import manager
from .broadcast import _targets

router = APIRouter(tags=["polls"])

MAX_OPTIONS = 9                      # single-digit replies only
_REPLY_RE = re.compile(r"^\s*(?:option\s*|no\.?\s*|#)?([1-9])\s*[.)]?\s*$", re.IGNORECASE)


def poll_text(question: str, options: list[str]) -> str:
    lines = [f"📊 *{question.strip()}*"]
    lines += [f"{i}. {o}" for i, o in enumerate(options, 1)]
    lines.append("Reply with the number of your choice.")
    return "\n".join(lines)


def _clean_options(raw: list[str]) -> list[str]:
    options = [o.strip() for o in raw if o.strip()]
    if len(options) < 2:
        raise HTTPException(422, "a poll needs at least 2 options")
    if len(options) > MAX_OPTIONS:
        raise HTTPException(422, f"a poll can have at most {MAX_OPTIONS} options")
    return options


def _insert_poll(conn, chat_id: str, question: str, options: list[str], sender: str, send: bool) -> str:
    poll_id, msg_id = str(uuid.uuid4()), str(uuid.uuid4())
    now = datetime.now(timezone.utc).isoformat()
    conn.execute("INSERT INTO polls (id, chat_id, question, created_at) VALUES (?, ?, ?, ?)",
                 (poll_id, chat_id, question, now))
    for opt in options:
        conn.execute("INSERT INTO poll_options (id, poll_id, text) VALUES (?, ?, ?)",
                     (str(uuid.uuid4()), poll_id, opt))
    conn.execute(
        "INSERT INTO messages (id, chat_id, sender, kind, text, poll_id, created_at, direction, wa_status) "
        "VALUES (?, ?, ?, 'poll', ?, ?, ?, 'out', ?)",
        (msg_id, chat_id, sender or "Royal Crest", poll_text(question, options), poll_id, now,
         "pending" if send else None))
    return msg_id


@router.post("/api/chats/{chat_id}/polls")
async def create_poll(chat_id: str, body: CreatePollIn):
    options = _clean_options(body.options)
    question = body.question.strip()
    if not question:
        raise HTTPException(422, "the poll needs a question")
    conn = get_db()
    try:
        if not conn.execute("SELECT 1 FROM chats WHERE id=?", (chat_id,)).fetchone():
            raise HTTPException(404, "chat not found")
        msg_id = _insert_poll(conn, chat_id, question, options, body.sender.strip(), body.send_to_whatsapp)
        conn.commit()
        msg = serialize_message(conn, conn.execute("SELECT * FROM messages WHERE id=?", (msg_id,)).fetchone())
    finally:
        conn.close()
    await manager.broadcast(chat_id, {"type": "message", "data": msg})
    return msg


class BroadcastPollIn(BaseModel):
    sender: str = "Royal Crest"
    question: str = Field(..., min_length=1, max_length=300)
    options: List[str]
    chat_ids: List[str] = []
    audiences: List[str] = []


@router.post("/api/broadcast/poll")
async def broadcast_poll(body: BroadcastPollIn):
    options = _clean_options(body.options)
    conn = get_db()
    try:
        chat_ids = _targets(conn, body)
        made = [(c, _insert_poll(conn, c, body.question.strip(), options, body.sender.strip(), True))
                for c in chat_ids]
        conn.commit()
        msgs = [(c, serialize_message(conn, conn.execute("SELECT * FROM messages WHERE id=?", (m,)).fetchone()))
                for c, m in made]
    finally:
        conn.close()
    for chat_id, msg in msgs:
        await manager.broadcast(chat_id, {"type": "message", "data": msg})
    return {"sent_to": len(msgs)}


@router.get("/api/polls")
def list_polls(limit: int = 50):
    """Latest polls with their tallies (all chats), for the app's Polls view."""
    conn = get_db()
    rows = conn.execute(
        "SELECT p.id, p.chat_id, p.question, p.created_at, c.name AS chat_name FROM polls p "
        "JOIN chats c ON c.id = p.chat_id ORDER BY p.created_at DESC LIMIT ?", (max(1, min(limit, 500)),)
    ).fetchall()
    out = []
    for r in rows:
        d = dict(r)
        d["created_at"] = to_offset_iso(d["created_at"], DISPLAY_TZ_OFFSET_MINUTES) or d["created_at"]
        d["options"] = poll_options_with_counts(conn, r["id"])
        d["votes"] = sum(o["votes"] for o in d["options"])
        out.append(d)
    conn.close()
    return out


@router.post("/api/polls/{poll_id}/vote")
async def vote_poll(poll_id: str, body: VoteIn):
    conn = get_db()
    poll = conn.execute("SELECT * FROM polls WHERE id=?", (poll_id,)).fetchone()
    if not poll:
        conn.close()
        raise HTTPException(404, "poll not found")
    if not conn.execute("SELECT 1 FROM poll_options WHERE id=? AND poll_id=?", (body.option_id, poll_id)).fetchone():
        conn.close()
        raise HTTPException(404, "option not found")
    conn.execute(
        "INSERT INTO poll_votes (poll_id, option_id, voter_id) VALUES (?, ?, ?) "
        "ON CONFLICT(poll_id, voter_id) DO UPDATE SET option_id=excluded.option_id",
        (poll_id, body.option_id, body.voter_id))
    conn.commit()
    options = poll_options_with_counts(conn, poll_id)
    chat_id = poll["chat_id"]
    conn.close()
    payload = {"type": "poll_update", "data": {"poll_id": poll_id, "options": options}}
    await manager.broadcast(chat_id, payload)
    return payload["data"]


def tally_reply_votes(conn, chat_id: str) -> int:
    """Count number replies to this chat's polls as votes. Each inbound
    message after a poll (and before the next poll in the chat) whose text is
    a valid option number is one vote by its sender; later replies replace
    earlier ones. Returns how many votes were added or changed. Idempotent."""
    polls = conn.execute("SELECT id, created_at FROM polls WHERE chat_id=? ORDER BY created_at ASC",
                         (chat_id,)).fetchall()
    if not polls:
        return 0
    changed = 0
    for i, poll in enumerate(polls):
        until = polls[i + 1]["created_at"] if i + 1 < len(polls) else None
        options = [r["id"] for r in conn.execute(
            "SELECT id FROM poll_options WHERE poll_id=? ORDER BY rowid ASC", (poll["id"],))]
        replies = conn.execute(
            "SELECT sender, text FROM messages WHERE chat_id=? AND direction='in' AND created_at > ?"
            + (" AND created_at < ?" if until else "") + " ORDER BY created_at ASC, rowid ASC",
            (chat_id, poll["created_at"], until) if until else (chat_id, poll["created_at"])).fetchall()
        latest: dict[str, str] = {}
        for r in replies:
            m = _REPLY_RE.match(r["text"] or "")
            if m and int(m.group(1)) <= len(options):
                latest[f"wa:{r['sender'] or chat_id}"] = options[int(m.group(1)) - 1]
        for voter, option_id in latest.items():
            cur = conn.execute(
                "INSERT INTO poll_votes (poll_id, option_id, voter_id) VALUES (?, ?, ?) "
                "ON CONFLICT(poll_id, voter_id) DO UPDATE SET option_id=excluded.option_id "
                "WHERE poll_votes.option_id IS NOT excluded.option_id",
                (poll["id"], option_id, voter))
            changed += cur.rowcount
    return changed
