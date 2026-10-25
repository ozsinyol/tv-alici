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

    // Olcum: kare cozucuye girdigi an (pts sirasina gore) saklanir, cikista gecikme hesaplanir.
    private val inputNs = LongArray(256)

    private val startCode = byteArrayOf(0, 0, 0, 1)

    // Ses/goruntu ayari: kareler geldikleri andan itibaren SyncSettings.videoDelayMs kadar bekletilir.
    private class Frame(val data: ByteArray, val len: Int, val arrivedNs: Long)

    private val queue = java.util.concurrent.LinkedBlockingQueue<Frame>()

    @Volatile
    private var released = false

    // Birikme sonrasi bir sonraki anahtar kareye (IDR) kadar P kareler atilir (bozuk goruntu yerine kisa donma).
    @Volatile
    private var waitKey = false
    private var skipped = 0

    init {
        thread(name = "mirror-sched", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            while (!released) {
                val f = (try {
                    queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                }) ?: continue
                while (!released) {
                    val remaining = f.arrivedNs + SyncSettings.videoDelayMs * 1_000_000L - System.nanoTime()
                    if (remaining <= 0) break
                    java.util.concurrent.locks.LockSupport.parkNanos(minOf(remaining, 10_000_000L))
                }
                if (!released) feedNow(f.data, f.len, f.arrivedNs)
            }
        }
    }

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
            // Cozucuyu tam hizda calistir (gercek zamanli onerisi); dusuk guc/hiz modunda kalmasin.
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            applyVendorLowLatency(format, c.name)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            currentSps = sps
            currentPps = pps
            ptsUs = 0L
            waitKey = false
            log("Çözücü hazır ${width}x$height")
            startDrain(c)
        } catch (e: Exception) {
            log("Çözücü kurulamadı: ${e.javaClass.simpleName} ${e.message}")
            releaseCodec()
        }
    }

    private fun startDrain(c: MediaCodec) {
        thread(name = "mirror-drain", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            val info = MediaCodec.BufferInfo()
            // Cozucu geride kalip iki kare birden hazirsa eskisi cizilmez, yalnizca en yenisi cizilir:
            // goruntu geri kalan kareleri tek tek oynatarak gecikmeyi korumak yerine yetisir.
            var pending = -1
            var latSumNs = 0L
            var latMaxNs = 0L
            var outCount = 0
            var outSkipped = 0
            while (codec === c) {
                try {
                    val i = c.dequeueOutputBuffer(info, if (pending >= 0) 0L else 10_000L)
                    when {
                        i >= 0 -> {
                            if (pending >= 0) {
                                c.releaseOutputBuffer(pending, false)
                                outSkipped++
                            }
                            pending = i
                            val arrived = inputNs[((info.presentationTimeUs / 16_667L) % 256).toInt()]
                            if (arrived != 0L) {
                                val lat = System.nanoTime() - arrived
                                latSumNs += lat
                                if (lat > latMaxNs) latMaxNs = lat
                            }
                            outCount++
                            if (outCount % 300 == 0) {
                                log(
                                    "Çözücü gecikmesi: ort ${latSumNs / 300 / 1_000_000} ms, " +
                                        "en çok ${latMaxNs / 1_000_000} ms | çıkışta atlanan: $outSkipped, " +
                                        "girişte atlanan (toplam): $dropped"
                                )
                                latSumNs = 0L; latMaxNs = 0L; outSkipped = 0
                            }
                        }
                        i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> reportSize(c.outputFormat)
                        i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (pending >= 0) {
                            c.releaseOutputBuffer(pending, true) // hemen ciz
                            pending = -1
                        }
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

    /**
     * Annex-B bicimindeki bir kareyi sira ile (gecikme ayarina gore) cozucuye iletir.
     * [idr]: kare anahtar kare mi. Birikme olursa gecikme buyumesin diye eski kareler atilir:
     *  - anahtar kare geldiginde (ayar gecikmesi yokken) onundeki bekleyen kareler gereksizdir, referans bozulmaz;
     *  - birikme sinirini asarsa kuyruk bosaltilir ve sonraki anahtar kareye kadar P kareler beklenmez.
     */
    fun feed(data: ByteArray, len: Int, idr: Boolean) {
        if (released) return
        if (waitKey) {
            if (!idr) return
            waitKey = false
        }
        val backlog = queue.size
        val cap = MAX_BACKLOG + SyncSettings.videoDelayMs / 10
        if (idr) {
            if ((SyncSettings.videoDelayMs == 0 && backlog >= 2) || backlog > cap) {
                queue.clear()
                skipped += backlog
                log("Birikme giderildi: $backlog kare atlandı (toplam $skipped)")
            }
        } else if (backlog > cap) {
            queue.clear()
            skipped += backlog
            waitKey = true
            log("Çözücü yetişemedi: $backlog kare atıldı, anahtar kare bekleniyor")
            return
        }
        queue.add(Frame(data, len, System.nanoTime()))
    }

    @Synchronized
    private fun feedNow(data: ByteArray, len: Int, arrivedNs: Long) {
        val c = codec ?: return
        try {
            val idx = c.dequeueInputBuffer(40_000)
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
            inputNs[((ptsUs / 16_667L) % 256).toInt()] = arrivedNs
            c.queueInputBuffer(idx, 0, len, ptsUs, 0)
        } catch (e: Exception) {
            log("Çözücü hatası: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    @Synchronized
    fun release() {
        released = true
        queue.clear()
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

    /** Cozucunun adina gore uretici ozel dusuk gecikme anahtarlarini ekler (eslesmeyen marka dokunulmaz). */
    private fun applyVendorLowLatency(format: MediaFormat, codecName: String) {
        val n = codecName.lowercase()
        when {
            n.contains("qcom") || n.contains("qti") ->
                format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            n.contains("exynos") ->
                format.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
            n.contains("amlogic") ->
                format.setInteger("vendor.low-latency.enable", 1)
            n.contains("hisi") -> {
                format.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1)
                format.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy", -1)
            }
        }
        log("Çözücü: $codecName")
    }

    private companion object {
        /** Bu kadar kareden fazla birikirse (60 fps'te ~0,5 sn) kuyruk bosaltilir. */
        const val MAX_BACKLOG = 30
    }
}
