package com.example.tvalici

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var server: VideoServer? = null
    private lateinit var overlayView: LinearLayout
    private lateinit var logView: TextView
    private val logLines = mutableListOf<String>()

    private var airplayServer: AirPlayServer? = null
    private var airplayAdvertiser: AirPlayAdvertiser? = null

    private val port = 8080
    private val airplayPort = 7000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val playerView = PlayerView(this)
        player = ExoPlayer.Builder(this).build().also { playerView.player = it }

        val url = "http://${localIp()}:$port"

        val qr = ImageView(this).apply { setImageBitmap(qrBitmap(url, 600)) }
        val label = TextView(this).apply {
            text = "iPhone kamerasiyla QR'i okutun\n$url"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }
        logView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }
        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            addView(qr, LinearLayout.LayoutParams(600, 600))
            addView(label, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(logView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }

        setContentView(FrameLayout(this).apply {
            addView(playerView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(overlayView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })

        try {
            server = VideoServer(port, cacheDir) { file -> runOnUiThread { play(file) } }
                .also { it.start() }
        } catch (e: Exception) {
            appendLog("Web sunucusu başlamadı (port $port): ${e.message}")
        }

        try {
            val airplayInfo = AirPlayInfo.load(this, "TV Alici")
            airplayServer = AirPlayServer(airplayPort, airplayInfo) { msg -> appendLog(msg) }
                .also { it.start() }
            airplayAdvertiser = AirPlayAdvertiser(this, airplayInfo, airplayPort) { msg -> appendLog(msg) }
                .also { it.start() }
        } catch (e: Exception) {
            appendLog("AirPlay başlamadı: ${e.message}")
        }

        appendLog("Sürüm v${BuildConfig.VERSION_CODE}")
        Updater(this).check()
    }

    /** Gecici hata ayiklama: son satirlari QR ekraninin altinda gosterir. */
    private fun appendLog(msg: String) {
        Log.i("AirPlay", msg)
        runOnUiThread {
            logLines.add(msg)
            while (logLines.size > 8) logLines.removeAt(0)
            logView.text = logLines.joinToString("\n")
        }
    }

    private fun play(file: File) {
        overlayView.visibility = android.view.View.GONE
        player?.apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }

    /** Geri tusuna basinca oynatmayi durdur, QR ekranina don. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (overlayView.visibility == android.view.View.GONE) {
            player?.stop()
            overlayView.visibility = android.view.View.VISIBLE
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        server?.stop()
        airplayServer?.stop()
        airplayAdvertiser?.stop()
        player?.release()
        super.onDestroy()
    }

    private fun localIp(): String =
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress ?: "0.0.0.0"

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size)
            bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        return bmp
    }
}
