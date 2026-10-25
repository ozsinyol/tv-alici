package com.example.tvalici

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * GitHub Releases uzerinden guncelleme kontrolu.
 * Surum etiketi "v<numara>" biciminde olmali (workflow bunu run numarasindan uretir)
 * ve APK'nin versionCode'u ayni numaradir.
 * Not: depo herkese acik (public) olmalidir, aksi halde API yanit vermez.
 */
class Updater(private val activity: AppCompatActivity) {

    private val repo = "ozsinyol/tv-alici"
    private val tag = "Updater"

    fun check() {
        thread(name = "update-check", isDaemon = true) {
            try {
                val conn = URL("https://api.github.com/repos/$repo/releases/latest")
                    .openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                if (conn.responseCode != 200) {
                    Log.w(tag, "Surum bilgisi alinamadi: HTTP ${conn.responseCode}")
                    return@thread
                }
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val remote = json.getString("tag_name").removePrefix("v").toIntOrNull()
                    ?: return@thread
                if (remote <= BuildConfig.VERSION_CODE) return@thread

                val assets = json.getJSONArray("assets")
                var found: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) {
                        found = a.getString("browser_download_url")
                        break
                    }
                }
                val apkUrl = found ?: return@thread
                activity.runOnUiThread { askUser(remote, apkUrl) }
            } catch (e: Exception) {
                Log.w(tag, "Guncelleme kontrolu basarisiz: ${e.message}")
            }
        }
    }

    private fun askUser(remote: Int, apkUrl: String) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle("Güncelleme var")
            .setMessage("Yeni sürüm: v$remote (şu an v${BuildConfig.VERSION_CODE}). İndirip kuralım mı?")
            .setPositiveButton("Güncelle") { _, _ -> download(apkUrl) }
            .setNegativeButton("Sonra", null)
            .show()
    }

    private fun download(apkUrl: String) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Güncelleniyor")
            .setMessage("İndiriliyor...")
            .setCancelable(false)
            .create()
        dialog.show()

        thread(name = "update-download", isDaemon = true) {
            try {
                val dir = File(activity.cacheDir, "updates")
                dir.mkdirs()
                val file = File(dir, "update.apk")
                val conn = URL(apkUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                val total = conn.contentLength
                var done = 0L
                var lastPercent = -1
                conn.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt()
                                if (percent != lastPercent && percent % 5 == 0) {
                                    lastPercent = percent
                                    activity.runOnUiThread { dialog.setMessage("İndiriliyor... %$percent") }
                                }
                            }
                        }
                    }
                }
                activity.runOnUiThread {
                    dialog.dismiss()
                    install(file)
                }
            } catch (e: Exception) {
                Log.w(tag, "Indirme basarisiz: ${e.message}")
                activity.runOnUiThread {
                    dialog.dismiss()
                    Toast.makeText(activity, "İndirme başarısız: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun install(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                activity,
                "Önce bu uygulamaya 'bilinmeyen kaynaklardan yükleme' izni verin, sonra güncellemeyi tekrar deneyin.",
                Toast.LENGTH_LONG
            ).show()
            try {
                val settings = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivity(settings)
            } catch (e: Exception) {
                Log.w(tag, "Izin ekrani acilamadi: ${e.message}")
            }
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, "Kurulum ekranı açılamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
