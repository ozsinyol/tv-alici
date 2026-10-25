package com.example.tvalici

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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
class MainActivity : AppCompatActivity(), VideoEvents {

    /** Her uygulama acilisinda yeni 4 haneli PIN; QR'a gomulu oldugu icin elle girmek gerekmez. */
    private val pin = "%04d".format(SecureRandom().nextInt(10000))

    private var player: ExoPlayer? = null
    private var server: VideoServer? = null
    private lateinit var overlayView: LinearLayout

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
            mirrorActive = true
            runOnUiThread {
                player?.stop()
                airplayVideoActive = false
                overlayView.visibility = android.view.View.GONE
            }
        }

        override fun mirrorSize(width: Int, height: Int) {
            runOnUiThread { fitMirror(width, height) }
        }

        override fun mirrorStopped() {
            mirrorActive = false
            runOnUiThread {
                if (!airplayVideoActive) overlayView.visibility = android.view.View.VISIBLE
            }
        }
    }

    private val port = 8080
    private val airplayPort = 7000

    // Video AirPlay (/play): oynatici durumu ana is parcacigindan guncellenir, sunucu bunu okur.
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var videoSnapshot = VideoInfo()
    private var airplayVideoActive = false

    @Volatile
    private var mirrorActive = false
    private var pendingStartSec = -1.0
    private var pendingStartFrac = -1.0
    private var lastVideoState = ""

    private val snapshotTicker = object : Runnable {
        override fun run() {
            updateSnapshot()
            if (airplayVideoActive) mainHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        SyncSettings.offsetMs = getSharedPreferences("airplay", Context.MODE_PRIVATE)
            .getInt("syncOffsetMs", 0).coerceIn(-SyncSettings.MAX_MS, SyncSettings.MAX_MS)

        val playerView = PlayerView(this)
        player = ExoPlayer.Builder(this).build().also { p ->
            playerView.player = p
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    appendLog("Oynatma hatası: ${error.errorCodeName}")
                    overlayView.visibility = android.view.View.VISIBLE
                    if (airplayVideoActive) endAirplayVideo()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (!airplayVideoActive) return
                    if (state == Player.STATE_READY && (pendingStartSec >= 0 || pendingStartFrac >= 0)) {
                        val dur = p.duration
                        val ms = when {
                            pendingStartSec >= 0 -> (pendingStartSec * 1000).toLong()
                            dur > 0 -> (pendingStartFrac * dur).toLong()
                            else -> 0L
                        }
                        pendingStartSec = -1.0
                        pendingStartFrac = -1.0
                        if (ms > 0) p.seekTo(ms)
                    }
                    if (state == Player.STATE_ENDED) {
                        reportVideoState("stopped")
                    }
                    updateSnapshot()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!airplayVideoActive) return
                    when {
                        isPlaying -> reportVideoState("playing")
                        p.playbackState == Player.STATE_BUFFERING -> reportVideoState("loading")
                        !p.playWhenReady -> reportVideoState("paused")
                    }
                    updateSnapshot()
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
        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            addView(qr, LinearLayout.LayoutParams(380, 380))
            addView(label, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
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
            val approvals = Approvals(
                getSharedPreferences("airplay", Context.MODE_PRIVATE),
                { ip, answer -> askApproval(ip, answer) },
                { msg -> appendLog(msg) }
            )
            airplayServer = AirPlayServer(airplayPort, airplayInfo, mirrorEvents, this, approvals) { msg -> appendLog(msg) }
                .also { it.start() }
            airplayAdvertiser = AirPlayAdvertiser(this, airplayInfo, airplayPort) { msg -> appendLog(msg) }
                .also { it.start() }
        } catch (e: Exception) {
            appendLog("AirPlay başlamadı: ${e.message}")
        }

        appendLog("Sürüm v${BuildConfig.VERSION_CODE}")
        Updater(this).check()
    }

    // ---- VideoEvents (iPhone Fotoğraflar > AirPlay > video) -------------------------------

    override fun videoPlay(url: String, startSeconds: Double, startFraction: Double) {
        runOnUiThread {
            val p = player ?: return@runOnUiThread
            // Yansitma yuzeyi eski kareyi tutabilir: videoyu kapatmasin diye 1x1'e kucult.
            mirrorView.layoutParams = FrameLayout.LayoutParams(1, 1, Gravity.CENTER)
            overlayView.visibility = android.view.View.GONE
            pendingStartSec = startSeconds
            pendingStartFrac = startFraction
            airplayVideoActive = true
            lastVideoState = ""
            val item = MediaItem.Builder().setUri(url).apply {
                if (url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
            }.build()
            p.setMediaItem(item)
            p.prepare()
            p.playWhenReady = true
            appendLog("Video yükleniyor")
            reportVideoState("loading")
            mainHandler.removeCallbacks(snapshotTicker)
            mainHandler.post(snapshotTicker)
        }
    }

    override fun videoRate(rate: Float) {
        runOnUiThread {
            val p = player ?: return@runOnUiThread
            if (rate <= 0f) {
                p.pause()
            } else {
                p.setPlaybackSpeed(rate)
                p.play()
            }
        }
    }

    override fun videoSeek(seconds: Double) {
        runOnUiThread { player?.seekTo((seconds * 1000).toLong().coerceAtLeast(0L)) }
    }

    override fun videoStop() {
        runOnUiThread {
            player?.stop()
            overlayView.visibility = android.view.View.VISIBLE
            endAirplayVideo(notify = false)
        }
    }

    override fun videoInfo(): VideoInfo = videoSnapshot

    private fun reportVideoState(state: String) {
        if (state == lastVideoState) return
        lastVideoState = state
        airplayServer?.notifyVideoState(state)
    }

    private fun endAirplayVideo(notify: Boolean = true) {
        if (notify) reportVideoState("stopped")
        airplayVideoActive = false
        mainHandler.removeCallbacks(snapshotTicker)
        videoSnapshot = VideoInfo()
    }

    /** Ana is parcacigindan cagrilir; sunucu is parcaciklari bu kopyayi okur. */
    private fun updateSnapshot() {
        val p = player
        if (p == null || !airplayVideoActive) {
            videoSnapshot = VideoInfo()
            return
        }
        val dur = if (p.duration > 0) p.duration / 1000.0 else 0.0
        videoSnapshot = VideoInfo(
            active = true,
            duration = dur,
            position = p.currentPosition / 1000.0,
            rate = if (p.isPlaying) p.playbackParameters.speed else 0f,
            ready = p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_ENDED,
            bufferEmpty = p.playbackState == Player.STATE_BUFFERING
        )
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

    /** Gunluk yalnizca bellekte tutulur; /log sayfasindan okunur, TV ekraninda gosterilmez. */
    private fun appendLog(msg: String) {
        Log.i("AirPlay", msg)
        LogBuffer.add(msg)
    }

    /** Bilinmeyen bir cihaz baglanmak isteyince TV'de izin penceresi acar. */
    private fun askApproval(ip: String, answer: (Approvals.Result) -> Unit): () -> Unit {
        var dialog: androidx.appcompat.app.AlertDialog? = null
        runOnUiThread {
            dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Bağlantı isteği")
                .setMessage("$ip adresindeki cihaz TV'ye bağlanmak istiyor.")
                .setPositiveButton("İzin ver") { _, _ -> answer(Approvals.Result.ONCE) }
                .setNeutralButton("Hep izin ver") { _, _ -> answer(Approvals.Result.ALWAYS) }
                .setNegativeButton("Reddet") { _, _ -> answer(Approvals.Result.DENY) }
                .setOnCancelListener { answer(Approvals.Result.DENY) }
                .show()
        }
        return { runOnUiThread { dialog?.dismiss() } }
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

    /** Yansitma sirasinda SAG/SOL: ses ile goruntu arasindaki kaymayi 20 ms'lik adimlarla ayarlar. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (mirrorActive && (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT)) {
            val step = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) SyncSettings.STEP_MS else -SyncSettings.STEP_MS
            val v = (SyncSettings.offsetMs + step).coerceIn(-SyncSettings.MAX_MS, SyncSettings.MAX_MS)
            SyncSettings.offsetMs = v
            getSharedPreferences("airplay", Context.MODE_PRIVATE).edit().putInt("syncOffsetMs", v).apply()
            val text = when {
                v > 0 -> "Ses/görüntü: görüntü $v ms gecikiyor"
                v < 0 -> "Ses/görüntü: ses ${-v} ms gecikiyor"
                else -> "Ses/görüntü: ayar yok (0 ms)"
            }
            appendLog(text)
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Geri tusuna basinca oynatmayi durdur, QR ekranina don. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (overlayView.visibility == android.view.View.GONE) {
            player?.stop()
            if (airplayVideoActive) endAirplayVideo()
            airplayServer?.stopAllMirrors()
            overlayView.visibility = android.view.View.VISIBLE
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
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
