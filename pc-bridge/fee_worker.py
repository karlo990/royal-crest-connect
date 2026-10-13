"""fee_worker.py — turns Royal Crest fee invoices and receipts into PDFs.

Runs on the same PC as wa_bridge.py (rca_supervisor.py starts both). It polls
the server for documents created in the app's Fees tab, prints each one on the
school letterhead to an A4 PDF with Chromium (installed with Playwright for
wa_bridge.py already) and uploads it. The server then queues the PDF to the
parent's WhatsApp chat and wa_bridge.py sends it.

RUN:
    python fee_worker.py              # poll forever, every FEE_POLL_SEC (5 s)
    python fee_worker.py --sample     # write sample_invoice.pdf and
                                      # sample_receipt.pdf here, no server needed
"""

from __future__ import annotations

import base64
import html
import os
import sys
import tempfile
import time
from datetime import datetime
from pathlib import Path

from local_config import FEE_POLL_SEC, SERVER_URL, print_active_config
from rca_client import HTTP_TIMEOUT, UPLOAD_TIMEOUT, get_session

for _stream in (sys.stdout, sys.stderr):      # PowerShell's cp1252 console vs ✓/✗
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

ASSETS = Path(__file__).with_name("assets")
NAVY, GOLD, PANEL, INK, MUTED = "#0A1F5C", "#F2B705", "#EEF2FA", "#16204a", "#5b6485"

# Used when the server can't be reached for its catalogue (e.g. --sample).
DEFAULT_SCHOOL = {
    "name": "Royal Crest Academy",
    "motto": "Science · Innovation · Skill",
    "address": "1226 Chinobva, Nyika, Masvingo",
    "phones": ["+263 714 404 090", "+263 773 602 547", "+263 785 979 583"],
    "website": "royalcrestzw.vercel.app",
    "payment_methods": ["Cash at the bursar's office", "EcoCash / InnBucks", "Bank transfer"],
    "bank_details": [],
    "invoice_notes": "Please quote the invoice number and the pupil's name with every payment. "
                     "Fees are payable before the start of term.",
    "receipt_notes": "Thank you. Please keep this receipt as proof of payment.",
}


def _data_uri(name: str, mime: str) -> str:
    return f"data:{mime};base64,{base64.b64encode((ASSETS / name).read_bytes()).decode()}"


def _date(d: str | None) -> str:
    try:
        return datetime.fromisoformat(str(d)[:10]).strftime("%d %B %Y").lstrip("0")
    except (TypeError, ValueError):
        return "—"


def _money(v) -> str:
    return f"{float(v or 0):,.2f}"


def build_html(doc: dict, school: dict) -> str:
    """The document as a printable A4 page on the school letterhead."""
    e = lambda v: html.escape(str(v)) if v not in (None, "") else "—"  # noqa: E731
    kind = doc.get("kind", "invoice")
    receipt = kind == "receipt"
    cur = e(doc.get("currency") or "USD")
    items = doc.get("items") or []
    if receipt and not items:
        items = [{"description": f"Payment received{' — ' + doc['term'] if doc.get('term') else ''}",
                  "amount": doc.get("amount_paid") or 0}]
    total = float(doc.get("total") or 0)
    paid = float(doc.get("amount_paid") or 0)
    balance = doc.get("balance")
    if balance is None and not receipt:
        balance = round(total - paid, 2)

    if receipt:
        badge, badge_cls = "PAID", "gold"
    elif balance is not None and balance <= 0:
        badge, badge_cls = "PAID IN FULL", "gold"
    elif paid > 0:
        badge, badge_cls = "PART PAID", "navy"
    else:
        badge, badge_cls = "PAYMENT DUE", "navy"

    pupil_rows = [("Pupil", doc.get("pupil_name")), ("Admission no.", doc.get("admission_no")),
                  ("Class", doc.get("class_name")), ("Term", doc.get("term")),
                  ("Parent / guardian", doc.get("parent_name"))]
    pupil_html = "".join(f"<tr><td>{k}</td><td>{e(v)}</td></tr>" for k, v in pupil_rows if v)

    if receipt:
        meta = [("Receipt date", _date(doc.get("created_at"))), ("Paid by", doc.get("payment_method")),
                ("Reference", doc.get("payment_ref")), ("For invoice", doc.get("invoice_number"))]
    else:
        meta = [("Issue date", _date(doc.get("created_at"))), ("Due date", _date(doc.get("due_date"))),
                ("Reference", doc.get("number"))]
    meta_html = "".join(f"<tr><td>{k}</td><td>{e(v)}</td></tr>" for k, v in meta if v)

    lines = "".join(
        f'<tr><td class="n">{i}</td><td>{e(it.get("description"))}</td>'
        f'<td class="r">{_money(it.get("amount"))}</td></tr>' for i, it in enumerate(items, 1))

    if receipt:
        totals = f'<tr class="hl"><td>Amount received</td><td>{cur} {_money(paid)}</td></tr>'
        if balance is not None:
            totals += f'<tr><td>Balance still owed on that invoice</td><td>{cur} {_money(balance)}</td></tr>'
    else:
        totals = f'<tr><td>Total fees</td><td>{cur} {_money(total)}</td></tr>'
        totals += f'<tr><td>Already paid</td><td>{cur} {_money(paid)}</td></tr>'
        totals += f'<tr class="hl"><td>Balance due</td><td>{cur} {_money(balance)}</td></tr>'

    if receipt:
        side = f'<div class="label">Note</div><p class="muted">{e(doc.get("notes") or school.get("receipt_notes"))}</p>'
    else:
        methods = "".join(f"<li>{e(m)}</li>" for m in school.get("payment_methods") or [])
        bank = "".join(f"<li>{e(b)}</li>" for b in school.get("bank_details") or [])
        side = (f'<div class="cols"><div><div class="label">How to pay</div><ul>{methods}{bank}</ul></div>'
                f'<div><div class="label">Note</div><p class="muted">{e(doc.get("notes") or school.get("invoice_notes"))}'
                f'</p></div></div>')

    phones = " · ".join(e(p) for p in school.get("phones") or [])
    title = "Receipt" if receipt else "Fee invoice"
    return f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><title>{title} {e(doc.get('number'))}</title><style>
  @font-face {{ font-family: "Newsreader"; src: url({_data_uri("newsreader-latin.woff2", "font/woff2")}) format("woff2"); font-weight: 200 800; }}
  @font-face {{ font-family: "Jakarta"; src: url({_data_uri("plus-jakarta-sans-latin.woff2", "font/woff2")}) format("woff2"); font-weight: 200 800; }}
  @page {{ size: A4; margin: 0; }}
  * {{ box-sizing: border-box; }}
  body {{ margin: 0; font-family: "Jakarta", Arial, sans-serif; color: {INK}; font-size: 9.6pt; line-height: 1.45; }}
  .page {{ min-height: 297mm; display: flex; flex-direction: column; }}
  header {{ background: {NAVY}; color: #fff; padding: 12mm 15mm 10mm; display: flex; align-items: center; gap: 6mm;
            border-bottom: 2.2mm solid {GOLD}; }}
  header img {{ width: 21mm; height: auto; }}
  .school {{ flex: 1; }}
  .school h1 {{ font-family: "Newsreader", Georgia, serif; font-weight: 600; font-size: 22pt; margin: 0; letter-spacing: .2pt; }}
  .school .motto {{ color: {GOLD}; font-size: 7.6pt; letter-spacing: 2.4pt; text-transform: uppercase; margin-top: 1mm; font-weight: 600; }}
  .school .addr {{ font-size: 8pt; opacity: .85; margin-top: 2mm; }}
  .doc {{ text-align: right; }}
  .doc h2 {{ font-family: "Newsreader", Georgia, serif; font-weight: 500; font-size: 20pt; margin: 0; }}
  .doc .no {{ font-size: 9pt; opacity: .9; margin: 1mm 0 3mm; letter-spacing: .4pt; }}
  .badge {{ display: inline-block; font-weight: 800; font-size: 7.4pt; letter-spacing: 1.4pt; padding: 1.4mm 3.4mm; border-radius: 1mm; }}
  .badge.gold {{ background: {GOLD}; color: {NAVY}; }}
  .badge.navy {{ background: #fff; color: {NAVY}; }}
  main {{ padding: 9mm 15mm 0; flex: 1; }}
  .label {{ font-size: 7.2pt; font-weight: 800; letter-spacing: 1.6pt; text-transform: uppercase; color: {NAVY}; margin-bottom: 2mm; }}
  .cols {{ display: flex; gap: 10mm; }} .cols > div {{ flex: 1; }}
  table.kv {{ border-collapse: collapse; width: 100%; }}
  table.kv td {{ padding: 1mm 0; vertical-align: top; }}
  table.kv td:first-child {{ color: {MUTED}; width: 34mm; }}
  table.kv td:last-child {{ font-weight: 600; }}
  .card {{ background: {PANEL}; border-radius: 2mm; padding: 5mm 6mm; }}
  table.items {{ width: 100%; border-collapse: collapse; margin-top: 8mm; }}
  table.items th {{ text-align: left; font-size: 7.2pt; letter-spacing: 1.4pt; text-transform: uppercase; color: {NAVY};
                    border-bottom: .5mm solid {NAVY}; padding: 2mm 2mm; }}
  table.items td {{ padding: 2.6mm 2mm; border-bottom: .25mm solid #d5dbec; }}
  table.items .n {{ width: 9mm; color: {MUTED}; }} table.items .r {{ text-align: right; width: 38mm; font-variant-numeric: tabular-nums; }}
  table.totals {{ margin: 5mm 0 0 auto; width: 100mm; border-collapse: collapse; font-variant-numeric: tabular-nums; }}
  table.totals td {{ padding: 2mm 2mm; }} table.totals td:last-child {{ text-align: right; font-weight: 600; white-space: nowrap; }}
  table.totals tr.hl td {{ background: {NAVY}; color: #fff; font-weight: 700; font-size: 11pt; }}
  table.totals tr.hl td:last-child {{ color: {GOLD}; }}
  .after {{ margin-top: 10mm; }}
  ul {{ margin: 0; padding-left: 4mm; }} li {{ margin: .6mm 0; }}
  .muted {{ color: {MUTED}; margin: 0; }}
  footer {{ margin-top: 10mm; padding: 5mm 15mm 9mm; border-top: .25mm solid #d5dbec; display: flex; justify-content: space-between;
            font-size: 7.6pt; color: {MUTED}; }}
  footer b {{ color: {NAVY}; }}
</style></head><body><div class="page">
  <header>
    <img src="{_data_uri("crest.webp", "image/webp")}" alt="">
    <div class="school"><h1>{e(school.get("name"))}</h1><div class="motto">{e(school.get("motto"))}</div>
      <div class="addr">{e(school.get("address"))}<br>{phones}</div></div>
    <div class="doc"><h2>{"Receipt" if receipt else "Invoice"}</h2><div class="no">No. {e(doc.get("number"))}</div>
      <span class="badge {badge_cls}">{badge}</span></div>
  </header>
  <main>
    <div class="cols">
      <div class="card"><div class="label">{"Received from" if receipt else "Billed to"}</div><table class="kv">{pupil_html}</table></div>
      <div><div class="label">Details</div><table class="kv">{meta_html}</table></div>
    </div>
    <table class="items"><tr><th class="n">#</th><th>Description</th><th class="r">Amount ({cur})</th></tr>{lines}</table>
    <table class="totals">{totals}</table>
    <div class="after">{side}</div>
  </main>
  <footer><span><b>{e(school.get("name"))}</b> · {e(school.get("website"))}</span>
    <span>Issued by {e(doc.get("created_by") or "the bursar")} · computer-generated, valid without a signature</span></footer>
</div></body></html>"""


def render_pdf(doc: dict, school: dict, pdf_path: Path) -> Path:
    """Print the page to an A4 PDF with Playwright's Chromium."""
    from playwright.sync_api import sync_playwright
    exe = os.environ.get("FEE_CHROMIUM_PATH")
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, **({"executable_path": exe} if exe else {}))
        try:
            page = browser.new_page()
            page.set_content(build_html(doc, school), wait_until="load")
            page.evaluate("document.fonts.ready")
            page.pdf(path=str(pdf_path), format="A4", print_background=True, prefer_css_page_size=True)
        finally:
            browser.close()
    if not pdf_path.exists() or pdf_path.stat().st_size == 0:
        raise RuntimeError("Chromium produced no PDF")
    return pdf_path


# ─────────────────────────────── server calls ──────────────────────────────────

def fetch_school() -> dict:
    try:
        r = get_session().get(f"{SERVER_URL}/api/fees/catalogue", timeout=HTTP_TIMEOUT)
        r.raise_for_status()
        return {**DEFAULT_SCHOOL, **{k: v for k, v in r.json().items() if v is not None}}
    except Exception as e:
        print(f"[fee_worker] using built-in school details ({e})")
        return dict(DEFAULT_SCHOOL)


def fetch_pending() -> list[dict]:
    r = get_session().get(f"{SERVER_URL}/api/fees/worker/pending", timeout=HTTP_TIMEOUT)
    r.raise_for_status()
    return r.json()


def claim(doc_id: str) -> bool:
    r = get_session().patch(f"{SERVER_URL}/api/fees/{doc_id}/claim", timeout=HTTP_TIMEOUT)
    r.raise_for_status()
    return bool(r.json().get("claimed"))


def upload_pdf(doc_id: str, pdf_path: Path) -> None:
    with open(pdf_path, "rb") as f:
        r = get_session().post(f"{SERVER_URL}/api/fees/{doc_id}/pdf",
                               files={"file": (pdf_path.name, f, "application/pdf")}, timeout=UPLOAD_TIMEOUT)
    r.raise_for_status()


def mark_error(doc_id: str, message: str) -> None:
    try:
        get_session().patch(f"{SERVER_URL}/api/fees/{doc_id}/error",
                            json={"error_message": message[:500]}, timeout=HTTP_TIMEOUT)
    except Exception as e:
        print(f"[fee_worker] also failed to report the error: {e}")


def process_one(doc: dict, school: dict) -> None:
    doc_id = doc["id"]
    if not claim(doc_id):
        print(f"[fee_worker] {doc.get('number')} already taken by another worker, skipping")
        return
    with tempfile.TemporaryDirectory() as tmp:
        try:
            pdf = render_pdf(doc, school, Path(tmp) / f"{doc.get('number') or doc_id}.pdf")
            upload_pdf(doc_id, pdf)
            print(f"[fee_worker] ✓ {doc.get('number')} ({doc.get('pupil_name')}) ready")
        except Exception as e:
            print(f"[fee_worker] ✗ {doc.get('number')} failed: {e}")
            mark_error(doc_id, str(e))


SAMPLES = [
    {"id": "sample-invoice", "kind": "invoice", "number": "RCA-INV-2027-0001", "created_at": "2026-11-20T08:00:00+00:00",
     "due_date": "2026-12-04", "pupil_name": "Tariro Moyo", "admission_no": "RCA-0001", "class_name": "Form 1",
     "term": "Term 1 2027", "parent_name": "Mrs R. Moyo", "currency": "USD", "created_by": "Bursar",
     "items": [{"description": "Tuition", "amount": 450}, {"description": "Boarding", "amount": 300},
               {"description": "Development levy", "amount": 50}, {"description": "Uniform", "amount": 85}],
     "total": 885, "amount_paid": 200, "balance": 685},
    {"id": "sample-receipt", "kind": "receipt", "number": "RCA-RCT-2027-0001", "created_at": "2026-11-27T10:30:00+00:00",
     "pupil_name": "Tariro Moyo", "admission_no": "RCA-0001", "class_name": "Form 1", "term": "Term 1 2027",
     "parent_name": "Mrs R. Moyo", "currency": "USD", "created_by": "Bursar", "items": [],
     "total": 300, "amount_paid": 300, "payment_method": "EcoCash / InnBucks", "payment_ref": "MP261127.1030.A12345",
     "invoice_number": "RCA-INV-2027-0001", "balance": 385},
]


def write_samples(out_dir: Path = Path(".")) -> list[Path]:
    """`python fee_worker.py --sample`: renders a sample invoice and receipt
    (with made-up amounts) to check Chromium and the layout."""
    out = []
    for doc in SAMPLES:
        path = out_dir / f"sample_{doc['kind']}.pdf"
        render_pdf(doc, DEFAULT_SCHOOL, path)
        print(f"[fee_worker] wrote {path.resolve()}")
        out.append(path)
    return out


def main() -> None:
    if "--sample" in sys.argv:
        write_samples()
        return
    print_active_config()
    print(f"[fee_worker] polling {SERVER_URL} every {FEE_POLL_SEC}s")
    school, school_at = fetch_school(), time.time()
    while True:
        try:
            pending = fetch_pending()
            if pending and time.time() - school_at > 300:     # pick up school.json edits
                school, school_at = fetch_school(), time.time()
            for doc in pending:
                process_one(doc, school)
        except Exception as e:
            print(f"[fee_worker] poll error: {e}")
        time.sleep(FEE_POLL_SEC)


if __name__ == "__main__":
    main()
