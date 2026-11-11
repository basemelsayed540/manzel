package com.manzel.app

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Foreground Service: يبقي التحميل شغالاً حتى لو أُغلقت الشاشة أو خرج المستخدم من التطبيق.
 * يبدأ مع أول تحميل ويتوقف لما تنتهي كل التحميلات (انظر Engine.onStart/onEnd).
 */
class DownloadService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = progressNotification(this, "جاري التحميل…", 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID_PROGRESS, n)
        }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "manzel:download").apply {
                acquire(2 * 60 * 60 * 1000L)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val ID_PROGRESS = 4101
        private const val CH_PROGRESS = "manzel_progress"
        private const val CH_DONE = "manzel_done"
        private var doneId = 5000
        private var lastUpdate = 0L

        fun createChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH_PROGRESS, "التحميل الجاري", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_DONE, "اكتمال التحميل", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        private fun openAppIntent(ctx: Context): PendingIntent? {
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
            return PendingIntent.getActivity(
                ctx, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        fun progressNotification(ctx: Context, text: String, percent: Int): Notification {
            return NotificationCompat.Builder(ctx, CH_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("منزّل")
                .setContentText(text)
                .setProgress(100, percent, percent <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent(ctx))
                .build()
        }

        fun updateProgress(ctx: Context, text: String, percent: Int) {
            val now = System.currentTimeMillis()
            if (now - lastUpdate < 800) return
            lastUpdate = now
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(ID_PROGRESS, progressNotification(ctx, "$text  $percent%", percent))
        }

        fun notifyDone(ctx: Context, name: String, ok: Boolean) {
            createChannels(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val n = NotificationCompat.Builder(ctx, CH_DONE)
                .setSmallIcon(
                    if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error
                )
                .setContentTitle(if (ok) "تم التحميل" else "فشل التحميل")
                .setContentText(name)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(ctx))
                .build()
            nm.notify(doneId++, n)
        }
    }
}
