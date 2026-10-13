"""Broadcast targeting and WhatsApp polls answered by number."""
from app.wa_clean import chat_slug

G1 = chat_slug("Form 1 Parents")


def _chat(client, cid, name, audience=None):
    client.post("/api/chats", json={"id": cid, "name": name, "avatar_emoji": "?", "wa_name": name})
    if audience:
        assert client.patch(f"/api/chats/{cid}/audience", json={"audience": audience}).status_code == 200


def _setup(client):
    _chat(client, "p1", "Mrs Moyo", "parent")
    _chat(client, "p2", "Mr Dube", "parent")
    _chat(client, "s1", "Mr Chari", "staff")
    _chat(client, G1, "Form 1 Parents", "group")
    _chat(client, "e1", "+263 77 123 4567")          # unsaved number → enquiry


def test_broadcast_targets(client, db):
    _setup(client)
    assert {c["id"]: c["audience"] for c in client.get("/api/chats").json()}["e1"] == "enquiry"
    r = client.post("/api/broadcast", json={"text": "Schools open on 1 January 2027.", "audiences": ["parent", "group"]})
    assert r.json() == {"sent_to": 3, "skipped_duplicate": 0, "targets": 3}
    again = client.post("/api/broadcast", json={"text": "Schools  open on 1 January 2027.", "audiences": ["parent"]})
    assert again.json()["sent_to"] == 0 and again.json()["skipped_duplicate"] == 2      # no double sends
    assert client.post("/api/broadcast", json={"text": "Open day", "audiences": ["enquiry"]}).json()["sent_to"] == 1
    assert client.post("/api/broadcast", json={"text": "Staff meeting", "chat_ids": ["s1"]}).json()["sent_to"] == 1
    assert client.post("/api/broadcast", json={"text": "x", "chat_ids": ["zz"]}).status_code == 404
    assert client.post("/api/broadcast", json={"text": "x", "audiences": ["pupils"]}).status_code == 422
    assert client.post("/api/broadcast", json={"text": "Everyone"}).json()["sent_to"] == 5
    out = client.get("/api/outbox").json()
    assert {m["kind"] for m in out} == {"broadcast"} and len(out) == 10


def _snap(chat_id, name, messages):
    return {"schema": "rca.chat_snapshot/1", "chat": {"id": chat_id, "name": name, "wa_name": name},
            "window_start": "2026-01-01T00:00:00.000000+00:00", "window_end": "2027-12-31T00:00:00.000000+00:00",
            "messages": messages}


def _m(i, text, sender, at):
    return {"external_key": f"k{i}", "wa_id": f"false_{i}", "direction": "in", "sender": sender, "kind": "text",
            "text": text, "created_at": at, "seq": i}


def test_poll_goes_to_whatsapp_and_number_replies_are_votes(client, db):
    _setup(client)
    msg = client.post(f"/api/chats/{G1}/polls", json={"question": "Which day for the parents' meeting?",
                                                   "options": ["Friday", "Saturday"]}).json()
    assert msg["kind"] == "poll" and msg["wa_status"] == "pending"
    assert msg["text"].splitlines() == ["📊 *Which day for the parents' meeting?*", "1. Friday", "2. Saturday",
                                        "Reply with the number of your choice."]
    snap = _snap(G1, "Form 1 Parents", [
        _m(1, "2", "Mrs Moyo", "2099-01-01T08:00:00.000000+00:00"),
        _m(2, "Option 1", "Mr Dube", "2099-01-01T08:01:00.000000+00:00"),
        _m(3, "1.", "Mrs Moyo", "2099-01-01T08:02:00.000000+00:00"),      # changed her mind
        _m(4, "3", "Mrs Ncube", "2099-01-01T08:03:00.000000+00:00"),      # not an option
        _m(5, "2 please, Saturday is better", "Mr Banda", "2099-01-01T08:04:00.000000+00:00"),
    ])
    rep = client.post(f"/api/chats/{G1}/sync", json=snap).json()
    assert rep["poll_votes"] == 2
    polls = client.get("/api/polls").json()
    assert [(o["text"], o["votes"]) for o in polls[0]["options"]] == [("Friday", 2), ("Saturday", 0)]
    assert client.post(f"/api/chats/{G1}/sync", json=snap).json()["poll_votes"] == 0     # idempotent


def test_broadcast_poll_and_limits(client):
    _setup(client)
    r = client.post("/api/broadcast/poll", json={"question": "Bus to school?", "options": ["Yes", "No"],
                                                 "audiences": ["parent"]})
    assert r.json()["sent_to"] == 2
    assert client.post("/api/chats/p1/polls", json={"question": "Q", "options": ["only one"]}).status_code == 422
    assert client.post("/api/chats/p1/polls", json={"question": "Q", "options": [str(i) for i in range(10)]}).status_code == 422
    local = client.post("/api/chats/s1/polls", json={"question": "Q", "options": ["a", "b"],
                                                     "send_to_whatsapp": False}).json()
    assert local["wa_status"] is None
