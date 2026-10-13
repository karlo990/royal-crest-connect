"""models.py — Pydantic request bodies + row serialization."""

from typing import List, Optional

from pydantic import BaseModel, ConfigDict, Field


class UpsertChatIn(BaseModel):
    id: str
    name: str
    avatar_emoji: str = "?"
    profile_pic_url: Optional[str] = None
    # Raw WhatsApp name used by wa_bridge to locate the chat row in WA Web DOM.
    # Distinct from `name` which may have a display prefix (e.g. "📱 +263…").
    wa_name: Optional[str] = None


class SendMessageIn(BaseModel):
    sender: str = "Royal Crest"
    text: str


class BroadcastIn(BaseModel):
    sender: str = "Royal Crest"
    text: str = Field(..., min_length=1, max_length=4000)
    # Who gets it. Both empty = every chat. chat_ids wins over audiences.
    chat_ids: List[str] = []
    audiences: List[str] = []


class CreatePollIn(BaseModel):
    sender: str = "Royal Crest"
    question: str = Field(..., max_length=300)
    options: List[str]
    # False = record the poll in the app only (e.g. a staff-room vote).
    send_to_whatsapp: bool = True


class VoteIn(BaseModel):
    option_id: str
    voter_id: str


class ImportMessageItem(BaseModel):
    sender: str = "Unknown"
    text: Optional[str] = ""
    created_at: Optional[str] = None
    external_key: Optional[str] = None
    media_url: Optional[str] = None
    media_type: Optional[str] = None
    # "in" | "out" | None. wa_bridge.detect_direction() computes this at
    # scrape time (aria-label="You:" / tail-out vs the contact's own name /
    # tail-in — see wa_bridge.py). Optional and defaulted to None so older
    # wa_bridge builds that don't send it yet still validate; the import
    # route falls back to sniffing `sender` when it's absent.
    direction: Optional[str] = None


class ImportMessagesIn(BaseModel):
    messages: List[ImportMessageItem]


class SnapshotChatIn(BaseModel):
    id: str
    name: str
    wa_name: Optional[str] = None
    avatar_emoji: str = "?"
    profile_pic_url: Optional[str] = None


class SnapshotMessageIn(BaseModel):
    external_key: str
    wa_id: Optional[str] = None          # WhatsApp's DOM data-id
    direction: str = "in"                # "in" | "out"
    sender: str = "Unknown"
    kind: str = "text"                   # "text" | "image"
    text: Optional[str] = ""
    created_at: str                      # ISO, UTC; screen order preserved to the ms
    seq: Optional[int] = None
    time_source: Optional[str] = None


class ChatSnapshotIn(BaseModel):
    """One chat as wa_bridge read it (routers/chat_sync.py)."""
    model_config = ConfigDict(extra="ignore", populate_by_name=True)
    schema_name: str = Field("rca.chat_snapshot/1", alias="schema")
    chat: SnapshotChatIn
    scanned_at: Optional[str] = None
    window_start: Optional[str] = None
    window_end: Optional[str] = None
    reached_top: bool = False
    messages: List[SnapshotMessageIn] = []
    stats: Optional[dict] = None


class FeeLineIn(BaseModel):
    description: str = Field(..., min_length=1, max_length=120)
    amount: float = Field(..., ge=0)


class FeeDocumentIn(BaseModel):
    """Body the app POSTs to /api/fees (routers/fees.py)."""
    kind: str = Field("invoice", pattern="^(invoice|receipt)$")
    pupil_name: str = Field("", max_length=120)    # a receipt for an invoice may leave it out
    admission_no: Optional[str] = None
    class_name: Optional[str] = None
    parent_name: Optional[str] = None
    term: Optional[str] = None
    # Invoice: the fee lines. Receipt: optional; an empty list prints one
    # line "Payment received" for amount_paid.
    items: List[FeeLineIn] = []
    # Invoice: anything already paid. Receipt: the amount received.
    amount_paid: float = Field(0, ge=0)
    currency: Optional[str] = None
    due_date: Optional[str] = None             # YYYY-MM-DD, invoice only
    payment_method: Optional[str] = None       # receipt only
    payment_ref: Optional[str] = None          # receipt only
    invoice_id: Optional[str] = None           # receipt: the invoice it pays
    notes: Optional[str] = None
    # If set, the finished PDF is sent to this WhatsApp chat.
    chat_id: Optional[str] = None
    created_by: str = "Bursar"


class FeeErrorIn(BaseModel):
    error_message: str = ""


class ChatAudienceIn(BaseModel):
    audience: Optional[str] = Field(None, pattern="^(parent|staff|group|enquiry|other)$")


def poll_options_with_counts(conn, poll_id: str) -> list[dict]:
    rows = conn.execute(
        """
        SELECT po.id, po.text,
               (SELECT COUNT(*) FROM poll_votes pv
                WHERE pv.poll_id = po.poll_id AND pv.option_id = po.id) AS votes
        FROM poll_options po
        WHERE po.poll_id = ?
        ORDER BY po.rowid ASC
        """,
        (poll_id,),
    ).fetchall()
    return [dict(r) for r in rows]


def serialize_message(conn, row) -> dict:
    """Turn a `messages` row into the JSON shape clients expect (times at the
    display offset — see config.DISPLAY_TZ_OFFSET_MINUTES)."""
    from .config import DISPLAY_TZ_OFFSET_MINUTES
    from .wa_clean import to_offset_iso
    msg = dict(row)
    if msg.get("created_at"):
        msg["created_at"] = to_offset_iso(msg["created_at"], DISPLAY_TZ_OFFSET_MINUTES)
    if msg.get("kind") == "poll" and msg.get("poll_id"):
        msg["options"] = poll_options_with_counts(conn, msg["poll_id"])
    return msg
