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
import android.widget.ScrollView
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

    /** A new 4-digit PIN every time the app starts; it is embedded in the QR code, so it need not be typed by hand. */
    private val pin = "%04d".format(SecureRandom().nextInt(10000))

    private var player: ExoPlayer? = null
    private var server: VideoServer? = null
    private lateinit var overlayView: LinearLayout

    private var airplayServer: AirPlayServer? = null
    private var airplayAdvertiser: AirPlayAdvertiser? = null

    // Screen mirroring: MediaCodec draws onto this surface.
    private lateinit var rootLayout: FrameLayout
    private lateinit var mirrorView: SurfaceView

    @Volatile
    private var mirrorSurface: Surface? = null

    private val mirrorEvents = object : MirrorEvents {
        override fun mirrorSurface(): Surface? = this@MainActivity.mirrorSurface

        override fun mirrorStarted() {
            mirrorActive = true
            logWifi()
            runOnUiThread {
                mirrorZoom = 1f
                panX = 0f
                panY = 0f
                syncStreamLocks()
                updateNowPlaying()
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
            runOnUiThread {
                // Because this is in the same queue as mirrorSize, the size of a finished mirroring session cannot be written back.
                lastMirrorW = 0
                lastMirrorH = 0
                mirrorZoom = 1f
                panX = 0f
                panY = 0f
                syncStreamLocks()
                updateNowPlaying()
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
    private lateinit var clockTime: TextView
    private lateinit var clockDate: TextView
    private lateinit var connLabel: TextView
    private var clockOn = true
    private var mirrorFill = false
    private var statsOn = false
    private var connectedName: String? = null
    private var connectedIp = ""
    private var connectedCount = 0
    private lateinit var trackText: TextView
    private lateinit var infoLabel: TextView
    private var airplayInfoRef: AirPlayInfo? = null
    private var serverUrl = ""
    private lateinit var qrView: ImageView
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var baseLabel = ""

    // Video AirPlay (/play): the player state is updated from the main thread and the server reads it.
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
        clockOn = getSharedPreferences("airplay", Context.MODE_PRIVATE).getBoolean("clock", true)
        mirrorFill = getSharedPreferences("airplay", Context.MODE_PRIVATE).getBoolean("mirrorFill", false)
        SyncSettings.offsetMs = getSharedPreferences("airplay", Context.MODE_PRIVATE)
            .getInt("syncOffsetMs", 0).coerceIn(-SyncSettings.MAX_MS, SyncSettings.MAX_MS)
        SyncSettings.jitterBufferMs = getSharedPreferences("airplay", Context.MODE_PRIVATE)
            .getInt("jitterBufferMs", SyncSettings.AUTO).coerceIn(SyncSettings.AUTO, SyncSettings.MAX_JITTER_MS)

        val playerView = PlayerView(this)
        // Video AirPlay (/play): start earlier and resume sooner after a stall than the default 2.5 s / 5 s.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_500)
            .build()
        player = ExoPlayer.Builder(this).setLoadControl(loadControl).build().also { p ->
            playerView.player = p
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    appendLog("Playback error: ${error.errorCodeName}")
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
        clockTime = TextView(this).apply {
            textSize = 56f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        clockDate = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.rgb(181, 181, 189))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }
        connLabel = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.rgb(52, 199, 89))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            visibility = android.view.View.GONE
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
            addView(clockTime, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(clockDate, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(qr, LinearLayout.LayoutParams(380, 380))
            addView(label, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(connLabel, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
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
            setZOrderMediaOverlay(true) // draw on top of the ExoPlayer surface
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
            // Views like PlayerView/controller should not take focus and swallow the center (OK) key.
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            addView(playerView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(
                mirrorView,
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER)
            )
            addView(photoView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(overlayView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(nowPlayingView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(sayView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(
                statsView,
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                    setMargins(dp(12), dp(12), dp(12), dp(12))
                }
            )
            addView(menuLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(rootLayout)
        // If the screen rotates or the layout changes (the Activity is not recreated because of configChanges), fit the mirror again.
        rootLayout.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if ((r - l != oldR - oldL || b - t != oldB - oldT) && lastMirrorW > 0 && lastMirrorH > 0) {
                fitMirror(lastMirrorW, lastMirrorH)
            }
        }

        // Uploaded videos left over from the previous session (they can take GBs) are deleted before the server starts.
        cacheDir.listFiles()?.forEach { f -> if (f.name.startsWith("video_")) runCatching { f.delete() } }

        try {
            server = VideoServer(
                port,
                cacheDir,
                pin,
                { upload -> runOnUiThread { play(upload) } },
                { cmd -> runOnUiThread { control(cmd) } },
                { text -> runOnUiThread { say(text) } },
                { url -> runOnUiThread { playUrl(url) } },
                { file -> acceptApk(file) },
                approvals,
                this,
                { statusJson }
            ).also { it.start() }
        } catch (e: Exception) {
            appendLog("Web server did not start (port $port): ${e.message}")
        }

        try {
            val airplayInfo = AirPlayInfo.load(this, serverName()).also { airplayInfoRef = it }
            airplayServer = AirPlayServer(airplayPort, airplayInfo, mirrorEvents, this, approvals) { msg -> appendLog(msg) }
                .also { it.start() }
            airplayAdvertiser = AirPlayAdvertiser(this, airplayInfo, airplayPort) { msg -> appendLog(msg) }
                .also { it.start() }
        } catch (e: Exception) {
            appendLog("AirPlay did not start: ${e.message}")
        }

        startAirPlayService()
        mainHandler.post(statusTicker)
        watchNetwork()
        appendLog("Version v${BuildConfig.VERSION_CODE}")
        updater.check()
    }

    // ---- VideoEvents (iPhone Photos > AirPlay > video) -------------------------------

    override fun deviceConnected(name: String?, ip: String) {
        runOnUiThread {
            connectedCount++
            connectedName = name ?: "iPhone"
            connectedIp = ip
            updateConnLabel()
            Toast.makeText(this, "👋 " + connectedName, Toast.LENGTH_LONG).show()
        }
    }

    override fun deviceDisconnected() {
        runOnUiThread {
            connectedCount = maxOf(0, connectedCount - 1)
            if (connectedCount == 0) connectedName = null
            updateConnLabel()
        }
    }

    @Volatile
    private var lastTrack: TrackInfo? = null

    override fun audioTrack(info: TrackInfo) {
        lastTrack = info
        runOnUiThread {
            npDismissed = false
            updateNowPlaying()
            val text = listOfNotNull(info.title, info.artist, info.album).joinToString("\n")
            trackText.text = text
            trackText.visibility = if (text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    override fun audioArt(image: ByteArray?) {
        val bmp = image?.let { decodeScaled(it) }
        runOnUiThread {
            lastArt = bmp
            updateNowPlaying()
            if (bmp == null) {
                trackArt.setImageDrawable(null)
                trackArt.visibility = android.view.View.GONE
            } else {
                trackArt.setImageBitmap(bmp)
                trackArt.visibility = android.view.View.VISIBLE
            }
        }
    }

    // ---- Audio focus and locks: keep the CPU/Wi-Fi awake while streaming, and lower other apps' sound ----

    private var focusRequest: AudioFocusRequest? = null
    private var audioStreaming = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun audioStarted() {
        runOnUiThread {
            audioStreaming = true
            requestAudioFocus()
            syncStreamLocks()
            updateNowPlaying()
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
                    .setOnAudioFocusChangeListener { change -> appendLog("Audio focus changed: $change") }
                    .build().also { focusRequest = it }
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (e: Exception) {
            appendLog("Could not get audio focus: ${e.message}")
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
            // already released
        }
    }

    /** Holds the CPU and Wi-Fi locks while audio or mirroring is running, and releases them when it ends. */
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
                // Upper bound so the battery is not drained if forgotten. After 6 hours the lock is released automatically; if streaming continues, it is re-acquired at the next audio/mirroring state change.
                if (wakeLock?.isHeld != true) wakeLock?.acquire(6 * 60 * 60 * 1000L)
                if (wifiLock?.isHeld != true) wifiLock?.acquire()
            } else {
                if (wakeLock?.isHeld == true) wakeLock?.release()
                if (wifiLock?.isHeld == true) wifiLock?.release()
            }
        } catch (e: Exception) {
            appendLog("Lock error: ${e.message}")
        }
    }

    override fun audioEnded() {
        lastTrack = null
        runOnUiThread {
            audioStreaming = false
            lastArt = null
            npDismissed = false
            abandonAudioFocus()
            syncStreamLocks()
            updateNowPlaying()
            trackText.text = ""
            trackText.visibility = android.view.View.GONE
            trackArt.setImageDrawable(null)
            trackArt.visibility = android.view.View.GONE
        }
    }

    override fun videoPlay(url: String, startSeconds: Double, startFraction: Double) {
        runOnUiThread {
            val p = player ?: return@runOnUiThread
            // The mirroring surface may hold an old frame: shrink it to 1x1 so it does not cover the video.
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
            appendLog("Loading video")
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
            appendLog("Could not decode the photo (${jpeg.size} B)")
            return
        }
        runOnUiThread {
            player?.stop()
            if (airplayVideoActive) endAirplayVideo(notify = false)
            mirrorView.layoutParams = FrameLayout.LayoutParams(1, 1, Gravity.CENTER)
            photoView.setImageBitmap(bmp)
            photoView.visibility = android.view.View.VISIBLE
            overlayView.visibility = android.view.View.GONE
            appendLog("Showing photo ${bmp.width}x${bmp.height}")
        }
    }

    /** Large photos are decoded so that the longest side does not exceed ~2560 pixels, to avoid exhausting memory. */
    private fun decodeScaled(data: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        val dm = resources.displayMetrics
        val limit = minOf(2560, maxOf(maxOf(dm.widthPixels, dm.heightPixels), 1920))
        while (bounds.outWidth / sample > limit || bounds.outHeight / sample > limit) sample *= 2
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

    /** Called from the main thread; the server threads read this copy. */
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

    /** Fits the mirrored picture to the screen without distorting its aspect ratio (black bars at the edges). */
    private var lastMirrorW = 0
    private var lastMirrorH = 0

    // Zoom while mirroring: 1x = fit to the screen; as it grows, the picture overflows the screen and can be dragged.
    private var mirrorZoom = 1f
    private var panX = 0f
    private var panY = 0f

    private fun fitMirror(videoW: Int, videoH: Int) {
        lastMirrorW = videoW
        lastMirrorH = videoH
        val cw = rootLayout.width
        val ch = rootLayout.height
        if (cw <= 0 || ch <= 0 || videoW <= 0 || videoH <= 0) return
        val fit = if (mirrorFill) maxOf(cw.toFloat() / videoW, ch.toFloat() / videoH)
        else minOf(cw.toFloat() / videoW, ch.toFloat() / videoH)
        val scale = fit * mirrorZoom
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

    /** Pinch with two fingers to zoom while mirroring; once zoomed in, drag with one finger. */
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
                // When a finger is lifted, continue from the remaining finger's position (so there is no sudden jump).
                val i = if (ev.actionIndex == 0) 1 else 0
                if (ev.pointerCount > 1) {
                    touchX = ev.getX(i)
                    touchY = ev.getY(i)
                }
            }
        }
    }

    /** The log is kept in memory only; it is read from the /log page and is not shown on the TV screen. */
    private fun appendLog(msg: String) {
        Log.i("AirPlay", msg)
        LogBuffer.add(msg)
    }

    /** Opens the permission dialog on the TV when an unknown device tries to connect. */
    /** Verifies the APK uploaded from the web interface; if there is no problem, opens the install screen. null = OK. */
    private fun acceptApk(file: java.io.File): String? {
        val problem = updater.verifyUpload(file)
        if (problem != null) {
            appendLog("APK rejected: $problem")
            return problem
        }
        val needsPermission = android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()
        runOnUiThread { updater.install(file) }
        if (needsPermission) {
            appendLog("The 'unknown sources' permission is required to install the APK")
            return getString(R.string.unknown_sources_web)
        }
        appendLog("APK received, opening the install screen")
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

    /** Starts playback before the upload finishes: the file is read as it grows. */
    // ---- TV menu: opened with the MENU key, or with OK on the QR/mirroring screen (the Mi TV Stick has no MENU key) ----

    private class MenuItem(
        val label: Int,
        val value: () -> String,
        val left: () -> Unit,
        val right: () -> Unit,
        val ok: () -> Unit = right,
        val text: String? = null
    )

    private var menuOpen = false
    private var menuTrusted = false // true: the "Trusted devices" submenu
    private var menuIndex = 0
    private val menuHide = Runnable { closeMenu() }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private val menuBox: LinearLayout by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(28, 28, 30))
            setPadding(dp(24), dp(20), dp(24), dp(16))
            isClickable = true // the inside of the box should not pass clicks to the "tap outside = close" layer behind it
        }
    }
    // On small screens (a phone in landscape) the menu scrolls if the rows do not fit; the selected row is kept visible.
    private val menuScroll: ScrollView by lazy {
        ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            addView(menuBox, android.view.ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }
    private val menuLayer: FrameLayout by lazy {
        FrameLayout(this).apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            visibility = android.view.View.GONE
            isClickable = true
            setOnClickListener { closeMenu() }
            addView(
                menuScroll,
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

    // Sleep timer: when the chosen time is up, playback/mirroring is stopped (valid for this session only).
    private val sleepOptions = intArrayOf(0, 15, 30, 60, 90, 120)
    private var sleepIndex = 0
    private var sleepDeadline = 0L
    private val sleepRunnable = Runnable {
        sleepIndex = 0
        sleepDeadline = 0L
        appendLog("Sleep timer expired, playback stopped")
        stopPlayback()
        Toast.makeText(this, getString(R.string.sleep_stopped), Toast.LENGTH_LONG).show()
    }

    private fun setSleep(index: Int) {
        sleepIndex = ((index % sleepOptions.size) + sleepOptions.size) % sleepOptions.size
        mainHandler.removeCallbacks(sleepRunnable)
        val min = sleepOptions[sleepIndex]
        if (min > 0) {
            sleepDeadline = SystemClock.elapsedRealtime() + min * 60_000L
            mainHandler.postDelayed(sleepRunnable, min * 60_000L)
        } else {
            sleepDeadline = 0L
        }
    }

    private fun sleepText(): String =
        if (sleepDeadline == 0L) getString(R.string.buffer_off)
        else getString(R.string.sleep_min, ((sleepDeadline - SystemClock.elapsedRealtime() + 59_999L) / 60_000L).toString())

    private fun onOff(b: Boolean): String = getString(if (b) R.string.on else R.string.off)

    private fun toggleFill() {
        mirrorFill = !mirrorFill
        getSharedPreferences("airplay", Context.MODE_PRIVATE).edit().putBoolean("mirrorFill", mirrorFill).apply()
        if (lastMirrorW > 0 && lastMirrorH > 0) fitMirror(lastMirrorW, lastMirrorH)
    }

    private fun toggleClock() {
        clockOn = !clockOn
        getSharedPreferences("airplay", Context.MODE_PRIVATE).edit().putBoolean("clock", clockOn).apply()
        updateClock()
    }

    private fun menuBack() {
        if (menuTrusted) {
            menuTrusted = false
            menuIndex = 0
            renderMenu()
        } else {
            closeMenu()
        }
    }

    /** The "Trusted devices" submenu: OK on a row removes that device's trust. */
    private fun trustedItems(): List<MenuItem> {
        val list = mutableListOf<MenuItem>()
        val devices = approvals.trustedList()
        if (devices.isEmpty()) list += MenuItem(R.string.menu_none, { "" }, {}, {}, {})
        devices.forEach { line ->
            val ip = line.substringBefore(' ')
            list += MenuItem(
                R.string.menu_forget,
                { getString(R.string.menu_forget) },
                {}, {},
                { approvals.forget(ip) },
                text = ip + if (line.contains("(perm)")) " ★" else ""
            )
        }
        if (devices.size > 1) list += MenuItem(R.string.menu_forget_all, { "" }, {}, {}, { approvals.forgetAll() })
        list += MenuItem(R.string.menu_back, { "" }, {}, {}, { menuBack() })
        return list
    }

    private fun menuItems(): List<MenuItem> {
        if (menuTrusted) return trustedItems()
        val step = SyncSettings.STEP_MS
        val list = mutableListOf<MenuItem>()
        list += MenuItem(
            R.string.menu_autostart,
            { getString(if (autostartOn()) R.string.on else R.string.off) },
            { toggleAutostart() }, { toggleAutostart() }
        )
        list += MenuItem(
            R.string.menu_sleep,
            { sleepText() },
            { setSleep(sleepIndex - 1) }, { setSleep(sleepIndex + 1) },
            { setSleep(0) }
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
        list += MenuItem(
            R.string.menu_fit,
            { getString(if (mirrorFill) R.string.fit_fill else R.string.fit_contain) },
            { toggleFill() }, { toggleFill() }
        )
        list += MenuItem(R.string.menu_clock, { onOff(clockOn) }, { toggleClock() }, { toggleClock() })
        list += MenuItem(
            R.string.menu_stats,
            { onOff(statsOn) },
            { statsOn = !statsOn; updateStats() }, { statsOn = !statsOn; updateStats() }
        )
        list += MenuItem(
            R.string.menu_trusted,
            { "›" },
            {}, { menuTrusted = true; menuIndex = 0 }
        )
        list += MenuItem(
            R.string.menu_update,
            { "v${BuildConfig.VERSION_CODE}" },
            { updater.check(manual = true) }, { updater.check(manual = true) }
        )
        list += MenuItem(R.string.menu_exit, { "" }, {}, {}, { finish() })
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
                // Tap: the selected row's "OK" action; long press: decrease.
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
                text = item.text ?: getString(item.label)
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
            if (i == menuIndex) menuScroll.post { menuScroll.scrollTo(0, maxOf(0, row.top - dp(80))) }
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
        menuTrusted = false
        menuIndex = 0
        renderMenu()
        menuLayer.visibility = android.view.View.VISIBLE
        scheduleMenuClose()
    }

    private fun closeMenu() {
        menuOpen = false
        menuTrusted = false
        mainHandler.removeCallbacks(menuHide)
        menuLayer.visibility = android.view.View.GONE
    }

    /** While the menu is open it handles the keys; keys it does not care about (volume etc.) are left to the system (false). */
    private fun menuKey(keyCode: Int, event: KeyEvent): Boolean {
        val items = menuItems()
        scheduleMenuClose()
        when {
            keyCode == KeyEvent.KEYCODE_DPAD_UP -> menuIndex = (menuIndex - 1 + items.size) % items.size
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> menuIndex = (menuIndex + 1) % items.size
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> items[menuIndex.coerceIn(0, items.size - 1)].left()
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> items[menuIndex.coerceIn(0, items.size - 1)].right()
            isOkKey(keyCode) -> if (event.repeatCount == 0) items[menuIndex.coerceIn(0, items.size - 1)].ok()
            keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU -> if (event.repeatCount == 0) menuBack()
            else -> return false
        }
        if (menuOpen) renderMenu()
        return true
    }

    /** Shows the message from the web page on screen in large text for 20 s (an empty text removes it). */
    private fun say(text: String) {
        mainHandler.removeCallbacks(hideSay)
        if (text.isEmpty()) {
            sayView.visibility = android.view.View.GONE
            return
        }
        sayView.text = text
        sayView.visibility = android.view.View.VISIBLE
        mainHandler.postDelayed(hideSay, 20_000L)
        appendLog("Message shown")
    }

    // ---- Bouncing QR when idle (like the DVD logo; also prevents screen burn-in) ---------------

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

    // ---- "Now playing": full-screen cover art + song information for music (Back returns to the QR screen) ----

    private var npDismissed = false
    private var lastArt: Bitmap? = null
    private val npBackdrop: ImageView by lazy { ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; alpha = 0.55f } }
    private val npArt: ImageView by lazy { ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP } }
    private val npTitle: TextView by lazy {
        TextView(this).apply {
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 2
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(24), dp(24), dp(24), 0)
        }
    }
    private val npArtist: TextView by lazy {
        TextView(this).apply {
            textSize = 24f
            setTextColor(Color.rgb(209, 209, 214))
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(24), dp(6), dp(24), 0)
        }
    }
    private val npAlbum: TextView by lazy {
        TextView(this).apply {
            textSize = 18f
            setTextColor(Color.rgb(181, 181, 189))
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(24), dp(4), dp(24), 0)
        }
    }
    private val nowPlayingView: FrameLayout by lazy {
        FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = android.view.View.GONE
            addView(npBackdrop, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(npArt, LinearLayout.LayoutParams(dp(240), dp(240)))
                    addView(npTitle, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                    addView(npArtist, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                    addView(npAlbum, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            )
        }
    }

    private fun updateNowPlaying() {
        val t = lastTrack
        val art = lastArt
        val show = audioStreaming && !mirrorActive && !airplayVideoActive && !npDismissed && (t != null || art != null)
        if (!show) {
            nowPlayingView.visibility = android.view.View.GONE
            return
        }
        npTitle.text = t?.title ?: ""
        npArtist.text = t?.artist ?: ""
        npAlbum.text = t?.album ?: ""
        if (art != null) {
            val side = (minOf(rootLayout.width, rootLayout.height) * 0.5f).toInt().coerceAtLeast(dp(120))
            npArt.layoutParams = LinearLayout.LayoutParams(side, side)
            npArt.setImageBitmap(art)
            npArt.visibility = android.view.View.VISIBLE
            // Enlarging a small copy gives a cheap blur (for the background).
            npBackdrop.setImageBitmap(Bitmap.createScaledBitmap(art, 16, 16, true))
        } else {
            npArt.visibility = android.view.View.GONE
            npBackdrop.setImageDrawable(null)
        }
        nowPlayingView.visibility = android.view.View.VISIBLE
    }

    // ---- /api/status: the web remote's "now playing" information (once a second, from the main thread) ----

    @Volatile
    private var statusJson = """{"state":"idle"}"""

    private val statusTicker = object : Runnable {
        override fun run() {
            try {
                statusJson = buildStatus()
                updateClock()
                updateStats()
            } catch (e: Exception) {
                // will be retried on the next tick
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    private fun updateClock() {
        val vis = if (clockOn) android.view.View.VISIBLE else android.view.View.GONE
        clockTime.visibility = vis
        clockDate.visibility = vis
        if (!clockOn) return
        val now = java.util.Date()
        val t = android.text.format.DateFormat.getTimeFormat(this).format(now)
        val d = java.text.DateFormat.getDateInstance(java.text.DateFormat.FULL).format(now)
        if (clockTime.text.toString() != t) clockTime.text = t // so there is no needless relayout every second
        if (clockDate.text.toString() != d) clockDate.text = d
    }

    private val statsView: TextView by lazy {
        TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = android.view.View.GONE
        }
    }
    private var lastStatsFrames = 0L
    private var lastStatsAt = 0L

    private fun wifiSummary(): String = try {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        @Suppress("DEPRECATION")
        val i = wm?.connectionInfo
        if (i == null || i.rssi <= -127) "-" else {
            val f = i.frequency
            val band = when {
                f >= 5925 -> "6 GHz"
                f >= 4900 -> "5 GHz"
                f > 0 -> "2.4 GHz"
                else -> "?"
            }
            "$band ${i.rssi} dBm ${i.linkSpeed} Mbps"
        }
    } catch (e: Exception) {
        "-"
    }

    /** While "Statistics" is on in the menu, a technical info panel in the top right (once a second). The diagnostic text is language-independent. */
    private fun updateStats() {
        if (!statsOn) {
            statsView.visibility = android.view.View.GONE
            lastStatsAt = 0L // so the first frame rate after the panel opens is not an average over a long period
            return
        }
        val now = SystemClock.elapsedRealtime()
        val fps = if (lastStatsAt > 0 && now > lastStatsAt) (MirrorStats.frames - lastStatsFrames) * 1000.0 / (now - lastStatsAt) else 0.0
        lastStatsFrames = MirrorStats.frames
        lastStatsAt = now
        val sb = StringBuilder()
        if (mirrorActive && lastMirrorW > 0) {
            sb.append("Mirror   ${lastMirrorW}x$lastMirrorH  in ${"%.0f".format(java.util.Locale.US, maxOf(0.0, fps))} fps\n")
        } else {
            sb.append("Mirror   -\n")
        }
        sb.append("Buffer   ").append(
            if (SyncSettings.jitterBufferMs == SyncSettings.AUTO) "auto ${SyncSettings.autoJitterMs} ms" else "${SyncSettings.jitterBufferMs} ms"
        ).append('\n')
        sb.append("In-drops ${MirrorStats.dropped}\n")
        sb.append("A/V sync ${SyncSettings.offsetMs} ms\n")
        sb.append("Wi-Fi    ${wifiSummary()}")
        connectedName?.let { sb.append("\nDevice   ").append(it).append(if (connectedIp.isNotEmpty()) " ($connectedIp)" else "") }
        statsView.text = sb.toString()
        statsView.visibility = android.view.View.VISIBLE
    }

    private fun updateConnLabel() {
        val n = connectedName
        if (n == null || connectedCount <= 0) {
            connLabel.visibility = android.view.View.GONE
        } else {
            connLabel.text = "● $n" + (if (connectedIp.isNotEmpty()) " · $connectedIp" else "")
            connLabel.visibility = android.view.View.VISIBLE
        }
    }

    private fun buildStatus(): String {
        val p = player
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val o = org.json.JSONObject()
        val playing = p != null && p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED &&
            (currentUpload != null || airplayVideoActive || playingUrl != null)
        when {
            mirrorActive -> o.put("state", "mirror").put("zoom", mirrorZoom.toDouble())
            playing && p != null -> {
                o.put("state", if (p.playbackState == Player.STATE_BUFFERING) "loading" else if (p.isPlaying) "playing" else "paused")
                o.put("title", currentUpload?.name?.takeIf { it.isNotEmpty() } ?: playingUrl ?: "AirPlay")
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
        connectedName?.let { o.put("device", it) }
        val q = org.json.JSONArray()
        uploadQueue.toList().forEach { q.put(it.name.ifEmpty { "video" }) }
        o.put("queue", q)
        return o.toString()
    }

    // The queue of videos uploaded from the web page (those that arrived with X-Queue: 1).
    private val uploadQueue = ArrayDeque<Upload>()

    /** Empties the queue and deletes the files of the waiting uploads (so the cache does not fill up). */
    private fun clearQueue() {
        uploadQueue.forEach { runCatching { it.file.delete() } }
        uploadQueue.clear()
    }
    private var playingUrl: String? = null // the address opened with /api/play (shown on the status card)
    private var currentUpload: Upload? = null

    private fun play(upload: Upload) {
        val p = player
        val busy = p != null && currentUpload != null && !airplayVideoActive &&
            p.playbackState != Player.STATE_IDLE && p.playbackState != Player.STATE_ENDED
        if (upload.queued && busy) {
            uploadQueue.addLast(upload)
            appendLog("Added to the queue (${uploadQueue.size} waiting)")
            return
        }
        clearQueue()
        start(upload)
    }

    /** Siradaki videoya gecer; sira bossa false doner. */
    private fun playNext(): Boolean {
        val next = uploadQueue.removeFirstOrNull() ?: return false
        val old = currentUpload
        start(next)
        if (old != null && old !== next) runCatching { old.file.delete() }
        appendLog("Next video (${uploadQueue.size} waiting)")
        return true
    }

    /** Plays an http(s) video address coming from the web page/bookmarklet with ExoPlayer (MP4, HLS ...). */
    private fun playUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return
        clearQueue()
        currentUpload = null
        playingUrl = (uri.host ?: "") + (uri.lastPathSegment?.let { "/$it" } ?: "")
        overlayView.visibility = android.view.View.GONE
        photoView.visibility = android.view.View.GONE
        val item = MediaItem.Builder().setUri(uri).apply {
            if (url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player?.apply {
            setMediaItem(item)
            prepare()
            playWhenReady = true
        }
        appendLog("Playing from a link: $playingUrl")
    }

    private fun start(upload: Upload) {
        playingUrl = null
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

    /** The remote buttons on the web page (always called from the main thread). */
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
                clearQueue()
                p.stop()
                overlayView.visibility = android.view.View.VISIBLE
            }
        }
    }

    /** The Wi-Fi band, signal and link speed are written to the log (for diagnosing jitter). */
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
                else -> "unknown"
            }
            appendLog("Wi-Fi: $band ($f MHz), signal ${i.rssi} dBm, link speed ${i.linkSpeed} Mbps")
        } catch (e: Exception) {
            appendLog("Could not read Wi-Fi information: ${e.javaClass.simpleName}")
        }
    }

    /** RIGHT/LEFT while mirroring: adjusts the offset between audio and picture in 20 ms steps. */
    /** Full screen: the status and navigation bars are hidden (so mirroring uses the whole screen on a phone). */
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
        // The OK key that stops the bouncing QR should not trigger a separate action (toggling auto-start).
        if (wasBouncing && isOkKey(keyCode)) return true
        if (event.repeatCount == 0) appendLog("Key: ${KeyEvent.keyCodeToString(keyCode)}")
        if (menuOpen) return menuKey(keyCode, event) || super.onKeyDown(keyCode, event)
        // MENU always opens the menu; OK also opens it (for remotes without a MENU key) on the QR or mirroring screen.
        if (keyCode == KeyEvent.KEYCODE_MENU ||
            (isOkKey(keyCode) && (mirrorActive || overlayView.visibility == android.view.View.VISIBLE))
        ) {
            if (event.repeatCount == 0) openMenu()
            return true
        }
        if (mirrorActive && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            // While on automatic, the first press switches from the current automatic value to manual mode.
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
        appendLog("AirPlay name changed: $n")
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

    /** Stops everything and returns to the QR screen (used by the Back key and the sleep timer). */
    private fun stopPlayback() {
        clearQueue()
        playingUrl = null
        player?.stop()
        photoView.visibility = android.view.View.GONE
        if (airplayVideoActive) endAirplayVideo()
        airplayServer?.stopAllMirrors()
        // stopAllMirrors does not call mirrorStopped(); if the flag is not reset, OK on the QR screen changes the jitter setting.
        mirrorActive = false
        lastMirrorW = 0
        lastMirrorH = 0
        mirrorZoom = 1f
        panX = 0f
        panY = 0f
        audioStreaming = false
        lastTrack = null
        lastArt = null
        npDismissed = false
        abandonAudioFocus()
        syncStreamLocks()
        updateNowPlaying()
        overlayView.visibility = android.view.View.VISIBLE
    }

    /** When the Back key is pressed, stop playback and return to the QR screen. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (menuOpen) {
            menuBack()
            return
        }
        if (nowPlayingView.visibility == android.view.View.VISIBLE) {
            npDismissed = true
            updateNowPlaying()
            return
        }
        if (overlayView.visibility == android.view.View.GONE) {
            stopPlayback()
        } else {
            super.onBackPressed()
        }
    }

    /** Refreshes the address, the QR code and the label when Wi-Fi/DHCP changes. */
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
            appendLog("Could not start network monitoring: ${e.message}")
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
            appendLog("Address changed: $url")
        }
    }

    /** Starts the foreground service so the process is not killed in the background (the app still works if this fails). */
    private fun startAirPlayService() {
        try {
            val i = Intent(this, AirPlayService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
            appendLog("Could not start the background service: ${e.message}")
        }
    }

    override fun onStart() {
        super.onStart()
        mainHandler.removeCallbacks(bounceTicker)
        mainHandler.postDelayed(bounceTicker, 1000)
    }

    override fun onStop() {
        // While in the background, the bouncing QR animation (25 wake-ups/s) should not run needlessly.
        mainHandler.removeCallbacks(bounceTicker)
        if (bouncing) stopBounce()
        super.onStop()
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
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size)
            pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        return bmp
    }
}
