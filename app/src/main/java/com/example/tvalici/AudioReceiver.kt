package com.example.tvalici

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Screen mirroring audio. The packet format comes from UxPlay's raop_buffer.c / raop_rtp.c:
 *  - UDP, 12-byte RTP header + payload
 *  - the payload's full 16-byte blocks are encrypted with AES-128-CBC (every packet has the same IV, eiv),
 *    the remaining tail bytes are unencrypted
 *  - ct=8: AAC-ELD 44100 Hz stereo (mirroring), ct=4: AAC-LC, ct=2: ALAC (audio only, e.g. Music;
 *    decoded with AlacDecoder, with a larger output buffer and pre-buffering)
 * Audio is decoded with MediaCodec and played with AudioTrack. Lost packets are not re-requested (no resend).
 * Sync with the picture is done with the manually adjusted offset in SyncSettings (LEFT/RIGHT keys on the TV).
 */
class AudioReceiver(
    aesKey: ByteArray,
    aesIv: ByteArray,
    private val ct: Int,
    private val data: DatagramSocket,
    private val control: DatagramSocket,
    private val log: (String) -> Unit
) {
    private val keySpec = SecretKeySpec(aesKey, "AES")
    private val ivSpec = IvParameterSpec(aesIv)
    private val cbc: Cipher = Cipher.getInstance("AES/CBC/NoPadding")

    @Volatile
    private var running = true
    private var codec: MediaCodec? = null

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var gain = 1.0f
    @Volatile
    private var lastSeq = -1
    private var packets = 0
    private var lostPackets = 0 // packets with skipped (missing) sequence numbers
    private var alacErrors = 0
    private var latePackets = 0 // repeated or late packets
    private var ptsUs = 0L

    // Audio/video offset: audio delay is increased by writing silence and decreased by skipping samples.
    @Volatile
    private var outRate = 44100

    @Volatile
    private var outChannels = 2
    private var appliedDelayMs = 0
    private var skipBytes = 0

    // ALAC (audio-only) path: decoded PCM is put on a queue and a separate thread writes it to the AudioTrack.
    @Volatile
    private var alac: AlacDecoder? = null
    private val alacPcm = ByteArray(AlacDecoder.MAX_PCM_BYTES)
    private val pcmQueue = LinkedBlockingQueue<ByteArray>(512) // about 4 s

    @Volatile
    private var flushRequested = false

    /** Starts the audio stream. Returns false if the codec is not supported (the audio is ignored). */
    fun start(): Boolean {
        if (ct == 2) return startAlac()
        val config: Pair<ByteArray, Int> = when (ct) {
            // AudioSpecificConfig: ELD (type 39), 44100 Hz, stereo
            8 -> byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00) to 39
            // AAC-LC, 44100 Hz, stereo
            4 -> byteArrayOf(0x12, 0x10) to 2
            else -> {
                log("Audio type not supported (ct=$ct)")
                return false
            }
        }
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, config.second)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(config.first))
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(format, null, null, 0)
            c.start()
            codec = c
            startDrain(c)
        } catch (e: Exception) {
            log("Could not set up the audio decoder: ${e.javaClass.simpleName} ${e.message}")
            releaseAll()
            return false
        }
        thread(name = "audio-recv", isDaemon = true) { receiveLoop() }
        thread(name = "audio-ctrl", isDaemon = true) { drainControl() }
        log("Audio ready (ct=$ct)")
        return true
    }

    private fun startAlac(): Boolean {
        val d = AlacDecoder.create()
        if (d == null) {
            log("Could not load the ALAC decoder, audio ignored")
            return false
        }
        alac = d
        if (!createAlacTrack()) {
            releaseAll()
            return false
        }
        thread(name = "audio-recv", isDaemon = true) { receiveLoop() }
        thread(name = "audio-ctrl", isDaemon = true) { drainControl() }
        thread(name = "audio-alac-out", isDaemon = true) { alacWriteLoop() }
        log("Audio ready (ALAC, audio only)")
        return true
    }

    /** A wide buffer for music (~600 ms): latency does not matter, and Wi-Fi jitter is hidden. */
    private fun createAlacTrack(): Boolean {
        return try {
            outRate = 44100
            outChannels = 2
            val mask = AudioFormat.CHANNEL_OUT_STEREO
            val minBuf = AudioTrack.getMinBufferSize(44100, mask, AudioFormat.ENCODING_PCM_16BIT)
            val bufBytes = maxOf(minBuf * 2, ALAC_BUFFER_BYTES)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(44100)
                        .setChannelMask(mask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.setVolume(gain)
            // play() is called when the pre-buffering is full (alacWriteLoop).
            track = t
            log("Audio output (ALAC): 44100 Hz, 2 channels, buffer ${bufBytes / 4 * 1000 / 44100} ms")
            true
        } catch (e: Exception) {
            log("Could not set up AudioTrack: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    /** Writes the queued PCM to the AudioTrack; starts playing once enough data has accumulated. */
    private fun alacWriteLoop() {
        var written = 0
        var prebuffering = true
        while (running) {
            try {
                val pcm = pcmQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val t = track ?: break
                if (flushRequested) {
                    flushRequested = false
                    prebuffering = true
                    written = 0
                }
                t.write(pcm, 0, pcm.size)
                if (prebuffering) {
                    written += pcm.size
                    if (written >= ALAC_PREBUFFER_BYTES) {
                        t.play()
                        prebuffering = false
                    }
                }
            } catch (e: Exception) {
                break // AudioTrack was closed
            }
        }
    }

    private fun receiveLoop() {
        val buf = ByteArray(2048)
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                data.receive(pkt)
                handlePacket(buf, pkt.length)
            } catch (e: Exception) {
                if (running) log("Audio receive error: ${e.javaClass.simpleName} ${e.message}")
                break
            }
        }
    }

    /** Reads and discards the sync packets arriving on the control port (no reply is needed). */
    private fun drainControl() {
        val buf = ByteArray(2048)
        while (running) {
            try {
                control.receive(DatagramPacket(buf, buf.size))
            } catch (e: Exception) {
                break
            }
        }
    }

    private fun handlePacket(p: ByteArray, len: Int) {
        if (len <= 12) return // empty packets arrive before time sync
        val seq = ((p[2].toInt() and 0xff) shl 8) or (p[3].toInt() and 0xff)
        if (lastSeq >= 0) {
            val diff = ((seq - lastSeq) shl 16) shr 16 // handle 16-bit overflow correctly
            if (diff <= 0) {
                latePackets++ // repeated or late packet
                return
            }
            if (diff > 1) lostPackets += diff - 1
        }
        lastSeq = seq

        val size = len - 12
        val out = ByteArray(size)
        val enc = size / 16 * 16
        try {
            if (enc > 0) {
                cbc.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
                cbc.doFinal(p, 12, enc, out, 0)
            }
        } catch (e: Exception) {
            log("Could not decrypt the audio: ${e.message}")
            return
        }
        System.arraycopy(p, 12 + enc, out, enc, size - enc)

        packets++
        if (packets == 1) {
            log("First audio packet: ${size} B, first byte ${"%02x".format(out[0])}")
        }
        if (packets % 1000 == 0) {
            val total = packets + lostPackets
            log("Audio: $packets packets received, $lostPackets lost (%${"%.2f".format(lostPackets * 100.0 / total)}), $latePackets late/repeated")
        }
        feed(out, size)
    }

    private fun feed(frame: ByteArray, size: Int) {
        val a = alac
        if (a != null) {
            val n = a.decode(frame, size, alacPcm)
            if (n <= 0) {
                alacErrors++
                if (alacErrors == 1 || alacErrors % 200 == 0) log("Could not decode ALAC frame ($alacErrors)")
                return
            }
            val pcm = alacPcm.copyOf(n)
            if (!pcmQueue.offer(pcm)) {
                pcmQueue.poll() // the writer cannot keep up: drop the oldest frame
                pcmQueue.offer(pcm)
            }
            return
        }
        val c = codec ?: return
        try {
            val idx = c.dequeueInputBuffer(5_000)
            if (idx < 0) return
            val buf = c.getInputBuffer(idx) ?: return
            buf.clear()
            if (size > buf.capacity()) {
                c.queueInputBuffer(idx, 0, 0, ptsUs, 0)
                return
            }
            buf.put(frame, 0, size)
            ptsUs += 10_884L // 480 samples / 44100 Hz
            c.queueInputBuffer(idx, 0, size, ptsUs, 0)
        } catch (e: Exception) {
            if (running) log("Audio decoder error: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun startDrain(c: MediaCodec) {
        thread(name = "audio-drain", isDaemon = true) {
            val info = MediaCodec.BufferInfo()
            while (running && codec === c) {
                try {
                    val i = c.dequeueOutputBuffer(info, 10_000)
                    when {
                        i >= 0 -> {
                            val out = c.getOutputBuffer(i)
                            val t = track
                            if (out != null && t != null && info.size > 0) {
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                applyDelay(t)
                                var n = info.size
                                if (skipBytes > 0) {
                                    val s = minOf(skipBytes, n)
                                    out.position(info.offset + s)
                                    skipBytes -= s
                                    n -= s
                                }
                                if (n > 0) t.write(out, n, AudioTrack.WRITE_BLOCKING)
                            }
                            c.releaseOutputBuffer(i, false)
                        }
                        i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> createTrack(c.outputFormat)
                    }
                } catch (e: Exception) {
                    break // codec was closed
                }
            }
        }
    }

    /** If the audio delay in SyncSettings changed, adds silence or skips a short part. */
    private fun applyDelay(t: AudioTrack) {
        val want = SyncSettings.audioDelayMs
        if (want == appliedDelayMs) return
        val frameBytes = outChannels * 2
        if (want > appliedDelayMs) {
            var bytes = (want - appliedDelayMs) * outRate / 1000 * frameBytes
            val cancel = minOf(skipBytes, bytes)
            skipBytes -= cancel
            bytes -= cancel
            if (bytes > 0) t.write(ByteBuffer.allocate(bytes), bytes, AudioTrack.WRITE_BLOCKING)
        } else {
            skipBytes += (appliedDelayMs - want) * outRate / 1000 * frameBytes
        }
        appliedDelayMs = want
    }

    private fun createTrack(f: MediaFormat) {
        try {
            val rate = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channels = if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            outRate = rate
            outChannels = channels
            val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuf = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= 26) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            val t = builder.build()
            t.setVolume(gain)
            t.play()
            track?.release()
            track = t
            log("Audio output: ${rate} Hz, $channels channels")
        } catch (e: Exception) {
            log("Could not set up AudioTrack: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /** The AirPlay volume comes in dB: -144 is silent, the range is -30 ... 0. */
    fun setVolumeDb(db: Float) {
        gain = when {
            db <= -144f || db <= -30f -> 0f
            db >= 0f -> 1f
            else -> (30f + db) / 30f
        }
        try {
            track?.setVolume(gain)
        } catch (e: Exception) {
            // unimportant
        }
    }

    /** Empties the buffer (FLUSH request). */
    fun flush() {
        lastSeq = -1 // after FLUSH the sequence number may restart
        if (alac != null) {
            flushRequested = true
            pcmQueue.clear()
            try {
                track?.pause()
                track?.flush()
            } catch (e: Exception) {
                // unimportant
            }
            return
        }
        try {
            track?.pause()
            track?.flush()
            track?.play()
            appliedDelayMs = 0 // the silence added together with the emptied buffer is gone too
            skipBytes = 0
        } catch (e: Exception) {
            // unimportant
        }
    }

    fun stop() {
        running = false
        releaseAll()
    }

    private fun releaseAll() {
        runCatching { data.close() }
        runCatching { control.close() }
        pcmQueue.clear()
        val a = alac
        alac = null
        a?.release()
        val c = codec
        codec = null
        if (c != null) {
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        val t = track
        track = null
        if (t != null) {
            runCatching { t.stop() }
            runCatching { t.release() }
        }
    }

    private companion object {
        const val ALAC_BUFFER_BYTES = 44100 * 4 * 600 / 1000 // 600 ms
        const val ALAC_PREBUFFER_BYTES = 44100 * 4 * 300 / 1000 // 300 ms
    }
}
