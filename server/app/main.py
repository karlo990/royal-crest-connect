"""Royal Crest Connect — the school's messaging and fees server.

Parents' WhatsApp chats are mirrored here by pc-bridge/wa_bridge.py; staff
read and answer them in the Android app, send broadcasts and polls, and issue
fee invoices and receipts that pc-bridge/fee_worker.py turns into PDFs and
wa_bridge.py delivers on WhatsApp.
"""

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse
from fastapi.staticfiles import StaticFiles

from . import auth
from .config import ALLOWED_ORIGINS, FEES_DIR, MEDIA_DIR, PROFILE_PICS_DIR, STATIC_DIR
from .database import init_db
from .routers import broadcast, chat_sync, chats, customer_replies, fees, messages, outbox, polls, websocket

init_db()
app = FastAPI(title="Royal Crest Connect")
app.add_middleware(auth.RCAAuthMiddleware)
app.add_middleware(CORSMiddleware, allow_origins=ALLOWED_ORIGINS,
                   allow_credentials=False, allow_methods=["*"], allow_headers=["*"])
app.include_router(auth.router)

app.include_router(chat_sync.router)          # before chats/messages: /repair-names, /{id}/sync
app.include_router(chats.router)
app.include_router(messages.router)
app.include_router(polls.router)              # before broadcast: /api/broadcast/poll
app.include_router(broadcast.router)
app.include_router(websocket.router)
app.include_router(outbox.router)
app.include_router(customer_replies.router)   # POST /api/reply/{chat_id}
app.include_router(fees.router)


@app.get("/api/health")
def health():
    return {"status": "ok", "service": "royal-crest-connect", "key_required": bool(auth.API_KEY)}


@app.get("/favicon.ico", include_in_schema=False)
def favicon():
    return FileResponse(STATIC_DIR / "web" / "crest.webp", media_type="image/webp")


@app.get("/", response_class=HTMLResponse)
def index():
    return (STATIC_DIR / "index.html").read_text(encoding="utf-8")


# Most specific first: uploaded media, fee PDFs and profile pictures live in
# the data folder (RCA_DATA_DIR); everything else under /static ships with
# the code. All of /static except /static/web/ needs the API key (auth.py).
app.mount("/static/media", StaticFiles(directory=MEDIA_DIR), name="media")
app.mount("/static/fees", StaticFiles(directory=FEES_DIR), name="fees")
app.mount("/static/profile_pics", StaticFiles(directory=PROFILE_PICS_DIR), name="profile_pics")
app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")
