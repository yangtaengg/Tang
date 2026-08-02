package com.smsrelay.mvp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class RelayForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "relay_connection"
        private const val CHANNEL_NAME = "Relay connection"
        private const val NOTIFICATION_ID = 2011

        fun start(context: Context) {
            val appContext = context.applicationContext
            if (!PairingStore(appContext).isPairedAndValidNow()) {
                return
            }
            val intent = Intent(appContext, RelayForegroundService::class.java)
            appContext.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            val serviceIntent = Intent(appContext, RelayForegroundService::class.java)
            appContext.stopService(serviceIntent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!PairingStore(this).isPairedAndValidNow()) {
            stopSelf()
            return START_NOT_STICKY
        }
        RelayWebSocketClient.initialize(this)
        RelayWebSocketClient.connectIfNeeded()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannelIfNeeded() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.fg_service_title))
            .setContentText(getString(R.string.fg_service_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
