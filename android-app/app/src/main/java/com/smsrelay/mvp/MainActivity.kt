package com.smsrelay.mvp

import android.content.Intent
import android.os.Bundle
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

        binding.openNotificationAccessButton.setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }

        binding.scanQrButton.setOnClickListener {
            requestCameraPermission.launch(android.Manifest.permission.CAMERA)
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
    }

    private fun launchQrScanner() {
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt("Scan pairing QR from macOS app")
        options.setBeepEnabled(false)
        options.setOrientationLocked(true)
        scanLauncher.launch(options)
    }
}
