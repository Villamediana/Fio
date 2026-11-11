package com.villamediana.fio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Notifications {
    const val CHANNEL_REMIND = "remind"
    const val CHANNEL_CHECKIN = "checkin"
    const val CHANNEL_MIC = "mic"
    const val CHANNEL_UPDATES = "updates"

    const val ID_MIC = 1001
    const val ID_UPDATE_AVAILABLE = 3001
    const val ID_UPDATED = 3002

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_REMIND, context.getString(R.string.channel_remind), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_CHECKIN, context.getString(R.string.channel_checkin), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_MIC, context.getString(R.string.channel_mic), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
                NotificationChannel(CHANNEL_UPDATES, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }
}
