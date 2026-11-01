package com.example.tvalici

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/** Decodes H.264 frames with MediaCodec and draws them straight onto the Surface. */
class MirrorDecoder(
    private val surfaceProvider: () -> Surface?,
    private val onSize: (Int, Int) -> Unit,
    private val log: (String) -> Unit
) {
    @Volatile
    private var codec: MediaCodec? = null
    private var currentSps: ByteArray? = null
    private var currentPps: ByteArray? = null
    private var ptsUs = 0L
    private var dropped = 0

    // Measurement: the moment a frame enters the decoder is stored (in pts order), and the delay is computed at the output.
    private val inputNs = LongArray(256)

    private val startCode = byteArrayOf(0, 0, 0, 1)

    // Audio/video offset: frames are held for SyncSettings.videoDelayMs from the moment they arrive.
    // targetNs: the moment the frame should be drawn when the buffer is on (0: no buffer, arrivedNs is used).
    private class Frame(val data: ByteArray, val len: Int, val arrivedNs: Long, val targetNs: Long) {
        val baseNs: Long get() = if (targetNs != 0L && targetNs - arrivedNs <= 500_000_000L) targetNs else arrivedNs
    }

    private val queue = java.util.concurrent.LinkedBlockingQueue<Frame>()

    @Volatile
    private var released = false

    // After a backlog, P frames are dropped until the next key frame (IDR) (a short freeze instead of a corrupt picture).
    @Volatile
    private var waitKey = false

    // Until the decoder outputs its first frame (~0.6 s at startup) the queue naturally builds up; the protection does not kick in.
    @Volatile
    private var outputStarted = false
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
                    val remaining = f.baseNs + SyncSettings.videoDelayMs * 1_000_000L - System.nanoTime()
                    if (remaining <= 0) break
                    java.util.concurrent.locks.LockSupport.parkNanos(minOf(remaining, 10_000_000L))
                }
                if (!released) feedNow(f.data, f.len, f.arrivedNs)
            }
        }
    }

    /** Re-creates the decoder if SPS/PPS changed (or arrived for the first time). */
    @Synchronized
    fun configure(sps: ByteArray, pps: ByteArray, width: Int, height: Int) {
        if (codec != null &&
            currentSps?.contentEquals(sps) == true &&
            currentPps?.contentEquals(pps) == true
        ) return

        releaseCodec()
        val surface = surfaceProvider()
        if (surface == null || !surface.isValid) {
            log("Video surface is not ready")
            return
        }
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(startCode + sps))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(startCode + pps))
            format.setInteger("low-latency", 1) // ignored on older versions
            format.setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
            // Run the decoder at full speed (the real-time hint); it should not stay in a low-power/low-speed mode.
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
            outputStarted = false
            log("Decoder ready ${width}x$height")
            startDrain(c)
        } catch (e: Exception) {
            log("Could not set up the decoder: ${e.javaClass.simpleName} ${e.message}")
            releaseCodec()
        }
    }

    private fun startDrain(c: MediaCodec) {
        thread(name = "mirror-drain", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            val info = MediaCodec.BufferInfo()
            // If the decoder falls behind and two frames are ready at once, the older one is not drawn, only the newest:
            // the picture catches up instead of replaying the remaining frames one by one to keep the delay.
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
                            outputStarted = true
                            val arrived = inputNs[((info.presentationTimeUs / 16_667L) % 256).toInt()]
                            if (arrived != 0L) {
                                val lat = System.nanoTime() - arrived
                                latSumNs += lat
                                if (lat > latMaxNs) latMaxNs = lat
                            }
                            outCount++
                            if (outCount % 300 == 0) {
                                log(
                                    "Decoder latency: avg ${latSumNs / 300 / 1_000_000} ms, " +
                                        "max ${latMaxNs / 1_000_000} ms | skipped at output: $outSkipped, " +
                                        "dropped at input (total): $dropped"
                                )
                                latSumNs = 0L; latMaxNs = 0L; outSkipped = 0
                            }
                        }
                        i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> reportSize(c.outputFormat)
                        i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (pending >= 0) {
                            c.releaseOutputBuffer(pending, true) // draw immediately
                            pending = -1
                        }
                    }
                } catch (e: Exception) {
                    break // codec was closed
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
            log("Could not read the output size: ${e.message}")
        }
    }

    /**
     * Passes a frame in Annex-B format to the decoder in order (according to the delay setting).
     * [idr]: whether the frame is a key frame. If a backlog builds up, old frames are dropped so the delay does not grow:
     *  - when a key frame arrives (with no offset delay), the waiting frames before it are unnecessary and references stay intact;
     *  - if the backlog exceeds the limit, the queue is emptied and P frames are not waited for until the next key frame.
     */
    fun feed(data: ByteArray, len: Int, idr: Boolean, targetNs: Long) {
        if (released) return
        if (waitKey) {
            if (!idr) return
            waitKey = false
        }
        val backlog = if (outputStarted) queue.size else 0
        val cap = MAX_BACKLOG + (SyncSettings.videoDelayMs + SyncSettings.effectiveJitterMs) / 8
        // "Behind": the oldest frame in the queue is more than 50 ms past its draw time (frames waiting inside the buffer do not count as behind).
        val behind = backlog >= 2 && queue.peek()?.let {
            System.nanoTime() - it.baseNs - SyncSettings.videoDelayMs * 1_000_000L > 50_000_000L
        } == true
        if (idr) {
            if ((SyncSettings.videoDelayMs == 0 && behind) || backlog > cap) {
                queue.clear()
                skipped += backlog
                log("Backlog cleared: $backlog frames skipped (total $skipped)")
            }
        } else if (backlog > cap) {
            queue.clear()
            skipped += backlog
            waitKey = true
            log("Decoder could not keep up: $backlog frames dropped, waiting for a key frame")
            return
        }
        queue.add(Frame(data, len, System.nanoTime(), targetNs))
    }

    /**
     * Gives the frame to the decoder. At startup the decoder may hold the first frames and not provide an input buffer
     * (it waits for frame reordering, ~0.6 s): dropping the frame would break references, so it waits at most 1 s.
     * The lock is released between attempts so that release()/configure() are not blocked.
     */
    private fun feedNow(data: ByteArray, len: Int, arrivedNs: Long) {
        val deadline = System.nanoTime() + 1_000_000_000L
        while (!released) {
            if (tryFeed(data, len, arrivedNs)) return
            if (System.nanoTime() > deadline) {
                dropped++
                MirrorStats.dropped = dropped.toLong()
                log("Frame dropped (total $dropped)")
                return
            }
        }
    }

    /** true: the frame was handled (or must be ignored); false: no input buffer yet, try again. */
    @Synchronized
    private fun tryFeed(data: ByteArray, len: Int, arrivedNs: Long): Boolean {
        val c = codec ?: return true
        try {
            val idx = c.dequeueInputBuffer(20_000)
            if (idx < 0) return false
            val buf = c.getInputBuffer(idx) ?: return true
            buf.clear()
            if (len > buf.capacity()) {
                log("Frame too large: $len")
                c.queueInputBuffer(idx, 0, 0, ptsUs, 0)
                return true
            }
            buf.put(data, 0, len)
            ptsUs += 16_667L
            inputNs[((ptsUs / 16_667L) % 256).toInt()] = arrivedNs
            c.queueInputBuffer(idx, 0, len, ptsUs, 0)
        } catch (e: Exception) {
            log("Decoder error: ${e.javaClass.simpleName} ${e.message}")
        }
        return true
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
            // may have already stopped
        }
        try {
            c.release()
        } catch (e: Exception) {
            // unimportant
        }
    }

    /** Adds vendor-specific low-latency keys depending on the decoder's name (an unmatched brand is left alone). */
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
        log("Decoder: $codecName")
    }

    private companion object {
        /** If more frames than this pile up (~0.5 s at 60 fps), the queue is emptied. */
        const val MAX_BACKLOG = 30
    }
}
