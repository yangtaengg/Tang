package com.smsrelay.mvp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
    private var notificationAccessDialogShown = false
    private val authStateListener: (Boolean) -> Unit = {
        runOnUiThread {
            if (isFinishing || isDestroyed) {
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
                    onExit = { openManualPairScreen() }
                )
            }
        }

    private val requestSendSmsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Toast.makeText(this, getString(R.string.toast_sms_permission_granted), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.toast_sms_permission_denied),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val requestStatusNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val message = if (granted) {
                R.string.toast_status_notifications_granted
            } else {
                R.string.toast_status_notifications_denied
            }
            Toast.makeText(this, getString(message), Toast.LENGTH_SHORT).show()
        }

    private fun handleScannedQr(content: String) {
        pauseEmbeddedScanner()
        val parsed = pairingStore.parseQrJson(content)
        parsed.onSuccess { payload ->
            pairingStore.save(payload)
            RelayForegroundService.start(this)
            RelayWebSocketClient.clearConnection()
            RelayWebSocketClient.connectIfNeeded()
            Toast.makeText(this, getString(R.string.toast_paired_with_device, payload.deviceName), Toast.LENGTH_SHORT).show()
            checkConnectionStateAndShowQrScanner()
        }.onFailure {
            Toast.makeText(this, getString(R.string.qr_scan_invalid), Toast.LENGTH_LONG).show()
            startEmbeddedScanner()
        }
    }

    private var showingQrScanner = false
    private var showingConnectedScreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pairingStore = PairingStore(this)
        RelayWebSocketClient.initialize(this)
        RelayForegroundService.start(this)

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
        RelayWebSocketClient.connectIfNeeded()
        checkConnectionStateAndShowQrScanner()
        if (!notificationEnabled) {
            promptNotificationAccessIfNeeded()
        }
    }

    private fun promptNotificationAccessIfNeeded() {
        if (notificationAccessDialogShown || isFinishing || isDestroyed) {
            return
        }
        notificationAccessDialogShown = true
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.notification_access_required_title))
            .setMessage(getString(R.string.notification_access_required_message))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.open_settings)) { _, _ ->
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                notificationAccessDialogShown = false
            }
            .setNegativeButton(getString(R.string.later)) { _, _ ->
                notificationAccessDialogShown = false
            }
            .show()
    }

    private fun promptSmsPermission() {
        if (PermissionHelper.hasSendSmsPermission(this)) {
            Toast.makeText(this, getString(R.string.toast_sms_permission_granted), Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sms_permission_disclosure_title))
            .setMessage(getString(R.string.sms_permission_disclosure_message))
            .setPositiveButton(getString(R.string.continue_label)) { _, _ ->
                requestSendSmsPermission.launch(Manifest.permission.SEND_SMS)
            }
            .setNegativeButton(getString(R.string.later), null)
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

    private fun openManualPairScreen() {
        startActivity(Intent(this, ManualPairActivity::class.java))
    }

    private fun triggerCameraPermissionRequest() {
        if (hasCameraPermission()) {
            startEmbeddedScanner()
            return
        }
        requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun checkConnectionStateAndShowQrScanner() {
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
                val notificationAccessButton = findViewById<android.widget.Button>(R.id.connectedNotificationAccessButton)
                val statusNotificationButton = findViewById<android.widget.Button>(R.id.connectedStatusNotificationButton)
                val smsPermissionButton = findViewById<android.widget.Button>(R.id.connectedSmsPermissionButton)
                val batterySettingsButton = findViewById<android.widget.Button>(R.id.connectedBatterySettingsButton)
                val privacyButton = findViewById<android.widget.Button>(R.id.connectedPrivacyButton)

                statusText.text = getString(R.string.connected_status)
                detailsText.text = getString(R.string.qr_scan_connected_details, paired.deviceName)

                notificationAccessButton.setOnClickListener {
                    startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    statusNotificationButton.visibility = View.VISIBLE
                    statusNotificationButton.setOnClickListener {
                        requestStatusNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else {
                    statusNotificationButton.visibility = View.GONE
                }
                smsPermissionButton.setOnClickListener { promptSmsPermission() }
                batterySettingsButton.setOnClickListener {
                    BatteryOptimizationHelper.openBatteryOptimizationSettings(this)
                }
                privacyButton.setOnClickListener { openPrivacyPolicy() }

                disconnectButton.setOnClickListener {
                    pairingStore.clear()
                    RelayWebSocketClient.clearConnection()
                    RelayForegroundService.stop(this)
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
                if (NotificationAccessUtil.isEnabled(this)) {
                    triggerCameraPermissionRequest()
                }
            } else {
                bindQrScreen(pairing, pairingConnected)
                if (NotificationAccessUtil.isEnabled(this) && hasCameraPermission()) {
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
        val notificationAccessButton = findViewById<android.widget.Button>(R.id.qrNotificationAccessButton)
        val privacyButton = findViewById<android.widget.Button>(R.id.qrPrivacyButton)

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
        notificationAccessButton.setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
        privacyButton.setOnClickListener { openPrivacyPolicy() }
    }

    private fun openPrivacyPolicy() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
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

    private companion object {
        const val PRIVACY_POLICY_URL =
            "https://github.com/yangtaengg/Tang/blob/main/PRIVACY_POLICY.md"
    }
}
