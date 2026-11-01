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

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        val get = session.method == Method.GET
        val post = session.method == Method.POST
        return when {
            path == "/upload" && session.method == Method.PUT -> handleUpload(session)
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
            else -> text("Sayfa yok", Response.Status.NOT_FOUND)
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
            return text("Çok fazla yanlış deneme. $sec sn sonra tekrar deneyin.", tooMany)
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
        return text("PIN yanlış", Response.Status.UNAUTHORIZED)
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
            val wanted = session.parms["set"] ?: return text("Ad yok", Response.Status.BAD_REQUEST)
            return if (names.setServerName(wanted)) text("OK")
            else text("Geçersiz ad (1-32 karakter; @ ve kontrol karakterleri olmaz).", Response.Status.BAD_REQUEST)
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
            ?: return text("Boyut bilgisi yok", Response.Status.BAD_REQUEST)

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
                    if (n < 0) throw IOException("Bağlantı koptu")
                    out.write(buf, 0, n)
                    total += n
                    upload.update(total)
                }
            }
            upload.finish(true)
            text("OK")
        } catch (e: Exception) {
            upload.finish(false)
            val r = text("Hata: ${e.message}", Response.Status.INTERNAL_ERROR)
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

        private const val PAGE = """
<!doctype html>
<html lang="tr"><head>
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
  section{text-align:center;margin-bottom:8px}
  section>h2:first-child{margin-top:20px}
  details{text-align:center}
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

  <section id="gonder">
    <h2>TV'ye gönder</h2>
    <input type="file" id="dosya" accept="video/*"><br>
    <button id="btn">Gönder</button>
    <progress id="ilerleme" value="0" max="100" hidden></progress>
    <h2>Kumanda</h2>
    <div>
      <button class="gri" data-cmd="back10">⏪ 10 sn</button>
      <button data-cmd="playpause">⏯ Oynat / Duraklat</button>
      <button class="gri" data-cmd="fwd10">10 sn ⏩</button>
    </div>
    <div>
      <button class="gri" data-cmd="voldown">Ses −</button>
      <button class="gri" data-cmd="volup">Ses +</button>
      <button class="gri" data-cmd="stop">Durdur</button>
    </div>
  </section>

  <section id="ayar">
    <h2>AirPlay sunucu adı</h2>
    <div><input type="text" id="ad" maxlength="32"> <button id="adKaydet">Kaydet</button></div>
    <h2>Güvenilen cihazlar</h2>
    <ul id="liste"></ul>
    <button class="gri" id="hepsiniSil">Hepsini sil</button>
  </section>

  <details id="gunluk">
    <summary>Günlük</summary>
    <button class="gri" id="yenile">Yenile</button>
    <pre id="gunlukMetin"></pre>
  </details>

<script>
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
function hata(e) { mesaj(e && e.name === 'TypeError' ? 'Bağlantı hatası.' : (e && e.message) || 'Hata.'); }

function api(yol, yontem) {
  return fetch(yol, { method: yontem || 'GET', headers: { 'X-Pin': pin() } }).then(function (r) {
    return r.text().then(function (t) {
      if (r.status === 401) throw new Error('PIN yanlış.');
      if (!r.ok) throw new Error(t || ('Hata ' + r.status));
      return t;
    });
  });
}

function hepsiniYukle() {
  mesaj('');
  if (!pin()) { mesaj('Önce PIN gir.'); return; }
  ayarYukle();
  if (document.getElementById('gunluk').open) gunlukYukle();
}

function ayarYukle() {
  // Yanlis PIN kilit sayacindan tek hak yesin: once ad, basariliysa liste.
  api('/api/name').then(function (t) {
    document.getElementById('ad').value = t;
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
      bos.textContent = 'Kayıtlı cihaz yok.';
      ul.appendChild(bos);
      return;
    }
    satirlar.forEach(function (x) {
      var li = document.createElement('li');
      var etiket = document.createElement('span');
      etiket.textContent = x;
      var sil = document.createElement('button');
      sil.className = 'gri';
      sil.textContent = 'Sil';
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
    .then(function () { mesaj('Kaydedildi. iPhone listesi birkaç saniye içinde yenilenir.'); })
    .catch(hata);
};

document.getElementById('hepsiniSil').onclick = function () {
  if (!confirm('Tüm güvenilen cihazlar silinsin mi?')) return;
  api('/api/trusted?clear=1', 'POST').then(listeYukle).catch(hata);
};

function gunlukYukle() {
  api('/log').then(function (t) {
    var p = document.getElementById('gunlukMetin');
    p.textContent = t;
    p.scrollTop = p.scrollHeight;
  }).catch(hata);
}
document.getElementById('yenile').onclick = gunlukYukle;
document.getElementById('gunluk').ontoggle = function () { if (this.open && pin()) gunlukYukle(); };

function baslat(f) {
  var xhr = new XMLHttpRequest();
  xhr.open('PUT', '/upload');
  xhr.setRequestHeader('X-Pin', pin());
  xhr.upload.onprogress = function (e) {
    if (e.lengthComputable) { ilerleme.hidden = false; ilerleme.value = e.loaded / e.total * 100; }
  };
  xhr.onload = function () {
    if (xhr.status === 200) mesaj('Yüklendi.');
    else if (xhr.status === 401) mesaj('PIN yanlış.');
    else mesaj(xhr.responseText || ('Hata ' + xhr.status));
  };
  xhr.onerror = function () { mesaj('Bağlantı hatası.'); };
  mesaj('Gönderiliyor, TV oynatmaya başlayabilir...');
  xhr.send(f);
}

document.getElementById('btn').onclick = function () {
  var f = document.getElementById('dosya').files[0];
  if (!f) { mesaj('Önce bir video seç.'); return; }
  api('/control?cmd=ping', 'POST').then(function () { baslat(f); }).catch(hata);
};

var dugmeler = document.querySelectorAll('button[data-cmd]');
for (var i = 0; i < dugmeler.length; i++) {
  dugmeler[i].onclick = function () {
    api('/control?cmd=' + this.getAttribute('data-cmd'), 'POST').then(function () { mesaj(''); }).catch(hata);
  };
}

if (location.hash === '#gunluk') document.getElementById('gunluk').open = true;
hepsiniYukle();
</script>
</body></html>
"""
    }
}
