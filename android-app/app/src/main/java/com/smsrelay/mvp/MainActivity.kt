package com.smsrelay.mvp
import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.text.InputFilter
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.smsrelay.mvp.databinding.ActivityMainBinding
import java.net.URI

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var pairingStore: PairingStore
    private lateinit var onboardingPagerAdapter: OnboardingPagerAdapter
    private val authStateListener: (Boolean) -> Unit = {
        runOnUiThread { renderState() }
    }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchQrScanner()
            } else {
                Toast.makeText(this, "Camera permission is required for QR pairing.", Toast.LENGTH_LONG).show()
            }
        }

    private val requestPhoneStatePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                PhoneStateCallMonitor.start(this)
                Toast.makeText(this, "Incoming call alerts enabled.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Phone permission denied. Call alerts may be limited.", Toast.LENGTH_LONG).show()
            }
        }

    private val requestSendSmsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Toast.makeText(this, "SMS sending permission granted.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "SMS sending denied. Mac-triggered SMS send will be blocked.", Toast.LENGTH_LONG).show()
            }
            renderState()
        }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val content = result.contents ?: return@registerForActivityResult
        val parsed = pairingStore.parseQrJson(content)
        parsed.onSuccess { payload ->
            pairingStore.save(payload)
            RelayWebSocketClient.clearConnection()
            RelayWebSocketClient.connectIfNeeded()
            Toast.makeText(this, "Paired with ${payload.deviceName}", Toast.LENGTH_SHORT).show()
            renderState()
        }.onFailure { error ->
            Toast.makeText(this, "Invalid QR: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemInsets()

        pairingStore = PairingStore(this)
        RelayWebSocketClient.initialize(this)
        ensurePhoneStatePermission()

        onboardingPagerAdapter = OnboardingPagerAdapter(
            onOpenNotificationAccess = {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            },
            onOpenSamsungNotificationSettings = {
                openSamsungNotificationContentSettings()
            },
            onRequestSmsPermission = {
                requestSendSmsPermission.launch(Manifest.permission.SEND_SMS)
            },
            onScanQr = {
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            },
            onManualPair = {
                showManualPairDialog()
            },
            onClearPairing = {
                pairingStore.clear()
                RelayWebSocketClient.clearConnection()
                renderState()
                Toast.makeText(this, "Pairing cleared", Toast.LENGTH_SHORT).show()
            },
            onRequestBatteryExclusion = {
                val launched = BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(this)
                if (!launched) {
                    BatteryOptimizationHelper.openBatteryOptimizationSettings(this)
                }
            },
            onOpenBatterySettings = {
                BatteryOptimizationHelper.openBatteryOptimizationSettings(this)
            }
        )
        binding.stepPager.setAdapter(onboardingPagerAdapter)
        TabLayoutMediator(binding.stepTabs, binding.stepPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Alerts"
                1 -> "SMS"
                2 -> "Pair"
                else -> "Battery"
            }
        }.attach()
    }

    private fun applySystemInsets() {
        val root = binding.root
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

    override fun onResume() {
        super.onResume()
        renderState()
        PhoneStateCallMonitor.start(this)
        RelayWebSocketClient.connectIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        RelayWebSocketClient.addAuthStateListener(authStateListener)
    }

    override fun onStop() {
        RelayWebSocketClient.removeAuthStateListener(authStateListener)
        super.onStop()
    }

    private fun renderState() {
        val notificationAccess = NotificationAccessUtil.isEnabled(this)
        val notificationAccessStatus = if (notificationAccess) {
            "✓ Notification Access: Enabled"
        } else {
            "Notification Access: Disabled"
        }

        val pairing = pairingStore.load()
        val pairingConnected = pairing != null && RelayWebSocketClient.isAuthenticated()
        val pairingStatus: String
        val pairingDetails: String
        if (pairing == null) {
            pairingStatus = "Pairing: Not paired"
            pairingDetails = "Scan the QR shown by the macOS app."
        } else if (pairingConnected) {
            pairingStatus = "✓ Pairing: Token connected"
            pairingDetails = "${pairing.deviceName}\n${pairing.url}"
        } else {
            pairingStatus = "Pairing: Token saved"
            pairingDetails = "${pairing.deviceName}\n${pairing.url}\n(Token saved does not guarantee WebSocket auth connected)"
        }

        val excluded = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
        val batteryStatus = if (excluded) {
            "✓ Battery optimization exclusion: Enabled"
        } else {
            "Battery optimization exclusion: Disabled"
        }

        val smsGranted = PermissionHelper.hasSendSmsPermission(this)
        val smsPermissionStatus = if (smsGranted) {
            "✓ SMS Permission: Granted"
        } else {
            "SMS Permission: Not granted (reply_sms blocked)"
        }

        onboardingPagerAdapter.updateState(
            OnboardingUiState(
                notificationAccessStatus = notificationAccessStatus,
                smsPermissionStatus = smsPermissionStatus,
                pairingStatus = pairingStatus,
                pairingDetails = pairingDetails,
                batteryStatus = batteryStatus,
                batteryRequestEnabled = !excluded,
                notificationAccessGranted = notificationAccess,
                smsPermissionGranted = smsGranted,
                batteryExcluded = excluded,
                pairingConnected = pairingConnected
            )
        )
    }

    private fun launchQrScanner() {
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt("Scan pairing QR from macOS app")
        options.setBeepEnabled(false)
        options.setOrientationLocked(true)
        scanLauncher.launch(options)
    }

    private fun showManualPairDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_manual_pair, null)
        val hostInput = dialogView.findViewById<android.widget.EditText>(R.id.manualPairHostInput)
        val pinInputs = listOf(
            dialogView.findViewById<android.widget.EditText>(R.id.pinDigit1),
            dialogView.findViewById<android.widget.EditText>(R.id.pinDigit2),
            dialogView.findViewById<android.widget.EditText>(R.id.pinDigit3),
            dialogView.findViewById<android.widget.EditText>(R.id.pinDigit4),
            dialogView.findViewById<android.widget.EditText>(R.id.pinDigit5),
            dialogView.findViewById<android.widget.EditText>(R.id.pinDigit6)
        )

        hostInput.setText(defaultManualPairHost())
        pinInputs.forEachIndexed { index, editText ->
            editText.filters = arrayOf(InputFilter.LengthFilter(1))
            editText.doAfterTextChanged { text ->
                if (text?.length == 1 && index < pinInputs.lastIndex) {
                    pinInputs[index + 1].requestFocus()
                }
            }
            editText.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN && editText.text.isEmpty() && index > 0) {
                    pinInputs[index - 1].requestFocus()
                    pinInputs[index - 1].setSelection(pinInputs[index - 1].text.length)
                    true
                } else {
                    false
                }
            }
        }
        pinInputs.first().requestFocus()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.manual_pair_dialog_title))
            .setView(dialogView)
            .setNegativeButton(getString(R.string.manual_pair_cancel), null)
            .setPositiveButton(getString(R.string.manual_pair_confirm), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = pinInputs.joinToString(separator = "") { it.text?.toString().orEmpty() }
                val ok = pairWithCode(hostInput.text?.toString().orEmpty(), code)
                if (ok) {
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    private fun pairWithCode(hostRaw: String, codeRaw: String): Boolean {
        val host = hostRaw.trim()
        val code = codeRaw.trim().replace(Regex("\\D"), "")
        if (host.isBlank()) {
            Toast.makeText(this, "Mac host is required", Toast.LENGTH_LONG).show()
            return false
        }
        if (!code.matches(Regex("\\d{6}"))) {
            Toast.makeText(this, "Enter 6-digit code", Toast.LENGTH_LONG).show()
            return false
        }

        val payload = QrPayload(
            version = 1,
            url = "ws://$host:8765/ws",
            pairingToken = code,
            expiresAtMs = Long.MAX_VALUE,
            deviceName = "Mac"
        )
        pairingStore.save(payload)
        RelayWebSocketClient.clearConnection()
        RelayWebSocketClient.connectIfNeeded()
        Toast.makeText(this, "Pair code saved. Connecting...", Toast.LENGTH_SHORT).show()
        renderState()
        return true
    }

    private fun defaultManualPairHost(): String {
        val saved = pairingStore.load() ?: return ""
        return try {
            URI(saved.url).host?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun openSamsungNotificationContentSettings() {
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            Toast.makeText(this, "Samsung device only", Toast.LENGTH_SHORT).show()
            return
        }

        val intents = listOf(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, "com.samsung.android.messaging"),
            Intent("android.settings.NOTIFICATION_SETTINGS"),
            Intent("android.settings.LOCK_SCREEN_SETTINGS")
        )

        val launched = intents.firstOrNull { intent ->
            intent.resolveActivity(packageManager) != null
        }?.let {
            startActivity(it)
            true
        } ?: false

        if (!launched) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun ensurePhoneStatePermission() {
        if (PermissionHelper.hasPhoneStatePermission(this)) {
            PhoneStateCallMonitor.start(this)
            return
        }
        requestPhoneStatePermission.launch(Manifest.permission.READ_PHONE_STATE)
    }
}
