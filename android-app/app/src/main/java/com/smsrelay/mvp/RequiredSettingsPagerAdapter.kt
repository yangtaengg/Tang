package com.smsrelay.mvp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView

enum class RequiredSetting(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    @StringRes val actionRes: Int
) {
    NotificationAccess(
        R.drawable.ic_permission_notification_access,
        R.string.notification_access_required_title,
        R.string.step_notification_description,
        R.string.open_settings
    ),
    StatusNotifications(
        R.drawable.ic_permission_status,
        R.string.status_notification_permission_title,
        R.string.status_notification_permission_description,
        R.string.request_status_notifications
    ),
    SmsPermission(
        R.drawable.ic_permission_sms,
        R.string.sms_permission_disclosure_title,
        R.string.step_sms_description,
        R.string.request_sms_permission
    ),
    BatteryOptimization(
        R.drawable.ic_permission_battery,
        R.string.battery_optimization_title,
        R.string.battery_optimization_message,
        R.string.open_battery_settings
    )
}

class RequiredSettingsPagerAdapter(
    private val onAction: (RequiredSetting) -> Unit
) : RecyclerView.Adapter<RequiredSettingsPagerAdapter.CardViewHolder>() {

    private var items: List<RequiredSetting> = emptyList()

    fun updateItems(newItems: List<RequiredSetting>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_required_setting_card, parent, false)
        return CardViewHolder(view)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        val item = items[position]
        holder.step.text = holder.itemView.context.getString(
            R.string.required_settings_step,
            position + 1,
            items.size
        )
        holder.icon.setImageResource(item.iconRes)
        holder.title.setText(item.titleRes)
        holder.description.setText(item.descriptionRes)
        holder.action.setText(item.actionRes)
        holder.action.setOnClickListener { onAction(item) }
    }

    class CardViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val step: TextView = view.findViewById(R.id.requiredSettingCardStep)
        val icon: ImageView = view.findViewById(R.id.requiredSettingCardIcon)
        val title: TextView = view.findViewById(R.id.requiredSettingCardTitle)
        val description: TextView = view.findViewById(R.id.requiredSettingCardDescription)
        val action: Button = view.findViewById(R.id.requiredSettingActionButton)
    }
}
