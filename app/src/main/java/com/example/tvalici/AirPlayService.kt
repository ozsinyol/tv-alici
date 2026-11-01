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
 * Keeps the process alive as a "foreground service" while the app is open: since the AirPlay server
 * and the mDNS advertising run inside MainActivity, this prevents Android from killing the process
 * when you leave the app or the screen turns off (which would cut the music and make the device
 * disappear from the AirPlay list). It is started when MainActivity starts and stopped when it
 * closes. The notification is a small indicator that the system requires; tapping it returns to the app.
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
            // If it cannot be moved to the foreground because of a permission/restriction, the app keeps working anyway.
            stopSelf()
        }
        // If the process dies, the Activity is gone too; an empty service should not restart.
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL = "airplay"
        const val NOTIF_ID = 1
    }
}
