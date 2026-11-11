package com.villamediana.fio.audio

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.villamediana.fio.FioApplication
import com.villamediana.fio.MainActivity
import com.villamediana.fio.Notifications
import com.villamediana.fio.R
import com.villamediana.fio.mind.MicHolder

/**
 * Keeps the microphone open while the screen is off or the app is in the background ("quanto
 * tempo eu quiser"): a foreground service of type microphone, with a "Parar" button.
 */
class MicService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            FioApplication.graph.mind.stopListening()
            stopSelf()
            return START_NOT_STICKY
        }
        Notifications.createChannels(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, MicService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, Notifications.CHANNEL_MIC)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.accent))
            .setContentTitle(getString(R.string.mic_listening))
            .setContentIntent(open)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .addAction(0, getString(R.string.mic_stop), stop)
            .build()
        runCatching {
            ServiceCompat.startForeground(this, Notifications.ID_MIC, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        }.onFailure { stopSelf() }
        running = true
        // Let go before this even started (a very short recording): Android still wants the
        // foreground promise kept, so it is kept above and the service leaves right after.
        if (!wanted) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "com.villamediana.fio.mic.STOP"

        /** The recording wants the service (set by [AndroidMicHolder], read on the main thread). */
        @Volatile var wanted = false

        /** onStartCommand already ran: stopping it now is safe. */
        @Volatile var running = false
    }
}

class AndroidMicHolder(private val context: Context) : MicHolder {
    override fun hold() {
        MicService.wanted = true
        runCatching { ContextCompat.startForegroundService(context, Intent(context, MicService::class.java)) }
            .onFailure { MicService.wanted = false }
    }

    override fun release() {
        MicService.wanted = false
        // Stopping a foreground service before its onStartCommand crashes the app; in that case it
        // stops itself as soon as it starts.
        if (MicService.running) runCatching { context.stopService(Intent(context, MicService::class.java)) }
    }
}
