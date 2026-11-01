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
 * POST /control?cmd=..    -> oynatma kumandasi (playpause, back10, fwd10, volup, voldown, stop)
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

        // Onceki yuklemeleri temizle (onbellek dolmasin).
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith("video_")) f.delete()
        }
        val dest = File(dir, "video_${System.currentTimeMillis()}")
        dest.createNewFile()
        val upload = Upload(dest, length)
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
<meta name="color-scheme" content="dark">
<title>AirPlay for Android</title>
<style>
  [hidden]{display:none!important}
  body{font-family:-apple-system,sans-serif;background:#111;color:#fff;margin:0;padding:20px;max-width:560px;margin:0 auto}
  h1{font-size:22px;text-align:center;margin:8px 0 16px}
  h2{font-size:16px;color:#aaa;margin:28px 0 8px}
  .pin{text-align:center;margin-bottom:12px}
  input[type=text]{font-size:18px;padding:8px;border-radius:8px;border:0;background:#2c2c2e;color:#fff}
  #pin{width:5em;text-align:center;font-size:20px}
  #ad{width:60%}
  button{font-size:17px;padding:12px 18px;border:0;border-radius:12px;background:#0a84ff;color:#fff;margin:4px}
  button.gri{background:#3a3a3c}
  .sekmeler{display:flex;gap:6px;margin:0 0 8px}
  .sekmeler button{flex:1;margin:0;padding:10px 4px;font-size:15px;background:#2c2c2e;color:#ddd}
  .sekmeler button.aktif{background:#0a84ff;color:#fff}
  section{text-align:center;margin-bottom:8px}
  section>h2:first-child{margin-top:20px}
  details{text-align:center}
  .not{color:#aaa;font-size:14px;margin:0 0 8px}
  summary{font-size:16px;color:#aaa;margin:28px 0 8px;cursor:pointer}
  progress{width:100%;height:14px;margin-top:20px}
  #durum{margin:10px 0;color:#ffd60a;min-height:1.3em;text-align:center}
  ul{list-style:none;padding:0;margin:0;text-align:left}
  li{display:flex;justify-content:space-between;align-items:center;padding:6px 0;border-bottom:1px solid #2c2c2e}
  pre{text-align:left;background:#1c1c1e;border-radius:10px;padding:10px;font-size:12px;max-height:60vh;overflow:auto;white-space:pre-wrap;word-break:break-word}
</style></head>
<body>
  <h1>AirPlay for Android</h1>
  <div class="pin">PIN: <input type="text" id="pin" inputmode="numeric" maxlength="4" autocomplete="off"></div>
  <div id="durum" role="status"></div>

  <nav class="sekmeler" role="tablist">
    <button type="button" data-sekme="gonder" data-i="tab_send"></button>
    <button type="button" data-sekme="ayar" data-i="tab_settings"></button>
    <button type="button" data-sekme="guncelle" data-i="tab_update"></button>
    <button type="button" data-sekme="gunluk" data-i="tab_log"></button>
  </nav>

  <section id="gonder">
    <h2 data-i="h_send"></h2>
    <input type="file" id="dosya" accept="video/*"><br>
    <button id="btn" data-i="btn_send"></button>
    <progress id="ilerleme" value="0" max="100" hidden></progress>
    <h2 data-i="h_remote"></h2>
    <div>
      <button class="gri" data-cmd="back10" data-i="c_back"></button>
      <button data-cmd="playpause" data-i="c_play"></button>
      <button class="gri" data-cmd="fwd10" data-i="c_fwd"></button>
    </div>
    <div>
      <button class="gri" data-cmd="voldown" data-i="c_voldown"></button>
      <button class="gri" data-cmd="volup" data-i="c_volup"></button>
      <button class="gri" data-cmd="stop" data-i="c_stop"></button>
    </div>
  </section>

  <section id="ayar">
    <h2 data-i="h_name"></h2>
    <div><input type="text" id="ad" maxlength="32"> <button id="adKaydet" data-i="btn_save"></button></div>
    <h2 data-i="h_trusted"></h2>
    <ul id="liste"></ul>
    <button class="gri" id="hepsiniSil" data-i="btn_clear_all"></button>
  </section>

  <section id="guncelle">
    <h2><span data-i="h_update"></span> <span id="surum"></span></h2>
    <p class="not" data-i="note_update"></p>
    <input type="file" id="apk" accept=".apk,application/vnd.android.package-archive"><br>
    <button id="apkGonder" data-i="btn_apk"></button>
    <progress id="apkIlerleme" value="0" max="100" hidden></progress>
  </section>

  <section id="gunluk">
    <h2 data-i="h_log"></h2>
    <button class="gri" id="yenile" data-i="btn_refresh"></button>
    <pre id="gunlukMetin"></pre>
  </section>

<script>
var DIL = {
  tr: {
    tab_send: 'Gönder', tab_settings: 'Ayarlar', tab_update: 'Güncelle', tab_log: 'Günlük',
    h_send: "TV'ye gönder", btn_send: 'Gönder', h_remote: 'Kumanda',
    c_back: '⏪ 10 sn', c_play: '⏯ Oynat / Duraklat', c_fwd: '10 sn ⏩',
    c_voldown: 'Ses −', c_volup: 'Ses +', c_stop: 'Durdur',
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
    h_send: 'Send to TV', btn_send: 'Send', h_remote: 'Remote',
    c_back: '⏪ 10 s', c_play: '⏯ Play / Pause', c_fwd: '10 s ⏩',
    c_voldown: 'Vol −', c_volup: 'Vol +', c_stop: 'Stop',
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
  }
};
var lang = ((navigator.language || 'en').slice(0, 2).toLowerCase() === 'tr') ? 'tr' : 'en';
function L10(k) { return DIL[lang][k] || DIL.en[k] || k; }
(function () {
  document.documentElement.lang = lang;
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

function baslat(f) {
  var xhr = new XMLHttpRequest();
  xhr.open('PUT', '/upload');
  xhr.setRequestHeader('X-Pin', pin());
  xhr.upload.onprogress = function (e) {
    if (e.lengthComputable) { ilerleme.hidden = false; ilerleme.value = e.loaded / e.total * 100; }
  };
  xhr.onload = function () {
    if (xhr.status === 200) mesaj(L10('m_uploaded'));
    else if (xhr.status === 401) mesaj(L10('m_badpin'));
    else mesaj(xhr.responseText || (L10('m_err') + ' ' + xhr.status));
  };
  xhr.onerror = function () { mesaj(L10('m_conn')); };
  mesaj(L10('m_sending'));
  xhr.send(f);
}

document.getElementById('btn').onclick = function () {
  var f = document.getElementById('dosya').files[0];
  if (!f) { mesaj(L10('m_pick_video')); return; }
  api('/control?cmd=ping', 'POST').then(function () { baslat(f); }).catch(hata);
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
