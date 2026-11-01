package com.example.tvalici

import android.util.Log

/**
 * FairPlay stream key decryptor (playfair, JNI).
 * The native code is under src/main/cpp/playfair and was taken from the UxPlay project
 * (GNU GPL version 3, see LICENSE and playfair/LICENSE.md).
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
                Log.e("Playfair", "could not load playfair_jni: ${e.message}")
            }
        }
        return loaded
    }

    /**
     * @param keymsg the 164-byte message that arrives in /fp-setup stage 2
     * @param ekey the 72-byte encrypted key in the SETUP request
     * @return the 16-byte decrypted key, or null on failure
     */
    @JvmStatic
    external fun decrypt(keymsg: ByteArray, ekey: ByteArray): ByteArray?
}
