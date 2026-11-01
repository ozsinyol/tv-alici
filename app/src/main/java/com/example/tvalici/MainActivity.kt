package com.example.tvalici

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.PowerManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

@Suppress("UnsafeOptInUsageError")
class MainActivity : AppCompatActivity(), VideoEvents, NameAdmin {

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
            mirrorZoom = 1f
            panX = 0f
            panY = 0f
            logWifi()
            runOnUiThread {
                syncStreamLocks()
                player?.stop()
                airplayVideoActive = false
                photoView.visibility = android.view.View.GONE
                overlayView.visibility = android.view.View.GONE
            }
        }

        override fun mirrorSize(width: Int, height: Int) {
            runOnUiThread { fitMirror(width, height) }
        }

        override fun mirrorStopped() {
            mirrorActive = false
            lastMirrorW = 0
            lastMirrorH = 0
            mirrorZoom = 1f
            panX = 0f
            panY = 0f
            runOnUiThread {
                syncStreamLocks()
                if (!airplayVideoActive) overlayView.visibility = android.view.View.VISIBLE
            }
        }
    }

    private val port = 8080
    private val airplayPort = 7000

    private val updater: Updater by lazy { Updater(this) }

    private val approvals: Approvals by lazy {
        Approvals(
            getSharedPreferences("airplay", Context.MODE_PRIVATE),
            { ip, answer -> askApproval(ip, answer) },
            { msg -> appendLog(msg) }
        )
    }
    private lateinit var photoView: ImageView
    private lateinit var overlayContent: LinearLayout
    private val sayView: TextView by lazy {
        TextView(this).apply {
            textSize = 64f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setPadding(80, 80, 80, 80)
            visibility = android.view.View.GONE
        }
    }
    private val hideSay = Runnable { sayView.visibility = android.view.View.GONE }
    private lateinit var trackArt: ImageView
    private lateinit var trackText: TextView
    private lateinit var infoLabel: TextView
    private var airplayInfoRef: AirPlayInfo? = null
    private var serverUrl = ""
    private lateinit var qrView: ImageView
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var baseLabel = ""

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
        SyncSettings.jitterBufferMs = getSharedPreferences("airplay", Context.MODE_PRIVATE)
            .getInt("jitterBufferMs", SyncSettings.AUTO).coerceIn(SyncSettings.AUTO, SyncSettings.MAX_JITTER_MS)

        val playerView = PlayerView(this)
        // Video AirPlay (/play): varsayilan 2,5 sn / 5 sn yerine daha erken baslat ve takilma sonrasi daha cabuk devam et.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_500)
            .build()
        player = ExoPlayer.Builder(this).setLoadControl(loadControl).build().also { p ->
            playerView.player = p
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    appendLog("Oynatma hatası: ${error.errorCodeName}")
                    overlayView.visibility = android.view.View.VISIBLE
                    if (airplayVideoActive) endAirplayVideo()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED && !airplayVideoActive && uploadQueue.isNotEmpty()) {
                        playNext()
                        return
                    }
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
        serverUrl = url

        val qr = ImageView(this).apply { setImageBitmap(qrBitmap("$url/?pin=$pin", 600)) }
        qrView = qr
        val label = TextView(this).apply {
            text = baseLabelText()
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        }
        trackArt = ImageView(this).apply { visibility = android.view.View.GONE }
        trackText = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
            visibility = android.view.View.GONE
        }
        infoLabel = label
        baseLabel = label.text.toString()
        updateAutostartHint()
        overlayContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(qr, LinearLayout.LayoutParams(380, 380))
            addView(label, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(trackArt, LinearLayout.LayoutParams(260, 260).apply { topMargin = 24 })
            addView(trackText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            addView(overlayContent, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
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
        photoView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            visibility = android.view.View.GONE
        }
        rootLayout = FrameLayout(this).apply {
            // PlayerView/denetleyici gibi gorunumler odak alip orta (OK) tusunu yutmasin.
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            addView(playerView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(
                mirrorView,
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER)
            )
            addView(photoView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(overlayView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(sayView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(menuLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(rootLayout)
        // Ekran doner ya da yerlesim degisirse (configChanges oldugu icin Activity yeniden olusmaz) yansitmayi yeniden sigdir.
        rootLayout.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if ((r - l != oldR - oldL || b - t != oldB - oldT) && lastMirrorW > 0 && lastMirrorH > 0) {
                fitMirror(lastMirrorW, lastMirrorH)
            }
        }

        try {
            server = VideoServer(
                port,
                cacheDir,
                pin,
                { upload -> runOnUiThread { play(upload) } },
                { cmd -> runOnUiThread { control(cmd) } },
                { text -> runOnUiThread { say(text) } },
                { file -> acceptApk(file) },
                approvals,
                this,
                { statusJson }
            ).also { it.start() }
        } catch (e: Exception) {
            appendLog("Web sunucusu başlamadı (port $port): ${e.message}")
        }

        try {
            val airplayInfo = AirPlayInfo.load(this, serverName()).also { airplayInfoRef = it }
            airplayServer = AirPlayServer(airplayPort, airplayInfo, mirrorEvents, this, approvals) { msg -> appendLog(msg) }
                .also { it.start() }
            airplayAdvertiser = AirPlayAdvertiser(this, airplayInfo, airplayPort) { msg -> appendLog(msg) }
                .also { it.start() }
        } catch (e: Exception) {
            appendLog("AirPlay başlamadı: ${e.message}")
        }

        startAirPlayService()
        mainHandler.post(statusTicker)
        watchNetwork()
        mainHandler.postDelayed(bounceTicker, 1000)
        appendLog("Sürüm v${BuildConfig.VERSION_CODE}")
        updater.check()
    }

    // ---- VideoEvents (iPhone Fotoğraflar > AirPlay > video) -------------------------------

    override fun deviceConnected(name: String?) {
        runOnUiThread {
            Toast.makeText(this, "👋 " + (name ?: "iPhone"), Toast.LENGTH_LONG).show()
        }
    }

    @Volatile
    private var lastTrack: TrackInfo? = null

    override fun audioTrack(info: TrackInfo) {
        lastTrack = info
        runOnUiThread {
            val text = listOfNotNull(info.title, info.artist, info.album).joinToString("\n")
            trackText.text = text
            trackText.visibility = if (text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    override fun audioArt(image: ByteArray?) {
        val bmp = image?.let { decodeScaled(it) }
        runOnUiThread {
            if (bmp == null) {
                trackArt.setImageDrawable(null)
                trackArt.visibility = android.view.View.GONE
            } else {
                trackArt.setImageBitmap(bmp)
                trackArt.visibility = android.view.View.VISIBLE
            }
        }
    }

    // ---- Ses odagi ve kilitler: akis surerken CPU/Wi-Fi uyumasin, baska uygulamalarin sesi kissin ----

    private var focusRequest: AudioFocusRequest? = null
    private var audioStreaming = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun audioStarted() {
        runOnUiThread {
            audioStreaming = true
            requestAudioFocus()
            syncStreamLocks()
        }
    }

    private fun requestAudioFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { change -> appendLog("Ses odağı değişti: $change") }
                    .build().also { focusRequest = it }
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (e: Exception) {
            appendLog("Ses odağı alınamadı: ${e.message}")
        }
    }

    private fun abandonAudioFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            // zaten birakilmis
        }
    }

    /** Ses ya da yansitma suruyorsa CPU ve Wi-Fi kilitlerini tutar, bitince birakir. */
    private fun syncStreamLocks() {
        val need = audioStreaming || mirrorActive
        try {
            if (need) {
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvalici:stream").apply {
                        setReferenceCounted(false)
                    }
                }
                if (wifiLock == null) {
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                    @Suppress("DEPRECATION")
                    wifiLock = wm?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tvalici:stream")
                        ?.apply { setReferenceCounted(false) }
                }
                // Ust sinirli: unutulursa pil bitmesin (her ses/yansitma baslangicinda yenilenir).
                if (wakeLock?.isHeld != true) wakeLock?.acquire(6 * 60 * 60 * 1000L)
                if (wifiLock?.isHeld != true) wifiLock?.acquire()
            } else {
                if (wakeLock?.isHeld == true) wakeLock?.release()
                if (wifiLock?.isHeld == true) wifiLock?.release()
            }
        } catch (e: Exception) {
            appendLog("Kilit hatası: ${e.message}")
        }
    }

    override fun audioEnded() {
        lastTrack = null
        runOnUiThread {
            audioStreaming = false
            abandonAudioFocus()
            syncStreamLocks()
            trackText.text = ""
            trackText.visibility = android.view.View.GONE
            trackArt.setImageDrawable(null)
            trackArt.visibility = android.view.View.GONE
        }
    }

    override fun videoPlay(url: String, startSeconds: Double, startFraction: Double) {
        runOnUiThread {
            val p = player ?: return@runOnUiThread
            // Yansitma yuzeyi eski kareyi tutabilir: videoyu kapatmasin diye 1x1'e kucult.
            mirrorView.layoutParams = FrameLayout.LayoutParams(1, 1, Gravity.CENTER)
            photoView.visibility = android.view.View.GONE
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
            photoView.visibility = android.view.View.GONE
            overlayView.visibility = android.view.View.VISIBLE
            endAirplayVideo(notify = false)
        }
    }

    override fun videoInfo(): VideoInfo = videoSnapshot

    override fun showPhoto(jpeg: ByteArray) {
        val bmp = decodeScaled(jpeg)
        if (bmp == null) {
            appendLog("Fotoğraf çözülemedi (${jpeg.size} B)")
            return
        }
        runOnUiThread {
            player?.stop()
            if (airplayVideoActive) endAirplayVideo(notify = false)
            mirrorView.layoutParams = FrameLayout.LayoutParams(1, 1, Gravity.CENTER)
            photoView.setImageBitmap(bmp)
            photoView.visibility = android.view.View.VISIBLE
            overlayView.visibility = android.view.View.GONE
            appendLog("Fotoğraf gösteriliyor ${bmp.width}x${bmp.height}")
        }
    }

    /** Buyuk fotograflar bellegi tasirmasin diye en uzun kenar ~2560 pikseli gecmeyecek sekilde acilir. */
    private fun decodeScaled(data: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 2560 || bounds.outHeight / sample > 2560) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        } catch (e: OutOfMemoryError) {
            null
        }
    }

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
    private var lastMirrorW = 0
    private var lastMirrorH = 0

    // Yansitma yakinlastirma: 1x = ekrana sigdir; buyudukce goruntu ekrandan tasar ve kaydirilabilir.
    private var mirrorZoom = 1f
    private var panX = 0f
    private var panY = 0f

    private fun fitMirror(videoW: Int, videoH: Int) {
        lastMirrorW = videoW
        lastMirrorH = videoH
        val cw = rootLayout.width
        val ch = rootLayout.height
        if (cw <= 0 || ch <= 0 || videoW <= 0 || videoH <= 0) return
        val scale = minOf(cw.toFloat() / videoW, ch.toFloat() / videoH) * mirrorZoom
        val w = (videoW * scale).toInt().coerceAtLeast(1)
        val h = (videoH * scale).toInt().coerceAtLeast(1)
        val maxPanX = maxOf(0f, (w - cw) / 2f)
        val maxPanY = maxOf(0f, (h - ch) / 2f)
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
        val lp = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.START)
        lp.leftMargin = ((cw - w) / 2f + panX).toInt()
        lp.topMargin = ((ch - h) / 2f + panY).toInt()
        mirrorView.layoutParams = lp
    }

    private fun setMirrorZoom(z: Float) {
        mirrorZoom = z.coerceIn(1f, 4f)
        if (mirrorZoom == 1f) {
            panX = 0f
            panY = 0f
        }
        if (lastMirrorW > 0 && lastMirrorH > 0) fitMirror(lastMirrorW, lastMirrorH)
    }

    private val scaleDetector by lazy {
        android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                setMirrorZoom(mirrorZoom * detector.scaleFactor)
                return true
            }
        })
    }
    private var touchX = 0f
    private var touchY = 0f

    /** Yansitma sirasinda iki parmakla yakinlastirma, yakinlasinca tek parmakla kaydirma. */
    private fun mirrorTouch(ev: MotionEvent) {
        scaleDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchX = ev.x
                touchY = ev.y
            }
            MotionEvent.ACTION_MOVE -> if (ev.pointerCount == 1 && mirrorZoom > 1f) {
                panX += ev.x - touchX
                panY += ev.y - touchY
                touchX = ev.x
                touchY = ev.y
                fitMirror(lastMirrorW, lastMirrorH)
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                // Parmak kalkinca kalan parmagin konumundan devam et (ani siçrama olmasin).
                val i = if (ev.actionIndex == 0) 1 else 0
                if (ev.pointerCount > 1) {
                    touchX = ev.getX(i)
                    touchY = ev.getY(i)
                }
            }
        }
    }

    /** Gunluk yalnizca bellekte tutulur; /log sayfasindan okunur, TV ekraninda gosterilmez. */
    private fun appendLog(msg: String) {
        Log.i("AirPlay", msg)
        LogBuffer.add(msg)
    }

    /** Bilinmeyen bir cihaz baglanmak isteyince TV'de izin penceresi acar. */
    /** Web arayuzunden yuklenen APK'yi dogrular; sorun yoksa kurulum ekranini acar. null = tamam. */
    private fun acceptApk(file: java.io.File): String? {
        val problem = updater.verifyUpload(file)
        if (problem != null) {
            appendLog("APK reddedildi: $problem")
            return problem
        }
        val needsPermission = android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()
        runOnUiThread { updater.install(file) }
        if (needsPermission) {
            appendLog("APK kurulumu için 'bilinmeyen kaynaklar' izni gerekli")
            return getString(R.string.unknown_sources_web)
        }
        appendLog("APK alındı, kurulum ekranı açılıyor")
        return null
    }

    private fun askApproval(ip: String, answer: (Approvals.Result) -> Unit): () -> Unit {
        var dialog: androidx.appcompat.app.AlertDialog? = null
        runOnUiThread {
            dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.approval_title))
                .setMessage(getString(R.string.approval_message, ip))
                .setPositiveButton(getString(R.string.approval_once)) { _, _ -> answer(Approvals.Result.ONCE) }
                .setNeutralButton(getString(R.string.approval_always)) { _, _ -> answer(Approvals.Result.ALWAYS) }
                .setNegativeButton(getString(R.string.approval_deny)) { _, _ -> answer(Approvals.Result.DENY) }
                .setOnCancelListener { answer(Approvals.Result.DENY) }
                .show()
        }
        return { runOnUiThread { dialog?.dismiss() } }
    }

    /** Yukleme bitmeden oynatmaya baslar: dosya buyudukce okunur. */
    // ---- TV menusu: MENU tusu ya da QR/yansitma ekraninda OK ile acilir (Mi TV Stick'te MENU tusu yok) ----

    private class MenuItem(
        val label: Int,
        val value: () -> String,
        val left: () -> Unit,
        val right: () -> Unit,
        val ok: () -> Unit = right
    )

    private var menuOpen = false
    private var menuIndex = 0
    private val menuHide = Runnable { closeMenu() }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private val menuBox: LinearLayout by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(28, 28, 30))
            setPadding(dp(24), dp(20), dp(24), dp(16))
            isClickable = true // kutunun ici arkadaki "disari tikla = kapat" katmanina gitmesin
        }
    }
    private val menuLayer: FrameLayout by lazy {
        FrameLayout(this).apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            visibility = android.view.View.GONE
            isClickable = true
            setOnClickListener { closeMenu() }
            addView(
                menuBox,
                FrameLayout.LayoutParams(minOf(dp(560), resources.displayMetrics.widthPixels - dp(32)), WRAP_CONTENT, Gravity.CENTER)
            )
        }
    }

    private fun setSyncOffset(v: Int) {
        val c = v.coerceIn(-SyncSettings.MAX_MS, SyncSettings.MAX_MS)
        SyncSettings.offsetMs = c
        getSharedPreferences("airplay", Context.MODE_PRIVATE).edit().putInt("syncOffsetMs", c).apply()
    }

    private fun setBuffer(v: Int) {
        val c = v.coerceIn(0, SyncSettings.MAX_JITTER_MS)
        SyncSettings.jitterBufferMs = c
        getSharedPreferences("airplay", Context.MODE_PRIVATE).edit().putInt("jitterBufferMs", c).apply()
    }

    private fun setBufferAuto() {
        SyncSettings.jitterBufferMs = SyncSettings.AUTO
        getSharedPreferences("airplay", Context.MODE_PRIVATE).edit().putInt("jitterBufferMs", SyncSettings.AUTO).apply()
    }

    private fun bufferText(): String = when {
        SyncSettings.jitterBufferMs == SyncSettings.AUTO -> getString(R.string.buffer_auto, SyncSettings.autoJitterMs.toString())
        SyncSettings.jitterBufferMs == 0 -> getString(R.string.buffer_off)
        else -> "${SyncSettings.jitterBufferMs} ms"
    }

    private fun menuItems(): List<MenuItem> {
        val step = SyncSettings.STEP_MS
        val list = mutableListOf<MenuItem>()
        list += MenuItem(
            R.string.menu_autostart,
            { getString(if (autostartOn()) R.string.on else R.string.off) },
            { toggleAutostart() }, { toggleAutostart() }
        )
        list += MenuItem(
            R.string.menu_sync,
            { (if (SyncSettings.offsetMs > 0) "+" else "") + SyncSettings.offsetMs + " ms" },
            { setSyncOffset(SyncSettings.offsetMs - step) }, { setSyncOffset(SyncSettings.offsetMs + step) },
            { setSyncOffset(0) }
        )
        list += MenuItem(
            R.string.menu_buffer,
            { bufferText() },
            { setBuffer(SyncSettings.effectiveJitterMs - step) }, { setBuffer(SyncSettings.effectiveJitterMs + step) },
            { setBufferAuto() }
        )
        if (mirrorActive && lastMirrorW > 0) {
            list += MenuItem(
                R.string.menu_zoom,
                { "%.2f×".format(java.util.Locale.US, mirrorZoom.toDouble()) },
                { setMirrorZoom(mirrorZoom / 1.25f) }, { setMirrorZoom(mirrorZoom * 1.25f) },
                { setMirrorZoom(1f) }
            )
        }
        list += MenuItem(R.string.menu_close, { "" }, {}, {}, { closeMenu() })
        return list
    }

    private fun renderMenu() {
        val items = menuItems()
        menuIndex = menuIndex.coerceIn(0, items.size - 1)
        menuBox.removeAllViews()
        menuBox.addView(TextView(this).apply {
            text = getString(R.string.menu_title)
            textSize = 26f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        })
        items.forEachIndexed { i, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundColor(if (i == menuIndex) Color.rgb(0, 102, 214) else Color.TRANSPARENT)
                isClickable = true
                // Dokunma: secili satirin "tamam" eylemi; uzun basma: azalt.
                setOnClickListener {
                    menuIndex = i
                    item.ok()
                    scheduleMenuClose()
                    if (menuOpen) renderMenu()
                }
                setOnLongClickListener {
                    menuIndex = i
                    item.left()
                    scheduleMenuClose()
                    if (menuOpen) renderMenu()
                    true
                }
            }
            row.addView(TextView(this).apply {
                text = getString(item.label)
                textSize = 20f
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = item.value()
                textSize = 20f
                setTextColor(Color.WHITE)
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            menuBox.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        menuBox.addView(TextView(this).apply {
            text = getString(R.string.menu_hint)
            textSize = 14f
            setTextColor(Color.rgb(181, 181, 189))
            setPadding(0, dp(12), 0, 0)
        })
    }

    private fun scheduleMenuClose() {
        mainHandler.removeCallbacks(menuHide)
        mainHandler.postDelayed(menuHide, 30_000L)
    }

    private fun openMenu() {
        if (menuOpen) return
        menuOpen = true
        menuIndex = 0
        renderMenu()
        menuLayer.visibility = android.view.View.VISIBLE
        scheduleMenuClose()
    }

    private fun closeMenu() {
        menuOpen = false
        mainHandler.removeCallbacks(menuHide)
        menuLayer.visibility = android.view.View.GONE
    }

    /** Menu aciksa tuslari menu yonetir; ilgilenmedigi tuslar (ses vb.) sisteme birakilir (false). */
    private fun menuKey(keyCode: Int, event: KeyEvent): Boolean {
        val items = menuItems()
        scheduleMenuClose()
        when {
            keyCode == KeyEvent.KEYCODE_DPAD_UP -> menuIndex = (menuIndex - 1 + items.size) % items.size
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> menuIndex = (menuIndex + 1) % items.size
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> items[menuIndex.coerceIn(0, items.size - 1)].left()
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> items[menuIndex.coerceIn(0, items.size - 1)].right()
            isOkKey(keyCode) -> if (event.repeatCount == 0) items[menuIndex.coerceIn(0, items.size - 1)].ok()
            keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU -> if (event.repeatCount == 0) closeMenu()
            else -> return false
        }
        if (menuOpen) renderMenu()
        return true
    }

    /** Web sayfasindan gelen mesaji ekranda 20 sn buyuk yazi olarak gosterir (bos metin kaldirir). */
    private fun say(text: String) {
        mainHandler.removeCallbacks(hideSay)
        if (text.isEmpty()) {
            sayView.visibility = android.view.View.GONE
            return
        }
        sayView.text = text
        sayView.visibility = android.view.View.VISIBLE
        mainHandler.postDelayed(hideSay, 20_000L)
        appendLog("Mesaj gösterildi")
    }

    // ---- Bosta kalinca zıplayan QR (DVD logosu gibi; ekran yanmasini da onler) ---------------

    private var idleSince = SystemClock.elapsedRealtime()
    private var bouncing = false
    private var bx = 0f
    private var by = 0f
    private var bvx = 4f
    private var bvy = 3f
    private val bounceColors = intArrayOf(
        Color.WHITE, Color.rgb(255, 99, 132), Color.rgb(54, 162, 235), Color.rgb(255, 206, 86),
        Color.rgb(75, 192, 120), Color.rgb(153, 102, 255), Color.rgb(255, 159, 64)
    )
    private var bounceColor = 0

    private val bounceTicker = object : Runnable {
        override fun run() {
            bounceStep()
            mainHandler.postDelayed(this, 40)
        }
    }

    private fun bounceStep() {
        val now = SystemClock.elapsedRealtime()
        if (overlayView.visibility != android.view.View.VISIBLE) {
            idleSince = now
            if (bouncing) stopBounce()
            return
        }
        if (now - idleSince < 20_000L) return
        bouncing = true
        val maxX = (overlayView.width - overlayContent.width) / 2f
        val maxY = (overlayView.height - overlayContent.height) / 2f
        var hitX = false
        var hitY = false
        bx += bvx
        by += bvy
        if (maxX <= 0f) bx = 0f
        else if (bx > maxX) { bx = maxX; bvx = -bvx; hitX = true }
        else if (bx < -maxX) { bx = -maxX; bvx = -bvx; hitX = true }
        if (maxY <= 0f) by = 0f
        else if (by > maxY) { by = maxY; bvy = -bvy; hitY = true }
        else if (by < -maxY) { by = -maxY; bvy = -bvy; hitY = true }
        if (hitX || hitY) {
            bounceColor = (bounceColor + 1) % bounceColors.size
            infoLabel.setTextColor(bounceColors[bounceColor])
            trackText.setTextColor(bounceColors[bounceColor])
            if (hitX && hitY) Toast.makeText(this, "🎉", Toast.LENGTH_SHORT).show()
        }
        overlayContent.translationX = bx
        overlayContent.translationY = by
    }

    private fun stopBounce() {
        bouncing = false
        bx = 0f
        by = 0f
        overlayContent.translationX = 0f
        overlayContent.translationY = 0f
        infoLabel.setTextColor(Color.WHITE)
        trackText.setTextColor(Color.WHITE)
    }

    private fun userActivity() {
        idleSince = SystemClock.elapsedRealtime()
        if (bouncing) stopBounce()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        userActivity()
        if (mirrorActive && lastMirrorW > 0) mirrorTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ---- /api/status: web kumandasinin "su an oynuyor" bilgisi (saniyede bir, ana is parcacigindan) ----

    @Volatile
    private var statusJson = """{"state":"idle"}"""

    private val statusTicker = object : Runnable {
        override fun run() {
            try {
                statusJson = buildStatus()
            } catch (e: Exception) {
                // bir sonraki turda yeniden denenir
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    private fun buildStatus(): String {
        val p = player
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val o = org.json.JSONObject()
        val playing = p != null && p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED &&
            (currentUpload != null || airplayVideoActive)
        when {
            mirrorActive -> o.put("state", "mirror").put("zoom", mirrorZoom.toDouble())
            playing && p != null -> {
                o.put("state", if (p.playbackState == Player.STATE_BUFFERING) "loading" else if (p.isPlaying) "playing" else "paused")
                o.put("title", currentUpload?.name?.takeIf { it.isNotEmpty() } ?: "AirPlay")
                o.put("pos", p.currentPosition / 1000.0)
                o.put("dur", if (p.duration > 0) p.duration / 1000.0 else 0.0)
            }
            audioStreaming -> {
                o.put("state", "audio")
                lastTrack?.let { t ->
                    o.put("title", t.title ?: "")
                    o.put("sub", listOfNotNull(t.artist, t.album).joinToString(" · "))
                }
            }
            else -> o.put("state", "idle")
        }
        o.put("vol", audio.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / max)
        o.put("name", serverName())
        val q = org.json.JSONArray()
        uploadQueue.toList().forEach { q.put(it.name.ifEmpty { "video" }) }
        o.put("queue", q)
        return o.toString()
    }

    // Web sayfasindan yuklenen videolarin sirasi (X-Queue: 1 ile gelenler).
    private val uploadQueue = ArrayDeque<Upload>()
    private var currentUpload: Upload? = null

    private fun play(upload: Upload) {
        val p = player
        val busy = p != null && currentUpload != null && !airplayVideoActive &&
            p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED
        if (upload.queued && busy) {
            uploadQueue.addLast(upload)
            appendLog("Kuyruğa eklendi (${uploadQueue.size} bekliyor)")
            return
        }
        uploadQueue.clear()
        start(upload)
    }

    /** Siradaki videoya gecer; sira bossa false doner. */
    private fun playNext(): Boolean {
        val next = uploadQueue.removeFirstOrNull() ?: return false
        val old = currentUpload
        start(next)
        if (old != null && old !== next) runCatching { old.file.delete() }
        appendLog("Sıradaki video (${uploadQueue.size} bekliyor)")
        return true
    }

    private fun start(upload: Upload) {
        currentUpload = upload
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
    private fun control(raw: String) {
        val p = player ?: return
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val cmd = raw.substringBefore(':')
        val arg = if (raw.contains(':')) raw.substringAfter(':') else ""
        when (cmd) {
            "seek" -> arg.toDoubleOrNull()?.let { sec ->
                val dur = p.duration
                val ms = (sec * 1000).toLong().coerceAtLeast(0L)
                p.seekTo(if (dur > 0) minOf(ms, dur) else ms)
            }
            "vol" -> arg.toFloatOrNull()?.let { v ->
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v.coerceIn(0f, 1f) * max), 0)
            }
            "mute" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
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
            "zoomin" -> setMirrorZoom(mirrorZoom * 1.25f)
            "zoomout" -> setMirrorZoom(mirrorZoom / 1.25f)
            "zoomreset" -> setMirrorZoom(1f)
            "next" -> if (!playNext()) {
                p.stop()
                overlayView.visibility = android.view.View.VISIBLE
            }
            "stop" -> {
                uploadQueue.clear()
                p.stop()
                overlayView.visibility = android.view.View.VISIBLE
            }
        }
    }

    /** Wi-Fi bandi, sinyal ve baglanti hizi gunluge yazilir (titreme teshisi icin). */
    private fun logWifi() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return
            @Suppress("DEPRECATION")
            val i = wm.connectionInfo ?: return
            val f = i.frequency
            val band = when {
                f >= 5925 -> "6 GHz"
                f >= 4900 -> "5 GHz"
                f > 0 -> "2,4 GHz"
                else -> "bilinmiyor"
            }
            appendLog("Wi-Fi: $band ($f MHz), sinyal ${i.rssi} dBm, bağlantı hızı ${i.linkSpeed} Mbps")
        } catch (e: Exception) {
            appendLog("Wi-Fi bilgisi okunamadı: ${e.javaClass.simpleName}")
        }
    }

    /** Yansitma sirasinda SAG/SOL: ses ile goruntu arasindaki kaymayi 20 ms'lik adimlarla ayarlar. */
    /** Tam ekran: durum ve gezinme cubuklari gizlenir (telefonda yansitma tum ekrani kullansin). */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val wasBouncing = bouncing
        userActivity()
        // Ziplayan QR'i durduran OK tusu ayri bir islem (otomatik baslatma degisimi) tetiklemesin.
        if (wasBouncing && isOkKey(keyCode)) return true
        if (event.repeatCount == 0) appendLog("Tuş: ${KeyEvent.keyCodeToString(keyCode)}")
        if (menuOpen) return menuKey(keyCode, event) || super.onKeyDown(keyCode, event)
        // MENU her zaman menuyu acar; OK da (MENU tusu olmayan kumandalar icin) QR ya da yansitma ekraninda acar.
        if (keyCode == KeyEvent.KEYCODE_MENU ||
            (isOkKey(keyCode) && (mirrorActive || overlayView.visibility == android.view.View.VISIBLE))
        ) {
            if (event.repeatCount == 0) openMenu()
            return true
        }
        if (mirrorActive && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            // Otomatikteyken ilk basista guncel otomatik degerden elle kipe gecilir.
            val step = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) SyncSettings.STEP_MS else -SyncSettings.STEP_MS
            val v = (SyncSettings.effectiveJitterMs + step).coerceIn(0, SyncSettings.MAX_JITTER_MS)
            SyncSettings.jitterBufferMs = v
            getSharedPreferences("airplay", Context.MODE_PRIVATE).edit().putInt("jitterBufferMs", v).apply()
            val text = if (v == 0) getString(R.string.jitter_off)
            else getString(R.string.jitter_manual, v.toString())
            appendLog(text)
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
            return true
        }
        if (mirrorActive && (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT)) {
            val step = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) SyncSettings.STEP_MS else -SyncSettings.STEP_MS
            val v = (SyncSettings.offsetMs + step).coerceIn(-SyncSettings.MAX_MS, SyncSettings.MAX_MS)
            SyncSettings.offsetMs = v
            getSharedPreferences("airplay", Context.MODE_PRIVATE).edit().putInt("syncOffsetMs", v).apply()
            val text = when {
                v > 0 -> getString(R.string.sync_video_late, v.toString())
                v < 0 -> getString(R.string.sync_audio_late, (-v).toString())
                else -> getString(R.string.sync_none)
            }
            appendLog(text)
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun isOkKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A

    private fun toggleAutostart() {
        val on = !autostartOn()
        getSharedPreferences("airplay", Context.MODE_PRIVATE).edit().putBoolean("autostart", on).apply()
        updateAutostartHint()
        Toast.makeText(this, getString(R.string.autostart_toast, getString(if (on) R.string.on else R.string.off)), Toast.LENGTH_SHORT).show()
    }

    private fun baseLabelText(): String =
        getString(R.string.qr_hint, serverUrl, pin, serverName())

    override fun serverName(): String =
        getSharedPreferences("airplay", Context.MODE_PRIVATE).getString("serverName", null) ?: "TV Alici"

    override fun setServerName(raw: String): Boolean {
        val n = raw.replace(Regex("[\\p{Cntrl}@]"), " ").trim().replace(Regex("\\s+"), " ")
        if (n.isEmpty() || n.length > 32 || n.toByteArray().size > 40) return false
        getSharedPreferences("airplay", Context.MODE_PRIVATE).edit().putString("serverName", n).apply()
        airplayInfoRef?.name = n
        airplayAdvertiser?.restart()
        appendLog("AirPlay adı değişti: $n")
        runOnUiThread {
            baseLabel = baseLabelText()
            updateAutostartHint()
        }
        return true
    }

    private fun autostartOn(): Boolean =
        getSharedPreferences("airplay", Context.MODE_PRIVATE).getBoolean("autostart", false)

    private fun updateAutostartHint() {
        infoLabel.text = baseLabel + "\n" + getString(R.string.autostart_hint, getString(if (autostartOn()) R.string.on else R.string.off))
    }

    /** Geri tusuna basinca oynatmayi durdur, QR ekranina don. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (menuOpen) {
            closeMenu()
            return
        }
        if (overlayView.visibility == android.view.View.GONE) {
            player?.stop()
            photoView.visibility = android.view.View.GONE
            if (airplayVideoActive) endAirplayVideo()
            airplayServer?.stopAllMirrors()
            // stopAllMirrors mirrorStopped() cagirmaz; bayrak sifirlanmazsa QR ekraninda OK jitter ayarini degistirir.
            mirrorActive = false
            lastMirrorW = 0
            lastMirrorH = 0
            mirrorZoom = 1f
            panX = 0f
            panY = 0f
            audioStreaming = false
            abandonAudioFocus()
            syncStreamLocks()
            overlayView.visibility = android.view.View.VISIBLE
        } else {
            super.onBackPressed()
        }
    }

    /** Wi-Fi/DHCP degisince adresi, QR'i ve etiketi tazeler. */
    private fun watchNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshAddress()
            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) = refreshAddress()
        }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
            netCallback = cb
        } catch (e: Exception) {
            appendLog("Ağ izleme başlatılamadı: ${e.message}")
        }
    }

    private fun refreshAddress() {
        val url = try {
            "http://${localIp()}:$port"
        } catch (e: Exception) {
            return
        }
        runOnUiThread {
            if (url == serverUrl || url.startsWith("http://0.0.0.0")) return@runOnUiThread
            serverUrl = url
            qrView.setImageBitmap(qrBitmap("$url/?pin=$pin", 600))
            baseLabel = baseLabelText()
            updateAutostartHint()
            appendLog("Adres değişti: $url")
        }
    }

    /** Surecin arka planda oldurulmemesi icin on plan servisini baslatir (basarisiz olursa uygulama yine calisir). */
    private fun startAirPlayService() {
        try {
            val i = Intent(this, AirPlayService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
            appendLog("Arka plan servisi başlamadı: ${e.message}")
        }
    }

    override fun onDestroy() {
        audioStreaming = false
        mirrorActive = false
        abandonAudioFocus()
        syncStreamLocks()
        runCatching { stopService(Intent(this, AirPlayService::class.java)) }
        netCallback?.let { cb ->
            runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) }
        }
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
            .filter { !it.isLoopbackAddress && it is Inet4Address }
            .let { l -> l.firstOrNull { it.isSiteLocalAddress } ?: l.firstOrNull() }
            ?.hostAddress ?: "0.0.0.0"

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size)
            bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        return bmp
    }
}
