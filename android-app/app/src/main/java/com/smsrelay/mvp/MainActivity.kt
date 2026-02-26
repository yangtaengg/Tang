package com.smsrelay.mvp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView

class MainActivity : AppCompatActivity() {
    private lateinit var pairingStore: PairingStore
    private lateinit var onboardingPagerAdapter: OnboardingPagerAdapter
    private var notificationAccessDialogShown = false
    private var batteryOptimizationDialogShown = false
    private val authStateListener: (Boolean) -> Unit = {
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                return@runOnUiThread
            }
            renderState()
            if (hasPendingPermissionStep()) {
                pauseEmbeddedScanner()
                return@runOnUiThread
            }
            checkConnectionStateAndShowQrScanner()
        }
    }

    private var embeddedScannerView: DecoratedBarcodeView? = null
    private val qrScanCallback = BarcodeCallback { result: BarcodeResult? ->
        val content = result?.text?.trim().orEmpty()
        if (content.isEmpty()) {
            armSingleQrScan()
            return@BarcodeCallback
        }
        handleScannedQr(content)
    }

    private val requestCameraPermission: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startEmbeddedScanner()
            } else {
                showPermissionDeniedDialog(
                    title = getString(R.string.camera_permission_required),
                    message = getString(R.string.camera_permission_denied),
                    onRetry = { triggerCameraPermissionRequest() },
                    onExit = { finish() }
                )
            }
        }

    private val requestPhoneStatePermission: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                PhoneStateCallMonitor.start(this)
                Toast.makeText(this, "Incoming call alerts enabled.", Toast.LENGTH_SHORT).show()
                runSequentialPermissionFlow()
            } else {
                showPermissionDeniedDialog(
                    title = getString(R.string.phone_state_permission_required),
                    message = getString(R.string.phone_state_permission_denied),
                    onRetry = { triggerPhoneStatePermissionRequest() },
                    onExit = { finish() }
                )
            }
        }

    private val requestSendSmsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Toast.makeText(this, "SMS sending permission granted.", Toast.LENGTH_SHORT).show()
                runSequentialPermissionFlow()
            } else {
                Toast.makeText(
                    this,
                    "SMS sending denied. Mac-triggered SMS send will be blocked.",
                    Toast.LENGTH_LONG
                ).show()
            }
            renderState()
        }

    private fun handleScannedQr(content: String) {
        pauseEmbeddedScanner()
        val parsed = pairingStore.parseQrJson(content)
        parsed.onSuccess { payload ->
            pairingStore.save(payload)
            RelayWebSocketClient.clearConnection()
            RelayWebSocketClient.connectIfNeeded()
            Toast.makeText(this, "Paired with ${payload.deviceName}", Toast.LENGTH_SHORT).show()
            renderState()
            checkConnectionStateAndShowQrScanner()
        }.onFailure { error ->
            Toast.makeText(this, "Invalid QR: ${error.message}", Toast.LENGTH_LONG).show()
            armSingleQrScan()
        }
    }

    private var showingQrScanner = false
    private var showingConnectedScreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pairingStore = PairingStore(this)
        RelayWebSocketClient.initialize(this)
        RelayForegroundService.start(this)

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
                triggerCameraPermissionRequest()
            },
            onManualPair = {
                openManualPairScreen()
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

    override fun onResume() {
        super.onResume()
        RelayForegroundService.start(this)
        val notificationEnabled = NotificationAccessUtil.isEnabled(this)
        if (notificationEnabled) {
            notificationAccessDialogShown = false
        }
        if (BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
            batteryOptimizationDialogShown = false
        }
        val permissionFlowActive = runSequentialPermissionFlow()
        renderState()
        RelayWebSocketClient.connectIfNeeded()
        if (!permissionFlowActive) {
            PhoneStateCallMonitor.start(this)
            checkConnectionStateAndShowQrScanner()
        } else {
            pauseEmbeddedScanner()
        }
    }

    private fun runSequentialPermissionFlow(): Boolean {
        if (!NotificationAccessUtil.isEnabled(this)) {
            promptNotificationAccessIfNeeded()
            return true
        }
        if (!PermissionHelper.hasPhoneStatePermission(this)) {
            triggerPhoneStatePermissionRequest()
            return true
        }
        if (!PermissionHelper.hasSendSmsPermission(this)) {
            requestSendSmsPermission.launch(Manifest.permission.SEND_SMS)
            return true
        }
        if (!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
            promptBatteryOptimizationIfNeeded()
            return true
        }
        return false
    }

    private fun hasPendingPermissionStep(): Boolean {
        return !NotificationAccessUtil.isEnabled(this) ||
            !PermissionHelper.hasPhoneStatePermission(this) ||
            !PermissionHelper.hasSendSmsPermission(this) ||
            !BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
    }

    private fun promptNotificationAccessIfNeeded() {
        if (notificationAccessDialogShown || isFinishing || isDestroyed) {
            return
        }
        notificationAccessDialogShown = true
        MaterialAlertDialogBuilder(this)
            .setTitle("Notification access required")
            .setMessage("To relay SMS/call/alarm to Mac, enable notification access for SMS Relay.")
            .setCancelable(false)
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                notificationAccessDialogShown = false
            }
            .setNegativeButton("Later") { _, _ ->
                notificationAccessDialogShown = false
            }
            .show()
    }

    private fun promptBatteryOptimizationIfNeeded() {
        if (batteryOptimizationDialogShown || isFinishing || isDestroyed) {
            return
        }
        batteryOptimizationDialogShown = true
        MaterialAlertDialogBuilder(this)
            .setTitle("Battery optimization")
            .setMessage("Disable battery optimization for SMS Relay to keep background relay stable.")
            .setCancelable(false)
            .setPositiveButton("Open settings") { _, _ ->
                val launched = BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(this)
                if (!launched) {
                    BatteryOptimizationHelper.openBatteryOptimizationSettings(this)
                }
                batteryOptimizationDialogShown = false
            }
            .setNegativeButton("Later") { _, _ ->
                batteryOptimizationDialogShown = false
            }
            .show()
    }

    override fun onPause() {
        pauseEmbeddedScanner()
        super.onPause()
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
            pairingDetails = pairing.deviceName
        } else {
            pairingStatus = "Pairing: Token saved"
            pairingDetails = "${pairing.deviceName}\n(Token saved does not guarantee WebSocket auth connected)"
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

    private fun openManualPairScreen() {
        startActivity(Intent(this, ManualPairActivity::class.java))
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

    private fun triggerCameraPermissionRequest() {
        if (hasPendingPermissionStep()) {
            return
        }
        if (hasCameraPermission()) {
            startEmbeddedScanner()
            return
        }
        requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun triggerPhoneStatePermissionRequest() {
        requestPhoneStatePermission.launch(Manifest.permission.READ_PHONE_STATE)
    }

    private fun checkConnectionStateAndShowQrScanner() {
        if (hasPendingPermissionStep()) {
            pauseEmbeddedScanner()
            return
        }
        val pairing = pairingStore.load()
        val pairingConnected = pairing != null && RelayWebSocketClient.isAuthenticated()

        if (pairingConnected) {
            pauseEmbeddedScanner()
            val paired = pairing ?: return
            if (!showingConnectedScreen) {
                showingConnectedScreen = true
                showingQrScanner = false
                setContentView(R.layout.activity_connected)
                currentContentRoot()?.let { applySystemInsets(it) }
                val statusText = findViewById<android.widget.TextView>(R.id.connectedStatusText)
                val detailsText = findViewById<android.widget.TextView>(R.id.connectedDetailsText)
                val disconnectButton = findViewById<android.widget.Button>(R.id.disconnectButton)

                statusText.text = getString(R.string.connected_status)
                detailsText.text = getString(R.string.qr_scan_connected_details, paired.deviceName)

                disconnectButton.setOnClickListener {
                    pairingStore.clear()
                    RelayWebSocketClient.clearConnection()
                    showingConnectedScreen = false
                    showingQrScanner = false
                    checkConnectionStateAndShowQrScanner()
                }
            }
        } else {
            showingConnectedScreen = false
            if (!showingQrScanner) {
                showingQrScanner = true
                setContentView(R.layout.activity_qr_scanner)
                currentContentRoot()?.let { applySystemInsets(it) }
                bindQrScreen(pairing, pairingConnected)
                triggerCameraPermissionRequest()
            } else {
                bindQrScreen(pairing, pairingConnected)
                if (hasCameraPermission()) {
                    startEmbeddedScanner()
                }
            }
        }
    }

    private fun bindQrScreen(pairing: QrPayload?, pairingConnected: Boolean) {
        embeddedScannerView = findViewById(R.id.qrScannerView)
        val statusText = findViewById<android.widget.TextView>(R.id.qrScanStatusText)
        val detailsText = findViewById<android.widget.TextView>(R.id.qrScanDetailsText)
        val manualPairButton = findViewById<android.widget.Button>(R.id.manualPairButton)

        if (pairingConnected && pairing != null) {
            statusText.text = getString(R.string.qr_scan_connected)
            detailsText.text = getString(R.string.qr_scan_connected_details, pairing.deviceName)
        } else {
            statusText.text = getString(R.string.qr_scan_not_connected)
            detailsText.text = if (pairing != null) {
                getString(R.string.qr_scan_saved_waiting, pairing.deviceName)
            } else {
                getString(R.string.qr_scan_instructions)
            }
        }

        manualPairButton.setOnClickListener {
            openManualPairScreen()
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startEmbeddedScanner() {
        val scannerView = embeddedScannerView ?: return
        scannerView.resume()
        armSingleQrScan()
    }

    private fun armSingleQrScan() {
        embeddedScannerView?.decodeSingle(qrScanCallback)
    }

    private fun pauseEmbeddedScanner() {
        embeddedScannerView?.pause()
    }

    private fun currentContentRoot(): View? {
        val content = findViewById<ViewGroup>(android.R.id.content)
        return content.getChildAt(0)
    }

    private fun showPermissionDeniedDialog(
        title: String,
        message: String,
        onRetry: () -> Unit,
        onExit: () -> Unit
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.permission_retry)) { _, _ ->
                onRetry()
            }
            .setNegativeButton(getString(R.string.permission_exit)) { _, _ ->
                onExit()
            }
            .setCancelable(false)
            .show()
    }
}
