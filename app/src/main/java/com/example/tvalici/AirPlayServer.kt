package com.example.tvalici

import android.util.Log
import android.view.Surface
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSObject
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

/** Screen mirroring events (implemented by MainActivity). May be called from any thread. */
interface MirrorEvents {
    fun mirrorSurface(): Surface?
    fun mirrorStarted()
    fun mirrorSize(width: Int, height: Int)
    fun mirrorStopped()
}

/** Video AirPlay (/play) events (implemented by MainActivity). May be called from any thread. */
interface VideoEvents {
    /** -1 when neither startSeconds nor startFraction is given. */
    fun videoPlay(url: String, startSeconds: Double, startFraction: Double)
    fun videoRate(rate: Float)
    fun videoSeek(seconds: Double)
    fun videoStop()
    fun videoInfo(): VideoInfo

    /** Photo AirPlay: shows the JPEG on screen. */
    fun showPhoto(jpeg: ByteArray)

    /** Music AirPlay: song information arrived (fields may be empty). */
    fun audioTrack(info: TrackInfo) {}

    /** Music AirPlay: cover art arrived; null means the cover was removed. */
    fun audioArt(image: ByteArray?) {}

    /** An iPhone/iPad connected to the stream (null if the device name is unknown). */
    fun deviceConnected(name: String?, ip: String) {}

    /** That device's connection was closed. */
    fun deviceDisconnected() {}

    /** The audio stream started (for audio focus and locks). */
    fun audioStarted() {}

    /** The music AirPlay session ended; the song information should be cleared. */
    fun audioEnded() {}
}

/** Current player state for the /playback-info response. */
class VideoInfo(
    val active: Boolean = false,
    val duration: Double = 0.0,
    val position: Double = 0.0,
    val rate: Float = 0f,
    val ready: Boolean = false,
    val bufferEmpty: Boolean = false
)

/**
 * AirPlay server: /info, pairing (pair-setup / pair-verify), FairPlay (/fp-setup) and
 * the RTSP requests for screen mirroring (SETUP, RECORD, TEARDOWN ...). HTTP and RTSP
 * requests arrive mixed on the same TCP connection; both are read the same way.
 */
class AirPlayServer(
    private val port: Int,
    private val airplay: AirPlayInfo,
    private val events: MirrorEvents,
    private val video: VideoEvents,
    private val gate: ApprovalGate,
    private val onLog: (String) -> Unit
) {
    @Volatile
    private var running = false
    @Volatile
    private var serverSocket: ServerSocket? = null

    // Even before authentication a request body may allocate this much memory; an oversized request closes the connection.
    private val maxBody = 32 * 1024 * 1024
    private val sessions = mutableSetOf<Session>()

    /** The event channel the iPhone opens with /reverse; TV -> iPhone events go through it. */
    @Volatile
    private var reverse: Session? = null

    @Volatile
    private var videoSessionId: String? = null

    // The iPhone may send a photo to the cache first and say "show" later; keep the last few.
    private val photoCache = LinkedHashMap<String, ByteArray>()

    // Network writes cannot be made from the main thread; events are sent in order on a single worker thread.
    private val eventExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "airplay-event").apply { isDaemon = true }
    }

    @Volatile
    private var videoSessionPlist: NSObject? = null

    /** Every TCP connection has its own pairing and stream state. */
    private class Session(val socket: Socket) {
        var out: OutputStream? = null
        var authorized = false // connection from an IP address that was allowed on the TV
        var keyMsg: ByteArray? = null // the /fp-setup stage 2 message (164 B)
        var ecdhSecret: ByteArray? = null // the pair-verify X25519 shared secret
        var aesKey: ByteArray? = null
        var aesIv: ByteArray? = null // eiv from SETUP 1 (the audio AES-CBC IV)
        var ntp: NtpClient? = null
        var announced = false // deviceConnected was announced
        var mirror: MirrorReceiver? = null
        var audio: AudioReceiver? = null
        var volumeDb = 0f
        val udp = mutableListOf<DatagramSocket>()

        fun stopMirror(): Boolean {
            val m = mirror ?: return false
            mirror = null
            m.stop()
            return true
        }

        fun stopAudio() {
            audio?.stop()
            audio = null
        }

        fun closeUdp() {
            udp.forEach { runCatching { it.close() } }
            udp.clear()
        }

        fun closeAll(): Boolean {
            val hadMirror = stopMirror()
            stopAudio()
            closeUdp()
            ntp?.stop()
            ntp = null
            return hadMirror
        }
    }

    fun start() {
        running = true
        thread(name = "airplay-accept", isDaemon = true) {
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                log("AirPlay server :$port")
                while (running) {
                    val socket = ss.accept()
                    thread(name = "airplay-conn", isDaemon = true) { handle(socket) }
                }
            } catch (e: Exception) {
                if (running) log("Server error: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        eventExecutor.shutdownNow()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // errors while closing are unimportant
        }
        stopAllMirrors()
        synchronized(photoCache) { photoCache.clear() }
    }

    /** The playback state changed on the TV (loading / playing / paused / stopped): sends an event to the iPhone. */
    fun notifyVideoState(state: String) {
        val r = reverse ?: return
        val out = r.out ?: return
        eventExecutor.execute { sendVideoEvent(out, state) }
    }

    private fun sendVideoEvent(out: OutputStream, state: String) {
        try {
            val d = NSDictionary()
            d.put("category", NSString("video"))
            d.put("state", NSString(state))
            videoSessionPlist?.let { d.put("sessionID", it) }
            val body = BinaryPropertyListWriter.writeToArray(d)
            val sb = StringBuilder()
            sb.append("POST /event HTTP/1.1\r\n")
            sb.append("Content-Type: application/x-apple-binary-plist\r\n")
            videoSessionId?.let { sb.append("X-Apple-Session-ID: $it\r\n") }
            sb.append("Content-Length: ${body.size}\r\n\r\n")
            synchronized(out) {
                out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                out.write(body)
                out.flush()
            }
            log("  event -> iPhone: $state")
        } catch (e: Exception) {
            log("  event could not be sent: ${e.message}")
        }
    }

    /** Back key: closes the open mirroring connections. */
    fun stopAllMirrors() {
        val copy = synchronized(sessions) { sessions.toList() }
        for (s in copy) s.closeAll()
    }

    private fun log(msg: String) {
        Log.i("AirPlay", msg)
        onLog(msg)
    }

    private fun handle(socket: Socket) {
        log("Connection: ${socket.inetAddress.hostAddress}")
        val session = Session(socket)
        synchronized(sessions) { sessions.add(session) }
        try {
            socket.use { s ->
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                session.out = out
                while (true) {
                    val requestLine = readLine(input) ?: break
                    if (requestLine.isBlank()) continue
                    val parts = requestLine.split(" ")
                    val method = parts.getOrElse(0) { "" }
                    val uri = parts.getOrElse(1) { "/" }
                    val proto = parts.getOrElse(2) { "HTTP/1.1" }

                    val headers = HashMap<String, String>()
                    while (true) {
                        val line = readLine(input) ?: return
                        if (line.isEmpty()) break
                        val i = line.indexOf(':')
                        if (i > 0) {
                            headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                        }
                    }

                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    if (length < 0 || length > maxBody) {
                        log("Invalid/oversized request body ($length B), connection closed")
                        return
                    }
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(body, read, length - read)
                        if (n < 0) return
                        read += n
                    }

                    // After /reverse the iPhone replies to our /event requests: swallow them.
                    if (method.startsWith("HTTP/")) continue

                    // Do not log the frequent heartbeat requests.
                    if (uri != "/feedback") log("$method ${shortUri(uri)} ($length B)")
                    respond(session, out, proto, method, uri, headers, body)
                }
            }
        } catch (e: Exception) {
            log("Connection closed: ${e.message}")
        } finally {
            synchronized(sessions) { sessions.remove(session) }
            if (reverse === session) reverse = null
            if (session.announced) video.deviceDisconnected()
            val hadAudio = session.audio != null
            if (session.closeAll()) events.mirrorStopped()
            if (hadAudio) video.audioEnded()
        }
    }

    private fun shortUri(uri: String): String =
        if (uri.startsWith("rtsp://")) "rtsp://…/" + uri.substringAfterLast('/') else uri

    private fun respond(
        s: Session,
        out: OutputStream,
        proto: String,
        method: String,
        uri: String,
        headers: Map<String, String>,
        body: ByteArray
    ) {
        val cseq = headers["cseq"]
        val octet = "application/octet-stream"
        val plist = "application/x-apple-binary-plist"

        // /info is open to everyone (so the device shows up in the list); everything else needs permission on the TV.
        val open = uri == "/info" || uri == "/feedback"
        if (!open && !s.authorized) {
            log("  permission query: ${method} ${shortUri(uri)} device-id=${headers["x-apple-device-id"] ?: "none"}")
            if (gate.check(s.socket.inetAddress.hostAddress ?: "")) {
                s.authorized = true
            } else {
                log("  permission denied")
                send(out, proto, 403, "Forbidden", ByteArray(0), null, cseq)
                return
            }
        }

        if ((method == "GET" || method == "POST") && uri == "/info") {
            send(out, proto, 200, "OK", infoPlist(), plist, cseq)
            return
        }
        if (method == "POST" && uri == "/fp-setup") {
            // FairPlay handshake: 16 bytes -> 142 bytes, 164 bytes -> 32 bytes.
            val reply = when (body.size) {
                16 -> AirPlayFairPlay.setup(body)
                164 -> AirPlayFairPlay.handshake(body)
                else -> null
            }
            if (reply != null) {
                if (body.size == 164) s.keyMsg = body.copyOf()
                log("  fp-setup ${body.size} B ok")
                send(out, proto, 200, "OK", reply, octet, cseq)
            } else {
                log("  fp-setup rejected (${body.size} B)")
                send(out, proto, 400, "Bad Request", ByteArray(0), null, cseq)
            }
            return
        }
        if (method == "POST" && uri == "/pair-setup") {
            // The client sends its 32-byte Ed25519 public key and we return ours.
            send(out, proto, 200, "OK", airplay.publicKey, octet, cseq)
            return
        }
        if (method == "POST" && uri == "/pair-verify" && body.size >= 4) {
            if (body[0].toInt() == 1 && body.size == 68) {
                // Step 1: the client's ephemeral Curve25519 key (+ Ed25519 key) arrives.
                val clientCurve = body.copyOfRange(4, 36)
                val ephPriv = ByteArray(32)
                SecureRandom().nextBytes(ephPriv)
                val ephPub = X25519.publicKey(ephPriv)
                val secret = X25519.scalarMult(ephPriv, clientCurve)
                s.ecdhSecret = secret // used to derive the stream key
                val signature = Ed25519.sign(airplay.seed, ephPub + clientCurve)
                val encrypted = AirPlayCrypto.pairVerifyEncrypt(secret, signature)
                log("  pair-verify 1 ok")
                send(out, proto, 200, "OK", ephPub + encrypted, octet, cseq)
            } else {
                // Step 2: the client's signature. This is a home-network prototype, so verification is skipped.
                log("  pair-verify 2 ok")
                send(out, proto, 200, "OK", ByteArray(0), octet, cseq)
            }
            return
        }

        if (handleVideo(s, out, proto, method, uri, headers, body, cseq)) return

        when (method) {
            "OPTIONS" -> {
                send(
                    out, proto, 200, "OK", ByteArray(0), null, cseq,
                    mapOf("Public" to "SETUP, RECORD, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER")
                )
                return
            }
            "SETUP" -> {
                handleSetup(s, out, proto, cseq, body)
                return
            }
            "RECORD" -> {
                send(
                    out, proto, 200, "OK", ByteArray(0), null, cseq,
                    mapOf("Audio-Latency" to "0", "Audio-Jack-Status" to "connected; type=analog")
                )
                return
            }
            "FLUSH" -> {
                s.audio?.flush()
                send(out, proto, 200, "OK", ByteArray(0), null, cseq)
                return
            }
            "SET_PARAMETER" -> {
                val ctype = headers["content-type"]?.substringBefore(';')?.trim()?.lowercase()
                if (ctype == "application/x-dmap-tagged") {
                    val info = DmapParser.parse(body)
                    log("  song: ${info.artist ?: "?"} - ${info.title ?: "?"}")
                    video.audioTrack(info)
                } else if (ctype == "image/jpeg" || ctype == "image/png") {
                    log("  cover art ${body.size} B")
                    video.audioArt(if (body.isEmpty()) null else body)
                }
                val text = if (ctype == null || ctype.startsWith("text/")) String(body, Charsets.ISO_8859_1) else ""
                if (text.startsWith("volume:")) {
                    val db = text.removePrefix("volume:").trim().toFloatOrNull()
                    if (db != null) {
                        s.volumeDb = db
                        s.audio?.setVolumeDb(db)
                        log("  volume $db dB")
                    }
                }
                send(out, proto, 200, "OK", ByteArray(0), null, cseq)
                return
            }
            "GET_PARAMETER" -> {
                val text = String(body, Charsets.ISO_8859_1)
                if (text.startsWith("volume")) {
                    val reply = "volume: %.6f\r\n".format(java.util.Locale.US, s.volumeDb)
                    send(out, proto, 200, "OK", reply.toByteArray(), "text/parameters", cseq)
                } else {
                    send(out, proto, 200, "OK", ByteArray(0), null, cseq)
                }
                return
            }
            "TEARDOWN" -> {
                handleTeardown(s, out, proto, cseq, body)
                return
            }
        }
        if (method == "POST" && (uri == "/feedback" || uri == "/audioMode")) {
            send(out, proto, 200, "OK", ByteArray(0), null, cseq)
            return
        }

        val interesting = listOf("upgrade", "content-type", "user-agent", "x-apple-device-id")
            .mapNotNull { k -> headers[k]?.let { "$k=$it" } }
            .joinToString(" ")
        log("  not handled: $interesting")
        if (body.isNotEmpty()) {
            val preview = body.take(48).joinToString("") { "%02x".format(it) }
            log("  body: $preview${if (body.size > 48) "..." else ""}")
        }
        send(out, proto, 501, "Not Implemented", ByteArray(0), null, cseq)
    }

    // ---- Video AirPlay (/play) -----------------------------------------------------------

    private fun queryParam(uri: String, key: String): String? =
        uri.substringAfter('?', "").split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it[0] == key }?.getOrNull(1)

    private fun handleVideo(
        s: Session,
        out: OutputStream,
        proto: String,
        method: String,
        uri: String,
        headers: Map<String, String>,
        body: ByteArray,
        cseq: String?
    ): Boolean {
        val path = uri.substringBefore('?')
        val plist = "application/x-apple-binary-plist"
        when {
            method == "POST" && path == "/reverse" -> {
                // The HTTP connection is reversed: from now on the TV can send /event requests to the iPhone.
                reverse = s
                headers["x-apple-session-id"]?.let { videoSessionId = it }
                val resp = "$proto 101 Switching Protocols\r\n" +
                    "Upgrade: PTTH/1.0\r\nConnection: Upgrade\r\n" +
                    (if (cseq != null) "CSeq: $cseq\r\n" else "") + "\r\n"
                synchronized(out) {
                    out.write(resp.toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                }
                log("  /reverse: event channel opened")
                return true
            }
            method == "POST" && path == "/play" -> {
                var url: String? = null
                var startSec = -1.0
                var startFrac = -1.0
                val ctype = headers["content-type"] ?: ""
                if (ctype.contains("plist")) {
                    try {
                        val root = PropertyListParser.parse(body) as? NSDictionary
                        if (root != null) {
                            log("  /play plist: " + root.toXMLPropertyList().replace(Regex("\\s+"), " ").take(700))
                            url = (root.objectForKey("Content-Location") as? NSString)?.content
                            (root.objectForKey("Start-Position-Seconds") as? NSNumber)?.let { startSec = it.doubleValue() }
                            (root.objectForKey("Start-Position") as? NSNumber)?.let { startFrac = it.doubleValue() }
                            videoSessionPlist = root.objectForKey("sessionID")
                        }
                    } catch (e: Exception) {
                        log("  could not read /play plist: ${e.message}")
                    }
                } else {
                    // Old format: text/parameters
                    val text = String(body, Charsets.ISO_8859_1)
                    log("  /play text: " + text.replace("\r", "").replace("\n", " | ").take(300))
                    for (line in text.lines()) {
                        val kv = line.split(":", limit = 2)
                        if (kv.size < 2) continue
                        when (kv[0].trim().lowercase()) {
                            "content-location" -> url = kv[1].trim()
                            "start-position" -> startFrac = kv[1].trim().toDoubleOrNull() ?: -1.0
                        }
                    }
                }
                headers["x-apple-session-id"]?.let { videoSessionId = it }
                val u = url
                if (u == null || !(u.startsWith("http://") || u.startsWith("https://"))) {
                    log("  /play not supported: ${u ?: "no address"}")
                    send(out, proto, 501, "Not Implemented", ByteArray(0), null, cseq)
                    return true
                }
                log("  /play: $u")
                send(out, proto, 200, "OK", ByteArray(0), null, cseq)
                video.videoPlay(u, startSec, startFrac)
                return true
            }
            method == "GET" && path == "/playback-info" -> {
                val i = video.videoInfo()
                val d = NSDictionary()
                d.put("readyToPlay", NSNumber(i.ready))
                d.put("playbackBufferEmpty", NSNumber(i.bufferEmpty))
                d.put("playbackBufferFull", NSNumber(false))
                d.put("playbackLikelyToKeepUp", NSNumber(i.ready && !i.bufferEmpty))
                d.put("rate", NSNumber(i.rate.toDouble()))
                if (i.active && i.duration > 0) {
                    d.put("duration", NSNumber(i.duration))
                    d.put("position", NSNumber(i.position))
                    val range = NSDictionary()
                    range.put("start", NSNumber(0.0))
                    range.put("duration", NSNumber(i.duration))
                    d.put("loadedTimeRanges", NSArray(range))
                    val seek = NSDictionary()
                    seek.put("start", NSNumber(0.0))
                    seek.put("duration", NSNumber(i.duration))
                    d.put("seekableTimeRanges", NSArray(seek))
                }
                send(out, proto, 200, "OK", BinaryPropertyListWriter.writeToArray(d), plist, cseq)
                return true
            }
            method == "PUT" && path == "/photo" -> {
                val key = headers["x-apple-assetkey"]
                val action = headers["x-apple-assetaction"]
                log("  /photo key=${key ?: "-"} action=${action ?: "-"} (${body.size} B)")
                var bytes: ByteArray? = if (body.isNotEmpty()) body else null
                if (key != null) {
                    synchronized(photoCache) {
                        if (bytes != null) {
                            photoCache[key] = bytes!!
                            while (photoCache.size > 5) {
                                val iter = photoCache.entries.iterator()
                                iter.next()
                                iter.remove()
                            }
                        } else {
                            bytes = photoCache[key]
                        }
                    }
                }
                val b = bytes
                when {
                    action.equals("cacheOnly", ignoreCase = true) ->
                        send(out, proto, 200, "OK", ByteArray(0), null, cseq)
                    b == null -> {
                        log("  /photo: not in the cache, the iPhone will resend it")
                        send(out, proto, 412, "Precondition Failed", ByteArray(0), null, cseq)
                    }
                    else -> {
                        send(out, proto, 200, "OK", ByteArray(0), null, cseq)
                        video.showPhoto(b)
                    }
                }
                return true
            }
            method == "POST" && path == "/rate" -> {
                val v = queryParam(uri, "value")?.toFloatOrNull() ?: 0f
                video.videoRate(v)
                send(out, proto, 200, "OK", ByteArray(0), null, cseq)
                return true
            }
            method == "POST" && path == "/scrub" -> {
                queryParam(uri, "position")?.toDoubleOrNull()?.let { video.videoSeek(it) }
                send(out, proto, 200, "OK", ByteArray(0), null, cseq)
                return true
            }
            method == "GET" && path == "/scrub" -> {
                val i = video.videoInfo()
                val text = "duration: %f\r\nposition: %f\r\n".format(java.util.Locale.US, i.duration, i.position)
                send(out, proto, 200, "OK", text.toByteArray(), "text/parameters", cseq)
                return true
            }
            method == "POST" && path == "/stop" -> {
                video.videoStop()
                send(out, proto, 200, "OK", ByteArray(0), null, cseq)
                return true
            }
            (method == "POST" && (path == "/action" || path == "/event")) ||
                path == "/getProperty" || path == "/setProperty" -> {
                send(out, proto, 200, "OK", ByteArray(0), null, cseq)
                return true
            }
        }
        return false
    }

    // ---- SETUP / TEARDOWN ----------------------------------------------------------------

    private fun handleSetup(s: Session, out: OutputStream, proto: String, cseq: String?, body: ByteArray) {
        val plistType = "application/x-apple-binary-plist"
        val root = try {
            PropertyListParser.parse(body) as? NSDictionary
        } catch (e: Exception) {
            null
        }
        if (root == null) {
            log("  SETUP: could not read plist")
            send(out, proto, 400, "Bad Request", ByteArray(0), null, cseq)
            return
        }
        val resp = NSDictionary()

        val ekey = (root.objectForKey("ekey") as? NSData)?.bytes()
        val eiv = (root.objectForKey("eiv") as? NSData)?.bytes()
        if (ekey != null && eiv != null) {
            // SETUP 1: key and timing.
            val keyMsg = s.keyMsg
            if (ekey.size != 72 || eiv.size != 16 || keyMsg == null) {
                log("  SETUP 1 rejected (ekey=${ekey.size} eiv=${eiv.size} keymsg=${keyMsg?.size})")
                send(out, proto, 400, "Bad Request", ByteArray(0), null, cseq)
                return
            }
            if (!Playfair.load()) {
                log("  could not load playfair")
                send(out, proto, 500, "Internal Server Error", ByteArray(0), null, cseq)
                return
            }
            val decrypted = Playfair.decrypt(keyMsg, ekey)
            if (decrypted == null || decrypted.size != 16) {
                log("  could not decrypt the FairPlay key")
                send(out, proto, 400, "Bad Request", ByteArray(0), null, cseq)
                return
            }
            // If pairing was done, the key is run through SHA-512 together with the pair-verify shared secret.
            val secret = s.ecdhSecret
            val aes = if (secret != null) {
                MessageDigest.getInstance("SHA-512").digest(decrypted + secret).copyOf(16)
            } else {
                decrypted
            }
            s.aesKey = aes
            s.aesIv = eiv.copyOf()
            log("  SETUP 1: stream key ready")
            val devName = (root.objectForKey("name") as? NSString)?.content
                ?.replace(Regex("[\\p{Cntrl}]"), " ")?.trim()?.take(40)?.takeIf { it.isNotEmpty() }
            if (!s.announced) {
                video.deviceConnected(devName, s.socket.inetAddress?.hostAddress ?: "")
                s.announced = true
            }

            val timingPort = (root.objectForKey("timingPort") as? NSNumber)?.intValue() ?: 0
            s.ntp?.stop()
            val localTiming: Int
            if (timingPort > 0) {
                val ntp = NtpClient(s.socket.inetAddress, timingPort) { m -> log(m) }
                ntp.start()
                s.ntp = ntp
                localTiming = ntp.localPort
            } else {
                val ds = DatagramSocket()
                s.udp.add(ds)
                localTiming = ds.localPort
            }
            resp.put("timingPort", NSNumber(localTiming))
            resp.put("eventPort", NSNumber(0))
        }

        val streams = root.objectForKey("streams") as? NSArray
        if (streams != null) {
            val respStreams = ArrayList<NSObject>()
            for (item in streams.array) {
                val d = item as? NSDictionary ?: continue
                when (val type = (d.objectForKey("type") as? NSNumber)?.intValue() ?: -1) {
                    110 -> {
                        val aes = s.aesKey
                        if (aes == null) {
                            log("  SETUP 110: no stream key")
                            send(out, proto, 400, "Bad Request", ByteArray(0), null, cseq)
                            return
                        }
                        val id = (d.objectForKey("streamConnectionID") as? NSNumber)?.longValue() ?: 0L
                        s.stopMirror()
                        val decoder = MirrorDecoder(
                            { events.mirrorSurface() },
                            { w, h -> events.mirrorSize(w, h) },
                            { m -> log(m) }
                        )
                        val receiver = MirrorReceiver(aes, id, decoder, { m -> log(m) }) {
                            events.mirrorStopped()
                        }
                        receiver.start()
                        s.mirror = receiver
                        events.mirrorStarted()
                        log("  SETUP 110: mirroring port ${receiver.port}")
                        val rd = NSDictionary()
                        rd.put("dataPort", NSNumber(receiver.port))
                        rd.put("type", NSNumber(110))
                        respStreams.add(rd)
                    }
                    96 -> {
                        val data = DatagramSocket()
                        val control = DatagramSocket()
                        // Read the ports now: if the receiver cannot start, its sockets are closed and localPort becomes -1.
                        val dataPort = data.localPort
                        val controlPort = control.localPort
                        var ct = (d.objectForKey("ct") as? NSNumber)?.intValue() ?: 0
                        val audioFormat = (d.objectForKey("audioFormat") as? NSNumber)?.longValue() ?: 0L
                        if (ct == 0) {
                            // If ct did not arrive, derive it from the audioFormat bits.
                            ct = when (audioFormat) {
                                0x1000000L -> 8 // AAC-ELD
                                0x400000L -> 4 // AAC-LC
                                0x40000L -> 2 // ALAC
                                else -> 0
                            }
                        }
                        val spf = (d.objectForKey("spf") as? NSNumber)?.intValue() ?: 0
                        log("  SETUP 96: ct=$ct spf=$spf audioFormat=0x${audioFormat.toString(16)}")
                        val aes = s.aesKey
                        val iv = s.aesIv
                        s.stopAudio()
                        if (aes != null && iv != null) {
                            val receiver = AudioReceiver(aes, iv, ct, data, control) { m -> log(m) }
                            if (receiver.start()) {
                                receiver.setVolumeDb(s.volumeDb)
                                s.audio = receiver
                                video.audioStarted()
                            } else {
                                s.udp.add(data)
                                s.udp.add(control)
                            }
                        } else {
                            log("  SETUP 96: no stream key, audio ignored")
                            s.udp.add(data)
                            s.udp.add(control)
                        }
                        val rd = NSDictionary()
                        rd.put("dataPort", NSNumber(dataPort))
                        rd.put("controlPort", NSNumber(controlPort))
                        rd.put("type", NSNumber(96))
                        respStreams.add(rd)
                    }
                    else -> log("  SETUP: unknown stream type $type")
                }
            }
            resp.put("streams", NSArray(*respStreams.toTypedArray()))
        }

        send(out, proto, 200, "OK", BinaryPropertyListWriter.writeToArray(resp), plistType, cseq)
    }

    private fun handleTeardown(s: Session, out: OutputStream, proto: String, cseq: String?, body: ByteArray) {
        var has96 = false
        var has110 = false
        if (body.isNotEmpty()) {
            try {
                val root = PropertyListParser.parse(body) as? NSDictionary
                val arr = root?.objectForKey("streams") as? NSArray
                if (arr != null) {
                    for (item in arr.array) {
                        when ((item as? NSDictionary)?.objectForKey("type").let { (it as? NSNumber)?.intValue() }) {
                            96 -> has96 = true
                            110 -> has110 = true
                        }
                    }
                }
            } catch (e: Exception) {
                log("  could not read TEARDOWN plist: ${e.message}")
            }
        }
        log("  TEARDOWN 96=$has96 110=$has110")
        send(out, proto, 200, "OK", ByteArray(0), null, cseq, mapOf("Connection" to "close"))
        when {
            has96 -> {
                s.stopAudio()
                s.closeUdp()
                video.audioEnded()
            }
            has110 -> if (s.stopMirror()) events.mirrorStopped()
            else -> {
                if (s.closeAll()) events.mirrorStopped()
                video.audioEnded()
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun send(
        out: OutputStream,
        proto: String,
        code: Int,
        text: String,
        body: ByteArray,
        contentType: String?,
        cseq: String?,
        extra: Map<String, String> = emptyMap()
    ) {
        val sb = StringBuilder()
        sb.append("$proto $code $text\r\n")
        sb.append("Server: AirTunes/${airplay.srcvers}\r\n")
        if (cseq != null) sb.append("CSeq: $cseq\r\n")
        for ((k, v) in extra) sb.append("$k: $v\r\n")
        if (contentType != null) sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${body.size}\r\n\r\n")
        synchronized(out) {
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
        }
    }

    private fun infoPlist(): ByteArray {
        val d = NSDictionary()
        d.put("deviceid", NSString(airplay.deviceId))
        d.put("features", NSNumber(airplay.featuresLong))
        d.put("model", NSString(airplay.model))
        d.put("name", NSString(airplay.name))
        d.put("protovers", NSString("1.1"))
        d.put("srcvers", NSString(airplay.srcvers))
        d.put("pi", NSString(airplay.pi))
        d.put("pk", NSData(airplay.publicKey))
        d.put("vv", NSNumber(2))
        d.put("statusFlags", NSNumber(4))
        d.put("keepAliveLowPower", NSNumber(true))
        d.put("keepAliveSendStatsAsBody", NSNumber(true))

        // Display information for screen mirroring (same keys as UxPlay).
        val display = NSDictionary()
        display.put("uuid", NSString("e0ff8a27-6738-3d56-8a16-cc53aacee925"))
        display.put("widthPhysical", NSNumber(0))
        display.put("heightPhysical", NSNumber(0))
        display.put("width", NSNumber(1920))
        display.put("height", NSNumber(1080))
        display.put("widthPixels", NSNumber(1920))
        display.put("heightPixels", NSNumber(1080))
        display.put("rotation", NSNumber(false))
        display.put("refreshRate", NSNumber(1.0 / 60.0))
        display.put("maxFPS", NSNumber(60))
        display.put("overscanned", NSNumber(false))
        display.put("features", NSNumber(14))
        d.put("displays", NSArray(display))
        return BinaryPropertyListWriter.writeToArray(d)
    }

    /** Reads a line byte by byte so that a binary body is not corrupted. */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return buf.toString("ISO-8859-1")
    }
}
