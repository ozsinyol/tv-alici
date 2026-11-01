package com.example.tvalici

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps the last lines in memory; shown on the /log page (not on the TV screen). */
object LogBuffer {
    private const val MAX_LINES = 300
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(msg: String) {
        lines.addLast("${fmt.format(Date())} $msg")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}
