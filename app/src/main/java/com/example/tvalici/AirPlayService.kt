package com.example.tvalici

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Uygulama acikken sureci "on plan servisi" olarak tutar: AirPlay sunucusu ve mDNS yayini
 * MainActivity icinde calistigi icin, uygulamadan cikilinca ya da ekran kapaninca Android'in
 * sureci oldurmesini (muzigin kesilmesini, cihazin AirPlay listesinden kaybolmasini) onler.
 * MainActivity acilinca baslatilir, kapaninca durdurulur. Bildirim sistemin zorunlu kildigi
 * kucuk bir gostergedir; dokununca uygulamaya doner.
 */
class AirPlayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, applicationInfo.loadLabel(packageManager), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = builder
            .setContentTitle(applicationInfo.loadLabel(packageManager))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            // Izin/kisit nedeniyle on plana alinamazsa uygulama yine de calismaya devam eder.
            stopSelf()
        }
        // Surec olurse Activity de yok demektir; bos bir servis yeniden baslamasin.
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL = "airplay"
        const val NOTIF_ID = 1
    }
}
