package com.meshnet.meshnet_app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Foreground service: keeps mesh engine network activity alive.
 * Required on Android 8+ due to background restrictions.
 */
class MeshService : Service() {

    companion object {
        private const val TAG = "MeshService"
        private const val CHANNEL_ID = "meshnet_foreground"
        private const val NOTIFICATION_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        Log.i(TAG, "MeshService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Service allaqachon join; faqat notify yangilash mumkin
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "MeshService stopped")
    }

    private fun startForegroundCompat() {
        createChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MeshNet")
            .setContentText("Mesh network active — offline communication ready")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MeshNet network status",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "This notification appears when mesh network is connected"
        manager.createNotificationChannel(channel)
    }
}