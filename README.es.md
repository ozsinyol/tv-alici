# AirPlay for Android

🌐 [Türkçe](README.tr.md) · [English](README.md) · **Español** · [Français](README.fr.md) · [Deutsch](README.de.md) · [Português](README.pt.md) · [Italiano](README.it.md) · [Русский](README.ru.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [简体中文](README.zh.md) · [العربية](README.ar.md)

Convierte un dispositivo Android (Android TV, teléfono, tableta, caja) en un **receptor AirPlay** para iPhone y iPad: duplicación de pantalla, vídeo, fotos, transmisión solo de audio (Música, Spotify, …) y envío de vídeos desde Safari sin instalar nada.

Al iniciarse, la app muestra un código QR. Escanéalo con la cámara del iPhone para abrir el mando web, o elige **TV Alici** (puedes cambiarle el nombre) en Centro de control > Duplicar pantalla o en la lista de AirPlay.

## Características

- **Duplicación de pantalla** (vídeo H.264, audio AAC-ELD) con ajustes de sincronización audio/vídeo y búfer, zoom y modos ajustar/rellenar.
- **AirPlay de vídeo** (MP4 y HLS) y **AirPlay de fotos**.
- **AirPlay solo audio** (Música, Spotify, Podcasts) con una pantalla *Reproduciendo ahora* a pantalla completa con la carátula y los datos de la canción.
- **Mando web** en `http://<ip-del-dispositivo>:8080` (protegido con PIN): envía vídeos desde Safari sin instalar nada, pon varios vídeos en cola, reproducir/pausar/avanzar, volumen, mostrar un mensaje en la TV, reproducir un enlace de vídeo y añadirlo a la pantalla de inicio como app.
- **Menú en pantalla de la TV** — pulsa **MENU** (u **OK** en mandos sin tecla MENU, como el Mi TV Stick): inicio automático, temporizador de apagado, sincronización A/V, búfer, zoom, ajuste de pantalla, reloj, estadísticas, dispositivos de confianza, buscar actualizaciones.
- **Control de acceso:** los dispositivos nuevos deben aprobarse en la TV (permitir una vez / siempre / denegar).
- **Actualizaciones dentro de la app** verificadas por nombre de paquete, versión y certificado de firma.
- **12 idiomas** (según el idioma del dispositivo/navegador): English, Türkçe, Español, Français, Deutsch, Português, Italiano, Русский, 日本語, 한국어, 简体中文, العربية.

## Inicio rápido

1. Compila el APK con GitHub Actions (push a `main` y descarga el artefacto `tv-alici-apk`) o descarga un APK firmado desde [Releases](https://github.com/ozsinyol/tv-alici/releases).
2. Instálalo en el dispositivo Android (`adb install app-release.apk`, o abre el APK en un teléfono/tableta). Ambos dispositivos deben estar en la misma red Wi-Fi.
3. Abre la app y escanea el código QR con la cámara del iPhone, o elige «TV Alici» en AirPlay / Duplicar pantalla.

## Limitaciones

- Solo los protocolos clásicos de AirPlay (1) y de duplicación de pantalla: sin AirPlay 2 (multihabitación, audio con búfer, Siri/Casa).
- El vídeo protegido con FairPlay (YouTube, Netflix, Apple TV+ …) no se reproduce, y el contenido con DRM se ve en negro al duplicar.
- No se admiten presentaciones de fotos; la duplicación de pantalla es solo H.264.
- AirPlay es un protocolo propietario de Apple sin licencia oficial; este proyecto es de uso personal/experimental.

## Seguridad y actualizaciones

El mando web requiere el PIN que se muestra en la TV (se envía en una cabecera de la petición; cinco intentos fallidos bloquean ese dispositivo durante 5 minutos). Los APK de versión se firman con una clave privada guardada en GitHub Secrets, y el actualizador integrado solo instala un APK si coinciden el nombre del paquete, la versión y el SHA-256 del certificado.

## Documentación completa

La documentación completa (en inglés) está en [README.md](README.md): compilación y firma, dispositivos de confianza, ajuste de sincronización, API web, actualización manual y depuración. Para añadir un idioma a la app, copia `app/src/main/res/values/strings.xml` a `values-<código>/` y añade un bloque a la tabla `DIL` de `VideoServer.kt`.

## Licencia

GNU GPL v3 — consulta [LICENSE](LICENSE). El proyecto usa el código `playfair` de [UxPlay](https://github.com/FDH2/UxPlay) (GPL v3) y el decodificador ALAC de código abierto de Apple (Apple Public Source License 2.0, ver `app/src/main/cpp/alac/APPLE_LICENSE.txt`).
