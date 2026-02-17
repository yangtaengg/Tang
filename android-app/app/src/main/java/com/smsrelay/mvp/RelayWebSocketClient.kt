package com.smsrelay.mvp

import android.content.Context
import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min

object RelayWebSocketClient {
    private const val TAG = "RelayWebSocketClient"
    private const val CLOSE_NORMAL = 1000

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var socket: WebSocket? = null
    @Volatile
    private var authenticated = false

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var attempt = 0
    private val queue = ArrayDeque<RelaySmsEvent>()

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Synchronized
    fun initialize(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    @Synchronized
    fun connectIfNeeded() {
        val context = appContext ?: return
        val payload = PairingStore(context).load() ?: return
        if (socket != null) {
            return
        }

        val request = Request.Builder()
            .url(payload.url)
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
                authenticated = false
                Log.i(TAG, "WebSocket opened: ${payload.url}")
                val auth = JSONObject()
                    .put("type", "auth")
                    .put("token", payload.pairingToken)
                    .put("device", Build.MODEL)
                    .put("appVersion", "0.1.0")
                webSocket.send(auth.toString())
                Log.i(TAG, "Auth message sent")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val payload = runCatching { JSONObject(text) }.getOrNull() ?: return
                val type = payload.optString("type")
                when (type) {
                    "auth.ok" -> {
                        authenticated = true
                        Log.i(TAG, "Auth acknowledged by server")
                        flushQueue()
                    }
                    "auth.fail" -> {
                        authenticated = false
                        Log.w(TAG, "Auth rejected by server")
                        closeAndReset()
                    }
                    "sms.reply" -> {
                        handleReplyCommand(payload)
                    }
                    "pong" -> Unit
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Unit
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code / $reason")
                authenticated = false
                socket = null
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: ${t.message}")
                authenticated = false
                socket = null
                scheduleReconnect()
            }
        })
    }

    @Synchronized
    fun clearConnection() {
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        closeAndReset()
        queue.clear()
        QuickReplyStore.clear()
    }

    @Synchronized
    fun enqueueNotification(event: RelaySmsEvent) {
        if (queue.size >= 100) {
            queue.removeFirst()
        }
        queue.addLast(event)
        connectIfNeeded()
        if (authenticated) {
            flushQueue()
        }
    }

    @Synchronized
    private fun flushQueue() {
        val webSocket = socket ?: return
        if (!authenticated) {
            return
        }
        while (queue.isNotEmpty()) {
            val event = queue.removeFirst()
            val payload = JSONObject()
                .put("type", "sms.notification")
                .put("id", event.id)
                .put("timestamp", event.timestamp)
                .put("from", event.from)
                .put("body", event.body)
                .put("sourcePackage", event.sourcePackage)
                .put("conversationKey", event.conversationKey)
            event.replyKey?.let { payload.put("replyKey", it) }
            webSocket.send(payload.toString())
        }
    }

    @Synchronized
    private fun handleReplyCommand(payload: JSONObject) {
        val replyKey = payload.optString("replyKey")
        val sourcePackage = payload.optString("sourcePackage")
        val conversationKey = payload.optString("conversationKey")
        val body = payload.optString("body")
        if (body.isBlank()) {
            sendReplyResult(replyKey, success = false, reason = "invalid payload")
            return
        }

        val context = appContext
        if (context == null) {
            sendReplyResult(replyKey, success = false, reason = "context unavailable")
            return
        }

        val result = if (replyKey.isNotBlank()) {
            QuickReplyStore.sendReply(context, replyKey, body)
        } else {
            if (sourcePackage.isBlank()) {
                Result.failure(IllegalStateException("missing source package"))
            } else {
                QuickReplyStore.sendReplyByConversation(context, sourcePackage, conversationKey, body)
            }
        }
        if (result.isSuccess) {
            sendReplyResult(replyKey, success = true, reason = null)
            Log.i(TAG, "Quick reply sent for key=$replyKey")
        } else {
            val reason = result.exceptionOrNull()?.message ?: "quick reply failed"
            sendReplyResult(replyKey, success = false, reason = reason)
            Log.w(TAG, "Quick reply failed for key=$replyKey: $reason")
        }
    }

    @Synchronized
    private fun sendReplyResult(replyKey: String, success: Boolean, reason: String?) {
        val webSocket = socket ?: return
        if (!authenticated) {
            return
        }
        val payload = JSONObject()
            .put("type", "sms.reply.result")
            .put("replyKey", replyKey)
            .put("success", success)
        reason?.let { payload.put("reason", it) }
        webSocket.send(payload.toString())
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (reconnectFuture?.isDone == false) {
            return
        }
        val context = appContext ?: return
        if (!PairingStore(context).isPairedAndValidNow()) {
            return
        }

        val base = 1000L * (1L shl attempt.coerceAtMost(6))
        val delayMs = min(30_000L, base)
        attempt += 1

        reconnectFuture = scheduler.schedule(
            { connectIfNeeded() },
            delayMs,
            TimeUnit.MILLISECONDS
        )
    }

    @Synchronized
    private fun closeAndReset() {
        socket?.close(CLOSE_NORMAL, "reset")
        socket = null
        authenticated = false
    }
}
