package com.smsrelay.mvp

import android.animation.ObjectAnimator
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import java.net.Inet4Address
import java.net.URI
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors

class ManualPairActivity : AppCompatActivity() {
    private companion object {
        const val DEFAULT_WS_PORT = 8765
        const val AUTH_TIMEOUT_MS = 4_000L
    }

    private lateinit var pairingStore: PairingStore
    private lateinit var pinInputs: List<EditText>
    private lateinit var connectButton: Button
    private lateinit var pinRow: View
    private lateinit var errorText: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeAuthListener: ((Boolean) -> Unit)? = null
    private var authTimeoutRunnable: Runnable? = null
    private var previousPayloadBeforeAttempt: QrPayload? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manual_pair)

        pairingStore = PairingStore(this)
        RelayWebSocketClient.initialize(this)

        val root = findViewById<View>(R.id.manualPairRoot)
        applySystemInsets(root)

        pinInputs = listOf(
            findViewById(R.id.pinDigit1),
            findViewById(R.id.pinDigit2),
            findViewById(R.id.pinDigit3),
            findViewById(R.id.pinDigit4),
            findViewById(R.id.pinDigit5),
            findViewById(R.id.pinDigit6)
        )
        connectButton = findViewById(R.id.manualPairConnectButton)
        pinRow = findViewById(R.id.manualPairPinRow)
        errorText = findViewById(R.id.manualPairErrorText)

        bindPinInputs()

        findViewById<View>(R.id.manualPairBackButton).setOnClickListener {
            finish()
        }
        connectButton.setOnClickListener {
            val code = pinInputs.joinToString(separator = "") { it.text?.toString().orEmpty() }
            startPairingFlow(code)
        }
    }

    private fun bindPinInputs() {
        pinInputs.forEachIndexed { index, editText ->
            editText.filters = arrayOf(InputFilter.LengthFilter(1))
            editText.doAfterTextChanged { text ->
                clearPinErrorState()
                if (text?.length == 1 && index < pinInputs.lastIndex) {
                    pinInputs[index + 1].requestFocus()
                }
            }
            editText.setOnKeyListener { _, keyCode, event ->
                if (
                    keyCode == KeyEvent.KEYCODE_DEL &&
                    event.action == KeyEvent.ACTION_DOWN &&
                    editText.text.isEmpty() &&
                    index > 0
                ) {
                    pinInputs[index - 1].requestFocus()
                    pinInputs[index - 1].setSelection(pinInputs[index - 1].text.length)
                    true
                } else {
                    false
                }
            }
        }
        pinInputs.first().requestFocus()
    }

    private fun startPairingFlow(codeRaw: String) {
        val code = codeRaw.trim().replace(Regex("\\D"), "")
        if (!code.matches(Regex("\\d{6}"))) {
            showPinError(getString(R.string.manual_pair_invalid_code))
            return
        }

        clearPinErrorState()
        setBusy(true)
        Thread {
            val pairingUrl = resolvePairingUrl()
            runOnUiThread {
                if (pairingUrl == null) {
                    setBusy(false)
                    showPinError(getString(R.string.manual_pair_host_required))
                    return@runOnUiThread
                }
                pairWithCodeAndUrl(code, pairingUrl)
            }
        }.start()
    }

    private fun pairWithCodeAndUrl(code: String, pairingUrl: String) {
        cleanupAuthWatchers()
        previousPayloadBeforeAttempt = pairingStore.load()
        val payload = QrPayload(
            version = 1,
            url = pairingUrl,
            pairingToken = code,
            expiresAtMs = Long.MAX_VALUE,
            deviceName = "Mac"
        )
        pairingStore.save(payload)
        RelayWebSocketClient.clearConnection()
        RelayWebSocketClient.connectIfNeeded()

        val listener: (Boolean) -> Unit = { authenticated ->
            if (authenticated) {
                runOnUiThread {
                    cleanupAuthWatchers()
                    setBusy(false)
                    clearPinErrorState()
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
        activeAuthListener = listener
        RelayWebSocketClient.addAuthStateListener(listener)

        val timeout = Runnable {
            cleanupAuthWatchers()
            restorePreviousPairing()
            RelayWebSocketClient.clearConnection()
            setBusy(false)
            clearPinInputs()
            showPinError(getString(R.string.manual_pair_auth_failed))
        }
        authTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, AUTH_TIMEOUT_MS)
    }

    private fun setBusy(busy: Boolean) {
        connectButton.isEnabled = !busy
        pinInputs.forEach { it.isEnabled = !busy }
        connectButton.text = if (busy) {
            getString(R.string.manual_pair_finding_mac)
        } else {
            getString(R.string.manual_pair_confirm)
        }
    }

    private fun showPinError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
        pinInputs.forEach {
            it.background = ContextCompat.getDrawable(this, R.drawable.bg_pin_digit_error)
            it.setTextColor(ContextCompat.getColor(this, R.color.tang_error))
        }
        ObjectAnimator.ofFloat(pinRow, View.TRANSLATION_X, 0f, -18f, 18f, -12f, 12f, -6f, 6f, 0f)
            .setDuration(360)
            .start()
    }

    private fun clearPinErrorState() {
        errorText.visibility = View.GONE
        pinInputs.forEach {
            it.background = ContextCompat.getDrawable(this, R.drawable.bg_pin_digit)
            it.setTextColor(ContextCompat.getColor(this, R.color.tang_title))
        }
    }

    private fun clearPinInputs() {
        pinInputs.forEach { it.text?.clear() }
        pinInputs.firstOrNull()?.requestFocus()
    }

    private fun restorePreviousPairing() {
        val previous = previousPayloadBeforeAttempt
        if (previous == null) {
            pairingStore.clear()
            return
        }
        pairingStore.save(previous)
    }

    private fun cleanupAuthWatchers() {
        authTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        authTimeoutRunnable = null
        activeAuthListener?.let { RelayWebSocketClient.removeAuthStateListener(it) }
        activeAuthListener = null
    }

    override fun onDestroy() {
        cleanupAuthWatchers()
        super.onDestroy()
    }

    private fun resolvePairingUrl(): String? {
        val savedUrl = pairingStore.load()?.url
        if (!savedUrl.isNullOrBlank()) {
            val savedTarget = parseHostPort(savedUrl)
            if (savedTarget != null && isPortOpen(savedTarget.first, savedTarget.second, 220)) {
                return savedUrl
            }
        }
        val targetPort = preferredServerPort()
        val discoveredHost = discoverMacHostOnWifiSubnet(targetPort)
        if (!discoveredHost.isNullOrBlank()) {
            return "ws://$discoveredHost:$targetPort/ws"
        }
        return null
    }

    private fun preferredServerPort(): Int {
        val savedUrl = pairingStore.load()?.url ?: return DEFAULT_WS_PORT
        val uri = runCatching { URI(savedUrl) }.getOrNull() ?: return DEFAULT_WS_PORT
        return if (uri.port > 0) uri.port else DEFAULT_WS_PORT
    }

    private fun parseHostPort(url: String): Pair<String, Int>? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else if (uri.scheme == "wss") 443 else 80
        return host to port
    }

    private fun discoverMacHostOnWifiSubnet(port: Int): String? {
        val localIp = currentWifiIpv4() ?: return null
        val prefix = localIp.substringBeforeLast('.', "")
        if (prefix.isBlank()) {
            return null
        }
        val selfLast = localIp.substringAfterLast('.', "").toIntOrNull()
        val pool = Executors.newFixedThreadPool(24)
        val completion = ExecutorCompletionService<String?>(pool)
        var submitted = 0

        try {
            for (last in 1..254) {
                if (last == selfLast) {
                    continue
                }
                val host = "$prefix.$last"
                completion.submit(Callable {
                    if (isPortOpen(host, port, 120)) host else null
                })
                submitted++
            }

            repeat(submitted) {
                val found = completion.take().get()
                if (!found.isNullOrBlank()) {
                    return found
                }
            }
            return null
        } finally {
            pool.shutdownNow()
        }
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            }
            true
        }.getOrDefault(false)
    }

    private fun currentWifiIpv4(): String? {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return null
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork) ?: return null
        return linkProperties.linkAddresses
            .mapNotNull { it.address }
            .firstOrNull { address ->
                address is Inet4Address &&
                    !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress
            }
            ?.hostAddress
    }

    private fun applySystemInsets(root: View) {
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
