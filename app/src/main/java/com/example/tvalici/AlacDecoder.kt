package com.example.tvalici

import android.util.Log

/**
 * ALAC (Apple Lossless) cozucu, JNI ile. AirPlay ses akisi sabit ayarla gelir:
 * 352 ornek/kare, 16 bit, 2 kanal, 44100 Hz. Native kod src/main/cpp/alac altindadir
 * (Apple ALAC, Apache License 2.0).
 */
class AlacDecoder private constructor(private var handle: Long) {
    /** Bir ALAC karesini cozer; 16 bit stereo PCM'i [out]'a yazar. Donus: bayt sayisi, hata olursa -1. */
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
        /** Bir karenin en fazla PCM bayti: 352 ornek x 2 kanal x 2 bayt. */
        const val MAX_PCM_BYTES = 352 * 2 * 2

        private var loaded = false

        @Synchronized
        private fun load(): Boolean {
            if (!loaded) {
                try {
                    System.loadLibrary("alac_jni")
                    loaded = true
                } catch (e: UnsatisfiedLinkError) {
                    Log.e("AlacDecoder", "alac_jni yuklenemedi: ${e.message}")
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

/** JNI giris noktalari (Playfair ile ayni kalip: object + @JvmStatic). */
object AlacNative {
    @JvmStatic
    external fun nativeCreate(): Long

    @JvmStatic
    external fun nativeDecode(handle: Long, frame: ByteArray, len: Int, out: ByteArray): Int

    @JvmStatic
    external fun nativeDestroy(handle: Long)
}
