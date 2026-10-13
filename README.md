# Royal Crest Connect

The school's staff app. Parents' WhatsApp chats show up on every staff phone.
Staff answer them, send notices and polls to parents and class groups, and
issue fee invoices and receipts that reach the parent on WhatsApp as a PDF on
the school letterhead.

It is adapted from Karlon. The Airbnb, houses, reservations and team-location
parts are gone, and school fees, audiences and WhatsApp polls are new.

```
 Staff phones (Android app) ─┐                    ┌─ Office PC
 Bursar's browser (/)  ──────┼──►  server  ◄──────┤   wa_bridge.py   WhatsApp Web ⇄ server
                             │   (FastAPI,        │   fee_worker.py  fee PDFs (Chromium)
                             │    SQLite)         └─ rca_supervisor.py runs both
```

| Folder | What |
|---|---|
| `server/` | FastAPI + SQLite. Chats, messages, outbox, broadcasts, polls, fees, staff dashboard at `/`. |
| `pc-bridge/` | Runs on a Windows PC in the office: links the school WhatsApp number (WhatsApp Web) and makes the fee PDFs. |
| `android/` | The Android app (Kotlin, Jetpack Compose). The built APK is on the website at `/app`. |

## 1. Put the server online (Hugging Face Space)

1. Create a Space at huggingface.co → **New Space** → SDK **Docker**, blank,
   **private**.
2. In the Space: **Settings → Variables and secrets**, add two **secrets**:
   - `RCA_API_KEY`: a long random key (for example 32 letters and digits). Staff
     type it into the app once, and the office PC uses it.
   - `RCA_PASSWORD`: the password for the web dashboard.
3. **Settings → Persistent storage**: turn it on (a paid add-on; the smallest
   tier is plenty). The Dockerfile keeps the database and PDFs in `/data`, which
   survives restarts only with persistent storage. Without it, every restart
   starts empty. The Space itself can be free, and the office PC calls it every
   few seconds, so it doesn't go to sleep during the day.
4. Upload the contents of `server/` to the Space (Files → Add file →
   Upload, or `git push` to the Space's repo). The Space builds and starts by
   itself. The address is `https://<user>-<space>.hf.space`.
5. Check it: `https://<address>/api/health` shows `"key_required": true`.
   `https://<address>/` asks for the dashboard password.

Anything that runs Docker or Python works too (a VPS, or the office PC itself):

```bash
cd server
pip install -r requirements.txt
RCA_API_KEY=… RCA_PASSWORD=… uvicorn app.main:app --host 0.0.0.0 --port 8000
```

On the office network the app accepts `http://192.168.x.x:8000`. Anywhere else
it insists on `https://`.

### School details and fees

Copy `server/school.example.json` to `server/school.json` and edit it. It holds
the terms, classes, fee lines (with amounts once the fee schedule is approved),
payment methods, bank details for "How to pay", and the invoice due period.
Anything left out keeps the defaults in `server/app/config.py`. Restart after a
change.

Invoices are numbered `RCA-INV-<year>-0001` and receipts `RCA-RCT-<year>-0001`.
A receipt recorded against an invoice reduces that invoice's balance, and the
receipt shows what is still owed.

## 2. Set up the office PC (Windows)

This PC keeps WhatsApp Web open for the school number. Leave it on during
school hours.

1. Install **Python 3.11+** from python.org (tick "Add python.exe to PATH").
2. Open PowerShell in `pc-bridge` and run:
   ```powershell
   pip install -r requirements.txt
   python -m playwright install chromium
   ```
3. Copy `bridge.env.example` to `bridge.env` and fill in the server address
   and `RCA_API_KEY`.
4. Check the PDFs: `python fee_worker.py --sample` writes `sample_invoice.pdf`
   and `sample_receipt.pdf` here.
5. Double-click **`start-bridge.bat`**. A Chromium window opens WhatsApp Web.
   The first time, scan the QR code with the school phone (WhatsApp → Linked
   devices). The login is remembered in `wa_session\`.

`rca_supervisor.py` runs `wa_bridge.py` and `fee_worker.py` together, restarts
either one if it stops, and logs to `logs\`.

## 3. Install the app on staff phones

Open `https://<website>/app` (the download page on the school website) on the phone and tap **Download for Android**,
then follow the steps on that page. In the app, enter the server address, the
access key (`RCA_API_KEY`) and your name.

## Using it

- **Chats**: every chat from the school WhatsApp. Open one to reply, send a
  photo, send a poll, start an invoice for that parent, or mark the chat as
  *Parent*, *Group*, *Staff*, *Enquiry* or *Other*. Unsaved numbers count as
  *Enquiry* (the website's enquiry form opens a WhatsApp chat).
- **Broadcast**: one message to every chat in the chosen audiences. Copies go
  out `BROADCAST_GAP_SEC` (8 s) apart, at most `BROADCAST_DAILY_CAP` (250) a
  day. Any surplus waits for the next day. The same text never goes to the same
  chat twice in 24 hours. WhatsApp restricts numbers that message many people
  quickly, so for a notice to every parent, send it to the parents' WhatsApp
  groups (one send per group).
- **Polls**: sent as a numbered list. Every reply that is just a number counts
  as that person's vote, and a later reply replaces an earlier one.
- **Fees**: create an invoice (fee lines, anything already paid, due date) or a
  receipt (amount, method, reference, optionally against an invoice). The
  office PC makes the PDF within seconds and sends it to the chosen chat. The
  **Balances** tab lists what each pupil still owes.
- **Dashboard**: the same chats, broadcasts, polls and fees in a browser at the
  server address, for the bursar's computer.

## Security

- Every API call, PDF and photo needs `RCA_API_KEY` (as
  `Authorization: Bearer …`). `/api/health` is the only open endpoint.
- Fee PDFs live at unguessable addresses and still need the key.
- Keep `bridge.env`, the key and the dashboard password private. To lock
  everyone out (for example if a phone is lost), change `RCA_API_KEY` on the
  server and give staff the new key.

## Building the Android app

Needs JDK 17+ and the Android SDK (platform 35).

```bash
cd android
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/
./gradlew :app:assembleRelease        # signed if keystore.properties exists
```

Release signing reads `android/keystore.properties` (not in git):

```
storeFile=/path/to/royalcrest-release.jks
storePassword=…
keyAlias=royalcrest
keyPassword=…
```

Keep `royalcrest-release.jks` and its password safe. Every future update must
be signed with the same key, or phones refuse to install it over the old app.
When releasing, raise `versionCode`/`versionName` in `app/build.gradle.kts`,
copy the APK to `public/app/royal-crest-connect.apk` in the website repo
(`karlo990/royalcrest`), and update the version and SHA-256 on
`public/app/index.html` there.

The screen tests render every screen against a running server and save
screenshots to `app/build/screens/`:

```bash
RCA_TEST_SERVER=http://127.0.0.1:8765/ RCA_TEST_KEY=… ./gradlew :app:testDebugUnitTest
```

## Tests

```bash
cd server && python -m pytest       # 28 tests: fees, broadcasts, polls, auth, WhatsApp sync
cd pc-bridge && python -m pytest    # 59 tests: WhatsApp Web parsing, delivery, pacing, fee PDFs
```
