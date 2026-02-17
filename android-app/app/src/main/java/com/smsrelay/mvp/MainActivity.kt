package com.smsrelay.mvp

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.smsrelay.mvp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var pairingStore: PairingStore

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

        pairingStore = PairingStore(this)
        RelayWebSocketClient.initialize(this)
        ensurePhoneStatePermission()

        binding.openNotificationAccessButton.setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }

        binding.openSamsungNotificationContentButton.setOnClickListener {
            openSamsungNotificationContentSettings()
        }

        binding.requestSmsPermissionButton.setOnClickListener {
            requestSendSmsPermission.launch(Manifest.permission.SEND_SMS)
        }

        binding.scanQrButton.setOnClickListener {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }

        binding.clearPairingButton.setOnClickListener {
            pairingStore.clear()
            RelayWebSocketClient.clearConnection()
            renderState()
            Toast.makeText(this, "Pairing cleared", Toast.LENGTH_SHORT).show()
        }

        binding.openBatterySettingsButton.setOnClickListener {
            BatteryOptimizationHelper.openBatteryOptimizationSettings(this)
        }
    }

    override fun onResume() {
        super.onResume()
        renderState()
        PhoneStateCallMonitor.start(this)
        RelayWebSocketClient.connectIfNeeded()
    }

    private fun renderState() {
        val notificationAccess = NotificationAccessUtil.isEnabled(this)
        binding.notificationAccessStatusText.text = if (notificationAccess) {
            "Notification Access: Enabled"
        } else {
            "Notification Access: Disabled"
        }

        val pairing = pairingStore.load()
        if (pairing == null) {
            binding.pairingStatusText.text = "Pairing: Not paired"
            binding.pairingDetailsText.text = "Scan the QR shown by the macOS app."
        } else {
            binding.pairingStatusText.text = "Pairing: Token saved"
            binding.pairingDetailsText.text = "${pairing.deviceName}\n${pairing.url}\n(Token saved does not guarantee WebSocket auth connected)"
        }

        val batteryOptimized = !BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
        binding.socketStatusText.text = if (batteryOptimized) {
            "Battery optimization is ON. Allowing exclusion can improve background reliability."
        } else {
            "Battery optimization exclusion is already enabled for this app."
        }

        val smsGranted = PermissionHelper.hasSendSmsPermission(this)
        binding.smsPermissionStatusText.text = if (smsGranted) {
            "SMS Permission: Granted"
        } else {
            "SMS Permission: Not granted (reply_sms blocked)"
        }
    }

    private fun launchQrScanner() {
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt("Scan pairing QR from macOS app")
        options.setBeepEnabled(false)
        options.setOrientationLocked(true)
        scanLauncher.launch(options)
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
