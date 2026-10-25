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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Ekran yansitma sesi. Paket bicimi UxPlay'in raop_buffer.c / raop_rtp.c dosyalarindan:
 *  - UDP, 12 baytlik RTP basligi + yuk
 *  - yukun tam 16 baytlik bloklari AES-128-CBC ile sifreli (her paketin IV'si ayni, eiv),
 *    kalan kuyruk bayt sifresiz
 *  - ct=8: AAC-ELD 44100 Hz stereo (yansitma), ct=4: AAC-LC, ct=2: ALAC (desteklenmiyor)
 * Ses MediaCodec ile cozulup AudioTrack ile calinir. Kayip paket istegi (resend) yok. Goruntu ile
 * senkron SyncSettings'teki elle ayarlanan kaydirma ile yapilir (TV'de SAG/SOL tuslari).
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
    private var lastSeq = -1
    private var packets = 0
    private var ptsUs = 0L

    // Ses/goruntu ayari: ses gecikmesi sessizlik yazarak artirilir, ornek atlayarak azaltilir.
    @Volatile
    private var outRate = 44100

    @Volatile
    private var outChannels = 2
    private var appliedDelayMs = 0
    private var skipBytes = 0

    /** Ses akisini baslatir. Codec desteklenmiyorsa false doner (ses yok sayilir). */
    fun start(): Boolean {
        val config: Pair<ByteArray, Int> = when (ct) {
            // AudioSpecificConfig: ELD (tur 39), 44100 Hz, stereo
            8 -> byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00) to 39
            // AAC-LC, 44100 Hz, stereo
            4 -> byteArrayOf(0x12, 0x10) to 2
            else -> {
                log("Ses türü desteklenmiyor (ct=$ct)")
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
            log("Ses çözücüsü kurulamadı: ${e.javaClass.simpleName} ${e.message}")
            releaseAll()
            return false
        }
        thread(name = "audio-recv", isDaemon = true) { receiveLoop() }
        thread(name = "audio-ctrl", isDaemon = true) { drainControl() }
        log("Ses hazır (ct=$ct)")
        return true
    }

    private fun receiveLoop() {
        val buf = ByteArray(2048)
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                data.receive(pkt)
                handlePacket(buf, pkt.length)
            } catch (e: Exception) {
                if (running) log("Ses alma hatası: ${e.javaClass.simpleName} ${e.message}")
                break
            }
        }
    }

    /** Kontrol portuna gelen senkron paketlerini okuyup atar (yanit gerekmiyor). */
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
        if (len <= 12) return // zaman senkronundan once bos paketler gelir
        val seq = ((p[2].toInt() and 0xff) shl 8) or (p[3].toInt() and 0xff)
        if (lastSeq >= 0) {
            val diff = ((seq - lastSeq) shl 16) shr 16 // 16 bit tasmayi dogru isle
            if (diff <= 0) return // tekrar ya da gec gelen paket
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
            log("Ses şifresi çözülemedi: ${e.message}")
            return
        }
        System.arraycopy(p, 12 + enc, out, enc, size - enc)

        packets++
        if (packets == 1) {
            log("İlk ses paketi: ${size} B, ilk bayt ${"%02x".format(out[0])}")
        }
        feed(out, size)
    }

    private fun feed(frame: ByteArray, size: Int) {
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
            ptsUs += 10_884L // 480 ornek / 44100 Hz
            c.queueInputBuffer(idx, 0, size, ptsUs, 0)
        } catch (e: Exception) {
            if (running) log("Ses çözücü hatası: ${e.javaClass.simpleName} ${e.message}")
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
                    break // codec kapatildi
                }
            }
        }
    }

    /** SyncSettings'teki ses gecikmesi degistiyse sessizlik ekler ya da kisa bir kismi atlar. */
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
            log("Ses çıkışı: ${rate} Hz, $channels kanal")
        } catch (e: Exception) {
            log("AudioTrack kurulamadı: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /** AirPlay ses seviyesi dB cinsinden gelir: -144 sessiz, -30 ... 0 aralik. */
    fun setVolumeDb(db: Float) {
        gain = when {
            db <= -144f || db <= -30f -> 0f
            db >= 0f -> 1f
            else -> (30f + db) / 30f
        }
        try {
            track?.setVolume(gain)
        } catch (e: Exception) {
            // onemsiz
        }
    }

    /** Arabellegi bosaltir (FLUSH istegi). */
    fun flush() {
        try {
            track?.pause()
            track?.flush()
            track?.play()
            appliedDelayMs = 0 // bosalan arabellekle birlikte eklenen sessizlik de gitti
            skipBytes = 0
        } catch (e: Exception) {
            // onemsiz
        }
    }

    fun stop() {
        running = false
        releaseAll()
    }

    private fun releaseAll() {
        runCatching { data.close() }
        runCatching { control.close() }
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
}
