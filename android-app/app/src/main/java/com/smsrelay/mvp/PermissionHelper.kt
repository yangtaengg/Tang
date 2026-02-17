package com.smsrelay.mvp

import android.Manifest
import android.content.Context
import androidx.core.content.ContextCompat

object PermissionHelper {
    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun hasPhoneStatePermission(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.READ_PHONE_STATE)
    }

    fun hasSendSmsPermission(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.SEND_SMS)
    }
}
