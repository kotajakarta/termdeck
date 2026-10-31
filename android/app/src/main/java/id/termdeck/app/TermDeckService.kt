package id.termdeck.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class TermDeckService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LOCK_AND_DISCONNECT) {
            MainActivity.lockFromNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        val count = intent?.getIntExtra(EXTRA_TERMINAL_COUNT, 1) ?: 1
        val notification = buildNotification(count)
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sesi SSH Aktif",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menjaga koneksi SSH TermDeck tetap aktif di latar belakang"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(count: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val lockIntent = Intent(this, TermDeckService::class.java).apply {
            action = ACTION_LOCK_AND_DISCONNECT
        }
        val lockPendingIntent = PendingIntent.getService(
            this,
            1,
            lockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val action = Notification.Action.Builder(
            android.R.drawable.ic_lock_lock,
            "Kunci & Putuskan",
            lockPendingIntent
        ).build()

        return builder
            .setContentTitle("TermDeck")
            .setContentText("$count sesi terminal SSH aktif")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentIntent(pendingIntent)
            .addAction(action)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "termdeck_ssh_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_TERMINAL_COUNT = "terminal_count"
        const val ACTION_LOCK_AND_DISCONNECT = "id.termdeck.app.LOCK_AND_DISCONNECT"
    }
}
