package com.example.tvalici

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Screen mirroring video stream (TCP). The packet format comes from UxPlay's raop_rtp_mirror.c:
 *  - 128-byte header: [0..3] payload size (LE), [4] type, [8..15] timestamp
 *  - type 0: encrypted frame (AVCC format, NALs with a 4-byte length prefix)
 *  - type 1: unencrypted SPS+PPS
 *  - type 2, 5: heartbeat / report (ignored)
 * Cipher: AES-128-CTR, the key and IV come from "AirPlayStreamKey/IV<streamConnectionID>" + the SHA-512 of aeskey.
 * The key stream continues without interruption across packets (each packet continues the previous one).
 */
class MirrorReceiver(
    aesKey: ByteArray,
    streamConnectionId: Long,
    private val decoder: MirrorDecoder,
    private val log: (String) -> Unit,
    private val onEnded: () -> Unit
) {
    // The receive buffer must be set BEFORE bind so that the accepted socket inherits it (so the TCP window
    // does not stay small during Wi-Fi fluctuations and frames do not arrive in bursts and add delay).
    private val serverSocket = ServerSocket().apply {
        receiveBufferSize = 1 shl 20
        bind(InetSocketAddress(0))
    }
    val port: Int get() = serverSocket.localPort

    @Volatile
    private var running = true

    @Volatile
    private var client: Socket? = null
    private val cipher: Cipher

    init {
        val id = java.lang.Long.toUnsignedString(streamConnectionId)
        val keyHash = sha512("AirPlayStreamKey$id".toByteArray(Charsets.US_ASCII) + aesKey)
        val ivHash = sha512("AirPlayStreamIV$id".toByteArray(Charsets.US_ASCII) + aesKey)
        cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyHash.copyOf(16), "AES"),
            IvParameterSpec(ivHash.copyOf(16))
        )
    }

    fun start() {
        thread(name = "mirror-recv", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            try {
                val s = serverSocket.accept()
                client = s
                s.tcpNoDelay = true
                log("Mirroring connection: ${s.inetAddress.hostAddress}")
                readLoop(s)
            } catch (e: Exception) {
                if (running) log("Mirroring error: ${e.javaClass.simpleName} ${e.message}")
            } finally {
                val wasRunning = running
                stop()
                if (wasRunning) onEnded()
            }
        }
    }

    fun stop() {
        running = false
        SyncSettings.mirrorLive = false
        try {
            client?.close()
        } catch (e: Exception) {
            // unimportant
        }
        try {
            serverSocket.close()
        } catch (e: Exception) {
            // unimportant
        }
        decoder.release()
    }

    private fun readLoop(socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 1 shl 16))
        val header = ByteArray(128)
        var frames = 0
        MirrorStats.reset()
        var packets = 0
        // Measurement: the arrival time between consecutive frames (network jitter).
        var lastArrivalNs = 0L
        var gapSumNs = 0L
        var gapMaxNs = 0L
        var gapCount = 0
        var gapOver50 = 0
        // Measurement: the difference between the frame timestamp (iPhone clock, 2^-32 s) and the arrival time = network jitter;
        // gaps in the timestamps themselves = the source (iPhone) did not produce frames.
        var arr0 = 0L
        var ts0 = 0L
        var lastTs = 0L
        // Jitter buffer: d = arrival - (iPhone timestamp); the smallest d (sliding window) = the best case of network delay.
        // j = d - minD = this frame's extra delay. Draw time = arrival - j + buffer: however late a frame arrives
        // (if j < buffer) it is drawn at the same rhythm.
        var winStartNs = 0L
        var curMinD = Long.MAX_VALUE
        var prevMinD = Long.MAX_VALUE
        val jitSamples = LongArray(300)
        var srcGapOver50 = 0
        // Automatic buffer: the needed buffer is computed from the jitter of the last 150 frames.
        val adaptSamples = LongArray(150)
        var adaptCount = 0
        while (running) {
            input.readFully(header)
            val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val size = hb.getInt(0)
            val type = header[4].toInt() and 0xff
            if (size < 0 || size > 16 * 1024 * 1024) {
                log("Invalid packet size: $size")
                return
            }
            val payload = ByteArray(size)
            if (size > 0) input.readFully(payload)

            packets++
            if (packets <= 6) log("Packet #$packets type=$type size=$size")

            when (type) {
                0 -> {
                    // Always decrypt: the key stream must stay aligned even if a packet is skipped.
                    // Decrypted in place (same array): no second array allocation + copy per frame.
                    val n = cipher.update(payload, 0, size, payload, 0)
                    if (n != size) {
                        log("Decrypted size mismatch ($n != $size)")
                        return
                    }
                    val kind = toAnnexB(payload, size)
                    if (kind != FRAME_INVALID) {
                        val now = System.nanoTime()
                        if (lastArrivalNs != 0L) {
                            val gap = now - lastArrivalNs
                            gapSumNs += gap
                            if (gap > gapMaxNs) gapMaxNs = gap
                            if (gap > 50_000_000L) gapOver50++
                            gapCount++
                        }
                        lastArrivalNs = now
                        val ts = hb.getLong(8)
                        val bufferNs = SyncSettings.effectiveJitterMs * 1_000_000L
                        var j = 0L
                        if (frames == 0) {
                            arr0 = now
                            ts0 = ts
                            winStartNs = now
                            SyncSettings.mirrorLive = true
                        } else {
                            val tsRelNs = ((ts - ts0).toDouble() * 1e9 / 4294967296.0).toLong()
                            val d = (now - arr0) - tsRelNs
                            if (d < curMinD) curMinD = d
                            if (now - winStartNs > 10_000_000_000L) { // 10 s window: adapts to clock drift / route changes
                                prevMinD = curMinD
                                curMinD = d
                                winStartNs = now
                            }
                            j = d - minOf(curMinD, prevMinD)
                            if (j > 3_000_000_000L) {
                                // The timestamp alignment broke (clock jump etc.): rebuild the baseline.
                                arr0 = now
                                ts0 = ts
                                curMinD = Long.MAX_VALUE
                                prevMinD = Long.MAX_VALUE
                                winStartNs = now
                                j = 0L
                            }
                            if ((ts - lastTs).toDouble() * 1000.0 / 4294967296.0 > 50.0) srcGapOver50++
                        }
                        jitSamples[frames % 300] = j
                        if (frames > 0 && SyncSettings.jitterBufferMs < 0) {
                            adaptSamples[adaptCount++] = j
                            if (adaptCount == adaptSamples.size) {
                                autoTune(adaptSamples)
                                adaptCount = 0
                            }
                        }
                        lastTs = ts
                        val targetNs = if (bufferNs > 0) now - j + bufferNs else 0L
                        decoder.feed(payload, size, kind == FRAME_IDR, targetNs)
                        frames++
                        MirrorStats.frames = frames.toLong()
                        if (frames == 1) log("First frame given to the decoder")
                        if (frames % 300 == 0) {
                            val avg = if (gapCount > 0) gapSumNs / gapCount / 1000 else 0L
                            log(
                                "Frames: $frames | arrival interval avg ${avg / 1000}.${(avg % 1000) / 100} ms, " +
                                    "max ${gapMaxNs / 1_000_000} ms, over 50 ms: $gapOver50"
                            )
                            val sorted = jitSamples.copyOf().also { it.sort() }
                            val over = sorted.count { it > bufferNs }
                            log(
                                "Jitter: p50 ${fmtMs(sorted[149])}, p95 ${fmtMs(sorted[284])}, p99 ${fmtMs(sorted[296])}, " +
                                    "max ${fmtMs(sorted[299])} ms | buffer ${SyncSettings.effectiveJitterMs} ms" +
                                    "${if (SyncSettings.jitterBufferMs < 0) " (otomatik)" else ""}, " +
                                    "frames over the buffer: $over/300 | gaps over 50 ms on the iPhone: $srcGapOver50"
                            )
                            gapSumNs = 0L; gapMaxNs = 0L; gapCount = 0; gapOver50 = 0
                            srcGapOver50 = 0
                        }
                    } else if (frames < 3) {
                        log("Invalid frame format (the key may be wrong)")
                    }
                }
                1 -> handleCodecPacket(header, payload)
                else -> {} // heartbeat / report
            }
        }
    }

    /**
     * AVCC (4-byte length) -> Annex-B (00 00 00 01), in place.
     * Returns: FRAME_INVALID, FRAME_DELTA (P frame) or FRAME_IDR (key frame, contains NAL type 5).
     */
    private var lowWindows = 0

    /**
     * Automatic buffer: the 98th-percentile jitter of the last window + 10 ms, rounded up to 20 ms (40-160 ms).
     * It grows immediately (at most +40 ms per window); it shrinks only when 3 windows in a row are low, and in 20 ms steps:
     * when the buffer changes, the audio gets the same amount of silence added/skipped, so frequent changes are audible.
     */
    private fun autoTune(samples: LongArray) {
        val sorted = samples.copyOf().also { it.sort() }
        val p98Ms = sorted[sorted.size * 98 / 100] / 1_000_000
        val desired = (((p98Ms + AUTO_MARGIN_MS + 19) / 20) * 20).toInt()
            .coerceIn(SyncSettings.AUTO_MIN_MS, SyncSettings.AUTO_MAX_MS)
        val cur = SyncSettings.autoJitterMs
        var next = cur
        when {
            desired > cur -> {
                lowWindows = 0
                next = minOf(desired, cur + 40)
            }
            desired < cur -> {
                lowWindows++
                if (lowWindows >= 3) {
                    lowWindows = 0
                    next = cur - 20
                }
            }
            else -> lowWindows = 0
        }
        if (next != cur) {
            SyncSettings.autoJitterMs = next
            log("Auto buffer: $cur -> $next ms (98% of jitter ${p98Ms} ms)")
        }
    }

    private fun fmtMs(ns: Long): String = "${ns / 1_000_000}.${(ns % 1_000_000) / 100_000}"

    private fun toAnnexB(data: ByteArray, size: Int): Int {
        var pos = 0
        var idr = false
        while (pos + 4 <= size) {
            val len = ((data[pos].toInt() and 0xff) shl 24) or
                ((data[pos + 1].toInt() and 0xff) shl 16) or
                ((data[pos + 2].toInt() and 0xff) shl 8) or
                (data[pos + 3].toInt() and 0xff)
            if (len < 0 || len > size - pos - 4) return FRAME_INVALID
            if (len > 0 && (data[pos + 4].toInt() and 0x1f) == 5) idr = true
            data[pos] = 0
            data[pos + 1] = 0
            data[pos + 2] = 0
            data[pos + 3] = 1
            pos += 4 + len
        }
        if (pos != size || size <= 0) return FRAME_INVALID
        return if (idr) FRAME_IDR else FRAME_DELTA
    }

    private fun handleCodecPacket(header: ByteArray, p: ByteArray) {
        if (p.size < 11) {
            log("SPS/PPS packet too short: ${p.size}")
            return
        }
        val spsSize = ((p[6].toInt() and 0xff) shl 8) or (p[7].toInt() and 0xff)
        if (8 + spsSize + 3 > p.size) {
            log("Invalid SPS size: $spsSize")
            return
        }
        val sps = p.copyOfRange(8, 8 + spsSize)
        val ppsSize = ((p[spsSize + 9].toInt() and 0xff) shl 8) or (p[spsSize + 10].toInt() and 0xff)
        if (spsSize + 11 + ppsSize > p.size) {
            log("Invalid PPS size: $ppsSize")
            return
        }
        val pps = p.copyOfRange(spsSize + 11, spsSize + 11 + ppsSize)

        val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        var w = hb.getFloat(40).toInt()
        var h = hb.getFloat(44).toInt()
        if (w <= 0 || h <= 0) {
            w = 1920
            h = 1080
        }
        log("SPS/PPS received (sps=$spsSize pps=$ppsSize ${w}x$h)")
        decoder.configure(sps, pps, w, h)
    }

    private fun sha512(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(data)

    private companion object {
        const val FRAME_INVALID = 0
        const val FRAME_DELTA = 1
        const val FRAME_IDR = 2
        const val AUTO_MARGIN_MS = 10L
    }
}
