package com.smsrelay.mvp

import android.content.Context
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import java.util.UUID

object PhoneStateCallMonitor {
    private var telephonyManager: TelephonyManager? = null
    private var listener: PhoneStateListener? = null
    private var lastState: Int = TelephonyManager.CALL_STATE_IDLE

    @Synchronized
    fun start(context: Context) {
        if (!hasPhoneStatePermission(context)) {
            stop()
            return
        }
        val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        if (telephonyManager != null) {
            return
        }
        telephonyManager = manager

        @Suppress("DEPRECATION")
        val callStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                handleCallStateChange(state, incomingNumber)
            }
        }
        listener = callStateListener
        @Suppress("DEPRECATION")
        manager.listen(callStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    @Synchronized
    fun stop() {
        val manager = telephonyManager ?: return
        @Suppress("DEPRECATION")
        val currentListener = listener
        if (currentListener != null) {
            @Suppress("DEPRECATION")
            manager.listen(currentListener, PhoneStateListener.LISTEN_NONE)
        }
        listener = null
        telephonyManager = null
        lastState = TelephonyManager.CALL_STATE_IDLE
    }

    private fun handleCallStateChange(state: Int, incomingNumber: String?) {
        if (state == lastState) {
            return
        }
        lastState = state
        if (state != TelephonyManager.CALL_STATE_RINGING) {
            return
        }

        val number = incomingNumber?.trim().orEmpty()
        if (number.isBlank()) {
            return
        }
        val event = RelayCallEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            from = number,
            name = null
        )
        if (NotificationDeduper.isDuplicate(event)) {
            return
        }
        RelayWebSocketClient.enqueueIncomingCall(event)
    }

    private fun hasPhoneStatePermission(context: Context): Boolean {
        return PermissionHelper.hasPhoneStatePermission(context)
    }
}
