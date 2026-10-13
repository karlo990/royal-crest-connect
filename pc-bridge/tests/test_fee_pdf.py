"""fee_worker.py prints invoices and receipts on the school letterhead."""
import glob
import os
import subprocess
import sys
from pathlib import Path

import fee_worker


def test_invoice_html_has_the_figures():
    inv, rct = fee_worker.SAMPLES
    page = fee_worker.build_html(inv, fee_worker.DEFAULT_SCHOOL)
    for s in ("Royal Crest Academy", "RCA-INV-2027-0001", "PART PAID", "Tariro Moyo", "Term 1 2027",
              "USD 885.00", "USD 200.00", "USD 685.00", "How to pay", "1226 Chinobva, Nyika, Masvingo"):
        assert s in page, s
    page = fee_worker.build_html(rct, fee_worker.DEFAULT_SCHOOL)
    for s in ("Receipt", "RCA-RCT-2027-0001", ">PAID<", "Payment received — Term 1 2027", "USD 300.00",
              "MP261127.1030.A12345", "Balance still owed on that invoice", "USD 385.00"):
        assert s in page, s
    assert "How to pay" not in page


def test_text_is_escaped():
    doc = dict(fee_worker.SAMPLES[0], pupil_name="<script>x</script>")
    assert "<script>x" not in fee_worker.build_html(doc, fee_worker.DEFAULT_SCHOOL)


def test_pdf_is_rendered(tmp_path):
    # In its own process: the other tests hold a Playwright session open.
    env = dict(os.environ)
    hits = sorted(glob.glob("/opt/pw-browsers/chromium-*/chrome-linux/chrome"))
    if hits:
        env["FEE_CHROMIUM_PATH"] = hits[-1]
    script = Path(fee_worker.__file__).resolve()
    subprocess.run([sys.executable, str(script), "--sample"], cwd=tmp_path, env=env, check=True, timeout=120)
    pdfs = [tmp_path / "sample_invoice.pdf", tmp_path / "sample_receipt.pdf"]
    assert all(p.read_bytes().startswith(b"%PDF") and p.stat().st_size > 20_000 for p in pdfs)
