package com.example.tvalici

import android.util.Log

/**
 * FairPlay yayin anahtari cozucu (playfair, JNI).
 * Native kod src/main/cpp/playfair altindadir, UxPlay projesinden alinmistir
 * (GNU GPL surum 3, bkz. LICENSE ve playfair/LICENSE.md).
 */
object Playfair {
    private var loaded = false

    @Synchronized
    fun load(): Boolean {
        if (!loaded) {
            try {
                System.loadLibrary("playfair_jni")
                loaded = true
            } catch (e: UnsatisfiedLinkError) {
                Log.e("Playfair", "playfair_jni yuklenemedi: ${e.message}")
            }
        }
        return loaded
    }

    /**
     * @param keymsg /fp-setup 2. asamasinda gelen 164 baytlik mesaj
     * @param ekey SETUP istegindeki 72 baytlik sifreli anahtar
     * @return 16 baytlik cozulmus anahtar, hata olursa null
     */
    @JvmStatic
    external fun decrypt(keymsg: ByteArray, ekey: ByteArray): ByteArray?
}
