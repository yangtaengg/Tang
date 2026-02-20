package com.smsrelay.mvp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

data class OnboardingUiState(
    val notificationAccessStatus: String,
    val smsPermissionStatus: String,
    val pairingStatus: String,
    val pairingDetails: String,
    val batteryStatus: String,
    val batteryRequestEnabled: Boolean,
    val notificationAccessGranted: Boolean,
    val smsPermissionGranted: Boolean,
    val batteryExcluded: Boolean
)

class OnboardingPagerAdapter(
    private val onOpenNotificationAccess: () -> Unit,
    private val onOpenSamsungNotificationSettings: () -> Unit,
    private val onRequestSmsPermission: () -> Unit,
    private val onScanQr: () -> Unit,
    private val onManualPair: () -> Unit,
    private val onClearPairing: () -> Unit,
    private val onRequestBatteryExclusion: () -> Unit,
    private val onOpenBatterySettings: () -> Unit
) : RecyclerView.Adapter<OnboardingPagerAdapter.StepViewHolder>() {

    private var state: OnboardingUiState = OnboardingUiState(
        notificationAccessStatus = "Notification Access: Unknown",
        smsPermissionStatus = "SMS Permission: Unknown",
        pairingStatus = "Pairing: Unknown",
        pairingDetails = "",
        batteryStatus = "Battery optimization exclusion: Unknown",
        batteryRequestEnabled = true,
        notificationAccessGranted = false,
        smsPermissionGranted = false,
        batteryExcluded = false
    )

    fun updateState(newState: OnboardingUiState) {
        state = newState
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = 4

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StepViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val layoutRes = when (viewType) {
            0 -> R.layout.item_step_notification
            1 -> R.layout.item_step_sms
            2 -> R.layout.item_step_pairing
            else -> R.layout.item_step_battery
        }
        val view = inflater.inflate(layoutRes, parent, false)
        return StepViewHolder(view)
    }

    override fun onBindViewHolder(holder: StepViewHolder, position: Int) {
        when (position) {
            0 -> bindNotificationStep(holder)
            1 -> bindSmsStep(holder)
            2 -> bindPairingStep(holder)
            else -> bindBatteryStep(holder)
        }
    }

    private fun bindNotificationStep(holder: StepViewHolder) {
        val statusText = holder.view.findViewById<TextView>(R.id.notificationAccessStatusText)
        statusText.text = state.notificationAccessStatus
        statusText.setTextColor(
            ContextCompat.getColor(
                holder.view.context,
                if (state.notificationAccessGranted) R.color.tang_success else R.color.tang_body
            )
        )
        holder.view.findViewById<Button>(R.id.openNotificationAccessButton).setOnClickListener { onOpenNotificationAccess() }
        holder.view.findViewById<Button>(R.id.openSamsungNotificationContentButton).setOnClickListener { onOpenSamsungNotificationSettings() }
    }

    private fun bindSmsStep(holder: StepViewHolder) {
        val statusText = holder.view.findViewById<TextView>(R.id.smsPermissionStatusText)
        statusText.text = state.smsPermissionStatus
        statusText.setTextColor(
            ContextCompat.getColor(
                holder.view.context,
                if (state.smsPermissionGranted) R.color.tang_success else R.color.tang_body
            )
        )
        holder.view.findViewById<Button>(R.id.requestSmsPermissionButton).setOnClickListener { onRequestSmsPermission() }
    }

    private fun bindPairingStep(holder: StepViewHolder) {
        holder.view.findViewById<TextView>(R.id.pairingStatusText).text = state.pairingStatus
        holder.view.findViewById<TextView>(R.id.pairingDetailsText).text = state.pairingDetails
        holder.view.findViewById<Button>(R.id.scanQrButton).setOnClickListener { onScanQr() }
        holder.view.findViewById<Button>(R.id.manualPairButton).setOnClickListener { onManualPair() }
        holder.view.findViewById<Button>(R.id.clearPairingButton).setOnClickListener { onClearPairing() }
    }

    private fun bindBatteryStep(holder: StepViewHolder) {
        val statusText = holder.view.findViewById<TextView>(R.id.socketStatusText)
        statusText.text = state.batteryStatus
        statusText.setTextColor(
            ContextCompat.getColor(
                holder.view.context,
                if (state.batteryExcluded) R.color.tang_success else R.color.tang_body
            )
        )
        holder.view.findViewById<Button>(R.id.requestBatteryExclusionButton).apply {
            isEnabled = state.batteryRequestEnabled
            setOnClickListener { onRequestBatteryExclusion() }
        }
        holder.view.findViewById<Button>(R.id.openBatterySettingsButton).setOnClickListener { onOpenBatterySettings() }
    }

    class StepViewHolder(val view: View) : RecyclerView.ViewHolder(view)
}
