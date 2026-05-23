# AirReceive

AirReceive is an Android app that lets you receive photos from other devices over the network—similar to Apple’s AirDrop, but aimed at sending from phones, tablets, or desktops to an Android phone.

This repository was originally scaffolded from [Google AI Studio](https://ai.studio); the README and project focus are now on **AirReceive** itself (photo transfer), not the generic AI Studio deploy template. The AI Studio banner and boilerplate instructions have been removed from this file.

This repo contains two parts that work together:

| Component | Role |
|-----------|------|
| **Android app** (`app/`) | Runs on your phone, receives images, keeps an in-app gallery, and can save to the device photo library. |
| **Public gateway** (`server.js`) | Optional Node.js relay so senders on the internet can upload photos through a web page; the phone is notified and pulls the file down. |

## How it works

### Local Wi‑Fi mode (default)

When your phone and the sender are on the same Wi‑Fi network, the app starts a small HTTP server on port `8080`. The sender opens a URL (shown in the app as a QR code or link) in a browser, picks photos, and uploads them directly to the phone. No cloud service is required.

### Public gateway mode (optional)

For senders who are **not** on the same network, deploy the included gateway server (e.g. on [Render](https://render.com)). The gateway:

1. Serves a web UI where anyone can drag-and-drop or select an image.
2. Accepts uploads via `POST /upload` and stores them temporarily.
3. Notifies the Android app over a WebSocket (`/ws/phone`).
4. Lets the app download the file via `GET /download/:id`, then deletes it from the server.

In the app, open **Settings** on the status card and paste your gateway’s public URL (for example `https://your-app.onrender.com`). The status badge on the gateway web page turns green when your phone is connected.

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

## Typical workflow

1. Install and open **AirReceive** on your Android phone.
2. **Same Wi‑Fi:** Share the app’s local URL or QR code with the sender and upload from their browser.
3. **Different networks:** Deploy `server.js`, paste the gateway URL into the app settings, then have the sender use the gateway’s web page to send photos.

Received photos appear in the in-app gallery and can be saved to the device’s media library.
