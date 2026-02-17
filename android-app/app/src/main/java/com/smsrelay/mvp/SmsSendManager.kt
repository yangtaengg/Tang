package com.smsrelay.mvp

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager

object SmsSendManager {
    private const val ACTION_SMS_SENT = "com.smsrelay.mvp.ACTION_SMS_SENT"
    private const val EXTRA_CLIENT_MSG_ID = "client_msg_id"

    private data class PendingSend(
        val expectedParts: Int,
        var completedParts: Int,
        var failureReason: String?,
        val callback: (Boolean, String?) -> Unit
    )

    private val pending = mutableMapOf<String, PendingSend>()
    @Volatile
    private var receiverRegistered = false

    private val sentStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val clientMsgId = intent.getStringExtra(EXTRA_CLIENT_MSG_ID) ?: return
            val reason = if (resultCode == Activity.RESULT_OK) {
                null
            } else {
                mapSendFailure(resultCode)
            }
            onPartFinished(clientMsgId, reason)
        }
    }

    @Synchronized
    fun send(
        context: Context,
        toRaw: String,
        body: String,
        clientMsgId: String,
        callback: (Boolean, String?) -> Unit
    ): Result<Unit> {
        ensureReceiver(context.applicationContext)
        val destination = normalizeDestination(toRaw)
            ?: return Result.failure(IllegalArgumentException("recipient unavailable"))
        val text = body.trim()
        if (text.isEmpty()) {
            return Result.failure(IllegalArgumentException("message body empty"))
        }

        val smsManager = SmsManager.getDefault()
        val parts = smsManager.divideMessage(text)
        if (parts.isEmpty()) {
            return Result.failure(IllegalStateException("message split failed"))
        }

        pending[clientMsgId] = PendingSend(
            expectedParts = parts.size,
            completedParts = 0,
            failureReason = null,
            callback = callback
        )

        return runCatching {
            val sentIntents = parts.mapIndexed { index, _ ->
                val sentIntent = Intent(ACTION_SMS_SENT)
                    .putExtra(EXTRA_CLIENT_MSG_ID, clientMsgId)
                    .putExtra("part_index", index)
                PendingIntent.getBroadcast(
                    context,
                    (clientMsgId.hashCode() and 0x7FFFFFFF) + index,
                    sentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            if (parts.size == 1) {
                smsManager.sendTextMessage(destination, null, parts[0], sentIntents[0], null)
            } else {
                smsManager.sendMultipartTextMessage(
                    destination,
                    null,
                    ArrayList(parts),
                    ArrayList(sentIntents),
                    null
                )
            }
        }.onFailure {
            pending.remove(clientMsgId)
        }
    }

    @Synchronized
    private fun onPartFinished(clientMsgId: String, reason: String?) {
        val state = pending[clientMsgId] ?: return
        state.completedParts += 1
        if (reason != null && state.failureReason == null) {
            state.failureReason = reason
        }
        if (state.completedParts >= state.expectedParts) {
            pending.remove(clientMsgId)
            val success = state.failureReason == null
            state.callback(success, state.failureReason)
        }
    }

    @Synchronized
    private fun ensureReceiver(context: Context) {
        if (receiverRegistered) {
            return
        }
        val filter = IntentFilter(ACTION_SMS_SENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(sentStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(sentStatusReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun normalizeDestination(raw: String): String? {
        val candidate = Regex("\\+?[0-9][0-9()\\-\\s]{6,}").find(raw)?.value ?: raw
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        val builder = StringBuilder(trimmed.length)
        trimmed.forEachIndexed { index, ch ->
            when {
                ch.isDigit() -> builder.append(ch)
                ch == '+' && index == 0 -> builder.append(ch)
            }
        }
        val normalized = builder.toString()
        return if (normalized.count { it.isDigit() } >= 7) normalized else null
    }

    private fun mapSendFailure(code: Int): String {
        return when (code) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic_failure"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "no_service"
            SmsManager.RESULT_ERROR_NULL_PDU -> "null_pdu"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "radio_off"
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "short_code_not_allowed"
            SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "short_code_never_allowed"
            SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED -> "rate_limited"
            else -> "send_failed_$code"
        }
    }
}
