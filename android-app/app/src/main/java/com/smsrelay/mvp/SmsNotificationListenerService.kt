package com.smsrelay.mvp

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class SmsNotificationListenerService : NotificationListenerService() {
    override fun onCreate() {
        super.onCreate()
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

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        QuickReplyStore.updateFromNotification(sbn)
        val active = activeNotifications ?: emptyArray()
        QuickReplyStore.refreshFromActiveNotifications(active)

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
        PhoneStateCallMonitor.stop()
    }
}
