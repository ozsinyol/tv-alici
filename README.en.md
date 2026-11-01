# AirPlay for Android

Turns an Android device (Android TV, phone, tablet, set-top box) into an **AirPlay receiver** for iPhones: screen mirroring, video, photos, audio-only streaming (Music, Spotify, ...), and video upload from Safari without installing anything.

The app shows a QR code on start. Scan it with the iPhone camera, pick a video on the page that opens, and it plays on the device. It also appears on the network as an AirPlay receiver named "TV Alici" (rename it from the web page).

The full documentation is in Turkish: see [README.md](README.md).

## Languages

The app UI and the web page (port 8080) follow the device/browser language: **English** (default), **Turkish**, Spanish, French, German, Portuguese, Italian, Russian, Japanese, Korean, Chinese (Simplified) and Arabic (right-to-left); other languages fall back to English. To add a language, create `app/src/main/res/values-<code>/strings.xml` (copy `values/strings.xml`) and add a block for it in the `DIL` table in `VideoServer.kt`. The developer log stays in Turkish.

## Quick start

1. Build the APK with GitHub Actions (push to `main`) or download it from [Releases](https://github.com/ozsinyol/tv-alici/releases).
2. Install it (`adb install app-debug.apk`) on the Android device; both devices must be on the same Wi-Fi network.
3. Open the app, scan the QR code with the iPhone camera, or choose "TV Alici" in AirPlay / Screen Mirroring.

License: GNU GPL v3 (see `LICENSE`).
