# AirPlay for Android

🌐 [Türkçe](README.tr.md) · [English](README.md) · [Español](README.es.md) · [Français](README.fr.md) · [Deutsch](README.de.md) · [Português](README.pt.md) · **Italiano** · [Русский](README.ru.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [简体中文](README.zh.md) · [العربية](README.ar.md)

Trasforma un dispositivo Android (Android TV, telefono, tablet, set-top box) in un **ricevitore AirPlay** per iPhone e iPad: mirroring dello schermo, video, foto, streaming solo audio (Musica, Spotify, …) e invio di video da Safari senza installare nulla.

All'avvio l'app mostra un codice QR. Inquadralo con la fotocamera dell'iPhone per aprire il telecomando web, oppure scegli **TV Alici** (puoi rinominarlo) in Centro di Controllo > Mirroring dello schermo o nell'elenco AirPlay.

## Funzionalità

- **Mirroring dello schermo** (video H.264, audio AAC-ELD) con impostazioni di sincronia audio/video e buffer, zoom e modalità adatta/riempi.
- **AirPlay video** (MP4 e HLS) e **AirPlay foto**.
- **AirPlay solo audio** (Musica, Spotify, Podcast) con una schermata *In riproduzione* a tutto schermo con copertina e info del brano.
- **Telecomando web** su `http://<ip-del-dispositivo>:8080` (protetto da PIN): invia video da Safari senza installare nulla, metti più video in coda, riproduci/pausa/avanza, volume, mostra un messaggio sulla TV, riproduci un link video e aggiungilo alla schermata Home come app.
- **Menu su schermo della TV** — premi **MENU** (oppure **OK** sui telecomandi senza tasto MENU, come il Mi TV Stick): avvio automatico, timer di spegnimento, sincronia A/V, buffer, zoom, adattamento schermo, orologio, statistiche, dispositivi attendibili, controllo aggiornamenti.
- **Controllo degli accessi:** i nuovi dispositivi devono essere approvati sulla TV (consenti una volta / sempre / nega).
- **Aggiornamenti nell'app** verificati tramite nome del pacchetto, versione e certificato di firma.
- **12 lingue** (secondo la lingua del dispositivo/browser): English, Türkçe, Español, Français, Deutsch, Português, Italiano, Русский, 日本語, 한국어, 简体中文, العربية.

## Avvio rapido

1. Compila l'APK con GitHub Actions (push su `main`, poi scarica l'artefatto `tv-alici-debug-apk`) oppure scarica un APK firmato da [Releases](https://github.com/ozsinyol/tv-alici/releases).
2. Installalo sul dispositivo Android (`adb install app-debug.apk`, oppure apri l'APK su telefono/tablet). Entrambi i dispositivi devono essere sulla stessa rete Wi-Fi.
3. Apri l'app e inquadra il codice QR con la fotocamera dell'iPhone, oppure scegli “TV Alici” in AirPlay / Mirroring dello schermo.

## Limitazioni

- Solo i protocolli AirPlay classico (1) e di mirroring dello schermo — niente AirPlay 2 (multi-room, audio con buffer, Siri/Casa).
- I video protetti da FairPlay (YouTube, Netflix, Apple TV+ …) non vengono riprodotti, e i contenuti DRM appaiono neri nel mirroring.
- Le presentazioni di foto non sono supportate; il mirroring dello schermo è solo H.264.
- AirPlay è un protocollo proprietario di Apple senza licenza ufficiale; questo progetto è per uso personale/sperimentale.

## Sicurezza e aggiornamenti

Il telecomando web richiede il PIN mostrato sulla TV (inviato in un'intestazione della richiesta; cinque tentativi errati bloccano quel dispositivo per 5 minuti). Gli APK di release sono firmati con una chiave privata conservata in GitHub Secrets, e l'aggiornatore integrato installa un APK solo se nome del pacchetto, versione e SHA-256 del certificato corrispondono.

## Documentazione completa

La documentazione completa (in inglese) è in [README.md](README.md): compilazione e firma, dispositivi attendibili, messa a punto della sincronia, API web, aggiornamento manuale, debug. Per aggiungere una lingua all'app, copia `app/src/main/res/values/strings.xml` in `values-<codice>/` e aggiungi un blocco alla tabella `DIL` in `VideoServer.kt`.

## Licenza

GNU GPL v3 — vedi [LICENSE](LICENSE). Il progetto usa il codice `playfair` di [UxPlay](https://github.com/FDH2/UxPlay) (GPL v3) e il decoder ALAC open source di Apple (Apple Public Source License 2.0, vedi `app/src/main/cpp/alac/APPLE_LICENSE.txt`).
