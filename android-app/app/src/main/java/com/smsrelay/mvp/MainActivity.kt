package com.smsrelay.mvp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView

class MainActivity : AppCompatActivity() {
    private lateinit var pairingStore: PairingStore
    private val authStateListener: (Boolean) -> Unit = {
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                return@runOnUiThread
            }
            checkConnectionStateAndShowQrScanner()
        }
    }

    private var embeddedScannerView: DecoratedBarcodeView? = null
    private var requiredSettingsPager: ViewPager2? = null
    private var requiredSettingsAdapter: RequiredSettingsPagerAdapter? = null
    private var requiredSettingsPageCallback: ViewPager2.OnPageChangeCallback? = null
    private var requiredSettingsSwipeAnimator: ObjectAnimator? = null
    private var requiredSettingsDismissed = false
    private var qrScanRequested = false
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
                qrScanRequested = false
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
            checkConnectionStateAndShowQrScanner()
        }

    private val requestStatusNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val message = if (granted) {
                R.string.toast_status_notifications_granted
            } else {
                R.string.toast_status_notifications_denied
            }
            Toast.makeText(this, getString(message), Toast.LENGTH_SHORT).show()
            checkConnectionStateAndShowQrScanner()
        }

    private fun handleScannedQr(content: String) {
        pauseEmbeddedScanner()
        val parsed = pairingStore.parseQrJson(content)
        parsed.onSuccess { payload ->
            qrScanRequested = false
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
        RelayWebSocketClient.connectIfNeeded()
        checkConnectionStateAndShowQrScanner()
        if (showingQrScanner && qrScanRequested && hasCameraPermission()) {
            startEmbeddedScanner()
        }
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
        qrScanRequested = true
        if (hasCameraPermission()) {
            startEmbeddedScanner()
            return
        }
        requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun checkConnectionStateAndShowQrScanner() {
        val pairing = pairingStore.load()
        val pairingConnected = pairing != null && RelayWebSocketClient.isAuthenticated()

        if (pairing != null) {
            pauseEmbeddedScanner()
            qrScanRequested = false
            if (!showingConnectedScreen) {
                showingConnectedScreen = true
                showingQrScanner = false
                releaseRequiredSettingsPager()
                setContentView(R.layout.activity_connected)
                currentContentRoot()?.let { applySystemInsets(it) }
            }
            bindConnectedScreen(pairing, pairingConnected)
        } else {
            showingConnectedScreen = false
            if (!showingQrScanner) {
                showingQrScanner = true
                releaseRequiredSettingsPager()
                setContentView(R.layout.activity_qr_scanner)
                currentContentRoot()?.let { applySystemInsets(it) }
                bindQrScreen(pairing, pairingConnected)
            } else {
                bindQrScreen(pairing, pairingConnected)
            }
        }
    }

    private fun bindConnectedScreen(pairing: QrPayload, pairingConnected: Boolean) {
        val statusText = findViewById<android.widget.TextView>(R.id.connectedStatusText)
        val detailsText = findViewById<android.widget.TextView>(R.id.connectedDetailsText)

        statusText.text = getString(
            if (pairingConnected) R.string.connected_status else R.string.reconnecting_status
        )
        detailsText.text = if (pairingConnected) {
            getString(R.string.qr_scan_connected_details, pairing.deviceName)
        } else {
            getString(R.string.qr_scan_saved_waiting, pairing.deviceName)
        }

        bindRequiredSettingsOverlay()
        findViewById<View>(R.id.reconnectButton).setOnClickListener {
            RelayForegroundService.start(this)
            RelayWebSocketClient.clearConnection()
            RelayWebSocketClient.connectIfNeeded()
            Toast.makeText(this, getString(R.string.toast_reconnecting), Toast.LENGTH_SHORT).show()
            checkConnectionStateAndShowQrScanner()
        }
        findViewById<View>(R.id.disconnectButton).setOnClickListener {
            pairingStore.clear()
            RelayWebSocketClient.clearConnection()
            RelayForegroundService.stop(this)
            showingConnectedScreen = false
            showingQrScanner = false
            qrScanRequested = false
            Toast.makeText(this, getString(R.string.toast_pairing_cleared), Toast.LENGTH_SHORT).show()
            checkConnectionStateAndShowQrScanner()
        }
    }

    private fun bindQrScreen(pairing: QrPayload?, pairingConnected: Boolean) {
        embeddedScannerView = findViewById(R.id.qrScannerView)
        val statusText = findViewById<android.widget.TextView>(R.id.qrScanStatusText)
        val detailsText = findViewById<android.widget.TextView>(R.id.qrScanDetailsText)
        val manualPairButton = findViewById<android.widget.Button>(R.id.manualPairButton)
        val startQrScanButton = findViewById<android.widget.Button>(R.id.startQrScanButton)
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
        startQrScanButton.setOnClickListener {
            triggerCameraPermissionRequest()
        }
        bindRequiredSettingsOverlay()
        privacyButton.setOnClickListener { openPrivacyPolicy() }
    }

    private fun requiredSettings(): List<RequiredSetting> = buildList {
        if (!NotificationAccessUtil.isEnabled(this@MainActivity)) {
            add(RequiredSetting.NotificationAccess)
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            add(RequiredSetting.StatusNotifications)
        }
        if (!PermissionHelper.hasSendSmsPermission(this@MainActivity)) {
            add(RequiredSetting.SmsPermission)
        }
        if (!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this@MainActivity)) {
            add(RequiredSetting.BatteryOptimization)
        }
    }

    private fun bindRequiredSettingsOverlay() {
        val overlay = findViewById<View>(R.id.requiredSettingsOverlay)
        val pager = findViewById<ViewPager2>(R.id.requiredSettingsPager)
        val indicator = findViewById<android.widget.TextView>(R.id.requiredSettingsIndicator)
        val swipeGuide = findViewById<android.widget.TextView>(R.id.requiredSettingsSwipeGuide)
        val items = requiredSettings()

        if (items.isEmpty() || requiredSettingsDismissed) {
            overlay.visibility = View.GONE
            stopSwipeGuideAnimation()
            return
        }
        overlay.visibility = View.VISIBLE

        if (requiredSettingsPager !== pager) {
            releaseRequiredSettingsPager()
            requiredSettingsPager = pager
            requiredSettingsAdapter = RequiredSettingsPagerAdapter(::onRequiredSettingAction)
            pager.adapter = requiredSettingsAdapter
            requiredSettingsPageCallback = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateRequiredSettingsIndicator(indicator, position)
                }
            }.also(pager::registerOnPageChangeCallback)
        }

        requiredSettingsAdapter?.updateItems(items)
        if (pager.currentItem >= items.size) {
            pager.setCurrentItem(0, false)
        }
        updateRequiredSettingsIndicator(indicator, pager.currentItem)

        swipeGuide.visibility = if (items.size > 1) View.VISIBLE else View.GONE
        if (items.size > 1) {
            startSwipeGuideAnimation(swipeGuide)
        } else {
            stopSwipeGuideAnimation()
        }
        findViewById<View>(R.id.requiredSettingsLaterButton).setOnClickListener {
            requiredSettingsDismissed = true
            overlay.visibility = View.GONE
            stopSwipeGuideAnimation()
        }
    }

    private fun updateRequiredSettingsIndicator(
        indicator: android.widget.TextView,
        position: Int
    ) {
        val count = requiredSettingsAdapter?.itemCount ?: return
        if (count > 0) {
            indicator.text = buildString {
                repeat(count) { index ->
                    if (index > 0) append("  ")
                    append(if (index == position) "●" else "○")
                }
            }
            indicator.contentDescription = getString(
                R.string.required_settings_page_indicator,
                position + 1,
                count
            )
        }
    }

    private fun startSwipeGuideAnimation(guide: View) {
        if (requiredSettingsSwipeAnimator?.isRunning == true) {
            return
        }
        val distance = 10f * resources.displayMetrics.density
        requiredSettingsSwipeAnimator = ObjectAnimator.ofFloat(
            guide,
            View.TRANSLATION_X,
            -distance,
            distance
        ).apply {
            duration = 900L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = 3
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    guide.translationX = 0f
                }
            })
            start()
        }
    }

    private fun stopSwipeGuideAnimation() {
        requiredSettingsSwipeAnimator?.cancel()
        requiredSettingsSwipeAnimator = null
    }

    private fun onRequiredSettingAction(setting: RequiredSetting) {
        when (setting) {
            RequiredSetting.NotificationAccess -> {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
            RequiredSetting.StatusNotifications -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestStatusNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            RequiredSetting.SmsPermission -> promptSmsPermission()
            RequiredSetting.BatteryOptimization -> {
                BatteryOptimizationHelper.openBatteryOptimizationSettings(this)
            }
        }
    }

    private fun releaseRequiredSettingsPager() {
        stopSwipeGuideAnimation()
        val pager = requiredSettingsPager
        val callback = requiredSettingsPageCallback
        if (pager != null && callback != null) {
            pager.unregisterOnPageChangeCallback(callback)
        }
        requiredSettingsPager = null
        requiredSettingsAdapter = null
        requiredSettingsPageCallback = null
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
        (scannerView.parent as? View)?.visibility = View.VISIBLE
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
