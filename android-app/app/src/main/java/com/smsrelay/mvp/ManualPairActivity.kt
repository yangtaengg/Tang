package com.smsrelay.mvp

import android.net.ConnectivityManager
import android.os.Bundle
import android.text.InputFilter
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import java.net.URI
import java.net.Inet4Address
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors

class ManualPairActivity : AppCompatActivity() {
    private companion object {
        const val DEFAULT_WS_PORT = 8765
    }

    private lateinit var pairingStore: PairingStore
    private lateinit var pinInputs: List<EditText>
    private lateinit var connectButton: Button

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
            Toast.makeText(this, "Enter 6-digit code", Toast.LENGTH_LONG).show()
            return
        }

        setBusy(true)
        Thread {
            val pairingUrl = resolvePairingUrl()
            runOnUiThread {
                setBusy(false)
                if (pairingUrl == null) {
                    Toast.makeText(this, getString(R.string.manual_pair_host_required), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (pairWithCodeAndUrl(code, pairingUrl)) {
                    finish()
                }
            }
        }.start()
    }

    private fun pairWithCodeAndUrl(code: String, pairingUrl: String): Boolean {

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
        Toast.makeText(this, "Pair code saved. Connecting...", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun setBusy(busy: Boolean) {
        connectButton.isEnabled = !busy
        connectButton.text = if (busy) {
            getString(R.string.manual_pair_finding_mac)
        } else {
            getString(R.string.manual_pair_confirm)
        }
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
