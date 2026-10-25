package com.example.tvalici

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/** H.264 karelerini MediaCodec ile cozup dogrudan Surface'e cizer. */
class MirrorDecoder(
    private val surfaceProvider: () -> Surface?,
    private val onSize: (Int, Int) -> Unit,
    private val log: (String) -> Unit
) {
    private var codec: MediaCodec? = null
    private var currentSps: ByteArray? = null
    private var currentPps: ByteArray? = null
    private var ptsUs = 0L
    private var dropped = 0

    private val startCode = byteArrayOf(0, 0, 0, 1)

    /** SPS/PPS degistiyse (ya da ilk kez geldiyse) cozucuyu yeniden kurar. */
    @Synchronized
    fun configure(sps: ByteArray, pps: ByteArray, width: Int, height: Int) {
        if (codec != null &&
            currentSps?.contentEquals(sps) == true &&
            currentPps?.contentEquals(pps) == true
        ) return

        releaseCodec()
        val surface = surfaceProvider()
        if (surface == null || !surface.isValid) {
            log("Video yüzeyi hazır değil")
            return
        }
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(startCode + sps))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(startCode + pps))
            format.setInteger("low-latency", 1) // eski surumlerde yok sayilir
            format.setInteger(MediaFormat.KEY_PRIORITY, 0) // gercek zamanli
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            currentSps = sps
            currentPps = pps
            ptsUs = 0L
            log("Çözücü hazır ${width}x$height")
            startDrain(c)
        } catch (e: Exception) {
            log("Çözücü kurulamadı: ${e.javaClass.simpleName} ${e.message}")
            releaseCodec()
        }
    }

    private fun startDrain(c: MediaCodec) {
        thread(name = "mirror-drain", isDaemon = true) {
            val info = MediaCodec.BufferInfo()
            while (codec === c) {
                try {
                    val i = c.dequeueOutputBuffer(info, 10_000)
                    when {
                        i >= 0 -> c.releaseOutputBuffer(i, true) // hemen ciz
                        i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> reportSize(c.outputFormat)
                    }
                } catch (e: Exception) {
                    break // codec kapatildi
                }
            }
        }
    }

    private fun reportSize(f: MediaFormat) {
        try {
            var w = f.getInteger(MediaFormat.KEY_WIDTH)
            var h = f.getInteger(MediaFormat.KEY_HEIGHT)
            if (f.containsKey("crop-left") && f.containsKey("crop-right") &&
                f.containsKey("crop-top") && f.containsKey("crop-bottom")
            ) {
                w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1
                h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
            }
            if (w > 0 && h > 0) onSize(w, h)
        } catch (e: Exception) {
            log("Çıkış boyutu okunamadı: ${e.message}")
        }
    }

    /** Annex-B bicimindeki bir kareyi cozucuye verir. */
    @Synchronized
    fun feed(data: ByteArray, len: Int) {
        val c = codec ?: return
        try {
            val idx = c.dequeueInputBuffer(20_000)
            if (idx < 0) {
                dropped++
                if (dropped % 30 == 1) log("Kare atlandı (toplam $dropped)")
                return
            }
            val buf = c.getInputBuffer(idx) ?: return
            buf.clear()
            if (len > buf.capacity()) {
                log("Kare çok büyük: $len")
                c.queueInputBuffer(idx, 0, 0, ptsUs, 0)
                return
            }
            buf.put(data, 0, len)
            ptsUs += 16_667L
            c.queueInputBuffer(idx, 0, len, ptsUs, 0)
        } catch (e: Exception) {
            log("Çözücü hatası: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    @Synchronized
    fun release() {
        releaseCodec()
        currentSps = null
        currentPps = null
    }

    private fun releaseCodec() {
        val c = codec ?: return
        codec = null
        try {
            c.stop()
        } catch (e: Exception) {
            // zaten durmus olabilir
        }
        try {
            c.release()
        } catch (e: Exception) {
            // onemsiz
        }
    }
}
