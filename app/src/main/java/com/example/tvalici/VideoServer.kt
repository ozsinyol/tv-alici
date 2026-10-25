package com.example.tvalici

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.IOException

/**
 * TV uzerinde calisan kucuk web sunucusu.
 * GET  /               -> video secme ve kumanda sayfasi
 * PUT  /upload         -> videoyu akis halinde alir; yukleme bitmeden oynatma baslar
 * POST /control?cmd=.. -> oynatma kumandasi (playpause, back10, fwd10, volup, voldown, stop)
 * Yukleme ve kumanda isteklerinde X-Pin basligi TV'de gosterilen PIN ile eslesmelidir.
 */
class VideoServer(
    port: Int,
    private val dir: File,
    private val pin: String,
    private val onUploadStart: (Upload) -> Unit,
    private val onControl: (String) -> Unit
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        return when {
            path == "/upload" && session.method == Method.PUT -> handleUpload(session)
            path == "/control" && session.method == Method.POST -> handleControl(session)
            else -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", PAGE)
        }
    }

    private fun pinOk(session: IHTTPSession): Boolean = session.headers["x-pin"] == pin

    private fun handleControl(session: IHTTPSession): Response {
        if (!pinOk(session)) return text("PIN yanlış", Response.Status.UNAUTHORIZED)
        val cmd = session.parms["cmd"] ?: return text("cmd yok", Response.Status.BAD_REQUEST)
        if (cmd != "ping") onControl(cmd)
        return text("OK")
    }

    private fun handleUpload(session: IHTTPSession): Response {
        if (!pinOk(session)) {
            val r = text("PIN yanlış", Response.Status.UNAUTHORIZED)
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

    private fun text(msg: String, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", msg)

    companion object {
        private const val PAGE = """
<!doctype html>
<html lang="tr"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TV'ye Gönder</title>
<style>
  body{font-family:-apple-system,sans-serif;background:#111;color:#fff;margin:0;padding:24px;text-align:center}
  h1{font-size:22px}
  h2{font-size:16px;color:#aaa;margin-top:36px}
  input[type=file]{margin:16px 0;font-size:16px}
  input[type=text]{font-size:20px;width:5em;text-align:center;padding:8px;border-radius:8px;border:0}
  button{font-size:18px;padding:14px 20px;border:0;border-radius:12px;background:#0a84ff;color:#fff;margin:4px}
  button.gri{background:#3a3a3c}
  progress{width:100%;height:14px;margin-top:24px}
  #durum{margin-top:12px;color:#aaa;min-height:1.2em}
</style></head>
<body>
  <h1>TV'ye gönder</h1>
  <div>PIN: <input type="text" id="pin" inputmode="numeric" maxlength="4"></div>
  <input type="file" id="dosya" accept="video/*"><br>
  <button id="btn">Gönder</button>
  <progress id="ilerleme" value="0" max="100" hidden></progress>
  <div id="durum"></div>

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

<script>
var pinInput = document.getElementById('pin');
var durum = document.getElementById('durum');
var ilerleme = document.getElementById('ilerleme');
var params = new URLSearchParams(location.search);
pinInput.value = params.get('pin') || '';

function pin() { return pinInput.value.trim(); }

function baslat(f) {
  var xhr = new XMLHttpRequest();
  xhr.open('PUT', '/upload');
  xhr.setRequestHeader('X-Pin', pin());
  xhr.upload.onprogress = function (e) {
    if (e.lengthComputable) { ilerleme.hidden = false; ilerleme.value = e.loaded / e.total * 100; }
  };
  xhr.onload = function () {
    if (xhr.status === 200) durum.textContent = 'Yüklendi.';
    else if (xhr.status === 401) durum.textContent = 'PIN yanlış.';
    else durum.textContent = 'Hata: ' + xhr.responseText;
  };
  xhr.onerror = function () { durum.textContent = 'Bağlantı hatası.'; };
  durum.textContent = 'Gönderiliyor, TV oynatmaya başlayabilir...';
  xhr.send(f);
}

document.getElementById('btn').onclick = function () {
  var f = document.getElementById('dosya').files[0];
  if (!f) { durum.textContent = 'Önce bir video seç.'; return; }
  fetch('/control?cmd=ping', { method: 'POST', headers: { 'X-Pin': pin() } })
    .then(function (r) {
      if (r.status === 401) { durum.textContent = 'PIN yanlış.'; return; }
      baslat(f);
    })
    .catch(function () { durum.textContent = 'Bağlantı hatası.'; });
};

var dugmeler = document.querySelectorAll('button[data-cmd]');
for (var i = 0; i < dugmeler.length; i++) {
  dugmeler[i].onclick = function () {
    var cmd = this.getAttribute('data-cmd');
    fetch('/control?cmd=' + cmd, { method: 'POST', headers: { 'X-Pin': pin() } })
      .then(function (r) { durum.textContent = r.status === 401 ? 'PIN yanlış.' : ''; })
      .catch(function () { durum.textContent = 'Bağlantı hatası.'; });
  };
}
</script>
</body></html>
"""
    }
}
