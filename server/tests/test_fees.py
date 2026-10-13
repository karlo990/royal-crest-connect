"""Fee invoices and receipts: numbering, totals, receipts against invoices,
the worker hand-off and WhatsApp delivery of the PDF."""


def _chat(client, cid="c1", name="Mrs Moyo"):
    client.post("/api/chats", json={"id": cid, "name": name, "avatar_emoji": "MM", "wa_name": name})


def _invoice(client, **kw):
    body = {"kind": "invoice", "pupil_name": "Tariro Moyo", "admission_no": "RCA-0001", "class_name": "Form 1",
            "term": "Term 1 2027", "parent_name": "Mrs R. Moyo",
            "items": [{"description": "Tuition", "amount": 450}, {"description": "Boarding", "amount": 300.5}]}
    body.update(kw)
    return client.post("/api/fees", json=body)


def test_invoices_and_receipts_are_numbered_separately(client):
    a, b = _invoice(client).json(), _invoice(client).json()
    year = a["created_at"][:4]
    assert (a["number"], b["number"]) == (f"RCA-INV-{year}-0001", f"RCA-INV-{year}-0002")
    assert a["total"] == 750.5 and a["balance"] == 750.5 and a["status"] == "pending"
    assert a["due_date"]                                   # defaults to today + invoice_due_days
    r = client.post("/api/fees", json={"kind": "receipt", "pupil_name": "Tariro Moyo", "amount_paid": 100}).json()
    assert r["number"] == f"RCA-RCT-{year}-0001" and r["total"] == 100


def test_receipt_against_an_invoice_updates_its_balance(client):
    inv = _invoice(client).json()
    r = client.post("/api/fees", json={"kind": "receipt", "pupil_name": "", "invoice_id": inv["id"],
                                       "amount_paid": 250.5, "payment_method": "EcoCash / InnBucks",
                                       "payment_ref": "MP1"})
    assert r.status_code == 200, r.text
    rec = r.json()
    assert rec["pupil_name"] == "Tariro Moyo" and rec["class_name"] == "Form 1"   # copied from the invoice
    assert rec["balance"] == 500 and rec["invoice_number"] == inv["number"]
    assert client.get(f"/api/fees/{inv['id']}").json()["balance"] == 500
    over = client.post("/api/fees", json={"kind": "receipt", "pupil_name": "x", "invoice_id": inv["id"],
                                          "amount_paid": 500.01})
    assert over.status_code == 422                         # more than is owed
    bal = client.get("/api/fees/balances").json()
    assert bal[0]["admission_no"] == "RCA-0001" and bal[0]["balance"] == 500


def test_bad_documents_are_refused(client):
    assert _invoice(client, items=[]).status_code == 422
    assert _invoice(client, items=[{"description": "Tuition", "amount": 0}]).status_code == 422
    assert _invoice(client, amount_paid=10_000).status_code == 422
    assert _invoice(client, due_date="next week").status_code == 422
    assert _invoice(client, chat_id="nope").status_code == 404
    assert client.post("/api/fees", json={"kind": "receipt", "pupil_name": "x", "amount_paid": 0}).status_code == 422
    assert client.post("/api/fees", json={"kind": "refund", "pupil_name": "x"}).status_code == 422


def test_worker_renders_and_the_pdf_goes_to_whatsapp(client, db):
    _chat(client)
    inv = _invoice(client, chat_id="c1").json()
    assert [d["id"] for d in client.get("/api/fees/worker/pending").json()] == [inv["id"]]
    assert client.patch(f"/api/fees/{inv['id']}/claim").json()["claimed"]
    assert not client.patch(f"/api/fees/{inv['id']}/claim").json()["claimed"]   # only once
    assert client.get("/api/fees/worker/pending").json() == []
    assert client.post(f"/api/fees/{inv['id']}/pdf", files={"file": ("x.pdf", b"not a pdf")}).status_code == 415
    r = client.post(f"/api/fees/{inv['id']}/pdf", files={"file": ("x.pdf", b"%PDF-1.4 test", "application/pdf")})
    doc = r.json()
    assert doc["status"] == "ready" and doc["pdf_url"].endswith(f"/Royal_Crest_Invoice_{inv['number']}.pdf")
    msg = db.execute("SELECT text, kind, media_url, wa_status FROM messages WHERE chat_id='c1'").fetchone()
    assert msg["kind"] == "document" and msg["wa_status"] == "documents" and msg["media_url"] == doc["pdf_url"]
    assert msg["text"] == f"Invoice {inv['number']} — Tariro Moyo"
    assert [m["id"] for m in client.get("/api/outbox/documents").json()]
    assert client.get(doc["pdf_url"]).content == b"%PDF-1.4 test"


def test_send_again_and_retry(client, db):
    _chat(client)
    inv = _invoice(client).json()
    assert client.post(f"/api/fees/{inv['id']}/send", json={"chat_id": "c1"}).status_code == 409   # not ready
    assert client.post(f"/api/fees/{inv['id']}/retry").status_code == 409                         # not failed
    client.patch(f"/api/fees/{inv['id']}/claim")
    client.patch(f"/api/fees/{inv['id']}/error", json={"error_message": "Chromium missing"})
    assert client.get(f"/api/fees/{inv['id']}").json()["status"] == "error"
    assert client.post(f"/api/fees/{inv['id']}/retry").json()["status"] == "pending"
    client.patch(f"/api/fees/{inv['id']}/claim")
    client.post(f"/api/fees/{inv['id']}/pdf", files={"file": ("x.pdf", b"%PDF-1.4", "application/pdf")})
    assert db.execute("SELECT COUNT(*) FROM messages").fetchone()[0] == 0        # no chat chosen yet
    assert client.post(f"/api/fees/{inv['id']}/send", json={"chat_id": "c1"}).status_code == 200
    assert client.get(f"/api/fees/{inv['id']}").json()["chat_id"] == "c1"
    assert db.execute("SELECT COUNT(*) FROM messages WHERE wa_status='documents'").fetchone()[0] == 1


def test_catalogue_and_search(client):
    cat = client.get("/api/fees/catalogue").json()
    assert cat["name"] == "Royal Crest Academy" and "Term 1 2027" in cat["terms"]
    assert {i["name"] for i in cat["fee_items"]} >= {"Tuition", "Boarding"}
    _invoice(client)
    _invoice(client, pupil_name="Nyasha Dube", admission_no="RCA-0002")
    assert [d["pupil_name"] for d in client.get("/api/fees", params={"q": "dube"}).json()] == ["Nyasha Dube"]
    assert len(client.get("/api/fees", params={"kind": "receipt"}).json()) == 0
