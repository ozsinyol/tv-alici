package com.example.tvalici

/**
 * Ses/goruntu kaydirma ayari (milisaniye). Pozitif: goruntu gecikir (ses goruntuden GEC geliyorsa),
 * negatif: ses gecikir (ses goruntuden ERKEN geliyorsa). TV'de yansitma sirasinda SAG/SOL tuslariyla
 * 20 ms'lik adimlarla degistirilir ve saklanir.
 */
object SyncSettings {
    const val MAX_MS = 500
    const val STEP_MS = 20

    /**
     * Goruntu tamponu (ag titremesini yumusatir). jitterBufferMs: AUTO (-1) = otomatik, 0 = kapali, >0 = elle (ms).
     * Elle ayar YUKARI/ASAGI ile 20 ms'lik adimlarla, OK tusu otomatige doner.
     */
    const val AUTO = -1
    const val MAX_JITTER_MS = 200
    const val AUTO_MIN_MS = 40
    const val AUTO_MAX_MS = 160
    const val AUTO_START_MS = 80

    @Volatile
    var offsetMs = 0

    /** Yapilandirilan tampon: AUTO, 0 (kapali) ya da elle ms. */
    @Volatile
    var jitterBufferMs = AUTO

    /** Otomatik kipte alicinin olctugu titremeye gore ayarladigi guncel tampon (ms). */
    @Volatile
    var autoJitterMs = AUTO_START_MS

    /** Gercekte uygulanan tampon (ms). */
    val effectiveJitterMs: Int get() = if (jitterBufferMs < 0) autoJitterMs else jitterBufferMs

    /** Yansitma akarken true. Tampon goruntuyu geciktirdigi icin ses de ayni miktar geciktirilir (senkron bozulmasin). */
    @Volatile
    var mirrorLive = false

    val activeJitterMs: Int get() = if (mirrorLive) effectiveJitterMs else 0

    val videoDelayMs: Int get() = maxOf(offsetMs, 0)
    val audioDelayMs: Int get() = maxOf(-offsetMs, 0) + activeJitterMs
}
