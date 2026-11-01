package com.example.tvalici

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * TV uzerinde calisan kucuk web sunucusu. Tek sayfalik arayuz (Gonder / Ayarlar / Gunluk).
 * GET  /                  -> arayuz sayfasi (?pin=XXXX ile PIN onceden dolar, #ayar #gunluk ile sekme secilir)
 * PUT  /upload            -> videoyu akis halinde alir; yukleme bitmeden oynatma baslar
 * PUT  /update-apk        -> uygulamanin yeni APK'sini alir; dogrulanirsa TV'de kurulum ekrani acilir
 * GET  /api/status        -> oynatma durumu (JSON: state, title, pos, dur, vol, queue ...)
 * GET  /manifest.json, /apple-touch-icon.png -> ana ekrana eklenebilir (PWA) kumanda
 * GET  /api/version       -> kurulu surum (v<numara>)
 * POST /control?cmd=..    -> oynatma kumandasi (playpause, back10, fwd10, volup, voldown, stop, next, zoomin, zoomout, zoomreset, mute; seek ve vol "cmd:deger" bicimiyle)
 * PUT  /upload + X-Queue: 1 -> video oynatma sirasinin sonuna eklenir (bitince siradaki oynar)
 * POST /api/play?u=URL    -> http(s) video adresini TV'de oynatir (yer imi / adres kutusu)
 * POST /api/say?text=..  -> TV ekraninda buyuk mesaj gosterir (bos metin kaldirir)
 * GET  /api/name          -> AirPlay'de gorunen sunucu adi;  POST /api/name?set=AD -> degistirir
 * GET  /api/trusted       -> guvenilen cihazlar (satir satir);  POST /api/trusted?forget=IP | ?clear=1
 * GET  /log               -> duz metin gunluk (PIN: ?pin=.. ya da X-Pin basligi)
 * GET  /name, /trusted    -> eski adresler; arayuzdeki ilgili sekmeye yonlendirir
 * PIN: X-Pin basligi ya da (yalnizca /log icin) ?pin=. Ayni IP'den 5 yanlis denemeden sonra 5 dk kilit.
 */
/** AirPlay'de gorunen sunucu adini okuma/degistirme. */
interface NameAdmin {
    fun serverName(): String

    /** Gecerliyse adi kaydedip yayini yeniler; gecersizse false doner. */
    fun setServerName(raw: String): Boolean
}

class VideoServer(
    port: Int,
    private val dir: File,
    private val pin: String,
    private val onUploadStart: (Upload) -> Unit,
    private val onControl: (String) -> Unit,
    /** Ekranda buyuk yazi olarak gosterilecek mesaj (bos metin mesaji kaldirir). */
    private val onSay: (String) -> Unit,
    /** Verilen http(s) video adresini TV'de oynatir (duz MP4/HLS). */
    private val onPlayUrl: (String) -> Unit,
    /** Yuklenen APK'yi dogrular, sorun yoksa kurulumu baslatir. Sorunu metin olarak dondurur (null = tamam). */
    private val onApk: (File) -> String?,
    private val trust: TrustAdmin,
    private val names: NameAdmin,
    /** /api/status yaniti (JSON); MainActivity saniyede bir gunceller. */
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

    /** Tarayicinin Accept-Language basligina gore Turkce ya da Ingilizce metin secer. */
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

    /** PIN dogruysa null; yanlissa ya da kilitliyse donecek yaniti verir. Yanlis denemeleri sayar. */
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
        val cmd = session.parms["cmd"] ?: return text("cmd yok", Response.Status.BAD_REQUEST)
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

        // Kuyruga eklenmiyorsa onceki yuklemeleri temizle (onbellek dolmasin).
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
        // FileProvider yalnizca cache/updates/ altini paylasir (file_paths.xml).
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

        // Ana ekrana ekle simgesi (180x180 PNG, base64).
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
