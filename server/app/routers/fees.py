"""routers/fees.py — school fee invoices and receipts.

Flow
====
1. The app (Fees tab) POSTs /api/fees with the pupil, term and fee lines
   (an invoice) or the amount received (a receipt). The row is numbered
   RCA-INV-2027-0001 / RCA-RCT-2027-0001 and stored with status 'pending'.
   A receipt that names an invoice adds its amount to that invoice's
   amount_paid and records the balance left after it.
2. pc-bridge/fee_worker.py polls GET /api/fees/worker/pending, claims one
   (PATCH /{id}/claim), prints it to PDF with Chromium and uploads it
   (POST /{id}/pdf).
3. The upload stores the PDF and, if the document has a chat_id, queues it as
   an outbound 'document' message (wa_status='documents'). wa_bridge.py sends
   it to that parent's WhatsApp ahead of bulk broadcast traffic.
"""

from __future__ import annotations

import json
import secrets
import uuid
from datetime import date, datetime, timedelta, timezone
from typing import Optional

from fastapi import APIRouter, File, HTTPException, Query, UploadFile
from pydantic import BaseModel

from .. import config
from ..database import get_db
from ..models import FeeDocumentIn, FeeErrorIn, serialize_message
from ..wa_clean import to_offset_iso
from ..ws_manager import manager

router = APIRouter(prefix="/api/fees", tags=["fees"])

PREFIX = {"invoice": "RCA-INV", "receipt": "RCA-RCT"}
LABEL = {"invoice": "Invoice", "receipt": "Receipt"}
# A document stuck in 'rendering' this long (worker died mid-render) is
# handed out again on the next poll.
STALE_RENDER_MINUTES = 10
MAX_PDF_BYTES = 10 * 1024 * 1024


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def doc_number(d: dict) -> str:
    return f"{PREFIX[d['kind']]}-{d['year']}-{int(d['doc_no']):04d}"


def file_name(d: dict) -> str:
    """What the parent sees on the document in WhatsApp."""
    return f"Royal_Crest_{LABEL[d['kind']]}_{doc_number(d)}.pdf"


def _row(row) -> dict:
    d = dict(row)
    d["number"] = doc_number(d)
    for k in ("created_at", "updated_at"):     # stored in UTC, served in local time like messages
        if d.get(k):
            d[k] = to_offset_iso(d[k], config.DISPLAY_TZ_OFFSET_MINUTES) or d[k]
    try:
        d["items"] = json.loads(d.get("items") or "[]")
    except ValueError:
        d["items"] = []
    if d["kind"] == "invoice":
        d["balance"] = round((d["total"] or 0) - (d["amount_paid"] or 0), 2)
    else:
        d["balance"] = d.get("balance_after")
    return d


def _get(conn, doc_id: str) -> dict:
    row = conn.execute(
        "SELECT f.*, c.name AS chat_name, i.doc_no AS inv_no, i.year AS inv_year "
        "FROM fee_documents f LEFT JOIN chats c ON c.id = f.chat_id "
        "LEFT JOIN fee_documents i ON i.id = f.invoice_id WHERE f.id=?", (doc_id,)).fetchone()
    if not row:
        raise HTTPException(404, "fee document not found")
    d = _row(row)
    if d.pop("inv_no", None) is not None:
        d["invoice_number"] = doc_number({"kind": "invoice", "year": d.pop("inv_year"), "doc_no": row["inv_no"]})
    else:
        d.pop("inv_year", None)
        d["invoice_number"] = None
    return d


def _clean(s: Optional[str]) -> Optional[str]:
    s = (s or "").strip()
    return s or None


def _valid_date(s: Optional[str]) -> Optional[str]:
    if not s:
        return None
    try:
        return date.fromisoformat(s[:10]).isoformat()
    except ValueError:
        raise HTTPException(422, f"not a date (YYYY-MM-DD): {s}")


# ─────────────────────────── settings for the app's form ───────────────────────

@router.get("/catalogue")
def catalogue():
    """School details, fee lines, terms, classes and payment methods for the
    app's dropdowns (and the fee worker's letterhead). Edited in school.json."""
    s = config.load_school()
    return {k: s.get(k) for k in ("name", "motto", "address", "phones", "website", "currency",
                                  "terms", "classes", "fee_items", "payment_methods",
                                  "bank_details", "invoice_due_days", "invoice_notes",
                                  "receipt_notes")}


# ─────────────────────────── create / list ─────────────────────────────────────

@router.post("")
def create_fee_document(body: FeeDocumentIn):
    school = config.load_school()
    items = [{"description": i.description.strip(), "amount": round(i.amount, 2)} for i in body.items]
    paid = round(body.amount_paid, 2)
    now = _now()
    year = int(now[:4])

    conn = get_db()
    try:
        conn.execute("BEGIN IMMEDIATE")      # numbering + invoice update in one step
        if body.chat_id and not conn.execute("SELECT 1 FROM chats WHERE id=?", (body.chat_id,)).fetchone():
            raise HTTPException(404, "chat not found")

        invoice = None
        balance_after = None
        if body.kind == "invoice":
            if not items:
                raise HTTPException(422, "an invoice needs at least one fee line")
            total = round(sum(i["amount"] for i in items), 2)
            if total <= 0:
                raise HTTPException(422, "the fee lines add up to 0")
            if paid > total:
                raise HTTPException(422, "amount already paid is more than the total")
            due = _valid_date(body.due_date) or (
                date.today() + timedelta(days=int(school.get("invoice_due_days") or 14))).isoformat()
        else:
            if paid <= 0:
                raise HTTPException(422, "a receipt needs the amount received")
            total = round(sum(i["amount"] for i in items), 2) if items else paid
            due = None
            if body.invoice_id:
                invoice = conn.execute("SELECT * FROM fee_documents WHERE id=? AND kind='invoice'",
                                       (body.invoice_id,)).fetchone()
                if not invoice:
                    raise HTTPException(404, "invoice not found")
                outstanding = round(invoice["total"] - invoice["amount_paid"], 2)
                if paid > outstanding + 0.005:
                    raise HTTPException(422, f"that is more than the {outstanding:.2f} still owed on this invoice")
                balance_after = round(outstanding - paid, 2)

        # A receipt for an invoice inherits the pupil's details it leaves out.
        def pick(field: str) -> Optional[str]:
            v = _clean(getattr(body, field))
            return v if v or invoice is None else invoice[field]

        pupil = _clean(body.pupil_name) or (invoice["pupil_name"] if invoice else None)
        if not pupil:
            raise HTTPException(422, "pupil name is required")
        chat_id = body.chat_id or (invoice["chat_id"] if invoice else None)
        currency = (_clean(body.currency) or (invoice["currency"] if invoice else None)
                    or school.get("currency") or "USD").upper()

        doc_no = conn.execute("SELECT COALESCE(MAX(doc_no), 0) + 1 FROM fee_documents WHERE kind=? AND year=?",
                              (body.kind, year)).fetchone()[0]
        doc_id = str(uuid.uuid4())
        conn.execute(
            "INSERT INTO fee_documents (id, kind, doc_no, year, chat_id, pupil_name, admission_no, class_name, "
            "parent_name, term, items, total, amount_paid, currency, due_date, payment_method, payment_ref, "
            "invoice_id, balance_after, notes, status, created_by, created_at, updated_at) "
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'pending',?,?,?)",
            (doc_id, body.kind, doc_no, year, chat_id, pupil, pick("admission_no"), pick("class_name"),
             pick("parent_name"), pick("term"), json.dumps(items), total, paid, currency, due,
             _clean(body.payment_method), _clean(body.payment_ref), invoice["id"] if invoice else None,
             balance_after, _clean(body.notes), body.created_by.strip() or "Bursar", now, now),
        )
        if invoice is not None:
            conn.execute("UPDATE fee_documents SET amount_paid = ROUND(amount_paid + ?, 2), updated_at=? WHERE id=?",
                         (paid, now, invoice["id"]))
        conn.commit()
        return _get(conn, doc_id)
    except BaseException:
        conn.rollback()
        raise
    finally:
        conn.close()


@router.get("")
def list_fee_documents(kind: Optional[str] = Query(None, pattern="^(invoice|receipt)$"),
                       chat_id: Optional[str] = None, q: Optional[str] = None,
                       limit: int = Query(200, ge=1, le=1000)):
    where, args = [], []
    if kind:
        where.append("f.kind=?"); args.append(kind)
    if chat_id:
        where.append("f.chat_id=?"); args.append(chat_id)
    if q and q.strip():
        like = f"%{q.strip().lower()}%"
        where.append("(lower(f.pupil_name) LIKE ? OR lower(COALESCE(f.admission_no,'')) LIKE ? "
                     "OR lower(COALESCE(f.parent_name,'')) LIKE ?)")
        args += [like, like, like]
    sql = ("SELECT f.*, c.name AS chat_name FROM fee_documents f LEFT JOIN chats c ON c.id = f.chat_id"
           + (" WHERE " + " AND ".join(where) if where else "")
           + " ORDER BY f.created_at DESC, f.rowid DESC LIMIT ?")
    conn = get_db()
    rows = conn.execute(sql, (*args, limit)).fetchall()
    conn.close()
    return [_row(r) for r in rows]


@router.get("/balances")
def balances(term: Optional[str] = None):
    """Outstanding balance per pupil across their invoices (optionally one
    term), largest first. Pupils are matched by admission number, else name."""
    conn = get_db()
    rows = conn.execute(
        "SELECT COALESCE(NULLIF(admission_no,''), lower(pupil_name)) AS pupil_key, "
        "MAX(pupil_name) AS pupil_name, MAX(admission_no) AS admission_no, MAX(class_name) AS class_name, "
        "MAX(chat_id) AS chat_id, currency, COUNT(*) AS invoices, ROUND(SUM(total), 2) AS invoiced, "
        "ROUND(SUM(amount_paid), 2) AS paid, ROUND(SUM(total - amount_paid), 2) AS balance "
        "FROM fee_documents WHERE kind='invoice'" + (" AND term=?" if term else "") +
        " GROUP BY pupil_key, currency ORDER BY balance DESC, pupil_name",
        (term,) if term else ()).fetchall()
    conn.close()
    return [dict(r) for r in rows]


# ─────────────────────────── worker hand-off ───────────────────────────────────
# Declared before "/{doc_id}" so "worker" is not taken for a document id.

@router.get("/worker/pending")
def worker_pending():
    """Polled by fee_worker.py: documents waiting for their PDF, oldest first,
    plus any whose render was claimed but never finished."""
    conn = get_db()
    stale = (datetime.now(timezone.utc) - timedelta(minutes=STALE_RENDER_MINUTES)).isoformat()
    conn.execute("UPDATE fee_documents SET status='pending' WHERE status='rendering' AND updated_at < ?", (stale,))
    conn.commit()
    ids = [r["id"] for r in conn.execute(
        "SELECT id FROM fee_documents WHERE status='pending' ORDER BY created_at ASC, rowid ASC")]
    out = [_get(conn, i) for i in ids]
    conn.close()
    return out


@router.get("/{doc_id}")
def get_fee_document(doc_id: str):
    conn = get_db()
    try:
        return _get(conn, doc_id)
    finally:
        conn.close()


@router.patch("/{doc_id}/claim")
def claim(doc_id: str):
    """pending → rendering, atomically, so two workers never render one twice."""
    conn = get_db()
    cur = conn.execute("UPDATE fee_documents SET status='rendering', updated_at=? WHERE id=? AND status='pending'",
                       (_now(), doc_id))
    conn.commit()
    try:
        return {"claimed": cur.rowcount > 0, "document": _get(conn, doc_id)}
    finally:
        conn.close()


def _queue_pdf(conn, d: dict, chat_id: str, sender: str) -> dict:
    """Queue the document's PDF to a chat (documents queue, never superseded)."""
    msg_id = str(uuid.uuid4())
    who = f" — {d['pupil_name']}" if d.get("pupil_name") else ""
    caption = f"{LABEL[d['kind']]} {d['number']}{who}"
    conn.execute(
        "INSERT INTO messages (id, chat_id, sender, kind, text, poll_id, created_at, media_url, media_type, "
        "direction, wa_status) VALUES (?, ?, ?, 'document', ?, NULL, ?, ?, 'document', 'out', 'documents')",
        (msg_id, chat_id, sender or "Bursar", caption, _now(), d["pdf_url"]))
    conn.commit()
    return serialize_message(conn, conn.execute("SELECT * FROM messages WHERE id=?", (msg_id,)).fetchone())


@router.post("/{doc_id}/pdf")
async def upload_pdf(doc_id: str, file: UploadFile = File(...)):
    data = await file.read(MAX_PDF_BYTES + 1)
    if not data.startswith(b"%PDF"):
        raise HTTPException(415, "not a PDF")
    if len(data) > MAX_PDF_BYTES:
        raise HTTPException(413, "PDF is larger than 10 MB")
    conn = get_db()
    try:
        d = _get(conn, doc_id)
        # A random folder per document, so a PDF can't be found by guessing
        # the next number.
        token = secrets.token_urlsafe(12)
        folder = config.FEES_DIR / token
        folder.mkdir(parents=True, exist_ok=True)
        (folder / file_name(d)).write_bytes(data)
        pdf_url = f"/static/fees/{token}/{file_name(d)}"
        conn.execute("UPDATE fee_documents SET status='ready', pdf_url=?, error_message=NULL, updated_at=? "
                     "WHERE id=?", (pdf_url, _now(), doc_id))
        conn.commit()
        d = _get(conn, doc_id)
        msg = _queue_pdf(conn, d, d["chat_id"], d.get("created_by")) if d.get("chat_id") else None
    finally:
        conn.close()
    if msg:
        await manager.broadcast(d["chat_id"], {"type": "message", "data": msg})
    return d


class SendIn(BaseModel):
    chat_id: Optional[str] = None
    sender: str = "Bursar"


@router.post("/{doc_id}/send")
async def send_again(doc_id: str, body: SendIn):
    """Send a finished PDF (again) to its chat, or to another chat."""
    conn = get_db()
    try:
        d = _get(conn, doc_id)
        if d["status"] != "ready" or not d.get("pdf_url"):
            raise HTTPException(409, "the PDF is not ready yet")
        chat_id = body.chat_id or d.get("chat_id")
        if not chat_id or not conn.execute("SELECT 1 FROM chats WHERE id=?", (chat_id,)).fetchone():
            raise HTTPException(404, "pick a chat to send it to")
        if not d.get("chat_id"):
            conn.execute("UPDATE fee_documents SET chat_id=? WHERE id=?", (chat_id, doc_id))
        msg = _queue_pdf(conn, d, chat_id, body.sender)
    finally:
        conn.close()
    await manager.broadcast(chat_id, {"type": "message", "data": msg})
    return msg


@router.patch("/{doc_id}/error")
def mark_error(doc_id: str, body: FeeErrorIn):
    conn = get_db()
    conn.execute("UPDATE fee_documents SET status='error', error_message=?, updated_at=? WHERE id=?",
                 (body.error_message[:500], _now(), doc_id))
    conn.commit()
    try:
        return _get(conn, doc_id)
    finally:
        conn.close()


@router.post("/{doc_id}/retry")
def retry(doc_id: str):
    """A failed render (or one stuck mid-render) goes back in the queue."""
    conn = get_db()
    cur = conn.execute("UPDATE fee_documents SET status='pending', error_message=NULL, updated_at=? "
                       "WHERE id=? AND status IN ('error', 'rendering')", (_now(), doc_id))
    conn.commit()
    try:
        d = _get(conn, doc_id)
    finally:
        conn.close()
    if not cur.rowcount:
        raise HTTPException(409, f"this document is {d['status']}; only failed renders can be retried")
    return d
