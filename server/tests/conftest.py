import importlib
import os
import sys
import tempfile
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

# Before anything imports app.config: keep test files out of the real data
# folder, and run with an API key the way the server is deployed.
os.environ.setdefault("RCA_DATA_DIR", tempfile.mkdtemp(prefix="rca-test-"))
os.environ["RCA_API_KEY"] = "test-key"
os.environ["RCA_SCHOOL_JSON"] = str(ROOT / "tests" / "no-school.json")
API_KEY = "test-key"


@pytest.fixture()
def anon_client(tmp_path, monkeypatch):
    """Fresh app + empty SQLite DB per test, no API key sent."""
    import app.config as config
    monkeypatch.setattr(config, "DB_PATH", tmp_path / "royalcrest.db")
    for name in ("profile_pics", "fees", "media"):
        (tmp_path / name).mkdir()
    monkeypatch.setattr(config, "PROFILE_PICS_DIR", tmp_path / "profile_pics")
    monkeypatch.setattr(config, "FEES_DIR", tmp_path / "fees")
    monkeypatch.setattr(config, "MEDIA_DIR", tmp_path / "media")
    for mod in [m for m in list(sys.modules) if m in ("app.main", "app.auth") or m.startswith("app.routers")]:
        del sys.modules[mod]
    main = importlib.import_module("app.main")   # runs init_db() against the temp DB
    from fastapi.testclient import TestClient
    with TestClient(main.app) as c:
        yield c


@pytest.fixture()
def client(anon_client):
    anon_client.headers["Authorization"] = f"Bearer {API_KEY}"
    return anon_client


@pytest.fixture()
def db(client):
    from app.database import get_db
    conn = get_db()
    yield conn
    conn.close()
