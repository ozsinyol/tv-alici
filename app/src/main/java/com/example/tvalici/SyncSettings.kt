package com.example.tvalici

/**
 * Ses/goruntu kaydirma ayari (milisaniye). Pozitif: goruntu gecikir (ses goruntuden GEC geliyorsa),
 * negatif: ses gecikir (ses goruntuden ERKEN geliyorsa). TV'de yansitma sirasinda SAG/SOL tuslariyla
 * 20 ms'lik adimlarla degistirilir ve saklanir.
 */
object SyncSettings {
    const val MAX_MS = 500
    const val STEP_MS = 20

    @Volatile
    var offsetMs = 0

    val videoDelayMs: Int get() = maxOf(offsetMs, 0)
    val audioDelayMs: Int get() = maxOf(-offsetMs, 0)
}
