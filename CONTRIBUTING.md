# Contributing

Thanks for your interest in AirPlay for Android! Bug reports, fixes, translations and ideas are all welcome.

## Building

There is no Gradle wrapper in the repository. The easiest way to build is **GitHub Actions**: open a pull request (or push to `main` in your fork) and download the `tv-alici-apk` artifact from the **Build APK** run.

To build locally you need JDK 17, Gradle 8.9, the Android SDK (compileSdk 34) and the NDK with CMake 3.22.1 (the app contains small native libraries):

```bash
gradle :app:assembleDebug
```

Without the signing key (kept in GitHub Secrets) the build is signed with Android's default debug key; such an APK installs fine but is rejected by the in-app updater. That is expected.

## Project layout

| Path | What it is |
|---|---|
| `app/src/main/java/com/example/tvalici/MainActivity.kt` | TV UI: QR screen, TV menu, mirroring layout, Now playing, player |
| `AirPlayServer.kt`, `AirPlayAdvertiser.kt` | RTSP/HTTP AirPlay server and mDNS advertising |
| `AirPlayCrypto.kt`, `AirPlayFairPlay.kt`, `Playfair.kt` | pairing and FairPlay key handling |
| `MirrorReceiver.kt`, `MirrorDecoder.kt` | screen-mirroring video (H.264 → MediaCodec) |
| `AudioReceiver.kt`, `AlacDecoder.kt`, `DmapParser.kt` | audio streams, ALAC decoding, song metadata |
| `VideoServer.kt` | web interface on port 8080 (HTML/JS is embedded in this file) and its API |
| `Approvals.kt`, `Updater.kt` | connection approval, signed in-app updates |
| `app/src/main/cpp/` | native code: `playfair` (GPL v3, from UxPlay) and Apple's ALAC decoder (APSL 2.0) |

## Adding or fixing a translation

The app UI and the web page use the same 12 languages.

- **App:** `app/src/main/res/values-<code>/strings.xml`. Every string must exist in **all** 12 files (copy `values/strings.xml` for a new language). Escape apostrophes as `\'`.
- **Web page:** the `DIL` table in `VideoServer.kt`. Missing keys fall back to English.
- **Careful with apostrophes in the web page.** The strings are JavaScript string literals: an unescaped `'` inside a single-quoted string is a syntax error that breaks the *whole* page for every language. Use `\'` or double quotes.
- Documentation lives in the `README.<code>.md` files; the English `README.md` is the main documentation.

## Pull requests

- Keep changes focused and describe what you tested (device, Android version, iPhone/iOS version).
- There are no automated tests; CI only checks that the app builds, the signing fingerprint matches and the APK is not debuggable. Please test on a real device when you change networking, decoding or key handling.
- Do not change `UPDATE_CERT_SHA256` in `app/build.gradle.kts` unless you are rotating the signing key: devices only accept updates signed with the pinned certificate.
- Add the new strings to all language files and update the README if behaviour changes.
- The developer log (`appendLog`/`log`) and the code comments are in **English**; please write new ones in English. Turkish text belongs only in `values-tr/strings.xml`, the Turkish block of the `DIL` table in `VideoServer.kt`, and `README.tr.md`.

## License

By contributing you agree that your contribution is licensed under the **GNU GPL v3** (see [LICENSE](LICENSE)). Third-party code keeps its own license (see the README).
