package com.example.tvalici

import fi.iki.elonen.NanoHTTPD
import java.io.File

/**
 * TV uzerinde calisan kucuk web sunucusu.
 * GET  /        -> video secme sayfasi
 * POST /upload  -> videoyu alir, kaydeder, onVideo ile oynaticiya haber verir
 */
class VideoServer(
    port: Int,
    private val dir: File,
    private val onVideo: (File) -> Unit
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.POST && session.uri == "/upload") {
            return try {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val tmpPath = files["video"] ?: return text("Dosya gelmedi", Response.Status.BAD_REQUEST)
                // Onceki yuklemeleri temizle (onbellek dolmasin).
                dir.listFiles()?.forEach { f ->
                    if (f.name.startsWith("video_")) f.delete()
                }
                val dest = File(dir, "video_${System.currentTimeMillis()}")
                File(tmpPath).copyTo(dest, overwrite = true)
                onVideo(dest)
                text("OK")
            } catch (e: Exception) {
                text("Hata: ${e.message}", Response.Status.INTERNAL_ERROR)
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", PAGE)
    }

    private fun text(msg: String, status: Response.Status = Response.Status.OK) =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", msg)

    companion object {
        private const val PAGE = """
<!doctype html>
<html lang="tr"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TV'ye Gonder</title>
<style>
  body{font-family:-apple-system,sans-serif;background:#111;color:#fff;margin:0;padding:24px;text-align:center}
  h1{font-size:22px}
  input[type=file]{margin:24px 0;font-size:16px}
  button{font-size:18px;padding:14px 28px;border:0;border-radius:12px;background:#0a84ff;color:#fff}
  progress{width:100%;height:14px;margin-top:24px}
  #durum{margin-top:12px;color:#aaa}
</style></head>
<body>
  <h1>Videoyu TV'ye gonder</h1>
  <input type="file" id="dosya" accept="video/*"><br>
  <button id="btn">Gonder</button>
  <progress id="ilerleme" value="0" max="100" hidden></progress>
  <div id="durum"></div>
<script>
const btn = document.getElementById('btn');
const dosya = document.getElementById('dosya');
const ilerleme = document.getElementById('ilerleme');
const durum = document.getElementById('durum');
btn.onclick = () => {
  const f = dosya.files[0];
  if (!f) { durum.textContent = 'Once bir video sec.'; return; }
  const fd = new FormData();
  fd.append('video', f);
  const xhr = new XMLHttpRequest();
  xhr.open('POST', '/upload');
  xhr.upload.onprogress = e => {
    if (e.lengthComputable) { ilerleme.hidden = false; ilerleme.value = e.loaded / e.total * 100; }
  };
  xhr.onload = () => durum.textContent = xhr.status === 200 ? 'TV\'de oynatiliyor.' : 'Hata: ' + xhr.responseText;
  xhr.onerror = () => durum.textContent = 'Baglanti hatasi.';
  durum.textContent = 'Gonderiliyor...';
  xhr.send(fd);
};
</script>
</body></html>
"""
    }
}
