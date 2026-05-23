# AirReceive

AirReceive is an Android app for cross-device photo transfer—similar to Apple’s AirDrop. It can **receive** photos on Android (from iPhone browsers or other devices) and **send** photos from Android to an iPhone via the public gateway.

This repo contains two parts that work together:

| Component | Role |
|-----------|------|
| **Android app** (`app/`) | Runs on your phone, receives images, keeps an in-app gallery, and can save to the device photo library. |
| **Public gateway** (`server.js`) | Node.js relay: browser ↔ Android (both directions) over WebSockets and temporary file storage. |

## How it works

### Local Wi‑Fi mode (default)

When your phone and the sender are on the same Wi‑Fi network, the app starts a small HTTP server on port `8080`. The sender opens a URL (shown in the app as a QR code or link) in a browser, picks photos, and uploads them directly to the phone. No cloud service is required.

### Public gateway mode (optional)

For senders who are **not** on the same network, deploy the included gateway server (e.g. on [Render](https://render.com)). The gateway:

1. Serves a web UI where anyone can drag-and-drop or select an image.
2. Accepts uploads via `POST /upload` and stores them temporarily.
3. Notifies the Android app over a WebSocket (`/ws/phone`).
4. Lets the app download the file via `GET /download/:id`, then deletes it from the server.

In the app, open **Settings** on the status card and paste your gateway’s public URL (for example `https://your-app.onrender.com`). The status badge on the gateway home page turns green when your Android phone is connected.

### Gateway URLs

| URL | Purpose |
|-----|---------|
| `https://your-app.onrender.com/` | Send photos **to Android** (any browser, including iPhone) |
| `https://your-app.onrender.com/receive` | Receive photos **on iPhone** (Safari must stay open on this page) |

Uploads use `POST /upload` with form field `target`: `phone` (default, to Android) or `receiver` (to Safari on `/receive`).

### Send from Android to iPhone (gateway, batch)

1. On Android, save your gateway URL in app settings (same as receive mode).
2. On iPhone, open **`{gateway}/receive`** in Safari and leave the tab in the foreground until status shows **Ready to Receive**.
3. On Android, tap **Send Photos to iPhone (batch)** and select **up to 20 photos** at once.
4. On iPhone, when the grid appears, tap **Download all photos (ZIP)**.
5. Open the ZIP in the **Files** app, then select images and **Save to Photos**.

Android uploads via `POST /upload/batch` (`target=receiver`). The gateway sends one `NOTIFY_BATCH` WebSocket message; iPhone downloads a single ZIP from `GET /download/batch/:batchId`.

**Limits:** 20 images per batch, 100 MB total per batch.

### Deploy / update on Render

No new environment variables or services are required. Batch ZIP support adds the `archiver` npm package — Render runs `npm install` on deploy automatically.

1. Push commits to the GitHub repo linked to your Render **Web Service** (not a Static Site).
2. Wait for auto-deploy, or use **Manual Deploy → Deploy latest commit**.
3. Confirm logs show `[Gateway] Server active on port ...`.
4. Verify `https://your-app.onrender.com/receive` loads and `/api/status` returns `receiverConnected` / `phoneConnected`.

**Settings:** Root directory empty, build `npm install`, start `npm start`.

## Repository layout

- `app/` — Kotlin / Jetpack Compose Android application
- `server.js` — Express + WebSocket gateway and upload web UI
- `package.json` — Node dependencies for the gateway
- `.env.example` — Optional `GEMINI_API_KEY` for AI Studio–related tooling (not required for photo transfer)

## Run the Android app locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio)

1. Open Android Studio.
2. Select **Open** and choose the directory containing this project.
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. *(Optional)* Create a file named `.env` in the project directory and set `GEMINI_API_KEY` to your Gemini API key (see `.env.example`). Photo transfer does not depend on this.
5. Remove this line from the app's `build.gradle.kts` file if you are building locally without the project’s debug signing setup: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device.

## Run the public gateway locally

**Prerequisites:** Node.js 18+

```bash
npm install
npm start
```

The server listens on port `8080` by default (override with the `PORT` environment variable). Open `http://localhost:8080` in a browser to test uploads, and point the Android app’s gateway URL setting at that address when testing on the same machine or network.

Uploaded files are stored under `/tmp/airreceive_uploads` and expire after about five minutes if not downloaded.

## Typical workflows

**Receive on Android**

1. Install and open **AirReceive** on your Android phone.
2. **Same Wi‑Fi:** Share the app’s local URL or QR code; sender uploads from their browser.
3. **Different networks:** Deploy `server.js`, paste the gateway URL into app settings, sender uses `https://your-gateway/` in a browser.

**Send to iPhone (batch)**

1. Gateway deployed; Android app has gateway URL saved.
2. iPhone opens `{gateway}/receive` in Safari (foreground).
3. Android selects multiple photos and sends one batch.
4. iPhone taps **Download all photos (ZIP)**.

Received photos appear in the Android in-app gallery and can be saved to the device photo library. Photos sent to iPhone are saved manually from Safari.

## Limitations

- iPhone `/receive` requires Safari to stay open; background tabs may drop the WebSocket.
- No pairing or encryption; anyone with the gateway URL can connect (same as before).
- HEIC images from Android may not preview correctly in all Safari versions; JPEG is most reliable.
- iPhone cannot save an entire batch to Photos in one system tap; use the ZIP download, then extract in Files.
