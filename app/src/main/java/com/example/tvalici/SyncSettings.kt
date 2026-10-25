package com.example.tvalici

/**
 * Ses/goruntu kaydirma ayari (milisaniye). Pozitif: goruntu gecikir (ses goruntuden GEC geliyorsa),
 * negatif: ses gecikir (ses goruntuden ERKEN geliyorsa). TV'de yansitma sirasinda SAG/SOL tuslariyla
 * 20 ms'lik adimlarla degistirilir ve saklanir.
 */
object SyncSettings {
    const val MAX_MS = 500
    const val STEP_MS = 20

    /** Goruntu tamponu (ag titremesini yumusatir): YUKARI/ASAGI tuslariyla 20 ms'lik adimlarla degisir. */
    const val MAX_JITTER_MS = 200
    const val DEFAULT_JITTER_MS = 60

    @Volatile
    var offsetMs = 0

    /** Yapilandirilan tampon suresi. 0: tampon kapali, kareler geldikleri anda cizilir. */
    @Volatile
    var jitterBufferMs = DEFAULT_JITTER_MS

    /** Yansitma akarken true. Tampon goruntuyu geciktirdigi icin ses de ayni miktar geciktirilir (senkron bozulmasin). */
    @Volatile
    var mirrorLive = false

    val activeJitterMs: Int get() = if (mirrorLive) jitterBufferMs else 0

    val videoDelayMs: Int get() = maxOf(offsetMs, 0)
    val audioDelayMs: Int get() = maxOf(-offsetMs, 0) + activeJitterMs
}
