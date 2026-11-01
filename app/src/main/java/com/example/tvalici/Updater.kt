package com.example.tvalici

import android.content.Intent
import android.content.pm.PackageManager
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
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Update check via GitHub Releases.
 * The release tag must be in the form "v<number>" (the workflow generates it from the run number)
 * and the APK's versionCode is the same number.
 * Note: the repository must be public, otherwise the API does not respond.
 */
class Updater(private val activity: AppCompatActivity) {

    private val repo = "ozsinyol/tv-alici"
    private val tag = "Updater"

    /** manual = true: reports the result (latest version / error) on screen; false: asks only if there is a new version. */
    fun check(manual: Boolean = false) {
        fun say(res: Int, vararg args: Any) {
            if (manual) activity.runOnUiThread { Toast.makeText(activity, activity.getString(res, *args), Toast.LENGTH_LONG).show() }
        }
        say(R.string.update_checking)
        thread(name = "update-check", isDaemon = true) {
            try {
                val conn = URL("https://api.github.com/repos/$repo/releases/latest")
                    .openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                if (conn.responseCode != 200) {
                    Log.w(tag, "Could not get the version information: HTTP ${conn.responseCode}")
                    say(R.string.update_check_failed)
                    return@thread
                }
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val remote = json.getString("tag_name").removePrefix("v").toIntOrNull()
                    ?: return@thread
                if (remote <= BuildConfig.VERSION_CODE) {
                    say(R.string.update_latest, BuildConfig.VERSION_CODE.toString())
                    return@thread
                }

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
                Log.w(tag, "Update check failed: ${e.message}")
                say(R.string.update_check_failed)
            }
        }
    }

    private fun askUser(remote: Int, apkUrl: String) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_available_title))
            .setMessage(activity.getString(R.string.update_available_message, remote.toString(), BuildConfig.VERSION_CODE.toString()))
            .setPositiveButton(activity.getString(R.string.update_btn)) { _, _ -> download(remote, apkUrl) }
            .setNegativeButton(activity.getString(R.string.update_later), null)
            .show()
    }

    private fun download(remote: Int, apkUrl: String) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.updating_title))
            .setMessage(activity.getString(R.string.downloading))
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
                                    activity.runOnUiThread { dialog.setMessage(activity.getString(R.string.downloading_percent, percent.toString())) }
                                }
                            }
                        }
                    }
                }
                val problem = verify(file, remote)
                activity.runOnUiThread {
                    dialog.dismiss()
                    if (problem == null) {
                        install(file)
                    } else {
                        file.delete()
                        Log.w(tag, "Update rejected: $problem")
                        Toast.makeText(activity, activity.getString(R.string.update_rejected, problem), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Download failed: ${e.message}")
                activity.runOnUiThread {
                    dialog.dismiss()
                    Toast.makeText(activity, activity.getString(R.string.download_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Verifies the downloaded APK before installing it: package name, version number and the SHA-256
     * fingerprint of the signing certificate (BuildConfig.UPDATE_CERT_SHA256). Returns null if there is no problem, otherwise the reason.
     */
    @Suppress("DEPRECATION")
    private fun verify(file: File, remote: Int?): String? {
        val pm = activity.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageArchiveInfo(file.path, flags) ?: return activity.getString(R.string.apk_unreadable)
        if (info.packageName != activity.packageName) return activity.getString(R.string.pkg_mismatch)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        if (remote != null && code != remote.toLong()) return activity.getString(R.string.version_mismatch)

        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return activity.getString(R.string.no_signature)
            if (si.hasMultipleSigners()) return activity.getString(R.string.multiple_signers)
            si.apkContentsSigners
        } else {
            info.signatures
        }
        if (sigs == null || sigs.size != 1) return activity.getString(R.string.no_signature)
        val digest = MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        if (!hex.equals(BuildConfig.UPDATE_CERT_SHA256, ignoreCase = true)) return activity.getString(R.string.signature_failed)
        return null
    }

    /**
     * For an APK uploaded by hand from the web interface: package name + signing fingerprint (same as the automatic update),
     * and it must not be older than the installed version. Returns null if there is no problem, otherwise the reason.
     */
    @Suppress("DEPRECATION")
    fun verifyUpload(file: File): String? {
        verify(file, null)?.let { return it }
        val info = activity.packageManager.getPackageArchiveInfo(file.path, 0) ?: return activity.getString(R.string.apk_unreadable)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        if (code < BuildConfig.VERSION_CODE) return activity.getString(R.string.older_version, BuildConfig.VERSION_CODE.toString(), code.toString())
        return null
    }

    /** Opens the system's install screen (must be called on the main thread). */
    fun install(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                activity,
                activity.getString(R.string.unknown_sources_toast),
                Toast.LENGTH_LONG
            ).show()
            try {
                val settings = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivity(settings)
            } catch (e: Exception) {
                Log.w(tag, "Could not open the permission screen: ${e.message}")
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
            Toast.makeText(activity, activity.getString(R.string.install_screen_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}
