package com.smsrelay.mvp

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.ComponentName

class SmsNotificationListenerService : NotificationListenerService() {
    companion object {
        @Volatile
        private var instance: SmsNotificationListenerService? = null

        fun hangUpIncomingCall(): Result<Unit> {
            val service = instance ?: return Result.failure(IllegalStateException("notification listener unavailable"))
            val active = service.activeNotifications ?: emptyArray()
            return CallActionExecutor.hangUpFrom(active)
        }

    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        RelayWebSocketClient.initialize(this)
        PhoneStateCallMonitor.start(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        RelayWebSocketClient.initialize(this)
        PhoneStateCallMonitor.start(this)
        QuickReplyStore.refreshFromActiveNotifications(activeNotifications ?: emptyArray())
        RelayWebSocketClient.connectIfNeeded()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        requestRebind(ComponentName(this, SmsNotificationListenerService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val isSmsSource = SmsNotificationParser.isSupportedPackage(sbn.packageName)
        val isCallSource = CallNotificationParser.isSupportedPackage(sbn.packageName)
        if (!isSmsSource && !isCallSource) {
            return
        }

        if (isSmsSource) {
            QuickReplyStore.updateFromNotification(sbn)
        }
        val active = activeNotifications ?: emptyArray()
        if (isSmsSource) {
            QuickReplyStore.refreshFromActiveNotifications(active)
        }

        val callEvent = if (isCallSource) CallNotificationParser.parse(sbn) else null
        if (callEvent != null && !NotificationDeduper.isDuplicate(callEvent)) {
            RelayWebSocketClient.enqueueIncomingCall(callEvent)
        }

        if (!isSmsSource) {
            return
        }
        val fallbackReplyKey = QuickReplyStore.fallbackReplyKeyFor(sbn, active)
        val event = SmsNotificationParser.parse(sbn, fallbackReplyKey) ?: return
        if (NotificationDeduper.isDuplicate(event)) {
            return
        }
        RelayWebSocketClient.enqueueNotification(event)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        QuickReplyStore.remove(sbn.key)
        QuickReplyStore.refreshFromActiveNotifications(activeNotifications ?: emptyArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        PhoneStateCallMonitor.stop()
    }
}
