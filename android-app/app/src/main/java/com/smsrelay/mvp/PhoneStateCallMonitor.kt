package com.smsrelay.mvp

import android.Manifest
import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import java.util.UUID

object PhoneStateCallMonitor {
    private var telephonyManager: TelephonyManager? = null
    private var callback: TelephonyCallback? = null
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callStateCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallStateChange(state, null)
                }
            }
            callback = callStateCallback
            manager.registerTelephonyCallback(context.mainExecutor, callStateCallback)
        } else {
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
    }

    @Synchronized
    fun stop() {
        val manager = telephonyManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val currentCallback = callback
            if (currentCallback != null) {
                manager.unregisterTelephonyCallback(currentCallback)
            }
            callback = null
        } else {
            @Suppress("DEPRECATION")
            val currentListener = listener
            if (currentListener != null) {
                @Suppress("DEPRECATION")
                manager.listen(currentListener, PhoneStateListener.LISTEN_NONE)
            }
            listener = null
        }
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

        val number = incomingNumber?.trim().orEmpty().ifBlank { "Unknown caller" }
        RelayWebSocketClient.enqueueIncomingCall(
            RelayCallEvent(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                from = number,
                name = null
            )
        )
    }

    private fun hasPhoneStatePermission(context: Context): Boolean {
        return PermissionHelper.hasPhoneStatePermission(context)
    }
}
