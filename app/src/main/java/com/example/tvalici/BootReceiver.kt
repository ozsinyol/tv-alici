package com.example.tvalici

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** TV acilinca uygulamayi baslatir (yalnizca "acilista otomatik baslat" ayari aciksa). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val on = context.getSharedPreferences("airplay", Context.MODE_PRIVATE).getBoolean("autostart", false)
        if (!on) return
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // Android 10+ arka plandan ekran acmayi kisitlayabilir.
            Log.w("TvAlici", "Acilista baslatilamadi: ${e.message}")
        }
    }
}
