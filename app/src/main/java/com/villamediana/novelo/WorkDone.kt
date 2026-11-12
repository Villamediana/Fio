package com.villamediana.novelo

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.villamediana.novelo.mind.MindState
import com.villamediana.novelo.settings.t
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * She minimized or closed the app while it worked: when it is done (kept, or it couldn't), a
 * notification says so — she knows it went on without her watching. Nothing when the app is on screen.
 */
object WorkDone {
    private var told = -1L

    fun watch(context: Context, scope: CoroutineScope, state: StateFlow<MindState>) {
        scope.launch {
            state.collect { s ->
                val onScreen = withContext(Dispatchers.Main) { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                if (onScreen) return@collect
                when (s) {
                    is MindState.Answer -> if (s.captureId != told) { told = s.captureId; tell(context, t(R.string.done_kept), s.reply) }
                    is MindState.Problem -> if (s.captureId != null && s.captureId != told) { told = s.captureId; tell(context, t(R.string.done_waiting), s.message) }
                    else -> Unit
                }
            }
        }
    }

    private fun tell(context: Context, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val open = PendingIntent.getActivity(context, 2, Intent(context, NoveloActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, Notifications.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(Notifications.ID_DONE, n) }
    }
}
