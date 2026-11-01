package com.example.tvalici

import android.util.Log

/**
 * ALAC (Apple Lossless) decoder, via JNI. The AirPlay audio stream always uses the same settings:
 * 352 samples/frame, 16-bit, 2 channels, 44100 Hz. The native code is under src/main/cpp/alac
 * (Apple ALAC, Apple Public Source License 2.0).
 */
class AlacDecoder private constructor(private var handle: Long) {
    /** Decodes one ALAC frame; writes 16-bit stereo PCM into [out]. Returns the byte count, or -1 on error. */
    @Synchronized
    fun decode(frame: ByteArray, len: Int, out: ByteArray): Int {
        val h = handle
        if (h == 0L) return -1
        return AlacNative.nativeDecode(h, frame, len, out)
    }

    @Synchronized
    fun release() {
        val h = handle
        handle = 0L
        if (h != 0L) AlacNative.nativeDestroy(h)
    }

    companion object {
        /** Maximum PCM bytes of one frame: 352 samples x 2 channels x 2 bytes. */
        const val MAX_PCM_BYTES = 352 * 2 * 2

        private var loaded = false

        @Synchronized
        private fun load(): Boolean {
            if (!loaded) {
                try {
                    System.loadLibrary("alac_jni")
                    loaded = true
                } catch (e: UnsatisfiedLinkError) {
                    Log.e("AlacDecoder", "could not load alac_jni: ${e.message}")
                }
            }
            return loaded
        }

        fun create(): AlacDecoder? {
            if (!load()) return null
            val h = AlacNative.nativeCreate()
            return if (h != 0L) AlacDecoder(h) else null
        }
    }
}

/** JNI entry points (same pattern as Playfair: object + @JvmStatic). */
object AlacNative {
    @JvmStatic
    external fun nativeCreate(): Long

    @JvmStatic
    external fun nativeDecode(handle: Long, frame: ByteArray, len: Int, out: ByteArray): Int

    @JvmStatic
    external fun nativeDestroy(handle: Long)
}
