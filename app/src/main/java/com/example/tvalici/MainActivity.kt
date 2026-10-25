package com.example.tvalici

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

@Suppress("UnsafeOptInUsageError")
class MainActivity : AppCompatActivity() {

    /** Her uygulama acilisinda yeni 4 haneli PIN; QR'a gomulu oldugu icin elle girmek gerekmez. */
    private val pin = "%04d".format(SecureRandom().nextInt(10000))

    private var player: ExoPlayer? = null
    private var server: VideoServer? = null
    private lateinit var overlayView: LinearLayout
    private lateinit var logView: TextView
    private val logLines = mutableListOf<String>()

    private var airplayServer: AirPlayServer? = null
    private var airplayAdvertiser: AirPlayAdvertiser? = null

    // Ekran yansitma: MediaCodec bu yuzeye cizer.
    private lateinit var rootLayout: FrameLayout
    private lateinit var mirrorView: SurfaceView

    @Volatile
    private var mirrorSurface: Surface? = null

    private val mirrorEvents = object : MirrorEvents {
        override fun mirrorSurface(): Surface? = this@MainActivity.mirrorSurface

        override fun mirrorStarted() {
            runOnUiThread {
                player?.stop()
                overlayView.visibility = android.view.View.GONE
            }
        }

        override fun mirrorSize(width: Int, height: Int) {
            runOnUiThread { fitMirror(width, height) }
        }

        override fun mirrorStopped() {
            runOnUiThread { overlayView.visibility = android.view.View.VISIBLE }
        }
    }

    private val port = 8080
    private val airplayPort = 7000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val playerView = PlayerView(this)
        player = ExoPlayer.Builder(this).build().also { p ->
            playerView.player = p
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    appendLog("Oynatma hatası: ${error.errorCodeName}")
                    overlayView.visibility = android.view.View.VISIBLE
                }
            })
        }

        val url = "http://${localIp()}:$port"

        val qr = ImageView(this).apply { setImageBitmap(qrBitmap("$url/?pin=$pin", 600)) }
        val label = TextView(this).apply {
            text = "iPhone kamerasiyla QR'i okutun\n$url  PIN: $pin\nGünlük: $url/log?pin=$pin"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }
        logView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }
        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            addView(qr, LinearLayout.LayoutParams(380, 380))
            addView(label, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(logView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }

        mirrorView = SurfaceView(this).apply {
            setZOrderMediaOverlay(true) // ExoPlayer yuzeyinin ustunde cizilsin
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) {
                    mirrorSurface = h.surface
                }

                override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, h2: Int) {
                    mirrorSurface = h.surface
                }

                override fun surfaceDestroyed(h: SurfaceHolder) {
                    mirrorSurface = null
                }
            })
        }
        rootLayout = FrameLayout(this).apply {
            addView(playerView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(
                mirrorView,
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER)
            )
            addView(overlayView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(rootLayout)

        try {
            server = VideoServer(
                port,
                cacheDir,
                pin,
                { upload -> runOnUiThread { play(upload) } },
                { cmd -> runOnUiThread { control(cmd) } }
            ).also { it.start() }
        } catch (e: Exception) {
            appendLog("Web sunucusu başlamadı (port $port): ${e.message}")
        }

        try {
            val airplayInfo = AirPlayInfo.load(this, "TV Alici")
            airplayServer = AirPlayServer(airplayPort, airplayInfo, mirrorEvents) { msg -> appendLog(msg) }
                .also { it.start() }
            airplayAdvertiser = AirPlayAdvertiser(this, airplayInfo, airplayPort) { msg -> appendLog(msg) }
                .also { it.start() }
        } catch (e: Exception) {
            appendLog("AirPlay başlamadı: ${e.message}")
        }

        appendLog("Sürüm v${BuildConfig.VERSION_CODE}")
        Updater(this).check()
    }

    /** Yansitilan goruntuyu en-boy oranini bozmadan ekrana sigdirir (kenarlarda siyah bosluk). */
    private fun fitMirror(videoW: Int, videoH: Int) {
        val cw = rootLayout.width
        val ch = rootLayout.height
        if (cw <= 0 || ch <= 0 || videoW <= 0 || videoH <= 0) return
        val scale = minOf(cw.toFloat() / videoW, ch.toFloat() / videoH)
        val lp = FrameLayout.LayoutParams(
            (videoW * scale).toInt().coerceAtLeast(1),
            (videoH * scale).toInt().coerceAtLeast(1),
            Gravity.CENTER
        )
        mirrorView.layoutParams = lp
    }

    /** Gecici hata ayiklama: son satirlari QR ekraninin altinda gosterir. */
    private fun appendLog(msg: String) {
        Log.i("AirPlay", msg)
        LogBuffer.add(msg)
        runOnUiThread {
            logLines.add(msg)
            while (logLines.size > 8) logLines.removeAt(0)
            logView.text = logLines.joinToString("\n")
        }
    }

    /** Yukleme bitmeden oynatmaya baslar: dosya buyudukce okunur. */
    private fun play(upload: Upload) {
        overlayView.visibility = android.view.View.GONE
        val factory = DataSource.Factory { GrowingFileDataSource(upload) }
        val source = ProgressiveMediaSource.Factory(factory)
            .createMediaSource(MediaItem.fromUri(Uri.fromFile(upload.file)))
        player?.apply {
            setMediaSource(source)
            prepare()
            playWhenReady = true
        }
    }

    /** Web sayfasindaki kumanda dugmeleri (her zaman ana is parcacigindan cagrilir). */
    private fun control(cmd: String) {
        val p = player ?: return
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (cmd) {
            "playpause" -> if (p.isPlaying) p.pause() else p.play()
            "back10" -> p.seekTo(maxOf(0L, p.currentPosition - 10_000L))
            "fwd10" -> {
                val target = p.currentPosition + 10_000L
                val duration = p.duration
                p.seekTo(if (duration > 0) minOf(target, duration) else target)
            }
            "volup" -> audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI
            )
            "voldown" -> audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI
            )
            "stop" -> {
                p.stop()
                overlayView.visibility = android.view.View.VISIBLE
            }
        }
    }

    /** Geri tusuna basinca oynatmayi durdur, QR ekranina don. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (overlayView.visibility == android.view.View.GONE) {
            player?.stop()
            airplayServer?.stopAllMirrors()
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
