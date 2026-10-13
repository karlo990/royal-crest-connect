"""database.py — SQLite connection + schema + idempotent migrations."""

import sqlite3

from . import config


def get_db() -> sqlite3.Connection:
    conn = sqlite3.connect(config.DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def _migrate(conn: sqlite3.Connection) -> None:
    """Idempotent column additions. SQLite raises OperationalError if a column
    already exists, so that is swallowed. Add new entries here as the schema
    grows; never remove existing ones."""
    new_cols: list[str] = []
    for stmt in new_cols:
        try:
            conn.execute(stmt)
        except sqlite3.OperationalError:
            pass  # column already exists
    conn.commit()


def init_db() -> None:
    """Create tables if they don't exist yet, then run column migrations."""
    conn = sqlite3.connect(config.DB_PATH)
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS chats (
            id              TEXT PRIMARY KEY,
            name            TEXT NOT NULL,
            avatar_emoji    TEXT DEFAULT '01',
            created_at      TEXT NOT NULL,
            profile_pic_url TEXT,
            wa_name         TEXT,
            -- who this chat is, so a broadcast can target some chats only:
            -- parent | staff | group | enquiry | other  (NULL = not set yet)
            audience        TEXT
        );

        CREATE TABLE IF NOT EXISTS messages (
            id           TEXT PRIMARY KEY,
            chat_id      TEXT NOT NULL,
            sender       TEXT NOT NULL,
            kind         TEXT NOT NULL DEFAULT 'text',
            text         TEXT,
            poll_id      TEXT,
            created_at   TEXT NOT NULL,
            external_key TEXT,
            media_url    TEXT,
            media_type   TEXT,
            -- who originated the message: 'out' = the school, 'in' = WhatsApp
            direction    TEXT DEFAULT 'in',
            -- WhatsApp delivery state for outbound messages:
            --   pending | urgent | documents → queued for wa_bridge
            --   sent | error | superseded    → done
            wa_status    TEXT,
            -- WhatsApp's own message id (the DOM data-id)
            wa_msg_id    TEXT,
            FOREIGN KEY (chat_id) REFERENCES chats(id)
        );

        CREATE TABLE IF NOT EXISTS polls (
            id         TEXT PRIMARY KEY,
            chat_id    TEXT NOT NULL,
            question   TEXT NOT NULL,
            created_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS poll_options (
            id      TEXT PRIMARY KEY,
            poll_id TEXT NOT NULL,
            text    TEXT NOT NULL,
            FOREIGN KEY (poll_id) REFERENCES polls(id)
        );

        CREATE TABLE IF NOT EXISTS poll_votes (
            poll_id   TEXT NOT NULL,
            option_id TEXT NOT NULL,
            voter_id  TEXT NOT NULL,
            PRIMARY KEY (poll_id, voter_id)
        );

        -- Fee invoices and receipts (routers/fees.py). PDFs are rendered by
        -- pc-bridge/fee_worker.py: status pending → rendering → ready | error.
        CREATE TABLE IF NOT EXISTS fee_documents (
            id             TEXT PRIMARY KEY,
            kind           TEXT NOT NULL,             -- invoice | receipt
            doc_no         INTEGER NOT NULL,          -- running number per kind per year
            year           INTEGER NOT NULL,
            chat_id        TEXT,                      -- WhatsApp chat the PDF goes to
            pupil_name     TEXT NOT NULL,
            admission_no   TEXT,
            class_name     TEXT,
            parent_name    TEXT,
            term           TEXT,
            items          TEXT NOT NULL DEFAULT '[]', -- JSON [{description, amount}]
            total          REAL NOT NULL DEFAULT 0,
            amount_paid    REAL NOT NULL DEFAULT 0,   -- invoice: paid so far; receipt: received
            currency       TEXT NOT NULL DEFAULT 'USD',
            due_date       TEXT,                      -- invoice only, YYYY-MM-DD
            payment_method TEXT,                      -- receipt only
            payment_ref    TEXT,                      -- receipt only (EcoCash/bank ref)
            invoice_id     TEXT,                      -- receipt: the invoice it pays
            balance_after  REAL,                      -- receipt: invoice balance after it
            notes          TEXT,
            status         TEXT NOT NULL DEFAULT 'pending',
            pdf_url        TEXT,
            error_message  TEXT,
            created_by     TEXT,
            created_at     TEXT NOT NULL,
            updated_at     TEXT NOT NULL,
            UNIQUE (kind, year, doc_no),
            FOREIGN KEY (chat_id) REFERENCES chats(id)
        );
        """
    )
    conn.execute("CREATE INDEX IF NOT EXISTS idx_messages_chat_extkey ON messages(chat_id, external_key)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_messages_chat_wa_msg_id ON messages(chat_id, wa_msg_id)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_messages_chat_created ON messages(chat_id, created_at)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_fee_documents_status ON fee_documents(status, created_at)")
    conn.commit()
    _migrate(conn)
    conn.close()
