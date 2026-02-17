package com.smsrelay.mvp

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object NotificationAccessUtil {
    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val component = ComponentName(context, SmsNotificationListenerService::class.java)
        return enabled.contains(component.flattenToString())
    }
}
