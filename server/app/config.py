"""config.py — paths and school settings shared across the server.

Everything the school might change (fee items, terms, classes, payment
methods, bank details) lives in school.json next to this package, so the
bursar's office can edit it without touching Python. Defaults below are used
for anything school.json leaves out.
"""

import json
import os
from pathlib import Path

# server/app/config.py -> parents[1] == server/
BASE_DIR = Path(__file__).resolve().parents[1]

# On Hugging Face Spaces (and most hosts) only one folder survives a restart.
# Point RCA_DATA_DIR at it so the database and PDFs are kept; default is the
# server folder itself.
DATA_DIR = Path(os.environ.get("RCA_DATA_DIR", str(BASE_DIR / "data")))
DATA_DIR.mkdir(parents=True, exist_ok=True)

STATIC_DIR = BASE_DIR / "static"
MEDIA_DIR = DATA_DIR / "media"
MEDIA_DIR.mkdir(parents=True, exist_ok=True)

# Fee invoice and receipt PDFs rendered by pc-bridge/fee_worker.py.
FEES_DIR = DATA_DIR / "fees"
FEES_DIR.mkdir(parents=True, exist_ok=True)

# WhatsApp profile pictures uploaded by wa_bridge.py, one file per chat
# (routers/chats.py: upload_profile_pic). Served at /static/profile_pics/...
PROFILE_PICS_DIR = DATA_DIR / "profile_pics"
PROFILE_PICS_DIR.mkdir(parents=True, exist_ok=True)
PROFILE_PIC_MAX_BYTES = 2 * 1024 * 1024

DB_PATH = DATA_DIR / "royalcrest.db"

# Timestamps are STORED in UTC (so they sort) and SERVED at this offset, in
# minutes east of UTC (Zimbabwe = +120). The Android app displays the ISO
# digits it receives.
DISPLAY_TZ_OFFSET_MINUTES = int(os.environ.get("DISPLAY_TZ_OFFSET_MINUTES", "120"))

# The app, the bridge and the fee worker all call the API from outside a
# browser, so CORS only matters for the web dashboard on this same origin.
ALLOWED_ORIGINS = [o.strip() for o in os.environ.get("RCA_ALLOWED_ORIGINS", "*").split(",") if o.strip()]

MEDIA_KIND_BY_EXT = {
    "image": {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic"},
    "audio": {".mp3", ".wav", ".m4a", ".ogg", ".opus", ".aac"},
    "video": {".mp4", ".mov", ".webm", ".mkv", ".avi"},
    "document": {".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".csv"},
}

# ── School details ──────────────────────────────────────────────────────────
SCHOOL_DEFAULTS: dict = {
    "name": "Royal Crest Academy",
    "motto": "Science · Innovation · Skill",
    "address": "1226 Chinobva, Nyika, Masvingo",
    "phones": ["+263 714 404 090", "+263 773 602 547", "+263 785 979 583"],
    "website": "royalcrestzw.vercel.app",
    "currency": "USD",
    # Shown as quick picks in the app; any other text can be typed.
    "terms": ["Term 1 2027", "Term 2 2027", "Term 3 2027"],
    "classes": ["Form 1", "Form 2", "Form 3", "Form 4", "Lower 6", "Upper 6"],
    # Fee lines offered in the app. amount = null means the bursar types it;
    # fill in real amounts in school.json once the fee schedule is approved.
    "fee_items": [
        {"name": "Tuition", "amount": None},
        {"name": "Boarding", "amount": None},
        {"name": "Development levy", "amount": None},
        {"name": "Uniform", "amount": None},
        {"name": "Books and stationery", "amount": None},
        {"name": "Sports and clubs", "amount": None},
        {"name": "Examination fees", "amount": None},
        {"name": "Transport", "amount": None},
    ],
    "payment_methods": [
        "Cash at the bursar's office",
        "EcoCash / InnBucks",
        "Bank transfer",
    ],
    # Printed on invoices under "How to pay". Leave empty until confirmed.
    "bank_details": [],
    "invoice_due_days": 14,
    "invoice_notes": "Please quote the invoice number and the pupil's name with every payment. "
                     "Fees are payable before the start of term.",
    "receipt_notes": "Thank you. Please keep this receipt as proof of payment.",
}


def load_school() -> dict:
    """SCHOOL_DEFAULTS overlaid with school.json (if present)."""
    data = dict(SCHOOL_DEFAULTS)
    path = Path(os.environ.get("RCA_SCHOOL_JSON", str(BASE_DIR / "school.json")))
    if path.is_file():
        try:
            data.update(json.loads(path.read_text(encoding="utf-8")))
        except (OSError, ValueError) as e:
            print(f"[config] ignoring {path}: {e}")
    return data
