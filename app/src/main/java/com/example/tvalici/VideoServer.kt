package com.example.tvalici

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * A small web server that runs on the TV. A single-page interface (Remote / Send / Settings).
 * GET  /                  -> the interface page (?pin=XXXX pre-fills the PIN, #ayar #gunluk select the tab)
 * PUT  /upload            -> receives a video as a stream; playback starts before the upload finishes
 * PUT  /update-apk        -> receives a new APK of the app; if it is verified, the install screen opens on the TV
 * GET  /api/status        -> playback state (JSON: state, title, pos, dur, vol, queue ...)
 * GET  /manifest.json, /apple-touch-icon.png -> an add-to-home-screen (PWA) remote
 * GET  /api/version       -> the installed version (v<number>)
 * POST /control?cmd=..    -> playback remote (playpause, back10, fwd10, volup, voldown, stop, next, zoomin, zoomout, zoomreset, mute; seek and vol use the "cmd:value" form)
 * PUT  /upload + X-Queue: 1 -> the video is added to the end of the playback queue (the next one plays when it finishes)
 * POST /api/play?u=URL    -> plays an http(s) video address on the TV (bookmarklet / address box)
 * POST /api/say?text=..  -> shows a large message on the TV screen (an empty text removes it)
 * GET  /api/name          -> the server name shown in AirPlay;  POST /api/name?set=NAME -> changes it
 * GET  /api/trusted       -> trusted devices (line by line);  POST /api/trusted?forget=IP | ?clear=1
 * GET  /log               -> plain-text log (PIN: ?pin=.. or the X-Pin header)
 * GET  /name, /trusted    -> old addresses; redirects to the matching tab of the interface
 * PIN: the X-Pin header or (for /log only) ?pin=. After 5 wrong attempts from the same IP, locked for 5 min.
 */
/** Reading/changing the server name shown in AirPlay. */
interface NameAdmin {
    fun serverName(): String

    /** If valid, saves the name and refreshes the advertising; returns false if invalid. */
    fun setServerName(raw: String): Boolean
}

class VideoServer(
    port: Int,
    private val dir: File,
    private val pin: String,
    private val onUploadStart: (Upload) -> Unit,
    private val onControl: (String) -> Unit,
    /** The message to show on screen as large text (an empty text removes the message). */
    private val onSay: (String) -> Unit,
    /** Plays the given http(s) video address on the TV (plain MP4/HLS). */
    private val onPlayUrl: (String) -> Unit,
    /** Verifies the uploaded APK and starts the installation if there is no problem. Returns the problem as text (null = OK). */
    private val onApk: (File) -> String?,
    private val trust: TrustAdmin,
    private val names: NameAdmin,
    /** The /api/status response (JSON); MainActivity updates it once a second. */
    private val status: () -> String
) : NanoHTTPD(port) {

    private class Fail {
        var count = 0
        var lockedUntil = 0L
    }

    private val failures = HashMap<String, Fail>()

    private fun status(code: Int, desc: String): Response.IStatus = object : Response.IStatus {
        override fun getRequestStatus(): Int = code
        override fun getDescription(): String = "$code $desc"
    }

    private val tooMany = status(429, "Too Many Requests")
    private val found = status(302, "Found")

    /** Picks the Turkish or English text according to the browser's Accept-Language header. */
    private fun L(session: IHTTPSession, tr: String, en: String): String =
        if ((session.headers["accept-language"] ?: "").trim().lowercase().startsWith("tr")) tr else en

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        val get = session.method == Method.GET
        val post = session.method == Method.POST
        return when {
            path == "/upload" && session.method == Method.PUT -> handleUpload(session)
            path == "/update-apk" && session.method == Method.PUT -> handleApkUpload(session)
            path == "/api/version" && get -> {
                authFail(session, session.headers["x-pin"]) ?: text("v${BuildConfig.VERSION_CODE}")
            }
            path == "/control" && post -> handleControl(session)
            path == "/log" && get -> handleLog(session)
            path == "/api/status" && get -> handleStatus(session)
            path == "/apple-touch-icon.png" && get -> icon()
            path == "/manifest.json" && get -> manifest()
            path == "/api/say" && post -> handleSay(session)
            path == "/api/play" && post -> handlePlayUrl(session)
            path == "/api/name" && (get || post) -> handleName(session)
            path == "/api/trusted" && (get || post) -> handleTrusted(session)
            (path == "/name" || path == "/trusted") && get ->
                redirect("/?pin=" + URLEncoder.encode(session.parms["pin"] ?: "", "UTF-8") + "#ayar")
            (path == "/" || path == "/index.html") && get -> {
                val r = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", PAGE)
                r.addHeader("Cache-Control", "no-store")
                r
            }
            else -> text(L(session, "Sayfa yok", "Page not found"), Response.Status.NOT_FOUND)
        }
    }

    /** Returns null if the PIN is correct; otherwise returns the response to send if it is wrong or locked. Counts wrong attempts. */
    @Synchronized
    private fun authFail(session: IHTTPSession, supplied: String?): Response? {
        val ip = session.remoteIpAddress ?: "?"
        val now = System.currentTimeMillis()
        val f = failures.getOrPut(ip) { Fail() }
        if (f.lockedUntil > now) {
            val sec = (f.lockedUntil - now) / 1000 + 1
            return text(L(session, "Çok fazla yanlış deneme. $sec sn sonra tekrar deneyin.", "Too many wrong attempts. Try again in $sec s."), tooMany)
        }
        if (supplied != null &&
            MessageDigest.isEqual(supplied.toByteArray(Charsets.UTF_8), pin.toByteArray(Charsets.UTF_8))
        ) {
            f.count = 0
            return null
        }
        f.count++
        if (f.count >= MAX_TRIES) {
            f.lockedUntil = now + LOCK_MS
            f.count = 0
        }
        return text(L(session, "PIN yanlış", "Wrong PIN"), Response.Status.UNAUTHORIZED)
    }

    private fun redirect(to: String): Response {
        val r = newFixedLengthResponse(found, "text/plain; charset=utf-8", "")
        r.addHeader("Location", to)
        return r
    }

    private fun handleLog(session: IHTTPSession): Response {
        authFail(session, session.headers["x-pin"] ?: session.parms["pin"])?.let { return it }
        return text(LogBuffer.dump())
    }

    private fun handleStatus(session: IHTTPSession): Response {
        authFail(session, session.headers["x-pin"])?.let { return it }
        val r = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", status())
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    private fun icon(): Response {
        val bytes = android.util.Base64.decode(ICON_PNG_B64, android.util.Base64.DEFAULT)
        val r = newFixedLengthResponse(Response.Status.OK, "image/png", java.io.ByteArrayInputStream(bytes), bytes.size.toLong())
        r.addHeader("Cache-Control", "public, max-age=86400")
        return r
    }

    private fun manifest(): Response {
        val json = """{"name":"AirPlay for Android","short_name":"AirPlay","start_url":"/","display":"standalone","background_color":"#000000","theme_color":"#000000","icons":[{"src":"/apple-touch-icon.png","sizes":"180x180","type":"image/png"}]}"""
        return newFixedLengthResponse(Response.Status.OK, "application/manifest+json; charset=utf-8", json)
    }

    private fun handlePlayUrl(session: IHTTPSession): Response {
        authFail(session, session.headers["x-pin"])?.let { return it }
        val u = (session.parms["u"] ?: "").trim()
        val ok = u.length in 8..2000 && (u.startsWith("http://") || u.startsWith("https://")) && !u.contains(' ')
        if (!ok) return text(L(session, "Geçersiz adres (http/https ile başlamalı).", "Invalid address (must start with http/https)."), Response.Status.BAD_REQUEST)
        onPlayUrl(u)
        return text("OK")
    }

    private fun handleSay(session: IHTTPSession): Response {
        authFail(session, session.headers["x-pin"])?.let { return it }
        val raw = session.parms["text"] ?: ""
        val clean = raw.replace(Regex("[\\p{Cntrl}]"), " ").trim().take(200)
        onSay(clean)
        return text("OK")
    }

    private fun handleName(session: IHTTPSession): Response {
        authFail(session, session.headers["x-pin"])?.let { return it }
        if (session.method == Method.POST) {
            val wanted = session.parms["set"] ?: return text(L(session, "Ad yok", "No name"), Response.Status.BAD_REQUEST)
            return if (names.setServerName(wanted)) text("OK")
            else text(L(session, "Geçersiz ad (1-32 karakter; @ ve kontrol karakterleri olmaz).", "Invalid name (1-32 characters; no @ or control characters)."), Response.Status.BAD_REQUEST)
        }
        return text(names.serverName())
    }

    private fun handleTrusted(session: IHTTPSession): Response {
        authFail(session, session.headers["x-pin"])?.let { return it }
        if (session.method == Method.POST) {
            session.parms["forget"]?.let { trust.forget(it) }
            if (session.parms["clear"] == "1") trust.forgetAll()
            return text("OK")
        }
        return text(trust.trustedList().joinToString("\n"))
    }

    private fun handleControl(session: IHTTPSession): Response {
        authFail(session, session.headers["x-pin"])?.let { return it }
        val cmd = session.parms["cmd"] ?: return text("Missing cmd", Response.Status.BAD_REQUEST)
        val arg = session.parms["v"]
        if (cmd != "ping") onControl(if (arg != null) "$cmd:$arg" else cmd)
        return text("OK")
    }

    private fun handleUpload(session: IHTTPSession): Response {
        authFail(session, session.headers["x-pin"])?.let { r ->
            r.closeConnection(true)
            return r
        }
        val length = session.headers["content-length"]?.toLongOrNull()
            ?: return text(L(session, "Boyut bilgisi yok", "No size information"), Response.Status.BAD_REQUEST)

        // If it is not being added to the queue, clean up the previous uploads (so the cache does not fill up).
        val queued = session.headers["x-queue"] == "1"
        if (!queued) {
            dir.listFiles()?.forEach { f ->
                if (f.name.startsWith("video_")) f.delete()
            }
        }
        val dest = File(dir, "video_${System.currentTimeMillis()}")
        dest.createNewFile()
        val fileName = try {
            java.net.URLDecoder.decode(session.headers["x-name"] ?: "", "UTF-8")
                .replace(Regex("[\\p{Cntrl}]"), " ").trim().take(80)
        } catch (e: Exception) {
            ""
        }
        val upload = Upload(dest, length, queued, fileName)
        onUploadStart(upload)

        return try {
            val input = session.inputStream
            val buf = ByteArray(64 * 1024)
            var total = 0L
            dest.outputStream().use { out ->
                while (total < length) {
                    val want = minOf(buf.size.toLong(), length - total).toInt()
                    val n = input.read(buf, 0, want)
                    if (n < 0) throw IOException("Connection lost")
                    out.write(buf, 0, n)
                    total += n
                    upload.update(total)
                }
            }
            upload.finish(true)
            text("OK")
        } catch (e: Exception) {
            upload.finish(false)
            val r = text(L(session, "Hata: ${e.message}", "Error: ${e.message}"), Response.Status.INTERNAL_ERROR)
            r.closeConnection(true)
            r
        }
    }

    private fun handleApkUpload(session: IHTTPSession): Response {
        authFail(session, session.headers["x-pin"])?.let { r ->
            r.closeConnection(true)
            return r
        }
        val length = session.headers["content-length"]?.toLongOrNull()
            ?: return text(L(session, "Boyut bilgisi yok", "No size information"), Response.Status.BAD_REQUEST)
        if (length <= 0 || length > MAX_APK) {
            val r = text(L(session, "APK boyutu geçersiz (en fazla ${MAX_APK / (1024 * 1024)} MB).", "Invalid APK size (max ${MAX_APK / (1024 * 1024)} MB)."), Response.Status.BAD_REQUEST)
            r.closeConnection(true)
            return r
        }
        // FileProvider shares only cache/updates/ (file_paths.xml).
        val updDir = File(dir, "updates")
        updDir.mkdirs()
        val dest = File(updDir, "update.apk")
        dest.delete()
        return try {
            val input = session.inputStream
            val buf = ByteArray(64 * 1024)
            var total = 0L
            dest.outputStream().use { out ->
                while (total < length) {
                    val want = minOf(buf.size.toLong(), length - total).toInt()
                    val n = input.read(buf, 0, want)
                    if (n < 0) throw IOException("Connection lost")
                    out.write(buf, 0, n)
                    total += n
                }
            }
            val problem = onApk(dest)
            if (problem != null) {
                dest.delete()
                text(L(session, "APK kurulmadı: $problem", "APK not installed: $problem"), Response.Status.BAD_REQUEST)
            } else {
                text("OK")
            }
        } catch (e: Exception) {
            dest.delete()
            val r = text(L(session, "Hata: ${e.message}", "Error: ${e.message}"), Response.Status.INTERNAL_ERROR)
            r.closeConnection(true)
            r
        }
    }

    private fun text(msg: String, status: Response.IStatus = Response.Status.OK): Response {
        val r = newFixedLengthResponse(status, "text/plain; charset=utf-8", msg)
        r.addHeader("Cache-Control", "no-store")
        return r
    }

    companion object {
        private const val MAX_TRIES = 5
        private const val LOCK_MS = 5 * 60 * 1000L
        private const val MAX_APK = 150L * 1024 * 1024

        // The add-to-home-screen icon (180x180 PNG, base64).
        private const val ICON_PNG_B64 = "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"

        private const val PAGE = """<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="color-scheme" content="light dark">
<meta name="theme-color" content="#000000">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="apple-mobile-web-app-title" content="AirPlay">
<link rel="manifest" href="/manifest.json">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<link rel="icon" type="image/png" href="/apple-touch-icon.png">
<title>AirPlay for Android</title>
<style>
  :root{--bg:#f2f2f7;--card:#fff;--fg:#1c1c1e;--mut:#58585e;--line:#e5e5ea;--field:#f2f2f7;--btn:#e9e9ee;--btnfg:#1c1c1e;--acc:#0066d6;--accfg:#fff;--acct:#0062cc;--warn:#8a4a00;--ok:#1e8e3e;--trk:#c7c7cc;--bar:rgba(249,249,251,.92)}
  @media (prefers-color-scheme:dark){:root{--bg:#000;--card:#1c1c1e;--fg:#fff;--mut:#b5b5bd;--line:#2c2c2e;--field:#2c2c2e;--btn:#3a3a3c;--btnfg:#fff;--acc:#0066d6;--accfg:#fff;--acct:#4da3ff;--warn:#ffd60a;--ok:#34c759;--trk:#48484a;--bar:rgba(28,28,30,.92)}}
  *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
  [hidden]{display:none!important}
  html{-webkit-text-size-adjust:100%}
  body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:var(--bg);color:var(--fg);margin:0 auto;max-width:560px;padding:calc(12px + env(safe-area-inset-top)) 16px calc(88px + env(safe-area-inset-bottom))}
  header{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:4px 0 12px}
  h1{font-size:22px;font-weight:700;margin:0;letter-spacing:-.3px}
  .chip{display:flex;align-items:center;gap:6px;font-size:13px;font-weight:600;color:var(--mut);background:var(--card);border-radius:999px;padding:6px 12px;max-width:50%}
  .chip span:last-child{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .dot{width:9px;height:9px;border-radius:50%;background:var(--mut);flex:none}
  .chip.on .dot{background:var(--ok)}
  .pinrow{display:flex;align-items:center;justify-content:center;gap:10px;color:var(--mut);font-size:14px;font-weight:600;margin:0 0 12px}
  body.pinok .pinrow{display:none}
  input[type=text]{font:inherit;font-size:17px;padding:11px 12px;border-radius:12px;border:1px solid transparent;background:var(--field);color:var(--fg);min-width:0;width:100%}
  input:focus-visible,button:focus-visible{outline:2px solid var(--acct);outline-offset:2px}
  #pin{width:6em;text-align:center;font-size:18px;font-weight:700;letter-spacing:3px;padding:8px;background:var(--card);border:1px solid var(--line)}
  button{font:inherit;font-size:16px;font-weight:600;padding:12px 16px;min-height:48px;border:0;border-radius:12px;background:var(--acc);color:var(--accfg);cursor:pointer;display:inline-flex;align-items:center;justify-content:center;gap:8px;transition:filter .15s,transform .05s}
  button:active{transform:scale(.97);filter:brightness(.92)}
  button.gri{background:var(--btn);color:var(--btnfg)}
  svg{width:24px;height:24px;fill:currentColor;flex:none}
  svg.line{fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}
  .card{background:var(--card);border-radius:18px;padding:16px;margin-bottom:12px}
  h2{font-size:14px;font-weight:700;color:var(--mut);margin:0 0 10px}
  .not{color:var(--mut);font-size:14px;line-height:1.4;margin:0 0 12px}
  #durum{margin:0 0 10px;color:var(--warn);min-height:1.2em;text-align:center;font-size:15px;font-weight:600}
  /* simdi oynuyor */
  .np{padding:20px 16px 16px}
  .np-title{font-size:20px;font-weight:700;line-height:1.25;text-align:center;overflow:hidden;text-overflow:ellipsis;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;word-break:break-word}
  .np-sub{font-size:15px;color:var(--mut);text-align:center;margin-top:4px;min-height:1.2em;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .np.bos .np-title{color:var(--mut);font-weight:600}
  .times{display:flex;justify-content:space-between;font-size:12px;color:var(--mut);font-variant-numeric:tabular-nums;margin-top:2px}
  input[type=range]{-webkit-appearance:none;appearance:none;width:100%;height:28px;background:transparent;margin:10px 0 0;padding:0}
  input[type=range]::-webkit-slider-runnable-track{height:6px;border-radius:3px;background:linear-gradient(to right,var(--acct) var(--p,0%),var(--trk) var(--p,0%))}
  input[type=range]::-moz-range-track{height:6px;border-radius:3px;background:var(--trk)}
  input[type=range]::-moz-range-progress{height:6px;border-radius:3px;background:var(--acct)}
  input[type=range]::-webkit-slider-thumb{-webkit-appearance:none;width:22px;height:22px;border-radius:50%;background:#fff;box-shadow:0 1px 4px rgba(0,0,0,.45);margin-top:-8px}
  input[type=range]::-moz-range-thumb{width:22px;height:22px;border:0;border-radius:50%;background:#fff;box-shadow:0 1px 4px rgba(0,0,0,.45)}
  input[type=range]:disabled{opacity:.4}
  .transport{display:flex;align-items:center;justify-content:center;gap:20px;margin-top:10px}
  .transport button{border-radius:50%;padding:0;min-height:0}
  .transport .sk{width:56px;height:56px}
  .transport .sk svg{width:30px;height:30px}
  .transport .play{width:72px;height:72px}
  .transport .play svg{width:38px;height:38px}
  .volrow{display:flex;align-items:center;gap:10px}
  .volrow button{width:48px;padding:0;flex:none}
  .volrow input{margin:0}
  .izgara{display:grid;gap:8px}
  .g2{grid-template-columns:1fr 1fr}
  .g3{grid-template-columns:1fr 1fr 1fr}
  .satir{display:flex;gap:8px;align-items:stretch}
  .satir button{flex:none}
  .tam{width:100%}
  #sira li{font-size:15px;border-bottom:1px solid var(--line);padding:10px 0;display:flex;gap:10px;align-items:center}
  #sira li:last-child{border-bottom:0;padding-bottom:0}
  #sira .n{color:var(--mut);font-variant-numeric:tabular-nums;width:1.4em;text-align:right;flex:none}
  #sira li{justify-content:flex-start}
  #sira .ad{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1;min-width:0}
  /* gonder */
  input[type=file]{width:100%;font-size:15px;color:var(--mut);padding:10px;border:1.5px dashed var(--line);border-radius:12px;background:var(--field);margin-bottom:10px}
  input[type=file]::file-selector-button{font:inherit;font-weight:600;border:0;border-radius:8px;background:var(--btn);color:var(--btnfg);padding:8px 12px;margin-right:10px}
  #dosyaListe{margin:0 0 10px}
  #dosyaListe li{font-size:14px;color:var(--mut)}
  #dosyaListe li.suren{color:var(--fg);font-weight:600}
  #dosyaListe li.bitti{color:var(--ok)}
  progress{display:block;width:100%;height:8px;margin-top:12px;border-radius:4px;accent-color:var(--acc)}
  ul{list-style:none;padding:0;margin:0 0 12px;text-align:left}
  li{display:flex;justify-content:space-between;align-items:center;gap:8px;padding:6px 0;border-bottom:1px solid var(--line);font-size:15px}
  li button{min-height:36px;padding:6px 12px;font-size:14px}
  pre{text-align:left;background:var(--field);border-radius:12px;padding:12px;font-size:12px;max-height:60vh;overflow:auto;white-space:pre-wrap;word-break:break-word;margin:0 0 10px}
  /* ayar alt sekmeleri */
  .alt{display:flex;gap:4px;padding:4px;background:var(--btn);border-radius:12px;margin:0 0 12px}
  .alt button{flex:1;min-height:38px;padding:6px 4px;font-size:14px;background:transparent;color:var(--mut);border-radius:9px}
  .alt button.aktif{background:var(--card);color:var(--fg);box-shadow:0 1px 3px rgba(0,0,0,.18)}
  /* alt sekme cubugu (baş parmak erişimi) */
  .tabbar{position:fixed;left:0;right:0;bottom:0;display:flex;justify-content:center;gap:4px;padding:8px 12px calc(8px + env(safe-area-inset-bottom));background:var(--bar);-webkit-backdrop-filter:blur(18px);backdrop-filter:blur(18px);border-top:1px solid var(--line)}
  .tabbar button{flex:1;max-width:180px;flex-direction:column;gap:2px;min-height:52px;padding:6px 4px;font-size:12px;background:transparent;color:var(--mut);border-radius:12px}
  .tabbar button.aktif{color:var(--acct)}
  @media (prefers-reduced-motion:reduce){*{transition:none!important;animation:none!important}}
</style></head>
<body>
  <header>
    <h1>AirPlay for Android</h1>
    <div class="chip" id="chip" role="status"><span class="dot"></span><span id="chipText">…</span></div>
  </header>
  <label class="pinrow" id="pinrow">PIN <input type="text" id="pin" inputmode="numeric" maxlength="4" autocomplete="off"></label>
  <div id="durum" role="status"></div>

  <section id="kumanda">
    <div class="card np bos" id="np">
      <div class="np-title" id="npTitle"></div>
      <div class="np-sub" id="npSub"></div>
      <input type="range" id="seek" min="0" max="1000" value="0" disabled aria-label="Seek">
      <div class="times"><span id="tPos">0:00</span><span id="tDur">0:00</span></div>
      <div class="transport">
        <button class="gri sk" id="geri" data-cmd="back10"><svg viewBox="0 0 24 24"><path d="M12 5V2L7 6l5 4V7a6 6 0 1 1-6 6H4a8 8 0 1 0 8-8z"/><text x="12" y="16.2" font-size="7.5" font-weight="700" text-anchor="middle">10</text></svg></button>
        <button class="play" id="oyna" data-cmd="playpause"><svg id="oynaIkon" viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg></button>
        <button class="gri sk" id="ileri" data-cmd="fwd10"><svg viewBox="0 0 24 24"><path d="M12 5V2l5 4-5 4V7a6 6 0 1 0 6 6h2a8 8 0 1 1-8-8z"/><text x="12" y="16.2" font-size="7.5" font-weight="700" text-anchor="middle">10</text></svg></button>
      </div>
    </div>
    <div class="card">
      <div class="volrow">
        <button class="gri" id="sessiz" data-cmd="mute" aria-label="Mute"><svg class="line" viewBox="0 0 24 24"><path d="M4 9v6h4l5 4V5L8 9H4z"/><path d="M16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12"/></svg></button>
        <input type="range" id="ses" min="0" max="100" value="50" aria-label="Volume">
      </div>
    </div>
    <div class="card" id="zoomKart" hidden>
      <h2 data-i="h_zoom"></h2>
      <div class="izgara g3">
        <button class="gri" data-cmd="zoomout" aria-label="Zoom out">−</button>
        <button class="gri" data-cmd="zoomreset" aria-label="Reset zoom">1×</button>
        <button class="gri" data-cmd="zoomin" aria-label="Zoom in">+</button>
      </div>
    </div>
    <div class="izgara g2" style="margin-bottom:12px">
      <button class="gri" data-cmd="next"><svg viewBox="0 0 24 24"><path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/></svg><span data-i="c_next"></span></button>
      <button class="gri" data-cmd="stop"><svg viewBox="0 0 24 24"><path d="M6 6h12v12H6z"/></svg><span data-i="c_stop"></span></button>
    </div>
    <div class="card" id="siraKart" hidden>
      <h2 data-i="h_queue"></h2>
      <ul id="sira"></ul>
    </div>
  </section>

  <section id="gonder">
    <div class="card">
      <h2 data-i="h_send"></h2>
      <input type="file" id="dosya" accept="video/*" multiple>
      <ul id="dosyaListe"></ul>
      <button id="btn" class="tam" data-i="btn_send"></button>
      <progress id="ilerleme" value="0" max="100" hidden></progress>
    </div>
    <div class="card">
      <h2 data-i="h_url"></h2>
      <div class="satir"><input type="text" id="url" inputmode="url" autocapitalize="none" autocorrect="off" placeholder="https://..."> <button id="urlGonder" data-i="btn_url"></button></div>
      <p class="not" data-i="note_bm" style="margin-top:12px"></p>
      <button class="gri tam" id="bmKopyala" data-i="btn_bm"></button>
      <input type="text" id="bmKod" readonly hidden style="margin-top:8px;font-size:12px">
    </div>
    <div class="card">
      <h2 data-i="h_say"></h2>
      <div class="satir"><input type="text" id="mesaj" maxlength="200"> <button id="mesajGonder" data-i="btn_say"></button></div>
    </div>
  </section>

  <nav class="alt" id="alt">
    <button type="button" data-sekme="ayar" data-i="tab_settings"></button>
    <button type="button" data-sekme="guncelle" data-i="tab_update"></button>
    <button type="button" data-sekme="gunluk" data-i="tab_log"></button>
  </nav>

  <section id="ayar">
    <div class="card">
      <h2 data-i="h_name"></h2>
      <div class="satir"><input type="text" id="ad" maxlength="32"> <button id="adKaydet" data-i="btn_save"></button></div>
    </div>
    <div class="card">
      <h2 data-i="h_trusted"></h2>
      <ul id="liste"></ul>
      <button class="gri tam" id="hepsiniSil" data-i="btn_clear_all"></button>
    </div>
  </section>

  <section id="guncelle">
    <div class="card">
      <h2><span data-i="h_update"></span> <span id="surum"></span></h2>
      <p class="not" data-i="note_update"></p>
      <input type="file" id="apk" accept=".apk,application/vnd.android.package-archive">
      <button id="apkGonder" class="tam" data-i="btn_apk"></button>
      <progress id="apkIlerleme" value="0" max="100" hidden></progress>
    </div>
  </section>

  <section id="gunluk">
    <div class="card">
      <h2 data-i="h_log"></h2>
      <button class="gri tam" id="yenile" data-i="btn_refresh" style="margin-bottom:10px"></button>
      <pre id="gunlukMetin"></pre>
    </div>
  </section>

  <nav class="tabbar" role="tablist">
    <button type="button" data-grp="kumanda"><svg class="line" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M10 8.5v7l6-3.5z" fill="currentColor"/></svg><span data-i="h_remote"></span></button>
    <button type="button" data-grp="gonder"><svg class="line" viewBox="0 0 24 24"><path d="M12 16V4M7 9l5-5 5 5M5 20h14"/></svg><span data-i="tab_send"></span></button>
    <button type="button" data-grp="ayar"><svg class="line" viewBox="0 0 24 24"><path d="M4 7h9M17 7h3M4 17h3M11 17h9"/><circle cx="15" cy="7" r="2"/><circle cx="9" cy="17" r="2"/></svg><span data-i="tab_settings"></span></button>
  </nav>

<script>


var DIL = {
  tr: {
    tab_send: 'Gönder', tab_settings: 'Ayarlar', tab_update: 'Güncelle', tab_log: 'Günlük',
    h_url: 'Bağlantıdan oynat', btn_url: 'Oynat', btn_bm: 'Yer imi kodunu kopyala', note_bm: "Düz adresli (MP4/HLS) bir videoyu TV'de oynatmak için adresini yapıştırın. Safari'de AirPlay simgesi çıkmayan sitelerde: bu kodu bir yer imine yapıştırın, video sayfasındayken yer imine dokunun. blob/DRM'li videolarda (YouTube, Netflix) çalışmaz; onlar için ekran yansıtmayı kullanın.", m_url_sent: 'TV\'ye gönderildi.', m_copied: 'Kopyalandı.', h_send: "TV'ye gönder", btn_send: 'Gönder', h_remote: 'Kumanda', h_say: "TV'ye mesaj", btn_say: 'Göster',
    c_back: '⏪ 10 sn', c_play: '⏯ Oynat / Duraklat', c_fwd: '10 sn ⏩',
    c_voldown: 'Ses −', c_volup: 'Ses +', c_stop: 'Durdur', c_next: 'Sonraki', np_idle: 'Şu an bir şey oynamıyor', np_mirror: 'Ekran yansıtılıyor', np_audio: 'Ses çalıyor', h_queue: 'Sıra', h_zoom: 'Yakınlaştır',
    h_name: 'AirPlay sunucu adı', btn_save: 'Kaydet', h_trusted: 'Güvenilen cihazlar', btn_clear_all: 'Hepsini sil',
    h_update: 'Uygulamayı güncelle',
    note_update: "Uygulamanın release anahtarıyla imzalı APK'sını seç. Doğrulanırsa TV'de kurulum ekranı açılır, kumandayla onayla. Uygulama TV'de açık olmalı.",
    btn_apk: 'APK gönder', h_log: 'Günlük', btn_refresh: 'Yenile', btn_delete: 'Sil',
    m_conn: 'Bağlantı hatası.', m_err: 'Hata', m_badpin: 'PIN yanlış.', m_enterpin: 'Önce PIN gir.',
    m_nodevice: 'Kayıtlı cihaz yok.', m_perm: 'kalıcı', m_temp: 'bu açılışta',
    m_saved: 'Kaydedildi. iPhone listesi birkaç saniye içinde yenilenir.',
    m_confirm_clear: 'Tüm güvenilen cihazlar silinsin mi?',
    m_pick_apk: 'Önce bir APK seç.', m_confirm_apk: "Bu APK TV'ye gönderilip kurulum için hazırlansın mı?",
    m_apk_ok: 'APK alındı ve doğrulandı. TV ekranındaki kurulum onayını verin.', m_sending_apk: 'APK gönderiliyor...',
    m_uploaded: 'Yüklendi.', m_sending: 'Gönderiliyor, TV oynatmaya başlayabilir...', m_pick_video: 'Önce bir video seç.'
  },
  en: {
    tab_send: 'Send', tab_settings: 'Settings', tab_update: 'Update', tab_log: 'Log',
    h_url: 'Play a link', btn_url: 'Play', btn_bm: 'Copy bookmarklet', note_bm: "Paste a direct (MP4/HLS) video address to play it on the TV. For sites where Safari shows no AirPlay icon: paste this code into a bookmark, then tap the bookmark while on the video page. Does not work for blob/DRM videos (YouTube, Netflix); use screen mirroring for those.", m_url_sent: 'Sent to the TV.', m_copied: 'Copied.', h_send: 'Send to TV', btn_send: 'Send', h_remote: 'Remote', h_say: 'Message on TV', btn_say: 'Show',
    c_back: '⏪ 10 s', c_play: '⏯ Play / Pause', c_fwd: '10 s ⏩',
    c_voldown: 'Vol −', c_volup: 'Vol +', c_stop: 'Stop', c_next: 'Next', np_idle: 'Nothing playing', np_mirror: 'Screen mirroring', np_audio: 'Playing audio', h_queue: 'Up next', h_zoom: 'Zoom',
    h_name: 'AirPlay server name', btn_save: 'Save', h_trusted: 'Trusted devices', btn_clear_all: 'Delete all',
    h_update: 'Update the app',
    note_update: 'Choose the APK signed with the same release key. If it checks out, the install screen opens on the TV; confirm with the remote. The app must be open on the TV.',
    btn_apk: 'Send APK', h_log: 'Log', btn_refresh: 'Refresh', btn_delete: 'Delete',
    m_conn: 'Connection error.', m_err: 'Error', m_badpin: 'Wrong PIN.', m_enterpin: 'Enter the PIN first.',
    m_nodevice: 'No saved devices.', m_perm: 'permanent', m_temp: 'this session',
    m_saved: 'Saved. The iPhone list refreshes in a few seconds.',
    m_confirm_clear: 'Delete all trusted devices?',
    m_pick_apk: 'Choose an APK first.', m_confirm_apk: 'Send this APK to the TV and prepare it for installation?',
    m_apk_ok: 'APK received and verified. Confirm the install on the TV screen.', m_sending_apk: 'Sending APK...',
    m_uploaded: 'Uploaded.', m_sending: 'Sending; the TV can start playing...', m_pick_video: 'Choose a video first.'
  },
  es: {
    tab_send: 'Enviar', tab_settings: 'Ajustes', tab_update: 'Actualizar',
    tab_log: 'Registro', h_send: 'Enviar al televisor', btn_send: 'Enviar',
    h_remote: 'Mando', h_say: 'Mensaje en el televisor', btn_say: 'Mostrar', c_back: '⏪ 10 s', c_play: '⏯ Reproducir / Pausa',
    c_fwd: '10 s ⏩', c_voldown: 'Vol −', c_volup: 'Vol +',
    c_stop: 'Detener', c_next: 'Siguiente', np_idle: 'No se está reproduciendo nada', np_mirror: 'Duplicando pantalla', np_audio: 'Reproduciendo audio', h_queue: 'A continuación', h_zoom: 'Zoom', h_name: 'Nombre del servidor AirPlay', btn_save: 'Guardar',
    h_trusted: 'Dispositivos de confianza', btn_clear_all: 'Eliminar todos', h_update: 'Actualizar la app',
    note_update: 'Elige el APK firmado con la clave de release de la app. Si se verifica, se abre la pantalla de instalación en el televisor; confirma con el mando. La app debe estar abierta en el televisor.', btn_apk: 'Enviar APK', h_log: 'Registro',
    btn_refresh: 'Actualizar', btn_delete: 'Eliminar', m_conn: 'Error de conexión.',
    m_err: 'Error', m_badpin: 'PIN incorrecto.', m_enterpin: 'Introduce primero el PIN.',
    m_nodevice: 'No hay dispositivos guardados.', m_perm: 'permanente', m_temp: 'esta sesión',
    m_saved: 'Guardado. La lista del iPhone se actualiza en unos segundos.', m_confirm_clear: '¿Eliminar todos los dispositivos de confianza?', m_pick_apk: 'Elige primero un APK.',
    m_confirm_apk: '¿Enviar este APK al televisor y prepararlo para la instalación?', m_apk_ok: 'APK recibido y verificado. Confirma la instalación en la pantalla del televisor.', m_sending_apk: 'Enviando APK...',
    m_uploaded: 'Subido.', m_sending: 'Enviando; el televisor puede empezar a reproducir...', m_pick_video: 'Elige primero un vídeo.'
  },
  fr: {
    tab_send: 'Envoyer', tab_settings: 'Réglages', tab_update: 'Mise à jour',
    tab_log: 'Journal', h_send: 'Envoyer au téléviseur', btn_send: 'Envoyer',
    h_remote: 'Télécommande', h_say: 'Message sur la TV', btn_say: 'Afficher', c_back: '⏪ 10 s', c_play: '⏯ Lecture / Pause',
    c_fwd: '10 s ⏩', c_voldown: 'Vol −', c_volup: 'Vol +',
    c_stop: 'Arrêter', c_next: 'Suivant', np_idle: 'Aucune lecture en cours', np_mirror: 'Recopie d\'écran', np_audio: 'Lecture audio', h_queue: 'À suivre', h_zoom: 'Zoom', h_name: 'Nom du serveur AirPlay', btn_save: 'Enregistrer',
    h_trusted: 'Appareils de confiance', btn_clear_all: 'Tout supprimer', h_update: 'Mettre à jour l\'application',
    note_update: 'Choisissez l\'APK signé avec la clé de release de l\'application. S\'il est validé, l\'écran d\'installation s\'ouvre sur le téléviseur ; confirmez avec la télécommande. L\'application doit être ouverte sur le téléviseur.', btn_apk: 'Envoyer l\'APK', h_log: 'Journal',
    btn_refresh: 'Actualiser', btn_delete: 'Supprimer', m_conn: 'Erreur de connexion.',
    m_err: 'Erreur', m_badpin: 'PIN incorrect.', m_enterpin: 'Saisissez d\'abord le PIN.',
    m_nodevice: 'Aucun appareil enregistré.', m_perm: 'permanent', m_temp: 'cette session',
    m_saved: 'Enregistré. La liste de l\'iPhone s\'actualise en quelques secondes.', m_confirm_clear: 'Supprimer tous les appareils de confiance ?', m_pick_apk: 'Choisissez d\'abord un APK.',
    m_confirm_apk: 'Envoyer cet APK au téléviseur et le préparer pour l\'installation ?', m_apk_ok: 'APK reçu et vérifié. Confirmez l\'installation sur l\'écran du téléviseur.', m_sending_apk: 'Envoi de l\'APK...',
    m_uploaded: 'Envoyé.', m_sending: 'Envoi en cours ; le téléviseur peut commencer la lecture...', m_pick_video: 'Choisissez d\'abord une vidéo.'
  },
  de: {
    tab_send: 'Senden', tab_settings: 'Einstellungen', tab_update: 'Update',
    tab_log: 'Protokoll', h_send: 'An den Fernseher senden', btn_send: 'Senden',
    h_remote: 'Fernbedienung', h_say: 'Nachricht auf dem TV', btn_say: 'Anzeigen', c_back: '⏪ 10 s', c_play: '⏯ Wiedergabe / Pause',
    c_fwd: '10 s ⏩', c_voldown: 'Lautst. −', c_volup: 'Lautst. +',
    c_stop: 'Stopp', c_next: 'Weiter', np_idle: 'Nichts wird wiedergegeben', np_mirror: 'Bildschirmspiegelung', np_audio: 'Audiowiedergabe', h_queue: 'Als Nächstes', h_zoom: 'Zoom', h_name: 'Name des AirPlay-Servers', btn_save: 'Speichern',
    h_trusted: 'Vertrauenswürdige Geräte', btn_clear_all: 'Alle löschen', h_update: 'App aktualisieren',
    note_update: 'Wähle die APK, die mit dem Release-Schlüssel der App signiert ist. Wird sie bestätigt, öffnet sich der Installationsbildschirm auf dem Fernseher; bestätige mit der Fernbedienung. Die App muss auf dem Fernseher geöffnet sein.', btn_apk: 'APK senden', h_log: 'Protokoll',
    btn_refresh: 'Aktualisieren', btn_delete: 'Löschen', m_conn: 'Verbindungsfehler.',
    m_err: 'Fehler', m_badpin: 'Falsche PIN.', m_enterpin: 'Gib zuerst die PIN ein.',
    m_nodevice: 'Keine gespeicherten Geräte.', m_perm: 'dauerhaft', m_temp: 'diese Sitzung',
    m_saved: 'Gespeichert. Die iPhone-Liste wird in wenigen Sekunden aktualisiert.', m_confirm_clear: 'Alle vertrauenswürdigen Geräte löschen?', m_pick_apk: 'Wähle zuerst eine APK.',
    m_confirm_apk: 'Diese APK an den Fernseher senden und für die Installation vorbereiten?', m_apk_ok: 'APK empfangen und verifiziert. Bestätige die Installation auf dem Fernsehbildschirm.', m_sending_apk: 'APK wird gesendet...',
    m_uploaded: 'Hochgeladen.', m_sending: 'Wird gesendet; der Fernseher kann mit der Wiedergabe beginnen...', m_pick_video: 'Wähle zuerst ein Video.'
  },
  pt: {
    tab_send: 'Enviar', tab_settings: 'Ajustes', tab_update: 'Atualizar',
    tab_log: 'Registro', h_send: 'Enviar para a TV', btn_send: 'Enviar',
    h_remote: 'Controle', h_say: 'Mensagem na TV', btn_say: 'Mostrar', c_back: '⏪ 10 s', c_play: '⏯ Reproduzir / Pausar',
    c_fwd: '10 s ⏩', c_voldown: 'Vol −', c_volup: 'Vol +',
    c_stop: 'Parar', c_next: 'Próximo', np_idle: 'Nada em reprodução', np_mirror: 'Espelhando a tela', np_audio: 'Reproduzindo áudio', h_queue: 'A seguir', h_zoom: 'Zoom', h_name: 'Nome do servidor AirPlay', btn_save: 'Salvar',
    h_trusted: 'Dispositivos confiáveis', btn_clear_all: 'Excluir todos', h_update: 'Atualizar o app',
    note_update: 'Escolha o APK assinado com a chave de release do app. Se for validado, a tela de instalação abre na TV; confirme com o controle. O app precisa estar aberto na TV.', btn_apk: 'Enviar APK', h_log: 'Registro',
    btn_refresh: 'Atualizar', btn_delete: 'Excluir', m_conn: 'Erro de conexão.',
    m_err: 'Erro', m_badpin: 'PIN incorreto.', m_enterpin: 'Digite primeiro o PIN.',
    m_nodevice: 'Nenhum dispositivo salvo.', m_perm: 'permanente', m_temp: 'esta sessão',
    m_saved: 'Salvo. A lista do iPhone é atualizada em alguns segundos.', m_confirm_clear: 'Excluir todos os dispositivos confiáveis?', m_pick_apk: 'Escolha primeiro um APK.',
    m_confirm_apk: 'Enviar este APK para a TV e prepará-lo para instalação?', m_apk_ok: 'APK recebido e verificado. Confirme a instalação na tela da TV.', m_sending_apk: 'Enviando APK...',
    m_uploaded: 'Enviado.', m_sending: 'Enviando; a TV pode começar a reproduzir...', m_pick_video: 'Escolha primeiro um vídeo.'
  },
  it: {
    tab_send: 'Invia', tab_settings: 'Impostazioni', tab_update: 'Aggiorna',
    tab_log: 'Registro', h_send: 'Invia alla TV', btn_send: 'Invia',
    h_remote: 'Telecomando', h_say: 'Messaggio sulla TV', btn_say: 'Mostra', c_back: '⏪ 10 s', c_play: '⏯ Riproduci / Pausa',
    c_fwd: '10 s ⏩', c_voldown: 'Vol −', c_volup: 'Vol +',
    c_stop: 'Ferma', c_next: 'Avanti', np_idle: 'Nessuna riproduzione', np_mirror: 'Mirroring dello schermo', np_audio: 'Riproduzione audio', h_queue: 'Prossimi', h_zoom: 'Zoom', h_name: 'Nome del server AirPlay', btn_save: 'Salva',
    h_trusted: 'Dispositivi attendibili', btn_clear_all: 'Elimina tutti', h_update: 'Aggiorna l\'app',
    note_update: 'Scegli l\'APK firmato con la chiave di release dell\'app. Se viene verificato, sulla TV si apre la schermata di installazione; conferma con il telecomando. L\'app deve essere aperta sulla TV.', btn_apk: 'Invia APK', h_log: 'Registro',
    btn_refresh: 'Aggiorna', btn_delete: 'Elimina', m_conn: 'Errore di connessione.',
    m_err: 'Errore', m_badpin: 'PIN errato.', m_enterpin: 'Inserisci prima il PIN.',
    m_nodevice: 'Nessun dispositivo salvato.', m_perm: 'permanente', m_temp: 'questa sessione',
    m_saved: 'Salvato. L\'elenco dell\'iPhone si aggiorna in pochi secondi.', m_confirm_clear: 'Eliminare tutti i dispositivi attendibili?', m_pick_apk: 'Scegli prima un APK.',
    m_confirm_apk: 'Inviare questo APK alla TV e prepararlo per l\'installazione?', m_apk_ok: 'APK ricevuto e verificato. Conferma l\'installazione sullo schermo della TV.', m_sending_apk: 'Invio dell\'APK...',
    m_uploaded: 'Caricato.', m_sending: 'Invio in corso; la TV può iniziare la riproduzione...', m_pick_video: 'Scegli prima un video.'
  },
  ru: {
    tab_send: 'Отправка', tab_settings: 'Настройки', tab_update: 'Обновление',
    tab_log: 'Журнал', h_send: 'Отправить на ТВ', btn_send: 'Отправить',
    h_remote: 'Пульт', h_say: 'Сообщение на ТВ', btn_say: 'Показать', c_back: '⏪ 10 с', c_play: '⏯ Пуск / Пауза',
    c_fwd: '10 с ⏩', c_voldown: 'Гр. −', c_volup: 'Гр. +',
    c_stop: 'Стоп', c_next: 'Далее', np_idle: 'Ничего не воспроизводится', np_mirror: 'Дублирование экрана', np_audio: 'Воспроизведение звука', h_queue: 'Далее в очереди', h_zoom: 'Масштаб', h_name: 'Имя сервера AirPlay', btn_save: 'Сохранить',
    h_trusted: 'Доверенные устройства', btn_clear_all: 'Удалить все', h_update: 'Обновить приложение',
    note_update: 'Выберите APK, подписанный релизным ключом приложения. Если проверка пройдена, на телевизоре откроется экран установки; подтвердите пультом. Приложение должно быть открыто на телевизоре.', btn_apk: 'Отправить APK', h_log: 'Журнал',
    btn_refresh: 'Обновить', btn_delete: 'Удалить', m_conn: 'Ошибка соединения.',
    m_err: 'Ошибка', m_badpin: 'Неверный PIN.', m_enterpin: 'Сначала введите PIN.',
    m_nodevice: 'Нет сохранённых устройств.', m_perm: 'постоянно', m_temp: 'этот сеанс',
    m_saved: 'Сохранено. Список на iPhone обновится через несколько секунд.', m_confirm_clear: 'Удалить все доверенные устройства?', m_pick_apk: 'Сначала выберите APK.',
    m_confirm_apk: 'Отправить этот APK на телевизор и подготовить к установке?', m_apk_ok: 'APK получен и проверен. Подтвердите установку на экране телевизора.', m_sending_apk: 'Отправка APK...',
    m_uploaded: 'Загружено.', m_sending: 'Отправка; телевизор может начать воспроизведение...', m_pick_video: 'Сначала выберите видео.'
  },
  ja: {
    tab_send: '送信', tab_settings: '設定', tab_update: 'アップデート',
    tab_log: 'ログ', h_send: 'テレビに送信', btn_send: '送信',
    h_remote: 'リモコン', h_say: 'テレビにメッセージ', btn_say: '表示', c_back: '⏪ 10 秒', c_play: '⏯ 再生 / 一時停止',
    c_fwd: '10 秒 ⏩', c_voldown: '音量 −', c_volup: '音量 +',
    c_stop: '停止', c_next: '次へ', np_idle: '再生中のものはありません', np_mirror: '画面ミラーリング中', np_audio: '音声を再生中', h_queue: '次に再生', h_zoom: 'ズーム', h_name: 'AirPlay サーバー名', btn_save: '保存',
    h_trusted: '信頼済みデバイス', btn_clear_all: 'すべて削除', h_update: 'アプリをアップデート',
    note_update: 'アプリのリリース鍵で署名された APK を選択してください。検証に成功すると、テレビにインストール画面が開くので、リモコンで確認します。アプリはテレビで開いている必要があります。', btn_apk: 'APK を送信', h_log: 'ログ',
    btn_refresh: '更新', btn_delete: '削除', m_conn: '接続エラー。',
    m_err: 'エラー', m_badpin: 'PIN が違います。', m_enterpin: '先に PIN を入力してください。',
    m_nodevice: '保存されたデバイスはありません。', m_perm: '常時', m_temp: 'このセッション',
    m_saved: '保存しました。iPhone の一覧は数秒で更新されます。', m_confirm_clear: '信頼済みデバイスをすべて削除しますか?', m_pick_apk: '先に APK を選択してください。',
    m_confirm_apk: 'この APK をテレビに送信し、インストールの準備をしますか?', m_apk_ok: 'APK を受信し、検証しました。テレビの画面でインストールを確認してください。', m_sending_apk: 'APK を送信中...',
    m_uploaded: 'アップロードしました。', m_sending: '送信中です。テレビは再生を開始できます...', m_pick_video: '先に動画を選択してください。'
  },
  ko: {
    tab_send: '보내기', tab_settings: '설정', tab_update: '업데이트',
    tab_log: '로그', h_send: 'TV로 보내기', btn_send: '보내기',
    h_remote: '리모컨', h_say: 'TV에 메시지', btn_say: '표시', c_back: '⏪ 10초', c_play: '⏯ 재생 / 일시정지',
    c_fwd: '10초 ⏩', c_voldown: '볼륨 −', c_volup: '볼륨 +',
    c_stop: '정지', c_next: '다음', np_idle: '재생 중인 항목 없음', np_mirror: '화면 미러링 중', np_audio: '오디오 재생 중', h_queue: '다음 재생', h_zoom: '확대', h_name: 'AirPlay 서버 이름', btn_save: '저장',
    h_trusted: '신뢰하는 기기', btn_clear_all: '모두 삭제', h_update: '앱 업데이트',
    note_update: '앱의 릴리스 키로 서명된 APK를 선택하세요. 확인되면 TV에 설치 화면이 열리며, 리모컨으로 확인하세요. 앱이 TV에서 열려 있어야 합니다.', btn_apk: 'APK 보내기', h_log: '로그',
    btn_refresh: '새로고침', btn_delete: '삭제', m_conn: '연결 오류.',
    m_err: '오류', m_badpin: 'PIN이 틀렸습니다.', m_enterpin: '먼저 PIN을 입력하세요.',
    m_nodevice: '저장된 기기가 없습니다.', m_perm: '영구', m_temp: '이번 세션',
    m_saved: '저장했습니다. iPhone 목록은 몇 초 안에 새로고침됩니다.', m_confirm_clear: '신뢰하는 기기를 모두 삭제할까요?', m_pick_apk: '먼저 APK를 선택하세요.',
    m_confirm_apk: '이 APK를 TV로 보내 설치를 준비할까요?', m_apk_ok: 'APK를 받아 확인했습니다. TV 화면에서 설치를 확인하세요.', m_sending_apk: 'APK 보내는 중...',
    m_uploaded: '업로드했습니다.', m_sending: '보내는 중입니다. TV가 재생을 시작할 수 있습니다...', m_pick_video: '먼저 동영상을 선택하세요.'
  },
  zh: {
    tab_send: '发送', tab_settings: '设置', tab_update: '更新',
    tab_log: '日志', h_send: '发送到电视', btn_send: '发送',
    h_remote: '遥控', h_say: '在电视上显示消息', btn_say: '显示', c_back: '⏪ 10 秒', c_play: '⏯ 播放 / 暂停',
    c_fwd: '10 秒 ⏩', c_voldown: '音量 −', c_volup: '音量 +',
    c_stop: '停止', c_next: '下一个', np_idle: '当前没有播放内容', np_mirror: '正在镜像屏幕', np_audio: '正在播放音频', h_queue: '接下来播放', h_zoom: '缩放', h_name: 'AirPlay 服务器名称', btn_save: '保存',
    h_trusted: '受信任的设备', btn_clear_all: '全部删除', h_update: '更新应用',
    note_update: '请选择使用应用发布密钥签名的 APK。验证通过后,电视上会打开安装界面,请用遥控器确认。应用必须在电视上保持打开。', btn_apk: '发送 APK', h_log: '日志',
    btn_refresh: '刷新', btn_delete: '删除', m_conn: '连接错误。',
    m_err: '错误', m_badpin: 'PIN 错误。', m_enterpin: '请先输入 PIN。',
    m_nodevice: '没有已保存的设备。', m_perm: '永久', m_temp: '本次会话',
    m_saved: '已保存。iPhone 上的列表将在几秒内刷新。', m_confirm_clear: '要删除所有受信任的设备吗?', m_pick_apk: '请先选择 APK。',
    m_confirm_apk: '要把此 APK 发送到电视并准备安装吗?', m_apk_ok: '已收到并验证 APK。请在电视屏幕上确认安装。', m_sending_apk: '正在发送 APK...',
    m_uploaded: '已上传。', m_sending: '正在发送;电视可以开始播放...', m_pick_video: '请先选择视频。'
  },
  ar: {
    tab_send: 'إرسال', tab_settings: 'الإعدادات', tab_update: 'تحديث',
    tab_log: 'السجل', h_send: 'إرسال إلى التلفاز', btn_send: 'إرسال',
    h_remote: 'جهاز التحكم', h_say: 'رسالة على التلفاز', btn_say: 'عرض', c_back: '⏪ 10 ث', c_play: '⏯ تشغيل / إيقاف مؤقت',
    c_fwd: '10 ث ⏩', c_voldown: 'الصوت −', c_volup: 'الصوت +',
    c_stop: 'إيقاف', c_next: 'التالي', np_idle: 'لا يوجد شيء قيد التشغيل', np_mirror: 'عكس الشاشة', np_audio: 'تشغيل الصوت', h_queue: 'التالي في القائمة', h_zoom: 'تكبير', h_name: 'اسم خادم AirPlay', btn_save: 'حفظ',
    h_trusted: 'الأجهزة الموثوقة', btn_clear_all: 'حذف الكل', h_update: 'تحديث التطبيق',
    note_update: 'اختر ملف APK الموقَّع بمفتاح الإصدار الخاص بالتطبيق. إذا تم التحقق منه، تُفتح شاشة التثبيت على التلفاز؛ أكّد بجهاز التحكم. يجب أن يكون التطبيق مفتوحًا على التلفاز.', btn_apk: 'إرسال APK', h_log: 'السجل',
    btn_refresh: 'تحديث', btn_delete: 'حذف', m_conn: 'خطأ في الاتصال.',
    m_err: 'خطأ', m_badpin: 'رمز PIN غير صحيح.', m_enterpin: 'أدخل رمز PIN أولًا.',
    m_nodevice: 'لا توجد أجهزة محفوظة.', m_perm: 'دائم', m_temp: 'هذه الجلسة',
    m_saved: 'تم الحفظ. تتحدث قائمة iPhone خلال ثوانٍ.', m_confirm_clear: 'هل تريد حذف كل الأجهزة الموثوقة؟', m_pick_apk: 'اختر ملف APK أولًا.',
    m_confirm_apk: 'هل تريد إرسال ملف APK هذا إلى التلفاز وتجهيزه للتثبيت؟', m_apk_ok: 'تم استلام ملف APK والتحقق منه. أكّد التثبيت على شاشة التلفاز.', m_sending_apk: 'جارٍ إرسال APK...',
    m_uploaded: 'تم الرفع.', m_sending: 'جارٍ الإرسال؛ يمكن للتلفاز بدء التشغيل...', m_pick_video: 'اختر فيديو أولًا.'
  }
};
var lang = (navigator.language || 'en').slice(0, 2).toLowerCase();
if (!DIL[lang]) lang = 'en';
function L10(k) { return DIL[lang][k] || DIL.en[k] || k; }
(function () {
  document.documentElement.lang = lang;
  document.documentElement.dir = (lang === 'ar') ? 'rtl' : 'ltr';
  var els = document.querySelectorAll('[data-i]');
  for (var n = 0; n < els.length; n++) els[n].textContent = L10(els[n].getAttribute('data-i'));
  document.getElementById('geri').setAttribute('aria-label', L10('c_back'));
  document.getElementById('ileri').setAttribute('aria-label', L10('c_fwd'));
  document.getElementById('oyna').setAttribute('aria-label', L10('c_play'));
})();
var pinInput = document.getElementById('pin');
var durum = document.getElementById('durum');
var ilerleme = document.getElementById('ilerleme');
var params = new URLSearchParams(location.search);
// Ana ekrana eklenmis (tam ekran) kumandada PIN cihazda hatirlanir; tarayici sekmesinde yalnizca oturum boyunca.
var bagimsiz = (window.navigator.standalone === true) || (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches);
function depo() { try { return bagimsiz ? localStorage : sessionStorage; } catch (e) { return null; } }
function pinOku() { try { var d = depo(); return d ? (d.getItem('pin') || '') : ''; } catch (e) { return ''; } }
function pinYaz(v) { try { var d = depo(); if (d) d.setItem('pin', v); } catch (e) {} }
pinInput.value = params.get('pin') || pinOku();
pinInput.onchange = function () { pinYaz(pin()); canli = false; hepsiniYukle(); };
if (params.get('pin')) pinYaz(pin());

function pin() { return pinInput.value.trim(); }
function mesaj(t) { durum.textContent = t || ''; }
function hata(e) { mesaj(e && e.name === 'TypeError' ? L10('m_conn') : (e && e.message) || L10('m_err')); }

// Yanlis PIN kilit sayacina yazilmasin diye, durum sorgusu ancak bir kez dogru PIN'le giris yapildiktan sonra baslar.
var canli = false;

function api(yol, yontem) {
  return fetch(yol, { method: yontem || 'GET', headers: { 'X-Pin': pin() } }).then(function (r) {
    return r.text().then(function (t) {
      if (r.status === 401) {
        canli = false;
        document.body.classList.remove('pinok');
        throw new Error(L10('m_badpin'));
      }
      if (!r.ok) throw new Error(t || (L10('m_err') + ' ' + r.status));
      canli = true;
      document.body.classList.add('pinok');
      return t;
    });
  });
}

function hepsiniYukle() {
  mesaj('');
  if (!pin()) { mesaj(L10('m_enterpin')); return; }
  ayarYukle();
  if (aktifSekme === 'gunluk') gunlukYukle();
}

function ayarYukle() {
  // Yanlis PIN kilit sayacindan tek hak yesin: once ad, basariliysa liste.
  api('/api/name').then(function (t) {
    document.getElementById('ad').value = t;
    return api('/api/version');
  }).then(function (v) {
    document.getElementById('surum').textContent = '(' + v + ')';
    listeYukle();
    durumCek();
    if (bekleyenUrl) {
      var u = bekleyenUrl;
      bekleyenUrl = null;
      try { history.replaceState(null, '', location.pathname + location.hash); } catch (e) {}
      urlGonder(u);
    }
  }).catch(hata);
}

function listeYukle() {
  api('/api/trusted').then(function (t) {
    var ul = document.getElementById('liste');
    ul.textContent = '';
    var satirlar = t.split('\n').filter(function (x) { return x.length > 0; });
    if (!satirlar.length) {
      var bos = document.createElement('li');
      bos.textContent = L10('m_nodevice');
      ul.appendChild(bos);
      return;
    }
    satirlar.forEach(function (x) {
      var li = document.createElement('li');
      var etiket = document.createElement('span');
      etiket.textContent = x.replace(/\((perm|temp)\)$/, function (m, k) { return '(' + L10(k === 'perm' ? 'm_perm' : 'm_temp') + ')'; });
      var sil = document.createElement('button');
      sil.className = 'gri';
      sil.textContent = L10('btn_delete');
      sil.onclick = function () {
        api('/api/trusted?forget=' + encodeURIComponent(x.split(' ')[0]), 'POST').then(listeYukle).catch(hata);
      };
      li.appendChild(etiket);
      li.appendChild(sil);
      ul.appendChild(li);
    });
  }).catch(hata);
}

document.getElementById('adKaydet').onclick = function () {
  var yeni = document.getElementById('ad').value;
  api('/api/name?set=' + encodeURIComponent(yeni), 'POST')
    .then(function () { mesaj(L10('m_saved')); })
    .catch(hata);
};

document.getElementById('hepsiniSil').onclick = function () {
  if (!confirm(L10('m_confirm_clear'))) return;
  api('/api/trusted?clear=1', 'POST').then(listeYukle).catch(hata);
};

document.getElementById('apkGonder').onclick = function () {
  var f = document.getElementById('apk').files[0];
  if (!f) { mesaj(L10('m_pick_apk')); return; }
  if (!pin()) { mesaj(L10('m_enterpin')); return; }
  if (!confirm(L10('m_confirm_apk'))) return;
  var bar = document.getElementById('apkIlerleme');
  var xhr = new XMLHttpRequest();
  xhr.open('PUT', '/update-apk');
  xhr.setRequestHeader('X-Pin', pin());
  xhr.upload.onprogress = function (e) {
    if (e.lengthComputable) { bar.hidden = false; bar.value = e.loaded / e.total * 100; }
  };
  xhr.onload = function () {
    if (xhr.status === 200) mesaj(L10('m_apk_ok'));
    else if (xhr.status === 401) mesaj(L10('m_badpin'));
    else mesaj(xhr.responseText || (L10('m_err') + ' ' + xhr.status));
  };
  xhr.onerror = function () { mesaj(L10('m_conn')); };
  mesaj(L10('m_sending_apk'));
  xhr.send(f);
};

function gunlukYukle() {
  api('/log').then(function (t) {
    var p = document.getElementById('gunlukMetin');
    p.textContent = t;
    p.scrollTop = p.scrollHeight;
  }).catch(hata);
}
document.getElementById('yenile').onclick = gunlukYukle;

// ---- Video gonderme (birden fazla dosya sirayla yuklenir; ilki hemen oynar) ----
function boyut(b) { return b > 1048576 ? (b / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(b / 1024)) + ' KB'; }

function dosyaListele() {
  var ul = document.getElementById('dosyaListe');
  ul.textContent = '';
  var fs = document.getElementById('dosya').files;
  for (var i = 0; i < fs.length; i++) {
    var li = document.createElement('li');
    li.textContent = fs[i].name + ' (' + boyut(fs[i].size) + ')';
    ul.appendChild(li);
  }
}
document.getElementById('dosya').onchange = dosyaListele;

function baslat(dosyalar, i) {
  var f = dosyalar[i];
  var lis = document.querySelectorAll('#dosyaListe li');
  if (lis[i]) lis[i].className = 'suren';
  var xhr = new XMLHttpRequest();
  xhr.open('PUT', '/upload');
  xhr.setRequestHeader('X-Pin', pin());
  xhr.setRequestHeader('X-Name', encodeURIComponent(f.name));
  if (i > 0) xhr.setRequestHeader('X-Queue', '1');
  var sayac = dosyalar.length > 1 ? ' (' + (i + 1) + '/' + dosyalar.length + ')' : '';
  xhr.upload.onprogress = function (e) {
    if (e.lengthComputable) { ilerleme.hidden = false; ilerleme.value = e.loaded / e.total * 100; }
  };
  xhr.onload = function () {
    if (xhr.status === 200) {
      mesaj(L10('m_uploaded') + sayac);
      if (lis[i]) lis[i].className = 'bitti';
      if (i + 1 < dosyalar.length) baslat(dosyalar, i + 1);
      else { ilerleme.hidden = true; durumCek(); }
    }
    else if (xhr.status === 401) mesaj(L10('m_badpin'));
    else mesaj(xhr.responseText || (L10('m_err') + ' ' + xhr.status));
  };
  xhr.onerror = function () { mesaj(L10('m_conn')); };
  mesaj(L10('m_sending') + sayac);
  xhr.send(f);
}

document.getElementById('btn').onclick = function () {
  var dosyalar = Array.prototype.slice.call(document.getElementById('dosya').files);
  if (!dosyalar.length) { mesaj(L10('m_pick_video')); return; }
  api('/control?cmd=ping', 'POST').then(function () { baslat(dosyalar, 0); }).catch(hata);
};

// ---- Baglantidan oynat + yer imi ----
function urlGonder(u) {
  return api('/api/play?u=' + encodeURIComponent(u), 'POST').then(function () { mesaj(L10('m_url_sent')); setTimeout(durumCek, 800); }).catch(hata);
}
document.getElementById('urlGonder').onclick = function () {
  var u = document.getElementById('url').value.trim();
  if (u) urlGonder(u);
};
document.getElementById('bmKopyala').onclick = function () {
  var kod = "javascript:(function(){var v=[].slice.call(document.querySelectorAll('video')),u='',i,s;for(i=0;i<v.length;i++){s=v[i].currentSrc||v[i].src;if(s&&s.indexOf('blob:')!==0){u=s;break}}"
    + "if(!u){var m=document.querySelector('meta[property=\"og:video\"],meta[property=\"og:video:url\"]');u=m&&m.content}"
    + "if(!u){alert('No direct video address found (blob/DRM?)');return}"
    + "var w=window.open('" + location.origin + "/?play='+encodeURIComponent(u),'_blank');if(!w)location.href='" + location.origin + "/?play='+encodeURIComponent(u)})()";
  var alan = document.getElementById('bmKod');
  alan.hidden = false;
  alan.value = kod;
  alan.focus();
  alan.select();
  try { alan.setSelectionRange(0, kod.length); } catch (e) {}
  var ok = false;
  try { ok = document.execCommand('copy'); } catch (e) {}
  mesaj(ok ? L10('m_copied') : '');
};
var bekleyenUrl = params.get('play');

document.getElementById('mesajGonder').onclick = function () {
  api('/api/say?text=' + encodeURIComponent(document.getElementById('mesaj').value), 'POST')
    .then(function () { mesaj(''); }).catch(hata);
};

// ---- Kumanda: "su an oynuyor" karti, ilerleme ve ses cubuklari ----
var IK = {
  play: '<path d="M8 5v14l11-7z"/>',
  pause: '<path d="M6 5h4v14H6zM14 5h4v14h-4z"/>'
};
var seek = document.getElementById('seek');
var ses = document.getElementById('ses');
var surukle = false, sesTut = false, sonSes = 0, sureSn = 0;

function dk(s) {
  s = Math.max(0, Math.floor(s || 0));
  var m = Math.floor(s / 60), x = s % 60;
  return m + ':' + (x < 10 ? '0' : '') + x;
}
function dolgu(el) {
  el.style.setProperty('--p', (el.max > 0 ? (el.value / el.max * 100) : 0) + '%');
}

function cevrimdisi(e) {
  var chip = document.getElementById('chip');
  chip.className = 'chip';
  document.getElementById('chipText').textContent = L10('m_conn');
}

function durumCiz(d) {
  var chip = document.getElementById('chip');
  chip.className = 'chip on';
  document.getElementById('chipText').textContent = d.name || '';
  var np = document.getElementById('np');
  var t = document.getElementById('npTitle');
  var sub = document.getElementById('npSub');
  var calan = (d.state === 'playing' || d.state === 'paused' || d.state === 'loading');
  var title = '', altyazi = '';
  if (d.state === 'mirror') title = L10('np_mirror');
  else if (d.state === 'audio') { title = d.title || L10('np_audio'); altyazi = d.sub || ''; }
  else if (calan) title = d.title || '';
  else title = L10('np_idle');
  np.className = 'card np' + ((calan || d.state === 'audio' || d.state === 'mirror') ? '' : ' bos');
  t.textContent = title;
  sub.textContent = altyazi;
  document.getElementById('oynaIkon').innerHTML = (d.state === 'playing') ? IK.pause : IK.play;
  sureSn = d.dur || 0;
  seek.disabled = !(calan && sureSn > 0);
  if (!surukle) {
    seek.value = (calan && sureSn > 0) ? Math.round(d.pos / sureSn * 1000) : 0;
    dolgu(seek);
    document.getElementById('tPos').textContent = dk(calan ? d.pos : 0);
  }
  document.getElementById('tDur').textContent = dk(calan ? sureSn : 0);
  if (!sesTut && typeof d.vol === 'number') { ses.value = Math.round(d.vol * 100); dolgu(ses); }
  document.getElementById('zoomKart').hidden = d.state !== 'mirror';
  var q = d.queue || [];
  document.getElementById('siraKart').hidden = !q.length;
  var ul = document.getElementById('sira');
  ul.textContent = '';
  q.forEach(function (ad, i) {
    var li = document.createElement('li');
    var n = document.createElement('span'); n.className = 'n'; n.textContent = (i + 1);
    var a = document.createElement('span'); a.className = 'ad'; a.textContent = ad;
    li.appendChild(n); li.appendChild(a);
    ul.appendChild(li);
  });
}

function durumCek() {
  if (!canli || document.hidden) return;
  api('/api/status').then(function (t) { durumCiz(JSON.parse(t)); }).catch(cevrimdisi);
}
setInterval(durumCek, 1000);
document.addEventListener('visibilitychange', function () { if (!document.hidden) durumCek(); });

seek.oninput = function () {
  surukle = true;
  dolgu(seek);
  document.getElementById('tPos').textContent = dk(seek.value / 1000 * sureSn);
};
seek.onchange = function () {
  api('/control?cmd=seek&v=' + (seek.value / 1000 * sureSn).toFixed(1), 'POST').catch(hata);
  setTimeout(function () { surukle = false; durumCek(); }, 600);
};
ses.oninput = function () {
  sesTut = true;
  dolgu(ses);
  var simdi = Date.now();
  if (simdi - sonSes > 150) {
    sonSes = simdi;
    api('/control?cmd=vol&v=' + (ses.value / 100).toFixed(2), 'POST').catch(hata);
  }
};
ses.onchange = function () {
  api('/control?cmd=vol&v=' + (ses.value / 100).toFixed(2), 'POST').catch(hata);
  setTimeout(function () { sesTut = false; }, 800);
};

var dugmeler = document.querySelectorAll('button[data-cmd]');
for (var i = 0; i < dugmeler.length; i++) {
  dugmeler[i].onclick = function () {
    var cmd = this.getAttribute('data-cmd');
    if (cmd === 'playpause') {
      var ik = document.getElementById('oynaIkon');
      ik.innerHTML = (ik.innerHTML.indexOf('M8 5v14') >= 0) ? IK.pause : IK.play;
    }
    api('/control?cmd=' + cmd, 'POST').then(function () { mesaj(''); setTimeout(durumCek, 250); }).catch(hata);
  };
}

// ---- Sekmeler: altta ana sekmeler; Ayarlar'in altinda Guncelle ve Gunluk ----
var sekmeAdlari = ['kumanda', 'gonder', 'ayar', 'guncelle', 'gunluk'];
var aktifSekme = 'kumanda';
function grup(ad) { return (ad === 'guncelle' || ad === 'gunluk') ? 'ayar' : ad; }

function sekmeAc(ad) {
  if (sekmeAdlari.indexOf(ad) < 0) ad = 'kumanda';
  aktifSekme = ad;
  for (var i = 0; i < sekmeAdlari.length; i++) {
    document.getElementById(sekmeAdlari[i]).hidden = sekmeAdlari[i] !== ad;
  }
  var g = grup(ad);
  document.getElementById('alt').hidden = g !== 'ayar';
  var tb = document.querySelectorAll('.tabbar button');
  for (var j = 0; j < tb.length; j++) {
    var on = tb[j].getAttribute('data-grp') === g;
    tb[j].className = on ? 'aktif' : '';
    tb[j].setAttribute('aria-selected', on ? 'true' : 'false');
  }
  var ab = document.querySelectorAll('.alt button');
  for (var k = 0; k < ab.length; k++) {
    ab[k].className = ab[k].getAttribute('data-sekme') === ad ? 'aktif' : '';
  }
  try { history.replaceState(null, '', location.pathname + location.search + '#' + ad); } catch (e) {}
  if (ad === 'gunluk' && pin()) gunlukYukle();
}

var tabDugmeleri = document.querySelectorAll('.tabbar button');
for (var a = 0; a < tabDugmeleri.length; a++) {
  tabDugmeleri[a].onclick = function () { sekmeAc(this.getAttribute('data-grp')); };
}
var altDugmeleri = document.querySelectorAll('.alt button');
for (var b = 0; b < altDugmeleri.length; b++) {
  altDugmeleri[b].onclick = function () { sekmeAc(this.getAttribute('data-sekme')); };
}
window.onhashchange = function () { sekmeAc(location.hash.slice(1)); };

dolgu(seek);
dolgu(ses);
sekmeAc(location.hash.slice(1));
hepsiniYukle();
</script>
</body></html>
"""
    }
}
