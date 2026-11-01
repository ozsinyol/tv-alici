package com.example.tvalici

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Starts the app when the TV turns on (only if the "start at boot" setting is on). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val on = context.getSharedPreferences("airplay", Context.MODE_PRIVATE).getBoolean("autostart", false)
        if (!on) return
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // Android 10+ may restrict launching a screen from the background.
            Log.w("TvAlici", "Could not start at boot: ${e.message}")
        }
    }
}
