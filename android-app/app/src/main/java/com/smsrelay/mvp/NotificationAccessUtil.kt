package com.smsrelay.mvp

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

object NotificationAccessUtil {
    fun isEnabled(context: Context): Boolean {
        val packageEnabled = NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName)
        if (packageEnabled) {
            return true
        }
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val target = ComponentName(context, SmsNotificationListenerService::class.java)
        return enabled.split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it == target }
    }
}
