"""The API key guards the API, the PDFs and the websocket."""
import pytest
from starlette.websockets import WebSocketDisconnect


def test_api_needs_the_key(anon_client):
    assert anon_client.get("/api/health").status_code == 200          # open, so the app can test an address
    assert anon_client.get("/api/health").json()["key_required"] is True
    assert anon_client.get("/api/chats").status_code == 401
    assert anon_client.get("/api/chats", headers={"Authorization": "Bearer wrong"}).status_code == 401
    assert anon_client.get("/api/chats", headers={"Authorization": "Bearer test-key"}).status_code == 200
    assert anon_client.get("/api/chats", headers={"X-RCA-Key": "test-key"}).status_code == 200
    assert anon_client.get("/api/chats?key=test-key").status_code == 200
    assert anon_client.get("/static/fees/abc/x.pdf").status_code == 401
    assert anon_client.get("/api/auth/check", headers={"Authorization": "Bearer test-key"}).json()["ok"]


def test_websocket_needs_the_key(anon_client):
    with pytest.raises(WebSocketDisconnect):
        with anon_client.websocket_connect("/ws/c1") as ws:
            ws.receive_text()
    with anon_client.websocket_connect("/ws/c1?key=test-key"):
        pass


def test_dashboard_login(anon_client):
    page = anon_client.get("/")
    assert page.status_code == 200 and "Staff sign-in" in page.text
    bad = anon_client.post("/login", data={"password": "nope", "next": "/"}, follow_redirects=False)
    assert bad.status_code == 401
    ok = anon_client.post("/login", data={"password": "test-key", "next": "//evil.example"}, follow_redirects=False)
    assert ok.status_code == 303 and ok.headers["location"] == "/"
    cookie = ok.headers["set-cookie"].split(";")[0]
    assert anon_client.get("/api/chats", headers={"Cookie": cookie}).status_code == 200
