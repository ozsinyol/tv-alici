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
 * GET  /api/version       -> kurulu surum (v<numara>)
 * POST /control?cmd=..    -> oynatma kumandasi (playpause, back10, fwd10, volup, voldown, stop, next, zoomin, zoomout, zoomreset)
 * PUT  /upload + X-Queue: 1 -> video oynatma sirasinin sonuna eklenir (bitince siradaki oynar)
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
    /** Yuklenen APK'yi dogrular, sorun yoksa kurulumu baslatir. Sorunu metin olarak dondurur (null = tamam). */
    private val onApk: (File) -> String?,
    private val trust: TrustAdmin,
    private val names: NameAdmin
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
            path == "/api/say" && post -> handleSay(session)
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
        if (cmd != "ping") onControl(cmd)
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
        val upload = Upload(dest, length, queued)
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

        private const val PAGE = """
<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>AirPlay for Android</title>
<style>
  :root{--bg:#f2f2f7;--card:#fff;--fg:#1c1c1e;--mut:#6e6e73;--line:#e5e5ea;--field:#f2f2f7;--btn:#e9e9ee;--btnfg:#1c1c1e;--acc:#0a84ff;--accfg:#fff;--warn:#b25e00}
  @media (prefers-color-scheme:dark){:root{--bg:#000;--card:#1c1c1e;--fg:#fff;--mut:#98989f;--line:#2c2c2e;--field:#2c2c2e;--btn:#3a3a3c;--btnfg:#fff;--acc:#0a84ff;--accfg:#fff;--warn:#ffd60a}}
  *{box-sizing:border-box}
  [hidden]{display:none!important}
  body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:var(--bg);color:var(--fg);margin:0;padding:16px;max-width:560px;margin:0 auto;-webkit-text-size-adjust:100%}
  header{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:4px 0 14px}
  h1{font-size:22px;font-weight:700;margin:0;letter-spacing:-.3px}
  .pin{display:flex;align-items:center;gap:8px;color:var(--mut);font-size:14px;font-weight:600}
  input[type=text]{font:inherit;font-size:17px;padding:11px 12px;border-radius:12px;border:1px solid transparent;background:var(--field);color:var(--fg);min-width:0;width:100%}
  input[type=text]:focus{outline:2px solid var(--acc);outline-offset:0}
  #pin{width:5.2em;text-align:center;font-size:18px;font-weight:700;letter-spacing:3px;padding:8px;background:var(--card);border:1px solid var(--line)}
  button{font:inherit;font-size:16px;font-weight:600;padding:12px 16px;min-height:48px;border:0;border-radius:12px;background:var(--acc);color:var(--accfg);cursor:pointer;transition:filter .15s,transform .05s}
  button:active{transform:scale(.98);filter:brightness(.92)}
  button.gri{background:var(--btn);color:var(--btnfg)}
  .sekmeler{display:flex;gap:4px;padding:4px;background:var(--btn);border-radius:14px;margin:0 0 14px}
  .sekmeler button{flex:1;min-height:40px;padding:8px 4px;font-size:14px;background:transparent;color:var(--mut);border-radius:10px}
  .sekmeler button.aktif{background:var(--card);color:var(--fg);box-shadow:0 1px 3px rgba(0,0,0,.18)}
  section{display:block;background:var(--card);border-radius:16px;padding:6px 16px 16px;margin-bottom:14px}
  h2{font-size:13px;font-weight:700;text-transform:uppercase;letter-spacing:.6px;color:var(--mut);margin:18px 0 10px}
  .not{color:var(--mut);font-size:14px;line-height:1.4;margin:0 0 12px}
  .satir{display:flex;gap:8px;align-items:stretch}
  .satir button{flex:none}
  .izgara{display:grid;gap:8px;margin-bottom:8px}
  .g2{grid-template-columns:1fr 1fr}
  .g3{grid-template-columns:1fr 1fr 1fr}
  .izgara .genis{grid-column:1/-1}
  input[type=file]{width:100%;font-size:15px;color:var(--mut);padding:10px;border:1.5px dashed var(--line);border-radius:12px;background:var(--field);margin-bottom:10px}
  input[type=file]::file-selector-button{font:inherit;font-weight:600;border:0;border-radius:8px;background:var(--btn);color:var(--btnfg);padding:8px 12px;margin-right:10px}
  .tam{width:100%}
  progress{display:block;width:100%;height:8px;margin-top:12px;border-radius:4px;accent-color:var(--acc)}
  #durum{margin:0 0 12px;color:var(--warn);min-height:1.3em;text-align:center;font-size:15px;font-weight:600}
  ul{list-style:none;padding:0;margin:0 0 12px;text-align:left}
  li{display:flex;justify-content:space-between;align-items:center;gap:8px;padding:6px 0;border-bottom:1px solid var(--line);font-size:15px}
  li button{min-height:36px;padding:6px 12px;font-size:14px}
  pre{text-align:left;background:var(--field);border-radius:12px;padding:12px;font-size:12px;max-height:60vh;overflow:auto;white-space:pre-wrap;word-break:break-word;margin:0 0 10px}
</style></head>
<body>
  <header>
    <h1>AirPlay for Android</h1>
    <label class="pin">PIN <input type="text" id="pin" inputmode="numeric" maxlength="4" autocomplete="off"></label>
  </header>
  <div id="durum" role="status"></div>

  <nav class="sekmeler" role="tablist">
    <button type="button" data-sekme="gonder" data-i="tab_send"></button>
    <button type="button" data-sekme="ayar" data-i="tab_settings"></button>
    <button type="button" data-sekme="guncelle" data-i="tab_update"></button>
    <button type="button" data-sekme="gunluk" data-i="tab_log"></button>
  </nav>

  <section id="gonder">
    <h2 data-i="h_send"></h2>
    <input type="file" id="dosya" accept="video/*" multiple>
    <button id="btn" class="tam" data-i="btn_send"></button>
    <progress id="ilerleme" value="0" max="100" hidden></progress>
    <h2 data-i="h_say"></h2>
    <div class="satir"><input type="text" id="mesaj" maxlength="200"> <button id="mesajGonder" data-i="btn_say"></button></div>
    <h2 data-i="h_remote"></h2>
    <div class="izgara g2">
      <button class="genis" data-cmd="playpause" data-i="c_play"></button>
      <button class="gri" data-cmd="back10" data-i="c_back"></button>
      <button class="gri" data-cmd="fwd10" data-i="c_fwd"></button>
    </div>
    <div class="izgara g2">
      <button class="gri" data-cmd="voldown" data-i="c_voldown"></button>
      <button class="gri" data-cmd="volup" data-i="c_volup"></button>
    </div>
    <div class="izgara g3">
      <button class="gri" data-cmd="zoomout">🔍−</button>
      <button class="gri" data-cmd="zoomreset">1×</button>
      <button class="gri" data-cmd="zoomin">🔍+</button>
    </div>
    <div class="izgara g2">
      <button class="gri" data-cmd="next" data-i="c_next"></button>
      <button class="gri" data-cmd="stop" data-i="c_stop"></button>
    </div>
  </section>

  <section id="ayar">
    <h2 data-i="h_name"></h2>
    <div class="satir"><input type="text" id="ad" maxlength="32"> <button id="adKaydet" data-i="btn_save"></button></div>
    <h2 data-i="h_trusted"></h2>
    <ul id="liste"></ul>
    <button class="gri tam" id="hepsiniSil" data-i="btn_clear_all"></button>
  </section>

  <section id="guncelle">
    <h2><span data-i="h_update"></span> <span id="surum"></span></h2>
    <p class="not" data-i="note_update"></p>
    <input type="file" id="apk" accept=".apk,application/vnd.android.package-archive">
    <button id="apkGonder" class="tam" data-i="btn_apk"></button>
    <progress id="apkIlerleme" value="0" max="100" hidden></progress>
  </section>

  <section id="gunluk">
    <h2 data-i="h_log"></h2>
    <button class="gri tam" id="yenile" data-i="btn_refresh"></button>
    <pre id="gunlukMetin"></pre>
  </section>

<script>
var DIL = {
  tr: {
    tab_send: 'Gönder', tab_settings: 'Ayarlar', tab_update: 'Güncelle', tab_log: 'Günlük',
    h_send: "TV'ye gönder", btn_send: 'Gönder', h_remote: 'Kumanda', h_say: "TV'ye mesaj", btn_say: 'Göster',
    c_back: '⏪ 10 sn', c_play: '⏯ Oynat / Duraklat', c_fwd: '10 sn ⏩',
    c_voldown: 'Ses −', c_volup: 'Ses +', c_stop: 'Durdur', c_next: 'Sonraki',
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
    h_send: 'Send to TV', btn_send: 'Send', h_remote: 'Remote', h_say: 'Message on TV', btn_say: 'Show',
    c_back: '⏪ 10 s', c_play: '⏯ Play / Pause', c_fwd: '10 s ⏩',
    c_voldown: 'Vol −', c_volup: 'Vol +', c_stop: 'Stop', c_next: 'Next',
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
    c_stop: 'Detener', c_next: 'Siguiente', h_name: 'Nombre del servidor AirPlay', btn_save: 'Guardar',
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
    c_stop: 'Arrêter', c_next: 'Suivant', h_name: 'Nom du serveur AirPlay', btn_save: 'Enregistrer',
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
    c_stop: 'Stopp', c_next: 'Weiter', h_name: 'Name des AirPlay-Servers', btn_save: 'Speichern',
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
    c_stop: 'Parar', c_next: 'Próximo', h_name: 'Nome do servidor AirPlay', btn_save: 'Salvar',
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
    c_stop: 'Ferma', c_next: 'Avanti', h_name: 'Nome del server AirPlay', btn_save: 'Salva',
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
    c_stop: 'Стоп', c_next: 'Далее', h_name: 'Имя сервера AirPlay', btn_save: 'Сохранить',
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
    c_stop: '停止', c_next: '次へ', h_name: 'AirPlay サーバー名', btn_save: '保存',
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
    c_stop: '정지', c_next: '다음', h_name: 'AirPlay 서버 이름', btn_save: '저장',
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
    c_stop: '停止', c_next: '下一个', h_name: 'AirPlay 服务器名称', btn_save: '保存',
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
    c_stop: 'إيقاف', c_next: 'التالي', h_name: 'اسم خادم AirPlay', btn_save: 'حفظ',
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
})();
var pinInput = document.getElementById('pin');
var durum = document.getElementById('durum');
var ilerleme = document.getElementById('ilerleme');
var params = new URLSearchParams(location.search);
var kayitli = '';
try { kayitli = sessionStorage.getItem('pin') || ''; } catch (e) {}
pinInput.value = params.get('pin') || kayitli;
pinInput.onchange = function () {
  try { sessionStorage.setItem('pin', pin()); } catch (e) {}
  hepsiniYukle();
};
if (params.get('pin')) { try { sessionStorage.setItem('pin', pin()); } catch (e) {} }

function pin() { return pinInput.value.trim(); }
function mesaj(t) { durum.textContent = t || ''; }
function hata(e) { mesaj(e && e.name === 'TypeError' ? L10('m_conn') : (e && e.message) || L10('m_err')); }

function api(yol, yontem) {
  return fetch(yol, { method: yontem || 'GET', headers: { 'X-Pin': pin() } }).then(function (r) {
    return r.text().then(function (t) {
      if (r.status === 401) throw new Error(L10('m_badpin'));
      if (!r.ok) throw new Error(t || (L10('m_err') + ' ' + r.status));
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

function baslat(dosyalar, i) {
  var f = dosyalar[i];
  var xhr = new XMLHttpRequest();
  xhr.open('PUT', '/upload');
  xhr.setRequestHeader('X-Pin', pin());
  if (i > 0) xhr.setRequestHeader('X-Queue', '1');
  var sayac = dosyalar.length > 1 ? ' (' + (i + 1) + '/' + dosyalar.length + ')' : '';
  xhr.upload.onprogress = function (e) {
    if (e.lengthComputable) { ilerleme.hidden = false; ilerleme.value = e.loaded / e.total * 100; }
  };
  xhr.onload = function () {
    if (xhr.status === 200) {
      mesaj(L10('m_uploaded') + sayac);
      if (i + 1 < dosyalar.length) baslat(dosyalar, i + 1);
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

document.getElementById('mesajGonder').onclick = function () {
  api('/api/say?text=' + encodeURIComponent(document.getElementById('mesaj').value), 'POST')
    .then(function () { mesaj(''); }).catch(hata);
};

var dugmeler = document.querySelectorAll('button[data-cmd]');
for (var i = 0; i < dugmeler.length; i++) {
  dugmeler[i].onclick = function () {
    api('/control?cmd=' + this.getAttribute('data-cmd'), 'POST').then(function () { mesaj(''); }).catch(hata);
  };
}

var sekmeAdlari = ['gonder', 'ayar', 'guncelle', 'gunluk'];
var aktifSekme = 'gonder';

function sekmeAc(ad) {
  if (sekmeAdlari.indexOf(ad) < 0) ad = 'gonder';
  aktifSekme = ad;
  for (var i = 0; i < sekmeAdlari.length; i++) {
    document.getElementById(sekmeAdlari[i]).hidden = sekmeAdlari[i] !== ad;
  }
  var bs = document.querySelectorAll('button[data-sekme]');
  for (var j = 0; j < bs.length; j++) {
    bs[j].className = bs[j].getAttribute('data-sekme') === ad ? 'aktif' : '';
  }
  try { history.replaceState(null, '', location.pathname + location.search + '#' + ad); } catch (e) {}
  if (ad === 'gunluk' && pin()) gunlukYukle();
}

var sekmeDugmeleri = document.querySelectorAll('button[data-sekme]');
for (var k = 0; k < sekmeDugmeleri.length; k++) {
  sekmeDugmeleri[k].onclick = function () { sekmeAc(this.getAttribute('data-sekme')); };
}
window.onhashchange = function () { sekmeAc(location.hash.slice(1)); };

sekmeAc(location.hash.slice(1));
hepsiniYukle();
</script>
</body></html>
"""
    }
}
