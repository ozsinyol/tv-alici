package com.example.tvalici

/**
 * Audio/video offset setting (milliseconds). Positive: the picture is delayed (if the audio arrives LATE relative to the picture),
 * negative: the audio is delayed (if the audio arrives EARLY relative to the picture). While mirroring on the TV it is changed
 * in 20 ms steps with the RIGHT/LEFT keys and is saved.
 */
object SyncSettings {
    const val MAX_MS = 500
    const val STEP_MS = 20

    /**
     * Video buffer (smooths network jitter). jitterBufferMs: AUTO (-1) = automatic, 0 = off, >0 = manual (ms).
     * Manual adjustment is in 20 ms steps with UP/DOWN, and the OK key returns to automatic.
     */
    const val AUTO = -1
    const val MAX_JITTER_MS = 200
    const val AUTO_MIN_MS = 40
    const val AUTO_MAX_MS = 160
    const val AUTO_START_MS = 80

    @Volatile
    var offsetMs = 0

    /** The configured buffer: AUTO, 0 (off) or manual ms. */
    @Volatile
    var jitterBufferMs = AUTO

    /** In automatic mode, the current buffer the receiver sets according to the jitter it measures (ms). */
    @Volatile
    var autoJitterMs = AUTO_START_MS

    /** The buffer actually applied (ms). */
    val effectiveJitterMs: Int get() = if (jitterBufferMs < 0) autoJitterMs else jitterBufferMs

    /** True while mirroring is live. Because the buffer delays the picture, the audio is delayed by the same amount (so sync is not broken). */
    @Volatile
    var mirrorLive = false

    val activeJitterMs: Int get() = if (mirrorLive) effectiveJitterMs else 0

    val videoDelayMs: Int get() = maxOf(offsetMs, 0)
    val audioDelayMs: Int get() = maxOf(-offsetMs, 0) + activeJitterMs
}

/** Mirroring statistics (for the "Statistics" panel in the TV menu). */
object MirrorStats {
    @Volatile
    var frames = 0L

    @Volatile
    var dropped = 0L

    fun reset() {
        frames = 0L
        dropped = 0L
    }
}
